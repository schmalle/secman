// Package httpx holds the transport-level controls every relay route inherits:
// security headers, body limits, per-client rate limiting, request identity,
// panic containment and access logging.
//
// These live in one place rather than in each handler because a control that
// has to be remembered per route is a control that will eventually be
// forgotten on one.
package httpx

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/schmalle/secman/src/relay/internal/logging"
)

type ctxKey int

const (
	ctxKeyRequestID ctxKey = iota
	ctxKeyClientIP
)

// Middleware is the standard decorator shape.
type Middleware func(http.Handler) http.Handler

// Chain applies middleware so that the first argument is the outermost layer.
func Chain(h http.Handler, mw ...Middleware) http.Handler {
	for i := len(mw) - 1; i >= 0; i-- {
		h = mw[i](h)
	}
	return h
}

// SecurityHeaders sets the response headers for an API that serves JSON to a
// native app and must never be framed, sniffed or cached.
//
// The CSP is `default-src 'none'` because nothing here is a web page: there is
// no script, style, image or frame to allow. If a future browser-based console
// is added it gets its own handler and its own policy — this one is not
// loosened.
func SecurityHeaders(tlsEnabled bool) Middleware {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			h := w.Header()
			h.Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
			h.Set("X-Content-Type-Options", "nosniff")
			h.Set("X-Frame-Options", "DENY")
			h.Set("Referrer-Policy", "no-referrer")
			h.Set("Cross-Origin-Resource-Policy", "same-origin")
			h.Set("Cross-Origin-Opener-Policy", "same-origin")
			h.Set("Permissions-Policy", "accelerometer=(), camera=(), geolocation=(), gyroscope=(), microphone=(), payment=(), usb=()")
			// Every response carries security state for one device. None of it
			// may be cached by anything between the relay and the app.
			h.Set("Cache-Control", "no-store, no-cache, must-revalidate, private")
			h.Set("Pragma", "no-cache")
			if tlsEnabled {
				// Only when the relay itself terminates TLS. Behind an ALB the
				// terminator owns HSTS; emitting it from here would let a
				// plaintext-mode misconfiguration pin clients to a scheme this
				// process is not actually serving.
				h.Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
			}
			next.ServeHTTP(w, r)
		})
	}
}

// NoCORS answers every cross-origin preflight with a refusal.
//
// The relay's clients are native apps, which do not send preflights. A browser
// that tries is not a supported client, and there is no origin allowlist to get
// wrong because there is no allowed origin at all.
func NoCORS() Middleware {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method == http.MethodOptions && r.Header.Get("Origin") != "" {
				WriteError(w, r, http.StatusForbidden, "cross-origin requests are not supported")
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// MaxBody caps the request body. http.MaxBytesReader makes the limit enforced
// by the reader itself, so a handler cannot accidentally read past it.
func MaxBody(limit int64) Middleware {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Body != nil {
				r.Body = http.MaxBytesReader(w, r.Body, limit)
			}
			next.ServeHTTP(w, r)
		})
	}
}

// RequestID attaches a random id to the request context and echoes it back.
// A client-supplied X-Request-Id is deliberately ignored: it would let a caller
// forge or collide log correlation ids.
func RequestID() Middleware {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			buf := make([]byte, 8)
			if _, err := rand.Read(buf); err != nil {
				// A failing CSPRNG is not a reason to drop the request; only
				// correlation suffers.
				buf = []byte("00000000")
			}
			id := hex.EncodeToString(buf)
			w.Header().Set("X-Request-Id", id)
			ctx := context.WithValue(r.Context(), ctxKeyRequestID, id)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// RequestIDFrom reads the id attached by RequestID.
func RequestIDFrom(ctx context.Context) string {
	if v, ok := ctx.Value(ctxKeyRequestID).(string); ok {
		return v
	}
	return ""
}

// ResolveClientIP determines the peer address and stores it in the context.
//
// X-Forwarded-For is honoured only when the TCP peer is inside one of the
// configured trusted CIDRs, and only its right-most entry that is not itself a
// trusted proxy is taken. Trusting the header unconditionally — the default in
// a lot of code — lets any client set its own rate-limit bucket and its own
// audit-log identity by sending one header.
func ResolveClientIP(trusted []*net.IPNet) Middleware {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ip := clientIP(r, trusted)
			ctx := context.WithValue(r.Context(), ctxKeyClientIP, ip)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// ClientIPFrom reads the resolved peer address.
func ClientIPFrom(ctx context.Context) string {
	if v, ok := ctx.Value(ctxKeyClientIP).(string); ok {
		return v
	}
	return "unknown"
}

func clientIP(r *http.Request, trusted []*net.IPNet) string {
	peerHost, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		peerHost = r.RemoteAddr
	}
	peer := net.ParseIP(peerHost)
	if peer == nil || len(trusted) == 0 || !ipInAny(peer, trusted) {
		return peerHost
	}

	xff := r.Header.Get("X-Forwarded-For")
	if xff == "" {
		return peerHost
	}
	parts := strings.Split(xff, ",")
	for i := len(parts) - 1; i >= 0; i-- {
		candidate := net.ParseIP(strings.TrimSpace(parts[i]))
		if candidate == nil {
			continue
		}
		if ipInAny(candidate, trusted) {
			continue // another hop of our own infrastructure
		}
		return candidate.String()
	}
	return peerHost
}

func ipInAny(ip net.IP, nets []*net.IPNet) bool {
	for _, n := range nets {
		if n.Contains(ip) {
			return true
		}
	}
	return false
}

// AllowCIDRs refuses any request whose peer is outside the given networks.
// Empty means "no network restriction"; authentication still applies. Used to
// pin the ingest plane to the secman egress range when one is known.
//
// This check uses the TCP peer address, never X-Forwarded-For: a network
// allowlist that can be satisfied by a header is not an allowlist.
func AllowCIDRs(nets []*net.IPNet, logger *slog.Logger) Middleware {
	return func(next http.Handler) http.Handler {
		if len(nets) == 0 {
			return next
		}
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			host, _, err := net.SplitHostPort(r.RemoteAddr)
			if err != nil {
				host = r.RemoteAddr
			}
			ip := net.ParseIP(host)
			if ip == nil || !ipInAny(ip, nets) {
				logger.Warn("request refused by network allowlist",
					"peer", logging.Sanitize(host),
					"path", logging.Sanitize(r.URL.Path))
				WriteError(w, r, http.StatusForbidden, "not permitted from this network")
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

// Recover turns a handler panic into a 500 with a generic body, and logs the
// detail server-side. A stack trace must never reach a client (A05).
func Recover(logger *slog.Logger) Middleware {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			defer func() {
				if rec := recover(); rec != nil {
					if errors.Is(r.Context().Err(), context.Canceled) {
						return
					}
					logger.Error("handler panic",
						"requestId", RequestIDFrom(r.Context()),
						"path", logging.Sanitize(r.URL.Path),
						"panic", logging.Sanitize(toString(rec)))
					WriteError(w, r, http.StatusInternalServerError, "internal error")
				}
			}()
			next.ServeHTTP(w, r)
		})
	}
}

// AccessLog records actor, target and outcome for every request (A09).
func AccessLog(logger *slog.Logger) Middleware {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
			next.ServeHTTP(rec, r)
			logger.Info("request",
				"requestId", RequestIDFrom(r.Context()),
				"method", r.Method,
				"path", logging.Sanitize(r.URL.Path),
				"status", rec.status,
				"bytes", rec.written,
				"peer", ClientIPFrom(r.Context()),
				"durationMs", time.Since(start).Milliseconds())
		})
	}
}

type statusRecorder struct {
	http.ResponseWriter
	status  int
	written int
}

func (s *statusRecorder) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

func (s *statusRecorder) Write(b []byte) (int, error) {
	n, err := s.ResponseWriter.Write(b)
	s.written += n
	return n, err
}

// ErrorBody is the only error shape the relay returns. It carries a generic
// message and the request id — enough for a user to quote in a support
// request, nothing an attacker can learn from.
type ErrorBody struct {
	Error     string `json:"error"`
	RequestID string `json:"requestId,omitempty"`
}

// WriteError renders a JSON error.
func WriteError(w http.ResponseWriter, r *http.Request, status int, message string) {
	WriteJSON(w, r, status, ErrorBody{Error: message, RequestID: RequestIDFrom(r.Context())})
}

// WriteJSON renders a JSON response.
func WriteJSON(w http.ResponseWriter, r *http.Request, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if r != nil && r.Method == http.MethodHead {
		return
	}
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(true)
	_ = enc.Encode(body)
}

// DecodeJSON reads a request body into v, refusing unknown fields and trailing
// data.
//
// DisallowUnknownFields is a deliberate choice: silently ignoring a field the
// client thought it was sending is exactly the failure mode that makes
// contract drift invisible.
func DecodeJSON(r *http.Request, v any) error {
	if ct := r.Header.Get("Content-Type"); ct != "" {
		base, _, _ := strings.Cut(ct, ";")
		if !strings.EqualFold(strings.TrimSpace(base), "application/json") {
			return errors.New("Content-Type must be application/json")
		}
	}
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		return errors.New("request body is not valid JSON for this endpoint")
	}
	if dec.More() {
		return errors.New("request body must contain exactly one JSON object")
	}
	return nil
}

func toString(v any) string {
	switch t := v.(type) {
	case string:
		return t
	case error:
		return t.Error()
	default:
		return "non-string panic value"
	}
}
