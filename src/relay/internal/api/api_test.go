package api

import (
	"bytes"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/schmalle/secman/src/relay/internal/auth"
	"github.com/schmalle/secman/src/relay/internal/devices"
	"github.com/schmalle/secman/src/relay/internal/httpx"
	"github.com/schmalle/secman/src/relay/internal/idp"
	"github.com/schmalle/secman/src/relay/internal/ingest"
	"github.com/schmalle/secman/src/relay/internal/model"
	"github.com/schmalle/secman/src/relay/internal/store"
)

const (
	ingestToken = "an-ingest-token-that-is-long-ok!"
	instanceID  = "secman-prod"
	appAudience = "com.example.secman"
)

var ingestKey = []byte("an-ingest-hmac-key-that-is-long1")

// --- mock identity provider --------------------------------------------------

var (
	oidcKeyOnce sync.Once
	oidcKey     *rsa.PrivateKey
)

func idpKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	oidcKeyOnce.Do(func() {
		k, err := rsa.GenerateKey(rand.Reader, 2048)
		if err != nil {
			panic(err)
		}
		oidcKey = k
	})
	return oidcKey
}

type mockIDP struct {
	t      *testing.T
	server *httptest.Server
	key    *rsa.PrivateKey
}

func newMockIDP(t *testing.T) *mockIDP {
	t.Helper()
	m := &mockIDP{t: t, key: idpKey(t)}
	m.server = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"keys": []map[string]string{{
			"kty": "RSA", "kid": "k1", "use": "sig", "alg": "RS256",
			"n": base64.RawURLEncoding.EncodeToString(m.key.PublicKey.N.Bytes()),
			"e": base64.RawURLEncoding.EncodeToString(big.NewInt(int64(m.key.PublicKey.E)).Bytes()),
		}}})
	}))
	t.Cleanup(m.server.Close)
	return m
}

func (m *mockIDP) idToken(subject, nonce string) string {
	m.t.Helper()
	header, _ := json.Marshal(map[string]string{"alg": "RS256", "kid": "k1", "typ": "JWT"})
	payload, _ := json.Marshal(map[string]any{
		"iss":   "https://issuer.example.com",
		"sub":   subject,
		"aud":   appAudience,
		"exp":   time.Now().Add(10 * time.Minute).Unix(),
		"iat":   time.Now().Unix(),
		"nonce": idp.HashNonce(nonce),
	})
	signingInput := base64.RawURLEncoding.EncodeToString(header) + "." + base64.RawURLEncoding.EncodeToString(payload)
	digest := sha256.Sum256([]byte(signingInput))
	sig, err := rsa.SignPKCS1v15(rand.Reader, m.key, crypto.SHA256, digest[:])
	if err != nil {
		m.t.Fatalf("signing id token: %v", err)
	}
	return signingInput + "." + base64.RawURLEncoding.EncodeToString(sig)
}

func (m *mockIDP) verifier(t *testing.T, provider string) *idp.Verifier {
	t.Helper()
	v, err := idp.NewVerifier(idp.OIDCConfig{
		Provider:  provider,
		Issuers:   []string{"https://issuer.example.com"},
		Audiences: []string{appAudience},
		JWKSURL:   m.server.URL + "/keys",
	}, m.server.Client())
	if err != nil {
		t.Fatalf("NewVerifier: %v", err)
	}
	return v
}

// --- the relay under test ----------------------------------------------------

type relay struct {
	t       *testing.T
	server  *httptest.Server
	store   *store.Store
	devices *devices.Registry
	idp     *mockIDP
	now     func() time.Time
}

func newRelay(t *testing.T) *relay {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	registry, err := devices.Open(t.TempDir(), 10, devices.Policy{
		PrivilegedRoles: []string{"ADMIN"},
		StrongProviders: []string{"apple", "google"},
	})
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

	mock := newMockIDP(t)
	r := &relay{t: t, store: snapshots, devices: registry, idp: mock, now: time.Now}
	clock := func() time.Time { return r.now() }

	mux := http.NewServeMux()
	(&Handler{
		Store:      snapshots,
		Devices:    registry,
		Tokens:     tokens,
		Challenges: auth.NewChallengeStore(2 * time.Minute),
		// Generous limits: these tests are about authorization; the rate
		// limiter has its own tests in package httpx.
		Limiter: httpx.NewRateLimiter(1000, 1000),
		Logger:  logger,
		MaxAge:  15 * time.Minute,
		Verifiers: map[string]*idp.Verifier{
			"apple":  mock.verifier(t, "apple"),
			"google": mock.verifier(t, "google"),
		},
		LoginNonces:            idp.NewEphemeralStore(idp.TicketTTL, 100),
		States:                 idp.NewEphemeralStore(idp.StateTTL, 100),
		Tickets:                idp.NewEphemeralStore(idp.TicketTTL, 100),
		EnrollmentCodesEnabled: true,
		Now:                    clock,
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
	// Never follow the custom-scheme redirect: the test wants to inspect it.
	client := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}}
	resp, err := client.Do(req)
	if err != nil {
		r.t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	return resp, body
}

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

// --- fixtures ----------------------------------------------------------------

// snapshotPayload mirrors what secman pushes: opaque section bodies plus the
// role gate each section's originating controller enforces.
func snapshotPayload(generated time.Time) map[string]any {
	return map[string]any{
		"schemaVersion": model.SnapshotSchemaVersion,
		"instanceId":    instanceID,
		"generatedAt":   generated.Format(time.RFC3339Nano),
		"sections": map[string]any{
			"kpis":    map[string]any{"edrCoveragePercent": 97.5},
			"imports": map[string]any{"crowdstrike": map[string]any{"available": true}},
		},
		"policy": map[string]any{
			"kpis":    map[string]any{"requiredRoles": []string{"ADMIN", "SECCHAMPION"}},
			"imports": map[string]any{"requiredRoles": []string{"ADMIN", "VULN"}},
		},
	}
}

type principalSpec struct {
	subject    string
	roles      []string
	appleSub   string
	githubSub  string
	enrollCode string
}

func (r *relay) seed(specs ...principalSpec) {
	r.t.Helper()

	principals := make([]map[string]any, 0, len(specs))
	enrollments := make([]map[string]any, 0)
	for _, s := range specs {
		identities := make([]map[string]any, 0, 2)
		if s.appleSub != "" {
			identities = append(identities, map[string]any{"provider": "apple", "subject": s.appleSub})
		}
		if s.githubSub != "" {
			identities = append(identities, map[string]any{"provider": "github", "subject": s.githubSub})
		}
		principals = append(principals, map[string]any{
			"subject":    s.subject,
			"roles":      s.roles,
			"identities": identities,
		})
		if s.enrollCode != "" {
			digest := sha256.Sum256([]byte(s.enrollCode))
			enrollments = append(enrollments, map[string]any{
				"codeSha256": hex.EncodeToString(digest[:]),
				"subject":    s.subject,
				"scopes":     []string{model.ScopeAll},
				"expiresAt":  r.now().Add(time.Hour).Format(time.RFC3339Nano),
			})
		}
	}

	resp, body := r.push("/ingest/v1/control", map[string]any{
		"schemaVersion":           model.ControlSchemaVersion,
		"instanceId":              instanceID,
		"issuedAt":                r.now().Format(time.RFC3339Nano),
		"principalsAuthoritative": true,
		"principals":              principals,
		"enrollments":             enrollments,
	})
	if resp.StatusCode != http.StatusOK {
		r.t.Fatalf("seeding control document: status %d body %s", resp.StatusCode, body)
	}
}

// signIn runs the full Apple/Google journey and returns the device id, its key
// and a usable access token.
func (r *relay) signIn(provider, providerSubject string) (string, *ecdsa.PrivateKey, string) {
	r.t.Helper()

	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	encodedKey := base64.StdEncoding.EncodeToString(der)

	resp, body := r.postJSON("/api/v1/auth/nonce", map[string]any{"publicKey": encodedKey}, "")
	if resp.StatusCode != http.StatusOK {
		r.t.Fatalf("nonce: status %d body %s", resp.StatusCode, body)
	}
	var issued nonceResponse
	if err := json.Unmarshal(body, &issued); err != nil {
		r.t.Fatalf("nonce response: %v", err)
	}

	resp, body = r.postJSON("/api/v1/auth/oidc", map[string]any{
		"provider":   provider,
		"idToken":    r.idp.idToken(providerSubject, issued.Nonce),
		"nonce":      issued.Nonce,
		"publicKey":  encodedKey,
		"signature":  signBinding(r.t, key, issued.Nonce, der),
		"deviceName": "Test iPhone",
	}, "")
	if resp.StatusCode != http.StatusCreated {
		r.t.Fatalf("oidc bind: status %d body %s", resp.StatusCode, body)
	}
	var bound bindResponse
	if err := json.Unmarshal(body, &bound); err != nil {
		r.t.Fatalf("bind response: %v", err)
	}
	return bound.DeviceID, key, r.authenticate(bound.DeviceID, key)
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

func signBinding(t *testing.T, key *ecdsa.PrivateKey, nonce string, der []byte) string {
	t.Helper()
	digest := sha256.Sum256(auth.DeviceBindingInput(nonce, idp.Fingerprint(der)))
	sig, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil {
		t.Fatalf("signing binding: %v", err)
	}
	return base64.StdEncoding.EncodeToString(sig)
}

func newDeviceKey(t *testing.T) (*ecdsa.PrivateKey, []byte, string) {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	return key, der, base64.StdEncoding.EncodeToString(der)
}

// --- the happy path ----------------------------------------------------------

func TestFullJourneyWithAppleSignIn(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{subject: "boss", roles: []string{"ADMIN"}, appleSub: "apple-boss"})

	if resp, body := r.push("/ingest/v1/snapshot", snapshotPayload(time.Now())); resp.StatusCode != http.StatusAccepted {
		t.Fatalf("snapshot push: status %d body %s", resp.StatusCode, body)
	}

	_, _, token := r.signIn("apple", "apple-boss")

	resp, body := r.get("/api/v1/status", token)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status read: %d body %s", resp.StatusCode, body)
	}
	var status statusResponse
	if err := json.Unmarshal(body, &status); err != nil {
		t.Fatalf("status response: %v", err)
	}
	if status.InstanceID != instanceID {
		t.Errorf("instanceId = %q", status.InstanceID)
	}
	if len(status.Sections) != 2 {
		t.Fatalf("an ADMIN should see both sections, got %d", len(status.Sections))
	}
	// The relay must re-serve exactly what secman sent.
	if string(status.Sections["kpis"]) != `{"edrCoveragePercent":97.5}` {
		t.Errorf("section bytes changed in transit: %s", status.Sections["kpis"])
	}
}

// --- the RBAC mirror ---------------------------------------------------------

// This is the requirement in one test: a user sees on the phone exactly the
// sections their secman roles allow, and nothing else.
func TestSectionVisibilityMirrorsSecmanRoles(t *testing.T) {
	r := newRelay(t)
	r.seed(
		principalSpec{subject: "boss", roles: []string{"ADMIN"}, appleSub: "apple-boss"},
		principalSpec{subject: "champ", roles: []string{"SECCHAMPION"}, appleSub: "apple-champ"},
		principalSpec{subject: "scanner", roles: []string{"VULN"}, appleSub: "apple-scanner"},
		principalSpec{subject: "plain", roles: []string{"USER"}, appleSub: "apple-plain"},
	)
	if resp, _ := r.push("/ingest/v1/snapshot", snapshotPayload(time.Now())); resp.StatusCode != http.StatusAccepted {
		t.Fatal("setup push failed")
	}

	cases := []struct {
		appleSub string
		want     map[string]bool // section -> visible
	}{
		{"apple-boss", map[string]bool{"kpis": true, "imports": true}},
		{"apple-champ", map[string]bool{"kpis": true, "imports": false}},
		{"apple-scanner", map[string]bool{"kpis": false, "imports": true}},
		{"apple-plain", map[string]bool{"kpis": false, "imports": false}},
	}
	for _, tc := range cases {
		_, _, token := r.signIn("apple", tc.appleSub)

		resp, body := r.get("/api/v1/status", token)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("%s: status read %d", tc.appleSub, resp.StatusCode)
		}
		var status statusResponse
		_ = json.Unmarshal(body, &status)

		for section, wantVisible := range tc.want {
			_, got := status.Sections[section]
			if got != wantVisible {
				t.Errorf("%s: section %q visible=%v, want %v", tc.appleSub, section, got, wantVisible)
			}

			resp, _ := r.get("/api/v1/status/"+section, token)
			wantStatus := http.StatusForbidden
			if wantVisible {
				wantStatus = http.StatusOK
			}
			if resp.StatusCode != wantStatus {
				t.Errorf("%s: GET /status/%s = %d, want %d", tc.appleSub, section, resp.StatusCode, wantStatus)
			}
		}
	}
}

// A demotion in secman must reach the phone on the next request, without the
// app having to re-authenticate.
func TestDemotionTakesEffectImmediately(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{subject: "boss", roles: []string{"ADMIN"}, appleSub: "apple-boss"})
	if resp, _ := r.push("/ingest/v1/snapshot", snapshotPayload(time.Now())); resp.StatusCode != http.StatusAccepted {
		t.Fatal("setup push failed")
	}
	_, _, token := r.signIn("apple", "apple-boss")

	if resp, _ := r.get("/api/v1/status/kpis", token); resp.StatusCode != http.StatusOK {
		t.Fatalf("the admin should be able to read kpis first, got %d", resp.StatusCode)
	}

	// secman demotes them to VULN. Note the same token is still in hand.
	r.seed(principalSpec{subject: "boss", roles: []string{"VULN"}, appleSub: "apple-boss"})

	if resp, _ := r.get("/api/v1/status/kpis", token); resp.StatusCode != http.StatusForbidden {
		t.Errorf("kpis after demotion = %d, want 403 on the very next request", resp.StatusCode)
	}
	if resp, _ := r.get("/api/v1/status/imports", token); resp.StatusCode != http.StatusOK {
		t.Errorf("imports should still be readable by a VULN user, got %d", resp.StatusCode)
	}
}

// A section out of the caller's role, out of their scope, or simply absent must
// be indistinguishable.
func TestForbiddenAndNonexistentLookTheSame(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{subject: "scanner", roles: []string{"VULN"}, appleSub: "apple-scanner"})
	if resp, _ := r.push("/ingest/v1/snapshot", snapshotPayload(time.Now())); resp.StatusCode != http.StatusAccepted {
		t.Fatal("setup push failed")
	}
	_, _, token := r.signIn("apple", "apple-scanner")

	forbidden, _ := r.get("/api/v1/status/kpis", token)
	missing, _ := r.get("/api/v1/status/does-not-exist", token)
	if forbidden.StatusCode != missing.StatusCode {
		t.Errorf("an existing-but-forbidden section (%d) and a nonexistent one (%d) must answer identically",
			forbidden.StatusCode, missing.StatusCode)
	}
}

// The metadata and session views must not name sections the caller cannot read.
func TestListingsDoNotLeakHiddenSections(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{subject: "scanner", roles: []string{"VULN"}, appleSub: "apple-scanner"})
	if resp, _ := r.push("/ingest/v1/snapshot", snapshotPayload(time.Now())); resp.StatusCode != http.StatusAccepted {
		t.Fatal("setup push failed")
	}
	_, _, token := r.signIn("apple", "apple-scanner")

	for _, path := range []string{"/api/v1/meta", "/api/v1/session"} {
		resp, body := r.get(path, token)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("%s: %d", path, resp.StatusCode)
		}
		var payload struct {
			Sections []string `json:"sections"`
			Roles    []string `json:"roles"`
		}
		_ = json.Unmarshal(body, &payload)
		for _, s := range payload.Sections {
			if s == "kpis" {
				t.Errorf("%s leaked a section the caller cannot read", path)
			}
		}
		if len(payload.Roles) != 1 || payload.Roles[0] != "VULN" {
			t.Errorf("%s roles = %v, want [VULN]", path, payload.Roles)
		}
	}
}

// --- the privileged-provider rule --------------------------------------------

// The deployment rule: an admin signs in with Apple or Google, never GitHub and
// never a typed code.
func TestAdminMustUseAStrongProvider(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{
		subject: "boss", roles: []string{"ADMIN"},
		appleSub: "apple-boss", githubSub: "gh-boss", enrollCode: "ADMIN-CODE-12345",
	})

	key, der, encoded := newDeviceKey(t)
	resp, _ := r.postJSON("/api/v1/enroll", map[string]any{
		"enrollmentCode": "ADMIN-CODE-12345",
		"publicKey":      encoded,
		"deviceName":     "boss phone",
	}, "")
	if resp.StatusCode != http.StatusForbidden {
		t.Errorf("an ADMIN enrolling by code = %d, want 403", resp.StatusCode)
	}
	_ = key
	_ = der

	// The strong path works for the same account.
	if _, _, token := r.signIn("apple", "apple-boss"); token == "" {
		t.Error("the admin should be able to sign in with Apple")
	}
}

// A non-privileged user may still use a code.
func TestNonAdminMayUseAnEnrollmentCode(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{subject: "scanner", roles: []string{"VULN"}, enrollCode: "USER-CODE-123456"})

	_, _, encoded := newDeviceKey(t)
	resp, body := r.postJSON("/api/v1/enroll", map[string]any{
		"enrollmentCode": "USER-CODE-123456",
		"publicKey":      encoded,
		"deviceName":     "scanner phone",
	}, "")
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("enroll: %d %s", resp.StatusCode, body)
	}
	var bound bindResponse
	_ = json.Unmarshal(body, &bound)
	if bound.BoundVia != "code" {
		t.Errorf("boundVia = %q, want code", bound.BoundVia)
	}
}

// The relay publishes the rule so the app can explain it before the user picks.
func TestProvidersEndpointPublishesThePolicy(t *testing.T) {
	r := newRelay(t)
	resp, body := r.get("/api/v1/providers", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("providers: %d", resp.StatusCode)
	}
	var payload providersResponse
	_ = json.Unmarshal(body, &payload)

	if len(payload.PrivilegedRoles) != 1 || payload.PrivilegedRoles[0] != "ADMIN" {
		t.Errorf("privilegedRoles = %v", payload.PrivilegedRoles)
	}
	if len(payload.StrongProviders) != 2 {
		t.Errorf("strongProviders = %v, want apple and google", payload.StrongProviders)
	}
	if len(payload.Providers) != 2 {
		t.Errorf("providers = %v, want the two configured verifiers", payload.Providers)
	}
}

// --- login negatives ----------------------------------------------------------

// A verified Apple account that secman has not mapped to a principal gets
// nothing. Signing in is not authorization.
func TestUnmappedIdentityIsRefused(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{subject: "boss", roles: []string{"ADMIN"}, appleSub: "apple-boss"})

	key, der, encoded := newDeviceKey(t)
	_, body := r.postJSON("/api/v1/auth/nonce", map[string]any{"publicKey": encoded}, "")
	var issued nonceResponse
	_ = json.Unmarshal(body, &issued)

	resp, _ := r.postJSON("/api/v1/auth/oidc", map[string]any{
		"provider":  "apple",
		"idToken":   r.idp.idToken("apple-stranger", issued.Nonce),
		"nonce":     issued.Nonce,
		"publicKey": encoded,
		"signature": signBinding(t, key, issued.Nonce, der),
	}, "")
	if resp.StatusCode != http.StatusForbidden {
		t.Errorf("an unmapped identity = %d, want 403", resp.StatusCode)
	}
}

// The login nonce is bound to the device key that asked for it, so a captured
// ID token cannot be used to register somebody else's key.
func TestIdentityTokenCannotBeBoundToAnotherKey(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{subject: "boss", roles: []string{"ADMIN"}, appleSub: "apple-boss"})

	_, _, victimKey := newDeviceKey(t)
	_, body := r.postJSON("/api/v1/auth/nonce", map[string]any{"publicKey": victimKey}, "")
	var issued nonceResponse
	_ = json.Unmarshal(body, &issued)

	// The attacker has the token and the nonce but presents their own key.
	attackerKey, attackerDER, attackerEncoded := newDeviceKey(t)
	resp, _ := r.postJSON("/api/v1/auth/oidc", map[string]any{
		"provider":  "apple",
		"idToken":   r.idp.idToken("apple-boss", issued.Nonce),
		"nonce":     issued.Nonce,
		"publicKey": attackerEncoded,
		"signature": signBinding(t, attackerKey, issued.Nonce, attackerDER),
	}, "")
	if resp.StatusCode != http.StatusForbidden {
		t.Errorf("binding a token to a different key = %d, want 403", resp.StatusCode)
	}
}

// Presenting a public key is not proof of holding the private half.
func TestBindingRequiresProofOfPossession(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{subject: "boss", roles: []string{"ADMIN"}, appleSub: "apple-boss"})

	_, _, encoded := newDeviceKey(t)
	_, body := r.postJSON("/api/v1/auth/nonce", map[string]any{"publicKey": encoded}, "")
	var issued nonceResponse
	_ = json.Unmarshal(body, &issued)

	other, otherDER, _ := newDeviceKey(t)
	resp, _ := r.postJSON("/api/v1/auth/oidc", map[string]any{
		"provider":  "apple",
		"idToken":   r.idp.idToken("apple-boss", issued.Nonce),
		"nonce":     issued.Nonce,
		"publicKey": encoded,
		// Signed by a key that is not the one being registered.
		"signature": signBinding(t, other, issued.Nonce, otherDER),
	}, "")
	if resp.StatusCode != http.StatusForbidden {
		t.Errorf("a binding signature from another key = %d, want 403", resp.StatusCode)
	}
}

func TestLoginNonceIsSingleUse(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{subject: "boss", roles: []string{"ADMIN"}, appleSub: "apple-boss"})

	key, der, encoded := newDeviceKey(t)
	_, body := r.postJSON("/api/v1/auth/nonce", map[string]any{"publicKey": encoded}, "")
	var issued nonceResponse
	_ = json.Unmarshal(body, &issued)

	bind := map[string]any{
		"provider":  "apple",
		"idToken":   r.idp.idToken("apple-boss", issued.Nonce),
		"nonce":     issued.Nonce,
		"publicKey": encoded,
		"signature": signBinding(t, key, issued.Nonce, der),
	}
	if resp, _ := r.postJSON("/api/v1/auth/oidc", bind, ""); resp.StatusCode != http.StatusCreated {
		t.Fatalf("first bind should succeed, got %d", resp.StatusCode)
	}
	if resp, _ := r.postJSON("/api/v1/auth/oidc", bind, ""); resp.StatusCode != http.StatusForbidden {
		t.Errorf("replaying the whole binding = %d, want 403", resp.StatusCode)
	}
}

func TestUnknownProviderRefused(t *testing.T) {
	r := newRelay(t)
	_, _, encoded := newDeviceKey(t)
	resp, _ := r.postJSON("/api/v1/auth/oidc", map[string]any{
		"provider":  "facebook",
		"idToken":   "x.y.z",
		"nonce":     "n",
		"publicKey": encoded,
		"signature": "sig",
	}, "")
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("an unconfigured provider = %d, want 400", resp.StatusCode)
	}
}

// GitHub is not configured in this relay, so its routes must refuse rather than
// half-work.
func TestGitHubRoutesRefuseWhenNotConfigured(t *testing.T) {
	r := newRelay(t)
	_, _, encoded := newDeviceKey(t)

	resp, _ := r.postJSON("/api/v1/auth/github/start", map[string]any{"publicKey": encoded}, "")
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("github/start = %d, want 400", resp.StatusCode)
	}
	resp, _ = r.get("/api/v1/auth/github/callback?code=x&state=y", "")
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("github/callback = %d, want 404", resp.StatusCode)
	}
	resp, _ = r.postJSON("/api/v1/auth/github/complete", map[string]any{"ticket": "t", "publicKey": encoded}, "")
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("github/complete = %d, want 400", resp.StatusCode)
	}
}

// --- device authentication ----------------------------------------------------

// Holding a device id is not authentication: the private key is.
func TestTokenRequiresPossessionOfTheDeviceKey(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{subject: "boss", roles: []string{"ADMIN"}, appleSub: "apple-boss"})
	deviceID, _, _ := r.signIn("apple", "apple-boss")

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

func TestReadsRequireAToken(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{subject: "boss", roles: []string{"ADMIN"}, appleSub: "apple-boss"})
	_, _ = r.push("/ingest/v1/snapshot", snapshotPayload(time.Now()))

	for _, path := range []string{"/api/v1/status", "/api/v1/meta", "/api/v1/status/kpis", "/api/v1/session"} {
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

// Revocation pushed by secman must bite on the very next request.
func TestRevocationInvalidatesLiveTokens(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{subject: "boss", roles: []string{"ADMIN"}, appleSub: "apple-boss"})
	_, _ = r.push("/ingest/v1/snapshot", snapshotPayload(time.Now()))
	deviceID, _, token := r.signIn("apple", "apple-boss")

	if resp, _ := r.get("/api/v1/status", token); resp.StatusCode != http.StatusOK {
		t.Fatalf("the device should read before revocation, got %d", resp.StatusCode)
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

// Removing a principal from the authoritative push must lock their device out.
func TestRemovingAPrincipalLocksTheirDeviceOut(t *testing.T) {
	r := newRelay(t)
	r.seed(
		principalSpec{subject: "leaver", roles: []string{"ADMIN"}, appleSub: "apple-leaver"},
		principalSpec{subject: "stayer", roles: []string{"ADMIN"}, appleSub: "apple-stayer"},
	)
	_, _ = r.push("/ingest/v1/snapshot", snapshotPayload(time.Now()))
	_, _, leaverToken := r.signIn("apple", "apple-leaver")

	if resp, _ := r.get("/api/v1/status", leaverToken); resp.StatusCode != http.StatusOK {
		t.Fatal("the device should work before the principal is removed")
	}

	r.seed(principalSpec{subject: "stayer", roles: []string{"ADMIN"}, appleSub: "apple-stayer"})

	if resp, _ := r.get("/api/v1/status", leaverToken); resp.StatusCode != http.StatusForbidden {
		t.Errorf("a removed principal's device = %d, want 403", resp.StatusCode)
	}
}

// --- ingest plane -------------------------------------------------------------

func TestIngestRequiresAuthentication(t *testing.T) {
	r := newRelay(t)
	raw, _ := json.Marshal(snapshotPayload(time.Now()))

	resp, _ := r.postJSON("/ingest/v1/snapshot", snapshotPayload(time.Now()), "")
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("unauthenticated push = %d, want 401", resp.StatusCode)
	}

	// Bearer token but no signature: a stolen token alone must not be enough.
	req, _ := http.NewRequest(http.MethodPost, r.server.URL+"/ingest/v1/snapshot", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+ingestToken)
	resp, _ = r.do(req)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("token-only push = %d, want 401", resp.StatusCode)
	}
}

func TestIngestRejectsReplayedPush(t *testing.T) {
	r := newRelay(t)
	raw, _ := json.Marshal(snapshotPayload(time.Now()))

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
		t.Fatalf("first push = %d, want 202", resp.StatusCode)
	}
	if resp, _ := r.do(build()); resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("replayed push = %d, want 401", resp.StatusCode)
	}
}

// A section with no policy entry is a section nobody could read; refusing the
// push tells secman immediately instead of shipping an invisible tile.
func TestSnapshotWithoutPolicyIsRefused(t *testing.T) {
	r := newRelay(t)
	payload := snapshotPayload(time.Now())
	sections := payload["sections"].(map[string]any)
	sections["orphan"] = map[string]any{"x": 1}

	resp, _ := r.push("/ingest/v1/snapshot", payload)
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("a section with no policy = %d, want 400", resp.StatusCode)
	}
}

func TestSnapshotWithUnknownRoleIsRefused(t *testing.T) {
	r := newRelay(t)
	payload := snapshotPayload(time.Now())
	policy := payload["policy"].(map[string]any)
	policy["kpis"] = map[string]any{"requiredRoles": []string{"ADMINISTRATOR"}}

	resp, _ := r.push("/ingest/v1/snapshot", payload)
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("an unknown role = %d, want 400", resp.StatusCode)
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
		t.Errorf("an older snapshot = %d, want 409", resp.StatusCode)
	}
}

// A device token must be worthless on the ingest plane.
func TestDeviceTokenCannotPush(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{subject: "boss", roles: []string{"ADMIN"}, appleSub: "apple-boss"})
	_, _, token := r.signIn("apple", "apple-boss")

	resp, _ := r.postJSON("/ingest/v1/snapshot", snapshotPayload(time.Now()), token)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("a device token on the ingest plane = %d, want 401", resp.StatusCode)
	}
	resp, _ = r.get("/ingest/v1/devices", token)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("a device token listing devices = %d, want 401", resp.StatusCode)
	}
}

// --- staleness and read-only ---------------------------------------------------

func TestStaleSnapshotIsLabelledNotHidden(t *testing.T) {
	r := newRelay(t)
	base := time.Now()
	r.now = func() time.Time { return base }
	r.seed(principalSpec{subject: "boss", roles: []string{"ADMIN"}, appleSub: "apple-boss"})

	if resp, _ := r.push("/ingest/v1/snapshot", snapshotPayload(base)); resp.StatusCode != http.StatusAccepted {
		t.Fatal("setup push failed")
	}
	deviceID, key, _ := r.signIn("apple", "apple-boss")

	// An hour later the first access token has expired — correctly — so the
	// device re-authenticates, exactly as the app would.
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
	r.seed(principalSpec{subject: "boss", roles: []string{"ADMIN"}, appleSub: "apple-boss"})
	_, _, token := r.signIn("apple", "apple-boss")

	resp, _ := r.get("/api/v1/status", token)
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Errorf("status = %d, want 503 before the first snapshot", resp.StatusCode)
	}
}

// There is no write path from a device into anything.
func TestMobilePlaneExposesNoWriteRoutes(t *testing.T) {
	r := newRelay(t)
	r.seed(principalSpec{subject: "boss", roles: []string{"ADMIN"}, appleSub: "apple-boss"})
	_, _, token := r.signIn("apple", "apple-boss")

	for _, path := range []string{"/api/v1/status", "/api/v1/meta", "/api/v1/status/kpis", "/api/v1/session"} {
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
