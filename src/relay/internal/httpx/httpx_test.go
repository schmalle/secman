package httpx

import (
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func quietLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func mustCIDR(t *testing.T, s string) *net.IPNet {
	t.Helper()
	_, n, err := net.ParseCIDR(s)
	if err != nil {
		t.Fatalf("ParseCIDR(%q): %v", s, err)
	}
	return n
}

func TestSecurityHeaders(t *testing.T) {
	h := Chain(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}), SecurityHeaders(true))

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/meta", nil))

	want := map[string]string{
		"X-Content-Type-Options":    "nosniff",
		"X-Frame-Options":           "DENY",
		"Referrer-Policy":           "no-referrer",
		"Strict-Transport-Security": "max-age=31536000; includeSubDomains",
	}
	for k, v := range want {
		if got := rec.Header().Get(k); got != v {
			t.Errorf("%s = %q, want %q", k, got, v)
		}
	}
	if csp := rec.Header().Get("Content-Security-Policy"); !strings.Contains(csp, "default-src 'none'") {
		t.Errorf("CSP should deny everything by default, got %q", csp)
	}
	if cc := rec.Header().Get("Cache-Control"); !strings.Contains(cc, "no-store") {
		t.Errorf("responses carry per-device security state and must not be cached, got %q", cc)
	}
}

// HSTS from a plaintext-mode relay would pin clients to a scheme this process
// is not serving; the terminator in front owns that header.
func TestNoHSTSWhenTLSTerminatedElsewhere(t *testing.T) {
	h := Chain(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}), SecurityHeaders(false))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))

	if rec.Header().Get("Strict-Transport-Security") != "" {
		t.Error("plaintext mode must not emit HSTS")
	}
}

func TestCrossOriginPreflightRefused(t *testing.T) {
	h := Chain(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Error("the preflight should not have reached the handler")
	}), RequestID(), NoCORS())

	req := httptest.NewRequest(http.MethodOptions, "/api/v1/status", nil)
	req.Header.Set("Origin", "https://evil.example.com")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Errorf("status = %d, want 403", rec.Code)
	}
	if rec.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Error("no CORS header should ever be emitted")
	}
}

// Trusting X-Forwarded-For unconditionally would let any client pick its own
// rate-limit bucket and its own audit identity.
func TestClientIPIgnoresForwardedHeaderFromUntrustedPeer(t *testing.T) {
	var seen string
	h := Chain(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = ClientIPFrom(r.Context())
	}), ResolveClientIP(nil))

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "203.0.113.9:44321"
	req.Header.Set("X-Forwarded-For", "1.2.3.4")
	h.ServeHTTP(httptest.NewRecorder(), req)

	if seen != "203.0.113.9" {
		t.Errorf("client IP = %q, want the TCP peer 203.0.113.9", seen)
	}
}

func TestClientIPHonoursForwardedHeaderFromTrustedProxy(t *testing.T) {
	trusted := []*net.IPNet{mustCIDR(t, "10.0.0.0/8")}
	var seen string
	h := Chain(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = ClientIPFrom(r.Context())
	}), ResolveClientIP(trusted))

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "10.0.1.5:44321" // the ALB
	req.Header.Set("X-Forwarded-For", "198.51.100.7, 10.0.1.5")
	h.ServeHTTP(httptest.NewRecorder(), req)

	if seen != "198.51.100.7" {
		t.Errorf("client IP = %q, want the real client 198.51.100.7", seen)
	}
}

// A network allowlist that a header can satisfy is not an allowlist.
func TestAllowCIDRsUsesTheTCPPeerNotTheHeader(t *testing.T) {
	h := Chain(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}), RequestID(), AllowCIDRs([]*net.IPNet{mustCIDR(t, "10.0.0.0/8")}, quietLogger()))

	req := httptest.NewRequest(http.MethodPost, "/ingest/v1/snapshot", nil)
	req.RemoteAddr = "203.0.113.9:1234"
	req.Header.Set("X-Forwarded-For", "10.0.0.1")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Errorf("status = %d, want 403 — the header must not satisfy the allowlist", rec.Code)
	}
}

func TestAllowCIDRsPassesPermittedPeer(t *testing.T) {
	h := Chain(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}), RequestID(), AllowCIDRs([]*net.IPNet{mustCIDR(t, "10.0.0.0/8")}, quietLogger()))

	req := httptest.NewRequest(http.MethodPost, "/ingest/v1/snapshot", nil)
	req.RemoteAddr = "10.4.5.6:1234"
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want 200", rec.Code)
	}
}

func TestMaxBodyRejectsOversizedPayload(t *testing.T) {
	h := Chain(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if _, err := io.ReadAll(r.Body); err != nil {
			w.WriteHeader(http.StatusRequestEntityTooLarge)
			return
		}
		w.WriteHeader(http.StatusOK)
	}), MaxBody(64))

	req := httptest.NewRequest(http.MethodPost, "/ingest/v1/snapshot", strings.NewReader(strings.Repeat("a", 1000)))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Errorf("status = %d, want 413", rec.Code)
	}
}

// A stack trace must never reach a client.
func TestRecoverReturnsGenericError(t *testing.T) {
	h := Chain(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		panic("secret internal detail: db password is hunter2")
	}), RequestID(), Recover(quietLogger()))

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/status", nil))

	if rec.Code != http.StatusInternalServerError {
		t.Errorf("status = %d, want 500", rec.Code)
	}
	if strings.Contains(rec.Body.String(), "hunter2") {
		t.Fatal("the panic detail leaked into the response body")
	}
}

// A client-supplied request id would let a caller forge log correlation.
func TestRequestIDIsNotClientControlled(t *testing.T) {
	h := Chain(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}), RequestID())
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("X-Request-Id", "attacker-chosen")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if got := rec.Header().Get("X-Request-Id"); got == "attacker-chosen" || got == "" {
		t.Errorf("X-Request-Id = %q; it must be generated server-side", got)
	}
}

func TestDecodeJSONRejectsUnknownFields(t *testing.T) {
	type payload struct {
		Known string `json:"known"`
	}
	req := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(`{"known":"a","surprise":"b"}`))
	req.Header.Set("Content-Type", "application/json")
	var p payload
	if err := DecodeJSON(req, &p); err == nil {
		t.Fatal("an unknown field should be rejected, not silently dropped")
	}
}

func TestDecodeJSONRejectsTrailingData(t *testing.T) {
	type payload struct {
		Known string `json:"known"`
	}
	req := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(`{"known":"a"}{"known":"b"}`))
	req.Header.Set("Content-Type", "application/json")
	var p payload
	if err := DecodeJSON(req, &p); err == nil {
		t.Fatal("a second JSON document should be rejected")
	}
}

func TestDecodeJSONRejectsWrongContentType(t *testing.T) {
	type payload struct {
		Known string `json:"known"`
	}
	req := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(`{"known":"a"}`))
	req.Header.Set("Content-Type", "text/plain")
	var p payload
	if err := DecodeJSON(req, &p); err == nil {
		t.Fatal("a non-JSON content type should be rejected")
	}
}

// --- rate limiter ----------------------------------------------------------

func TestRateLimiterBurstThenRefill(t *testing.T) {
	l := NewRateLimiter(1, 3)
	base := time.Now()
	l.now = func() time.Time { return base }

	for i := 0; i < 3; i++ {
		if !l.Allow("peer") {
			t.Fatalf("request %d should be inside the burst", i+1)
		}
	}
	if l.Allow("peer") {
		t.Fatal("the fourth request should exceed the burst")
	}

	l.now = func() time.Time { return base.Add(2 * time.Second) }
	if !l.Allow("peer") {
		t.Fatal("the bucket should have refilled after 2s at 1 rps")
	}
}

func TestRateLimiterIsPerClient(t *testing.T) {
	l := NewRateLimiter(1, 1)
	if !l.Allow("a") {
		t.Fatal("first client should be allowed")
	}
	if !l.Allow("b") {
		t.Fatal("a second client must have its own budget")
	}
	if l.Allow("a") {
		t.Fatal("the first client's budget should now be spent")
	}
}

// Enrollment guessing must not be able to exhaust a legitimate device's read
// budget, which is why the families are separate keys.
func TestRateLimitFamiliesAreIndependent(t *testing.T) {
	l := NewRateLimiter(1, 1)
	h := Chain(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}), RequestID(), ResolveClientIP(nil), Limit(l, "enroll", quietLogger()))

	req := httptest.NewRequest(http.MethodPost, "/api/v1/enroll", nil)
	req.RemoteAddr = "203.0.113.9:1234"

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("first request status = %d, want 200", rec.Code)
	}
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("second request status = %d, want 429", rec.Code)
	}
	if rec.Header().Get("Retry-After") == "" {
		t.Error("a 429 should tell the client when to come back")
	}

	// The same peer under a different family still has its own budget.
	readHandler := Chain(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}), RequestID(), ResolveClientIP(nil), Limit(l, "read", quietLogger()))
	rec = httptest.NewRecorder()
	readHandler.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/status", nil))
	if rec.Code != http.StatusOK {
		t.Errorf("a different family should have its own budget, got %d", rec.Code)
	}
}

func TestRateLimiterSweepReleasesIdleBuckets(t *testing.T) {
	l := NewRateLimiter(10, 10)
	base := time.Now()
	l.now = func() time.Time { return base }
	l.Allow("peer")
	if l.Size() != 1 {
		t.Fatalf("expected 1 tracked client, got %d", l.Size())
	}
	l.now = func() time.Time { return base.Add(time.Hour) }
	l.Sweep()
	if l.Size() != 0 {
		t.Errorf("idle buckets should be released, %d remain", l.Size())
	}
}
