package api

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/schmalle/secman/src/relay/internal/auth"
	"github.com/schmalle/secman/src/relay/internal/devices"
	"github.com/schmalle/secman/src/relay/internal/httpx"
	"github.com/schmalle/secman/src/relay/internal/ingest"
	"github.com/schmalle/secman/src/relay/internal/model"
	"github.com/schmalle/secman/src/relay/internal/store"
)

const (
	ingestToken = "an-ingest-token-that-is-long-ok!"
	instanceID  = "secman-prod"
)

var ingestKey = []byte("an-ingest-hmac-key-that-is-long1")

// relay is the whole server wired the way main.go wires it, minus the network.
type relay struct {
	t       *testing.T
	server  *httptest.Server
	store   *store.Store
	devices *devices.Registry
	now     func() time.Time
}

func newRelay(t *testing.T) *relay {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	registry, err := devices.Open(t.TempDir(), 10)
	if err != nil {
		t.Fatalf("opening registry: %v", err)
	}
	snapshots := store.New()
	verifier, err := auth.NewIngestVerifier(ingestToken, ingestKey, 5*time.Minute)
	if err != nil {
		t.Fatalf("verifier: %v", err)
	}
	tokens, err := auth.NewTokenIssuer([]byte("a-token-signing-key-long-enough!"), 15*time.Minute)
	if err != nil {
		t.Fatalf("issuer: %v", err)
	}

	r := &relay{t: t, store: snapshots, devices: registry, now: time.Now}
	clock := func() time.Time { return r.now() }

	mux := http.NewServeMux()
	(&Handler{
		Store:      snapshots,
		Devices:    registry,
		Tokens:     tokens,
		Challenges: auth.NewChallengeStore(2 * time.Minute),
		// Generous limits: these tests are about authorization, and the rate
		// limiter has its own tests in package httpx.
		Limiter: httpx.NewRateLimiter(1000, 1000),
		Logger:  logger,
		MaxAge:  15 * time.Minute,
		Now:     clock,
	}).Routes(mux)
	(&ingest.Handler{
		Verifier: verifier,
		Store:    snapshots,
		Devices:  registry,
		Logger:   logger,
		MaxBody:  1 << 20,
		Now:      clock,
	}).Routes(mux)

	r.server = httptest.NewServer(httpx.Chain(mux,
		httpx.RequestID(),
		httpx.ResolveClientIP(nil),
		httpx.Recover(logger),
		httpx.SecurityHeaders(false),
		httpx.NoCORS(),
		httpx.MaxBody(1<<20),
	))
	t.Cleanup(r.server.Close)
	return r
}

func (r *relay) do(req *http.Request) (*http.Response, []byte) {
	r.t.Helper()
	resp, err := r.server.Client().Do(req)
	if err != nil {
		r.t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	return resp, body
}

// push sends a correctly signed ingest request.
func (r *relay) push(path string, payload any) (*http.Response, []byte) {
	r.t.Helper()
	raw, err := json.Marshal(payload)
	if err != nil {
		r.t.Fatalf("encoding payload: %v", err)
	}
	req, _ := http.NewRequest(http.MethodPost, r.server.URL+path, bytes.NewReader(raw))
	now := r.now()
	nonceBytes := make([]byte, 16)
	_, _ = rand.Read(nonceBytes)
	nonce := hex.EncodeToString(nonceBytes)

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+ingestToken)
	req.Header.Set(auth.HeaderTimestamp, strconv.FormatInt(now.Unix(), 10))
	req.Header.Set(auth.HeaderNonce, nonce)
	req.Header.Set(auth.HeaderSignature, auth.SignPayload(ingestKey, now.Unix(), nonce, raw))
	return r.do(req)
}

func (r *relay) postJSON(path string, payload any, bearer string) (*http.Response, []byte) {
	r.t.Helper()
	raw, _ := json.Marshal(payload)
	req, _ := http.NewRequest(http.MethodPost, r.server.URL+path, bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	return r.do(req)
}

func (r *relay) get(path, bearer string) (*http.Response, []byte) {
	r.t.Helper()
	req, _ := http.NewRequest(http.MethodGet, r.server.URL+path, nil)
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	return r.do(req)
}

func snapshotPayload(generated time.Time) map[string]any {
	return map[string]any{
		"schemaVersion": model.SnapshotSchemaVersion,
		"instanceId":    instanceID,
		"generatedAt":   generated.Format(time.RFC3339Nano),
		"sections": map[string]any{
			"kpis":  map[string]any{"edrCoveragePercent": 97.5},
			"vulns": map[string]any{"critical": 3},
		},
	}
}

func controlPayload(code string, scopes []string, expires time.Time) map[string]any {
	digest := sha256.Sum256([]byte(code))
	return map[string]any{
		"schemaVersion": model.ControlSchemaVersion,
		"instanceId":    instanceID,
		"issuedAt":      time.Now().Format(time.RFC3339Nano),
		"enrollments": []map[string]any{{
			"codeSha256": hex.EncodeToString(digest[:]),
			"subject":    "admin@example.com",
			"scopes":     scopes,
			"expiresAt":  expires.Format(time.RFC3339Nano),
		}},
	}
}

// enrolledDevice runs the full enrollment + authentication journey and returns
// the device id, its key and a usable access token.
func (r *relay) enrolledDevice(code string, scopes []string) (string, *ecdsa.PrivateKey, string) {
	r.t.Helper()

	resp, _ := r.push("/ingest/v1/control", controlPayload(code, scopes, r.now().Add(time.Hour)))
	if resp.StatusCode != http.StatusOK {
		r.t.Fatalf("pushing the control document: status %d", resp.StatusCode)
	}

	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)

	resp, body := r.postJSON("/api/v1/enroll", map[string]any{
		"enrollmentCode": code,
		"publicKey":      base64.StdEncoding.EncodeToString(der),
		"deviceName":     "Test iPhone",
	}, "")
	if resp.StatusCode != http.StatusCreated {
		r.t.Fatalf("enroll: status %d body %s", resp.StatusCode, body)
	}
	var enrolled enrollResponse
	if err := json.Unmarshal(body, &enrolled); err != nil {
		r.t.Fatalf("enroll response: %v", err)
	}

	return enrolled.DeviceID, key, r.authenticate(enrolled.DeviceID, key)
}

func (r *relay) authenticate(deviceID string, key *ecdsa.PrivateKey) string {
	r.t.Helper()

	resp, body := r.postJSON("/api/v1/auth/challenge", map[string]any{"deviceId": deviceID}, "")
	if resp.StatusCode != http.StatusOK {
		r.t.Fatalf("challenge: status %d body %s", resp.StatusCode, body)
	}
	var ch challengeResponse
	if err := json.Unmarshal(body, &ch); err != nil {
		r.t.Fatalf("challenge response: %v", err)
	}

	digest := sha256.Sum256(auth.DeviceSigningInput(deviceID, ch.Nonce))
	sig, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil {
		r.t.Fatalf("signing challenge: %v", err)
	}

	resp, body = r.postJSON("/api/v1/auth/token", map[string]any{
		"deviceId":  deviceID,
		"nonce":     ch.Nonce,
		"signature": base64.StdEncoding.EncodeToString(sig),
	}, "")
	if resp.StatusCode != http.StatusOK {
		r.t.Fatalf("token: status %d body %s", resp.StatusCode, body)
	}
	var tok tokenResponse
	if err := json.Unmarshal(body, &tok); err != nil {
		r.t.Fatalf("token response: %v", err)
	}
	return tok.AccessToken
}

// --- the happy path --------------------------------------------------------

func TestFullJourney(t *testing.T) {
	r := newRelay(t)

	resp, body := r.push("/ingest/v1/snapshot", snapshotPayload(time.Now()))
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("snapshot push: status %d body %s", resp.StatusCode, body)
	}

	_, _, token := r.enrolledDevice("enrollment-code-1", []string{model.ScopeAll})

	resp, body = r.get("/api/v1/status", token)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status read: %d body %s", resp.StatusCode, body)
	}
	var status statusResponse
	if err := json.Unmarshal(body, &status); err != nil {
		t.Fatalf("status response: %v", err)
	}
	if status.InstanceID != instanceID {
		t.Errorf("instanceId = %q, want %q", status.InstanceID, instanceID)
	}
	if status.Stale {
		t.Error("a snapshot pushed a moment ago must not be stale")
	}
	if len(status.Sections) != 2 {
		t.Fatalf("expected 2 sections, got %d", len(status.Sections))
	}
	// The relay must re-serve exactly what secman sent, not a re-interpretation.
	if string(status.Sections["kpis"]) != `{"edrCoveragePercent":97.5}` {
		t.Errorf("section bytes changed in transit: %s", status.Sections["kpis"])
	}
}

// --- the ingest plane ------------------------------------------------------

func TestIngestRequiresAuthentication(t *testing.T) {
	r := newRelay(t)
	raw, _ := json.Marshal(snapshotPayload(time.Now()))

	// No credentials at all.
	resp, _ := r.postJSON("/ingest/v1/snapshot", snapshotPayload(time.Now()), "")
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("unauthenticated push status = %d, want 401", resp.StatusCode)
	}

	// Bearer token but no signature: a stolen token alone must not be enough.
	req, _ := http.NewRequest(http.MethodPost, r.server.URL+"/ingest/v1/snapshot", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+ingestToken)
	resp, _ = r.do(req)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("token-only push status = %d, want 401", resp.StatusCode)
	}
}

func TestIngestRejectsReplayedPush(t *testing.T) {
	r := newRelay(t)
	payload := snapshotPayload(time.Now())
	raw, _ := json.Marshal(payload)

	now := time.Now()
	nonce := "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	build := func() *http.Request {
		req, _ := http.NewRequest(http.MethodPost, r.server.URL+"/ingest/v1/snapshot", bytes.NewReader(raw))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+ingestToken)
		req.Header.Set(auth.HeaderTimestamp, strconv.FormatInt(now.Unix(), 10))
		req.Header.Set(auth.HeaderNonce, nonce)
		req.Header.Set(auth.HeaderSignature, auth.SignPayload(ingestKey, now.Unix(), nonce, raw))
		return req
	}

	if resp, _ := r.do(build()); resp.StatusCode != http.StatusAccepted {
		t.Fatalf("first push status = %d, want 202", resp.StatusCode)
	}
	if resp, _ := r.do(build()); resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("replayed push status = %d, want 401", resp.StatusCode)
	}
}

func TestIngestRejectsUnknownSchemaVersion(t *testing.T) {
	r := newRelay(t)
	payload := snapshotPayload(time.Now())
	payload["schemaVersion"] = 99

	resp, _ := r.push("/ingest/v1/snapshot", payload)
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", resp.StatusCode)
	}
}

func TestIngestRejectsOutOfOrderSnapshot(t *testing.T) {
	r := newRelay(t)
	now := time.Now()

	if resp, _ := r.push("/ingest/v1/snapshot", snapshotPayload(now)); resp.StatusCode != http.StatusAccepted {
		t.Fatal("setup push failed")
	}
	resp, _ := r.push("/ingest/v1/snapshot", snapshotPayload(now.Add(-time.Minute)))
	if resp.StatusCode != http.StatusConflict {
		t.Errorf("status = %d, want 409 for an older snapshot", resp.StatusCode)
	}
}

// --- authorization negatives ------------------------------------------------

func TestReadsRequireAToken(t *testing.T) {
	r := newRelay(t)
	_, _ = r.push("/ingest/v1/snapshot", snapshotPayload(time.Now()))

	for _, path := range []string{"/api/v1/status", "/api/v1/meta", "/api/v1/status/kpis"} {
		resp, _ := r.get(path, "")
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("%s without a token = %d, want 401", path, resp.StatusCode)
		}
		resp, _ = r.get(path, "smrt1.forged.token")
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("%s with a forged token = %d, want 401", path, resp.StatusCode)
		}
	}
}

// A device granted one section must not be able to read another.
func TestScopeIsEnforcedServerSide(t *testing.T) {
	r := newRelay(t)
	_, _ = r.push("/ingest/v1/snapshot", snapshotPayload(time.Now()))
	_, _, token := r.enrolledDevice("narrow-code", []string{"status:kpis"})

	resp, body := r.get("/api/v1/status", token)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status read: %d %s", resp.StatusCode, body)
	}
	var status statusResponse
	_ = json.Unmarshal(body, &status)
	if _, leaked := status.Sections["vulns"]; leaked {
		t.Fatal("an out-of-scope section was returned in the aggregate read")
	}
	if _, ok := status.Sections["kpis"]; !ok {
		t.Error("the in-scope section should be present")
	}

	resp, _ = r.get("/api/v1/status/vulns", token)
	if resp.StatusCode != http.StatusForbidden {
		t.Errorf("out-of-scope section read = %d, want 403", resp.StatusCode)
	}
	resp, _ = r.get("/api/v1/status/kpis", token)
	if resp.StatusCode != http.StatusOK {
		t.Errorf("in-scope section read = %d, want 200", resp.StatusCode)
	}

	// The metadata listing must not name sections the device cannot read.
	resp, body = r.get("/api/v1/meta", token)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("meta read: %d", resp.StatusCode)
	}
	var meta metaResponse
	_ = json.Unmarshal(body, &meta)
	for _, s := range meta.Sections {
		if s == "vulns" {
			t.Error("the metadata listing leaked an out-of-scope section name")
		}
	}
}

// A section that is out of scope answers 403 whether or not it exists, so the
// boundary cannot be used to discover what the relay holds.
func TestOutOfScopeAndNonexistentAreIndistinguishable(t *testing.T) {
	r := newRelay(t)
	_, _ = r.push("/ingest/v1/snapshot", snapshotPayload(time.Now()))
	_, _, token := r.enrolledDevice("narrow-code", []string{"status:kpis"})

	existing, _ := r.get("/api/v1/status/vulns", token)
	missing, _ := r.get("/api/v1/status/does-not-exist", token)
	if existing.StatusCode != missing.StatusCode {
		t.Errorf("an existing out-of-scope section (%d) and a nonexistent one (%d) must answer identically",
			existing.StatusCode, missing.StatusCode)
	}
}

func TestEnrollmentCodeCannotBeGuessedOrReused(t *testing.T) {
	r := newRelay(t)
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	encoded := base64.StdEncoding.EncodeToString(der)

	resp, _ := r.postJSON("/api/v1/enroll", map[string]any{
		"enrollmentCode": "never-issued",
		"publicKey":      encoded,
		"deviceName":     "attacker",
	}, "")
	if resp.StatusCode != http.StatusForbidden {
		t.Errorf("an unissued code = %d, want 403", resp.StatusCode)
	}

	deviceID, _, _ := r.enrolledDevice("one-shot-code", []string{model.ScopeAll})
	if deviceID == "" {
		t.Fatal("enrollment should have produced a device id")
	}
	resp, _ = r.postJSON("/api/v1/enroll", map[string]any{
		"enrollmentCode": "one-shot-code",
		"publicKey":      encoded,
		"deviceName":     "second",
	}, "")
	if resp.StatusCode != http.StatusForbidden {
		t.Errorf("a reused code = %d, want 403", resp.StatusCode)
	}
}

// Holding a device id is not authentication: the private key is.
func TestTokenRequiresPossessionOfTheDeviceKey(t *testing.T) {
	r := newRelay(t)
	deviceID, _, _ := r.enrolledDevice("code-1", []string{model.ScopeAll})

	resp, body := r.postJSON("/api/v1/auth/challenge", map[string]any{"deviceId": deviceID}, "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("challenge: %d %s", resp.StatusCode, body)
	}
	var ch challengeResponse
	_ = json.Unmarshal(body, &ch)

	attacker, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	digest := sha256.Sum256(auth.DeviceSigningInput(deviceID, ch.Nonce))
	sig, _ := ecdsa.SignASN1(rand.Reader, attacker, digest[:])

	resp, _ = r.postJSON("/api/v1/auth/token", map[string]any{
		"deviceId":  deviceID,
		"nonce":     ch.Nonce,
		"signature": base64.StdEncoding.EncodeToString(sig),
	}, "")
	if resp.StatusCode != http.StatusForbidden {
		t.Errorf("a signature from another key = %d, want 403", resp.StatusCode)
	}
}

// Revocation pushed by secman must take effect on the very next request, not
// when the already-issued token would have expired.
func TestRevocationInvalidatesLiveTokens(t *testing.T) {
	r := newRelay(t)
	_, _ = r.push("/ingest/v1/snapshot", snapshotPayload(time.Now()))
	deviceID, _, token := r.enrolledDevice("code-1", []string{model.ScopeAll})

	if resp, _ := r.get("/api/v1/status", token); resp.StatusCode != http.StatusOK {
		t.Fatalf("the device should be able to read before revocation, got %d", resp.StatusCode)
	}

	resp, body := r.push("/ingest/v1/control", map[string]any{
		"schemaVersion": model.ControlSchemaVersion,
		"instanceId":    instanceID,
		"issuedAt":      time.Now().Format(time.RFC3339Nano),
		"revocations": []map[string]any{{
			"deviceId":  deviceID,
			"revokedAt": time.Now().Format(time.RFC3339Nano),
			"reason":    "device lost",
		}},
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("revocation push: %d %s", resp.StatusCode, body)
	}

	if resp, _ := r.get("/api/v1/status", token); resp.StatusCode != http.StatusForbidden {
		t.Errorf("a revoked device = %d, want 403 on the very next request", resp.StatusCode)
	}
}

// --- staleness --------------------------------------------------------------

func TestStaleSnapshotIsLabelledNotHidden(t *testing.T) {
	r := newRelay(t)
	base := time.Now()
	r.now = func() time.Time { return base }

	if resp, _ := r.push("/ingest/v1/snapshot", snapshotPayload(base)); resp.StatusCode != http.StatusAccepted {
		t.Fatal("setup push failed")
	}
	deviceID, key, _ := r.enrolledDevice("code-1", []string{model.ScopeAll})

	// Move the relay's clock an hour ahead of the snapshot. The first access
	// token has expired by then — correctly — so the device re-authenticates,
	// exactly as the app would.
	r.now = func() time.Time { return base.Add(time.Hour) }
	token := r.authenticate(deviceID, key)

	resp, body := r.get("/api/v1/status", token)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("a stale snapshot should still be served: %d", resp.StatusCode)
	}
	var status statusResponse
	_ = json.Unmarshal(body, &status)
	if !status.Stale {
		t.Error("the response must be flagged stale")
	}
	if status.AgeSeconds < 3500 {
		t.Errorf("ageSeconds = %d, want roughly 3600", status.AgeSeconds)
	}
}

func TestReadsBeforeTheFirstPush(t *testing.T) {
	r := newRelay(t)
	_, _, token := r.enrolledDevice("code-1", []string{model.ScopeAll})

	resp, _ := r.get("/api/v1/status", token)
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Errorf("status = %d, want 503 before the first snapshot", resp.StatusCode)
	}
}

// --- the relay is read-only -------------------------------------------------

// There is no write path from a device into secman, and the mobile plane must
// not answer anything but the documented reads.
func TestMobilePlaneExposesNoWriteRoutes(t *testing.T) {
	r := newRelay(t)
	_, _, token := r.enrolledDevice("code-1", []string{model.ScopeAll})

	for _, path := range []string{"/api/v1/status", "/api/v1/meta", "/api/v1/status/kpis"} {
		for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete, http.MethodPatch} {
			req, _ := http.NewRequest(method, r.server.URL+path, nil)
			req.Header.Set("Authorization", "Bearer "+token)
			resp, _ := r.do(req)
			if resp.StatusCode != http.StatusMethodNotAllowed && resp.StatusCode != http.StatusNotFound {
				t.Errorf("%s %s = %d, want 404/405", method, path, resp.StatusCode)
			}
		}
	}
}

// A device token must be worthless on the ingest plane.
func TestDeviceTokenCannotPush(t *testing.T) {
	r := newRelay(t)
	_, _, token := r.enrolledDevice("code-1", []string{model.ScopeAll})

	resp, _ := r.postJSON("/ingest/v1/snapshot", snapshotPayload(time.Now()), token)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("a device token on the ingest plane = %d, want 401", resp.StatusCode)
	}
	resp, _ = r.get("/ingest/v1/devices", token)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("a device token listing devices = %d, want 401", resp.StatusCode)
	}
}

func TestIngestStatusReportsSnapshotHealth(t *testing.T) {
	r := newRelay(t)
	_, _ = r.push("/ingest/v1/snapshot", snapshotPayload(time.Now()))
	_, _, _ = r.enrolledDevice("code-1", []string{model.ScopeAll})

	// GET over the ingest plane still requires a signature over the empty body.
	raw := []byte{}
	now := time.Now()
	nonce := "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	req, _ := http.NewRequest(http.MethodGet, r.server.URL+"/ingest/v1/status", bytes.NewReader(raw))
	req.Header.Set("Authorization", "Bearer "+ingestToken)
	req.Header.Set(auth.HeaderTimestamp, strconv.FormatInt(now.Unix(), 10))
	req.Header.Set(auth.HeaderNonce, nonce)
	req.Header.Set(auth.HeaderSignature, auth.SignPayload(ingestKey, now.Unix(), nonce, raw))

	resp, body := r.do(req)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("ingest status: %d %s", resp.StatusCode, body)
	}
	var status map[string]any
	if err := json.Unmarshal(body, &status); err != nil {
		t.Fatalf("decoding: %v", err)
	}
	if status["hasSnapshot"] != true {
		t.Error("hasSnapshot should be true after a push")
	}
	if status["devices"].(float64) != 1 {
		t.Errorf("devices = %v, want 1", status["devices"])
	}
}
