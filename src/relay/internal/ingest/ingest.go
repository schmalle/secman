// Package ingest implements the secman -> relay plane.
//
// Direction is the security property that everything else hangs off: secman
// dials out to the relay and the relay never dials in. The relay holds no
// secman credential, knows no secman URL, and has no code path that could
// reach back into the trusted network even if it were fully compromised.
package ingest

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/schmalle/secman/src/relay/internal/auth"
	"github.com/schmalle/secman/src/relay/internal/devices"
	"github.com/schmalle/secman/src/relay/internal/httpx"
	"github.com/schmalle/secman/src/relay/internal/logging"
	"github.com/schmalle/secman/src/relay/internal/model"
	"github.com/schmalle/secman/src/relay/internal/store"
)

// Handler serves the ingest routes.
type Handler struct {
	Verifier *auth.IngestVerifier
	Store    *store.Store
	Devices  *devices.Registry
	Logger   *slog.Logger
	MaxBody  int64
	Now      func() time.Time
}

// Routes registers the ingest plane on a mux under /ingest/v1.
func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("POST /ingest/v1/snapshot", h.authenticated(h.handleSnapshot))
	mux.Handle("POST /ingest/v1/control", h.authenticated(h.handleControl))
	mux.Handle("GET /ingest/v1/devices", h.authenticated(h.handleDevices))
	mux.Handle("GET /ingest/v1/status", h.authenticated(h.handleStatus))
}

// authenticatedHandler receives the already-read, already-verified body. The
// body is read once, by the wrapper, because the signature covers exactly the
// bytes that arrived — re-reading or re-encoding would break that binding.
type authenticatedHandler func(w http.ResponseWriter, r *http.Request, body []byte)

func (h *Handler) authenticated(next authenticatedHandler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		now := h.now()
		peer := httpx.ClientIPFrom(r.Context())

		body, err := io.ReadAll(r.Body)
		if err != nil {
			// A MaxBytesReader trip lands here. It is not an auth failure, but
			// it is still a refused push, so it is counted.
			h.Store.NoteRejected()
			h.Logger.Warn("ingest body rejected",
				"requestId", httpx.RequestIDFrom(r.Context()),
				"peer", peer,
				"reason", "unreadable_or_too_large")
			httpx.WriteError(w, r, http.StatusRequestEntityTooLarge, "request body too large")
			return
		}

		reason, authErr := h.Verifier.Verify(r.Header, body, now)
		if authErr != nil {
			h.Store.NoteRejected()
			// Actor + target + outcome, with the reason kept server-side only.
			h.Logger.Warn("ingest authentication failed",
				"requestId", httpx.RequestIDFrom(r.Context()),
				"peer", peer,
				"path", logging.Sanitize(r.URL.Path),
				"reason", reason,
				"outcome", "denied")
			httpx.WriteError(w, r, http.StatusUnauthorized, "unauthorized")
			return
		}
		next(w, r, body)
	})
}

func (h *Handler) handleSnapshot(w http.ResponseWriter, r *http.Request, body []byte) {
	now := h.now()

	var snap model.Snapshot
	if err := json.Unmarshal(body, &snap); err != nil {
		h.reject(w, r, "malformed_json", "snapshot is not valid JSON")
		return
	}
	if err := snap.Validate(now); err != nil {
		h.reject(w, r, "invalid_envelope", err.Error())
		return
	}
	if err := h.Store.Put(&snap, now); err != nil {
		// Not an authentication problem: a stale or out-of-order push. 409
		// tells the publisher to stop retrying this particular body.
		h.Logger.Warn("snapshot refused",
			"requestId", httpx.RequestIDFrom(r.Context()),
			"peer", httpx.ClientIPFrom(r.Context()),
			"instanceId", logging.Sanitize(snap.InstanceID),
			"reason", logging.Sanitize(err.Error()),
			"outcome", "refused")
		httpx.WriteError(w, r, http.StatusConflict, err.Error())
		return
	}

	h.Logger.Info("snapshot accepted",
		"requestId", httpx.RequestIDFrom(r.Context()),
		"peer", httpx.ClientIPFrom(r.Context()),
		"instanceId", logging.Sanitize(snap.InstanceID),
		"generatedAt", snap.GeneratedAt.UTC().Format(time.RFC3339),
		"sections", len(snap.Sections),
		"bytes", len(body),
		"outcome", "accepted")

	httpx.WriteJSON(w, r, http.StatusAccepted, map[string]any{
		"accepted":    true,
		"sections":    snap.SectionNames(),
		"receivedAt":  now.UTC(),
		"generatedAt": snap.GeneratedAt.UTC(),
	})
}

func (h *Handler) handleControl(w http.ResponseWriter, r *http.Request, body []byte) {
	now := h.now()

	var ctrl model.Control
	if err := json.Unmarshal(body, &ctrl); err != nil {
		h.reject(w, r, "malformed_json", "control document is not valid JSON")
		return
	}
	if err := ctrl.Validate(now); err != nil {
		h.reject(w, r, "invalid_envelope", err.Error())
		return
	}

	applied, err := h.Devices.ApplyControl(&ctrl, now)
	if err != nil {
		h.Logger.Error("applying control document failed",
			"requestId", httpx.RequestIDFrom(r.Context()),
			"error", logging.Sanitize(err.Error()))
		httpx.WriteError(w, r, http.StatusInternalServerError, "control document could not be applied")
		return
	}

	h.Logger.Info("control document applied",
		"requestId", httpx.RequestIDFrom(r.Context()),
		"peer", httpx.ClientIPFrom(r.Context()),
		"instanceId", logging.Sanitize(ctrl.InstanceID),
		"principalsUpdated", applied.PrincipalsUpdated,
		"principalsDisabled", applied.PrincipalsDisabled,
		"enrollmentsAdded", applied.EnrollmentsAdded,
		"devicesRevoked", applied.DevicesRevoked,
		"authoritative", ctrl.PrincipalsAuthoritative,
		"outcome", "accepted")

	httpx.WriteJSON(w, r, http.StatusOK, map[string]any{
		"principalsUpdated":  applied.PrincipalsUpdated,
		"principalsDisabled": applied.PrincipalsDisabled,
		"enrollmentsAdded":   applied.EnrollmentsAdded,
		"devicesRevoked":     applied.DevicesRevoked,
		"pendingEnrollments": h.Devices.PendingEnrollments(now),
	})
}

func (h *Handler) handleDevices(w http.ResponseWriter, r *http.Request, _ []byte) {
	now := h.now()
	list := h.Devices.List()
	_, principals, pending := h.Devices.Counts(now)
	httpx.WriteJSON(w, r, http.StatusOK, map[string]any{
		"devices":            list,
		"count":              len(list),
		"principals":         principals,
		"pendingEnrollments": pending,
	})
}

// handleStatus lets secman render "is the relay healthy and current?" on its
// own admin page without exposing any of it publicly.
func (h *Handler) handleStatus(w http.ResponseWriter, r *http.Request, _ []byte) {
	now := h.now()
	accepted, rejected, has := h.Store.Stats()

	deviceCount, principalCount, pending := h.Devices.Counts(now)
	body := map[string]any{
		"hasSnapshot":        has,
		"pushesAccepted":     accepted,
		"pushesRejected":     rejected,
		"devices":            deviceCount,
		"principals":         principalCount,
		"pendingEnrollments": pending,
		"serverTime":         now.UTC(),
	}
	if meta, err := h.Store.Metadata(now, 0); err == nil {
		body["snapshotGeneratedAt"] = meta.GeneratedAt.UTC()
		body["snapshotReceivedAt"] = meta.ReceivedAt.UTC()
		body["snapshotAgeSeconds"] = meta.AgeSeconds
		body["sections"] = meta.Sections
	} else if !errors.Is(err, store.ErrEmpty) {
		h.Logger.Warn("reading snapshot metadata failed", "error", logging.Sanitize(err.Error()))
	}
	httpx.WriteJSON(w, r, http.StatusOK, body)
}

func (h *Handler) reject(w http.ResponseWriter, r *http.Request, reason, message string) {
	h.Store.NoteRejected()
	h.Logger.Warn("ingest payload rejected",
		"requestId", httpx.RequestIDFrom(r.Context()),
		"peer", httpx.ClientIPFrom(r.Context()),
		"path", logging.Sanitize(r.URL.Path),
		"reason", reason,
		"detail", logging.Sanitize(message),
		"outcome", "rejected")
	// The detail is safe to return here: it describes the caller's own
	// document, and the caller is an authenticated secman instance.
	httpx.WriteError(w, r, http.StatusBadRequest, message)
}

func (h *Handler) now() time.Time {
	if h.Now != nil {
		return h.Now()
	}
	return time.Now()
}
