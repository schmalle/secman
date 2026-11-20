package main

import (
	"crypto/sha256"
	_ "embed"
	"errors"
	"html/template"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/bcrypt"
)

//go:embed pages.html
var source string

//go:embed portal.js
var script string

//go:embed portal.css
var stylesheet string

const sessionCookie = "__Host-supplier_session"

type session struct {
	invitationID string
	expires      time.Time
}

type publicPortal struct {
	store    *store
	origin   string
	pages    *template.Template
	mu       sync.Mutex
	sessions map[[32]byte]session
	attempts map[string]int
	window   time.Time
	checks   chan struct{}
}

type renderedQuestion struct {
	question
	Answer  string
	Comment string
}

type pageData struct {
	Questionnaire questionnaire
	Questions     []renderedQuestion
	Answered      int
	Revision      uint64
	Receipt       *submission
	Error         string
	Review        bool
	Saved         bool
}

func publicHandler(s *store, origin string) http.Handler {
	p := &publicPortal{
		store: s, origin: origin, pages: template.Must(template.New("pages").Parse(source)),
		sessions: make(map[[32]byte]session), attempts: make(map[string]int),
		checks: make(chan struct{}, 8),
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) { p.render(w, "start", pageData{}) })
	mux.HandleFunc("GET /portal.js", asset("text/javascript; charset=utf-8", script))
	mux.HandleFunc("GET /portal.css", asset("text/css; charset=utf-8", stylesheet))
	mux.HandleFunc("POST /redeem", p.redeem)
	mux.HandleFunc("GET /questionnaire", p.questionnaire)
	mux.HandleFunc("POST /questionnaire", p.write)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Strict-Transport-Security", "max-age=31536000")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'self'; script-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
		if r.Method == http.MethodPost {
			if !p.sameOrigin(r) {
				http.Error(w, "Request unavailable", http.StatusForbidden)
				return
			}
			r.Body = http.MaxBytesReader(w, r.Body, maxBody)
		}
		mux.ServeHTTP(w, r)
	})
}

func (p *publicPortal) sameOrigin(r *http.Request) bool {
	origin, site := r.Header.Get("Origin"), r.Header.Get("Sec-Fetch-Site")
	if site != "" && site != "same-origin" {
		return false
	}
	// Browsers can send a null Origin for a form navigation under no-referrer.
	// Fetch Metadata still attests whether the form was submitted from this origin.
	return origin == p.origin || (origin == "null" && site == "same-origin")
}

func asset(contentType, body string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", contentType)
		if _, err := w.Write([]byte(body)); err != nil {
			slog.Debug("supplier asset", "outcome", "response interrupted")
		}
	}
}

func (p *publicPortal) redeem(w http.ResponseWriter, r *http.Request) {
	if !p.allowRedemption(r.RemoteAddr) {
		w.Header().Set("Retry-After", "60")
		http.Error(w, "Please try again later", http.StatusTooManyRequests)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 1024)
	if err := r.ParseForm(); err != nil {
		p.unavailable(w)
		return
	}
	id, secret, ok := strings.Cut(r.PostForm.Get("invitation"), ".")
	if !ok || !opaqueID.MatchString(id) || !credential.MatchString(secret) {
		p.unavailable(w)
		return
	}
	p.store.mu.Lock()
	record, err := p.store.load(id)
	p.store.mu.Unlock()
	now := time.Now()
	if err != nil || !record.active(now) {
		p.unavailable(w)
		return
	}
	select {
	case p.checks <- struct{}{}:
		defer func() { <-p.checks }()
	default:
		w.Header().Set("Retry-After", "10")
		http.Error(w, "Please try again later", http.StatusTooManyRequests)
		return
	}
	if bcrypt.CompareHashAndPassword([]byte(record.Provision.Verifier), []byte(secret)) != nil {
		p.unavailable(w)
		return
	}
	_, raw, err := randomValues()
	if err != nil || !p.putSession(raw, session{id, now.Add(30 * time.Minute)}, now) {
		http.Error(w, "Please try again later", http.StatusServiceUnavailable)
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name: sessionCookie, Value: raw, Path: "/", Secure: true, HttpOnly: true,
		SameSite: http.SameSiteStrictMode, MaxAge: 1800,
	})
	http.Redirect(w, r, "/questionnaire", http.StatusSeeOther)
}

func (p *publicPortal) allowRedemption(peer string) bool {
	ip, _, err := net.SplitHostPort(peer)
	if err != nil {
		ip = "unknown"
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	now := time.Now()
	if now.Sub(p.window) >= time.Minute {
		clear(p.attempts)
		p.window = now
	}
	if p.attempts[ip] >= 30 || (len(p.attempts) >= 4096 && p.attempts[ip] == 0) {
		return false
	}
	p.attempts[ip]++
	return true
}

func (p *publicPortal) putSession(raw string, value session, now time.Time) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.sessions) >= 8192 {
		for key, current := range p.sessions {
			if !now.Before(current.expires) {
				delete(p.sessions, key)
			}
		}
	}
	if len(p.sessions) >= 8192 {
		return false
	}
	p.sessions[sha256.Sum256([]byte(raw))] = value
	return true
}

func (p *publicPortal) sessionID(r *http.Request) string {
	cookie, err := r.Cookie(sessionCookie)
	if err != nil || !credential.MatchString(cookie.Value) {
		return ""
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	key := sha256.Sum256([]byte(cookie.Value))
	value := p.sessions[key]
	if !time.Now().Before(value.expires) {
		delete(p.sessions, key)
		return ""
	}
	return value.invitationID
}

func (i invitation) active(now time.Time) bool {
	return !i.Revoked && now.Before(i.Provision.ExpiresAt) && now.Before(i.Provision.Questionnaire.Deadline)
}

func (p *publicPortal) questionnaire(w http.ResponseWriter, r *http.Request) {
	id := p.sessionID(r)
	p.store.mu.Lock()
	record, err := p.store.load(id)
	p.store.mu.Unlock()
	if err != nil || !record.active(time.Now()) {
		p.unavailable(w)
		return
	}
	data := dataFor(record)
	data.Review = r.URL.Query().Get("review") == "1"
	data.Saved = r.URL.Query().Get("saved") == "1"
	p.render(w, "questionnaire", data)
}

func (p *publicPortal) write(w http.ResponseWriter, r *http.Request) {
	id := p.sessionID(r)
	if err := r.ParseForm(); err != nil {
		http.Error(w, "Invalid answers", http.StatusBadRequest)
		return
	}
	p.store.mu.Lock()
	defer p.store.mu.Unlock()
	record, err := p.store.load(id)
	now := time.Now()
	if err != nil || !record.active(now) {
		p.unavailable(w)
		return
	}
	if record.Submission != nil {
		http.Redirect(w, r, "/questionnaire", http.StatusSeeOther)
		return
	}
	action := r.PostForm.Get("action")
	revision, err := strconv.ParseUint(r.PostForm.Get("revision"), 10, 64)
	if err != nil || revision != record.Revision {
		http.Error(w, "A different tab saved a newer draft. Reload the questionnaire before continuing.", http.StatusConflict)
		return
	}
	var target string
	switch action {
	case "save", "review":
		answers, err := parseAnswers(r, record)
		if err != nil {
			http.Error(w, "Check the answer types and comment lengths.", http.StatusBadRequest)
			return
		}
		record.Draft = answers
		record.Revision++
		target = "/questionnaire?saved=1"
		if action == "review" {
			target = "/questionnaire?review=1"
		}
	case "complete":
		if err := complete(&record, now); err != nil {
			if errors.Is(err, errIncomplete) {
				http.Error(w, "Answer every question and explain each Not applicable answer.", http.StatusUnprocessableEntity)
			} else {
				http.Error(w, "Please try again later", http.StatusServiceUnavailable)
			}
			return
		}
		target = "/questionnaire"
	default:
		http.Error(w, "Invalid action", http.StatusBadRequest)
		return
	}
	if err := p.store.save(id, record); err != nil {
		slog.Warn("supplier questionnaire", "actor", "respondent", "target", id, "outcome", "storage failure")
		http.Error(w, "Your changes could not be saved. Please retry.", http.StatusServiceUnavailable)
		return
	}
	if action == "complete" {
		slog.Info("supplier questionnaire", "actor", "respondent", "target", id, "outcome", "completed")
	}
	http.Redirect(w, r, target, http.StatusSeeOther)
}

func parseAnswers(r *http.Request, record invitation) ([]answer, error) {
	answers := make([]answer, 0, len(record.Provision.Questionnaire.Questions))
	for _, q := range record.Provision.Questionnaire.Questions {
		choice := r.PostForm.Get("answer-" + q.Key)
		comment := r.PostForm.Get("comment-" + q.Key)
		if (choice != "" && choice != "YES" && choice != "NO" && choice != "N_A") ||
			(comment != "" && !validText(comment, 4000)) || strings.ContainsRune(comment, 0) {
			return nil, errIncomplete
		}
		answers = append(answers, answer{q.Key, choice, comment})
	}
	return answers, nil
}

func complete(record *invitation, now time.Time) error {
	q := record.Provision.Questionnaire
	if len(record.Draft) != len(q.Questions) {
		return errIncomplete
	}
	for _, answer := range record.Draft {
		if answer.Answer == "" || (answer.Answer == "N_A" && strings.TrimSpace(answer.Comment) == "") {
			return errIncomplete
		}
	}
	id, _, err := randomValues()
	if err != nil {
		return err
	}
	record.Submission = &submission{1, q.InvitationID, q.Generation, q.SnapshotHash, id, now.UTC(), record.Draft}
	record.Draft = nil
	return nil
}

func dataFor(record invitation) pageData {
	answers := make(map[string]answer, len(record.Draft))
	for _, answer := range record.Draft {
		answers[answer.QuestionKey] = answer
	}
	data := pageData{Questionnaire: record.Provision.Questionnaire, Revision: record.Revision, Receipt: record.Submission}
	for _, q := range data.Questionnaire.Questions {
		a := answers[q.Key]
		data.Questions = append(data.Questions, renderedQuestion{q, a.Answer, a.Comment})
		if a.Answer != "" {
			data.Answered++
		}
	}
	return data
}

func (p *publicPortal) unavailable(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusNotFound)
	p.render(w, "start", pageData{Error: "This invitation is unavailable. Reopen your email link or contact the requester."})
}

func (p *publicPortal) render(w http.ResponseWriter, page string, data pageData) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := p.pages.ExecuteTemplate(w, page, data); err != nil {
		slog.Warn("render supplier portal", "outcome", "response interrupted")
	}
}
