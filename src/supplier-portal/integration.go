package main

import (
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"reflect"
	"time"
	"unicode/utf16"

	"golang.org/x/crypto/bcrypt"
)

func integrationHandler(s *store) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("PUT /integration/v1/invitations/{id}", func(w http.ResponseWriter, r *http.Request) {
		var input provision
		id := r.PathValue("id")
		if !decode(w, r, &input) || !validProvision(id, input) {
			http.Error(w, "Invalid invitation", http.StatusBadRequest)
			return
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		err := applyProvision(s, id, input)
		integrationResult(w, id, "provision", err)
	})
	mux.HandleFunc("PUT /integration/v1/invitations/{id}/revocation", func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		if !opaqueID.MatchString(id) {
			http.NotFound(w, r)
			return
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		integrationResult(w, id, "revoke", revoke(s, id))
	})
	mux.HandleFunc("GET /integration/v1/invitations/{id}/submission", func(w http.ResponseWriter, r *http.Request) {
		s.mu.Lock()
		defer s.mu.Unlock()
		record, err := s.load(r.PathValue("id"))
		if errors.Is(err, errUnavailable) || record.Revoked {
			http.NotFound(w, r)
			return
		}
		if err != nil {
			integrationResult(w, "", "read", err)
			return
		}
		if record.Submission == nil {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		if err := json.NewEncoder(w).Encode(record.Submission); err != nil {
			slog.Warn("supplier integration", "operation", "read", "outcome", "response interrupted")
		}
	})
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		if r.TLS == nil || r.TLS.Version < tls.VersionTLS12 || len(r.TLS.VerifiedChains) == 0 {
			http.Error(w, "Integration identity required", http.StatusForbidden)
			return
		}
		mux.ServeHTTP(w, r)
	})
}

func decode(w http.ResponseWriter, r *http.Request, target any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxBody)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return false
	}
	return errors.Is(decoder.Decode(new(any)), io.EOF)
}

func validProvision(id string, input provision) bool {
	q := input.Questionnaire
	cost, hashError := bcrypt.Cost([]byte(input.Verifier))
	if !opaqueID.MatchString(id) || q.InvitationID != id || q.SchemaVersion != 1 || q.Generation < 1 ||
		q.Deadline.IsZero() || !hexDigest.MatchString(q.SnapshotHash) ||
		input.ExpiresAt.IsZero() || input.ExpiresAt.After(q.Deadline) ||
		hashError != nil || len(input.Verifier) != 60 || cost != bcrypt.DefaultCost ||
		!validText(q.RequesterLabel, 255) || !validText(q.ServiceScope, 1000) || len(q.Questions) < 1 || len(q.Questions) > 200 {
		return false
	}
	if q.Generation > 1 && (!opaqueID.MatchString(input.PreviousInvitationID) || input.PreviousInvitationID == id) {
		return false
	}
	if q.Generation == 1 && (input.PreviousInvitationID != "" || input.RetainDraft) {
		return false
	}
	keys := make(map[string]bool, len(q.Questions))
	for _, question := range q.Questions {
		if !opaqueID.MatchString(question.Key) || !validText(question.Text, 4000) || keys[question.Key] {
			return false
		}
		keys[question.Key] = true
	}
	return true
}

func validText(text string, max int) bool {
	return text != "" && len(utf16.Encode([]rune(text))) <= max
}

func applyProvision(s *store, id string, input provision) error {
	present, err := s.load(id)
	if err == nil {
		if !present.Revoked && reflect.DeepEqual(present.Provision, input) {
			return nil
		}
		return errConflict
	}
	if !errors.Is(err, errUnavailable) {
		return err
	}
	if !time.Now().Before(input.ExpiresAt) {
		return errConflict
	}
	next := invitation{Provision: input}
	if previousID := input.PreviousInvitationID; previousID != "" {
		old, err := s.load(previousID)
		if err != nil && !errors.Is(err, errUnavailable) {
			return err
		}
		if old.Submission != nil && !old.Revoked {
			return errConflict
		}
		if input.RetainDraft && old.Submission == nil && old.Provision.Questionnaire.SnapshotHash == input.Questionnaire.SnapshotHash {
			next.Draft, next.Revision = old.Draft, old.Revision
		}
		if err := revoke(s, previousID); err != nil {
			return err
		}
	}
	return s.save(id, next)
}

func revoke(s *store, id string) error {
	record, err := s.load(id)
	if err != nil && !errors.Is(err, errUnavailable) {
		return err
	}
	if record.Revoked {
		return nil
	}
	record.Revoked = true
	record.Provision.Verifier = ""
	return s.save(id, record)
}

func integrationResult(w http.ResponseWriter, id, operation string, err error) {
	status := http.StatusNoContent
	switch {
	case errors.Is(err, errConflict):
		status = http.StatusConflict
	case err != nil:
		status = http.StatusServiceUnavailable
	}
	slog.Info("supplier integration", "actor", "main-secman", "target", id, "operation", operation, "status", status)
	w.WriteHeader(status)
}
