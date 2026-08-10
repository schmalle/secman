package idp

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// RSA key generation is slow; the whole suite shares one pair.
var (
	testKeysOnce sync.Once
	testKey1     *rsa.PrivateKey
	testKey2     *rsa.PrivateKey
)

func testKeys(t *testing.T) (*rsa.PrivateKey, *rsa.PrivateKey) {
	t.Helper()
	testKeysOnce.Do(func() {
		var err error
		if testKey1, err = rsa.GenerateKey(rand.Reader, 2048); err != nil {
			panic(err)
		}
		if testKey2, err = rsa.GenerateKey(rand.Reader, 2048); err != nil {
			panic(err)
		}
	})
	return testKey1, testKey2
}

// --- a mock OIDC issuer ------------------------------------------------------

type mockIssuer struct {
	t      *testing.T
	server *httptest.Server
	key    *rsa.PrivateKey
	kid    string
	// rotated lets a test simulate the provider replacing its signing key.
	rotated bool
	key2    *rsa.PrivateKey
	kid2    string
}

func newMockIssuer(t *testing.T) *mockIssuer {
	t.Helper()
	key, key2 := testKeys(t)
	m := &mockIssuer{t: t, key: key, kid: "key-1", key2: key2, kid2: "key-2"}
	m.server = httptest.NewTLSServer(http.HandlerFunc(m.serveJWKS))
	t.Cleanup(m.server.Close)
	return m
}

func (m *mockIssuer) serveJWKS(w http.ResponseWriter, r *http.Request) {
	type jwkOut struct {
		Kty string `json:"kty"`
		Kid string `json:"kid"`
		Use string `json:"use"`
		Alg string `json:"alg"`
		N   string `json:"n"`
		E   string `json:"e"`
	}
	entry := func(kid string, k *rsa.PrivateKey) jwkOut {
		return jwkOut{
			Kty: "RSA", Kid: kid, Use: "sig", Alg: "RS256",
			N: base64.RawURLEncoding.EncodeToString(k.PublicKey.N.Bytes()),
			E: base64.RawURLEncoding.EncodeToString(big.NewInt(int64(k.PublicKey.E)).Bytes()),
		}
	}
	keys := []jwkOut{entry(m.kid, m.key)}
	if m.rotated {
		keys = []jwkOut{entry(m.kid2, m.key2)}
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"keys": keys})
}

type tokenClaims struct {
	Iss           string `json:"iss"`
	Sub           string `json:"sub"`
	Aud           any    `json:"aud"`
	Exp           int64  `json:"exp"`
	Iat           int64  `json:"iat"`
	Nonce         string `json:"nonce,omitempty"`
	Email         string `json:"email,omitempty"`
	EmailVerified any    `json:"email_verified,omitempty"`
	Name          string `json:"name,omitempty"`
}

// mint builds a signed JWT. `alg` and `kid` are parameters so a test can forge
// the header the way an attacker would.
func (m *mockIssuer) mint(claims tokenClaims, alg, kid string, key *rsa.PrivateKey) string {
	m.t.Helper()
	header, _ := json.Marshal(map[string]string{"alg": alg, "kid": kid, "typ": "JWT"})
	payload, _ := json.Marshal(claims)
	signingInput := base64.RawURLEncoding.EncodeToString(header) + "." + base64.RawURLEncoding.EncodeToString(payload)

	if alg == "none" {
		return signingInput + "."
	}
	digest := sha256.Sum256([]byte(signingInput))
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, digest[:])
	if err != nil {
		m.t.Fatalf("signing token: %v", err)
	}
	return signingInput + "." + base64.RawURLEncoding.EncodeToString(sig)
}

func (m *mockIssuer) verifier(t *testing.T, provider string, audiences []string) *Verifier {
	t.Helper()
	v, err := NewVerifier(OIDCConfig{
		Provider:  provider,
		Issuers:   []string{"https://issuer.example.com"},
		Audiences: audiences,
		JWKSURL:   m.server.URL + "/keys",
	}, m.server.Client())
	if err != nil {
		t.Fatalf("NewVerifier: %v", err)
	}
	// Production waits a minute before re-fetching a JWKS for an unknown key
	// id, so a hostile `kid` cannot make the relay hammer the provider. Tests
	// exercising rotation would otherwise have to sleep through it.
	v.minRefetchGap = 0
	return v
}

func validClaims(nonce string) tokenClaims {
	now := time.Now()
	return tokenClaims{
		Iss:           "https://issuer.example.com",
		Sub:           "001234.abcdef",
		Aud:           "com.example.secman",
		Exp:           now.Add(10 * time.Minute).Unix(),
		Iat:           now.Unix(),
		Nonce:         HashNonce(nonce),
		Email:         "user@example.com",
		EmailVerified: true,
		Name:          "Test User",
	}
}

// --- OIDC verification -------------------------------------------------------

func TestVerifyAcceptsAWellFormedToken(t *testing.T) {
	m := newMockIssuer(t)
	v := m.verifier(t, "apple", []string{"com.example.secman"})
	nonce, _ := NewNonce()

	identity, reason, err := v.Verify(context.Background(), m.mint(validClaims(nonce), "RS256", m.kid, m.key), nonce)
	if err != nil {
		t.Fatalf("Verify: %v (%s)", err, reason)
	}
	if identity.Subject != "001234.abcdef" {
		t.Errorf("subject = %q", identity.Subject)
	}
	if identity.Provider != "apple" {
		t.Errorf("provider = %q, want apple", identity.Provider)
	}
	if !identity.EmailVerified {
		t.Error("email_verified should have been parsed as true")
	}
}

// Algorithm confusion is the classic JWT break. The verifier pins RS256, so
// neither "none" nor a symmetric algorithm gets a hearing.
func TestVerifyPinsTheAlgorithm(t *testing.T) {
	m := newMockIssuer(t)
	v := m.verifier(t, "apple", []string{"com.example.secman"})
	nonce, _ := NewNonce()

	for _, alg := range []string{"none", "HS256", "RS512", ""} {
		token := m.mint(validClaims(nonce), alg, m.kid, m.key)
		if _, reason, err := v.Verify(context.Background(), token, nonce); err == nil {
			t.Errorf("alg=%q should have been refused", alg)
		} else if reason != "unsupported_alg" {
			t.Errorf("alg=%q refused for the wrong reason: %s", alg, reason)
		}
	}
}

// An ID token minted for another app is perfectly valid — checking `aud` is
// what stops it being accepted here.
func TestVerifyChecksAudience(t *testing.T) {
	m := newMockIssuer(t)
	v := m.verifier(t, "apple", []string{"com.example.secman"})
	nonce, _ := NewNonce()

	claims := validClaims(nonce)
	claims.Aud = "com.someone.else"
	if _, reason, err := v.Verify(context.Background(), m.mint(claims, "RS256", m.kid, m.key), nonce); err == nil {
		t.Fatal("a token for another audience must be refused")
	} else if reason != "audience_mismatch" {
		t.Errorf("reason = %s, want audience_mismatch", reason)
	}
}

func TestVerifyAcceptsAudienceArray(t *testing.T) {
	m := newMockIssuer(t)
	v := m.verifier(t, "google", []string{"client-id.apps.googleusercontent.com"})
	nonce, _ := NewNonce()

	claims := validClaims(nonce)
	claims.Aud = []any{"other", "client-id.apps.googleusercontent.com"}
	if _, reason, err := v.Verify(context.Background(), m.mint(claims, "RS256", m.kid, m.key), nonce); err != nil {
		t.Fatalf("an aud array containing the expected value should verify: %v (%s)", err, reason)
	}
}

func TestVerifyChecksIssuer(t *testing.T) {
	m := newMockIssuer(t)
	v := m.verifier(t, "apple", []string{"com.example.secman"})
	nonce, _ := NewNonce()

	claims := validClaims(nonce)
	claims.Iss = "https://evil.example.com"
	if _, reason, err := v.Verify(context.Background(), m.mint(claims, "RS256", m.kid, m.key), nonce); err == nil {
		t.Fatal("a token from another issuer must be refused")
	} else if reason != "issuer_mismatch" {
		t.Errorf("reason = %s, want issuer_mismatch", reason)
	}
}

func TestVerifyChecksExpiry(t *testing.T) {
	m := newMockIssuer(t)
	v := m.verifier(t, "apple", []string{"com.example.secman"})
	nonce, _ := NewNonce()

	claims := validClaims(nonce)
	claims.Exp = time.Now().Add(-time.Hour).Unix()
	if _, reason, err := v.Verify(context.Background(), m.mint(claims, "RS256", m.kid, m.key), nonce); err == nil {
		t.Fatal("an expired token must be refused")
	} else if reason != "expired" {
		t.Errorf("reason = %s, want expired", reason)
	}
}

// The nonce binds the token to one relay-issued login attempt. Without it, a
// token captured anywhere could be replayed to bind an attacker's device.
func TestVerifyRequiresTheExpectedNonce(t *testing.T) {
	m := newMockIssuer(t)
	v := m.verifier(t, "apple", []string{"com.example.secman"})
	nonce, _ := NewNonce()
	other, _ := NewNonce()

	if _, reason, err := v.Verify(context.Background(), m.mint(validClaims(nonce), "RS256", m.kid, m.key), other); err == nil {
		t.Fatal("a token carrying a different nonce must be refused")
	} else if reason != "nonce_mismatch" {
		t.Errorf("reason = %s, want nonce_mismatch", reason)
	}

	claims := validClaims(nonce)
	claims.Nonce = ""
	if _, reason, err := v.Verify(context.Background(), m.mint(claims, "RS256", m.kid, m.key), nonce); err == nil {
		t.Fatal("a token with no nonce must be refused")
	} else if reason != "token_carries_no_nonce" {
		t.Errorf("reason = %s, want token_carries_no_nonce", reason)
	}

	if _, reason, err := v.Verify(context.Background(), m.mint(validClaims(nonce), "RS256", m.kid, m.key), ""); err == nil {
		t.Fatal("verifying without an expected nonce must be refused")
	} else if reason != "no_expected_nonce" {
		t.Errorf("reason = %s, want no_expected_nonce", reason)
	}
}

// Some SDK paths pass the nonce through unhashed; both forms are accepted, and
// both are still a single-use relay-issued value.
func TestVerifyAcceptsRawNonceForm(t *testing.T) {
	m := newMockIssuer(t)
	v := m.verifier(t, "google", []string{"com.example.secman"})
	nonce, _ := NewNonce()

	claims := validClaims(nonce)
	claims.Nonce = nonce
	if _, reason, err := v.Verify(context.Background(), m.mint(claims, "RS256", m.kid, m.key), nonce); err != nil {
		t.Fatalf("the unhashed nonce form should verify: %v (%s)", err, reason)
	}
}

func TestVerifyRejectsSignatureFromAnotherKey(t *testing.T) {
	m := newMockIssuer(t)
	v := m.verifier(t, "apple", []string{"com.example.secman"})
	nonce, _ := NewNonce()

	// Signed with key2 but claiming kid of key1.
	token := m.mint(validClaims(nonce), "RS256", m.kid, m.key2)
	if _, reason, err := v.Verify(context.Background(), token, nonce); err == nil {
		t.Fatal("a token signed by the wrong key must be refused")
	} else if reason != "bad_signature" {
		t.Errorf("reason = %s, want bad_signature", reason)
	}
}

// Providers rotate keys; the verifier must pick up the new set.
func TestVerifyFollowsKeyRotation(t *testing.T) {
	m := newMockIssuer(t)
	v := m.verifier(t, "apple", []string{"com.example.secman"})
	nonce, _ := NewNonce()

	if _, _, err := v.Verify(context.Background(), m.mint(validClaims(nonce), "RS256", m.kid, m.key), nonce); err != nil {
		t.Fatalf("initial verification failed: %v", err)
	}

	m.rotated = true
	nonce2, _ := NewNonce()
	claims := validClaims(nonce2)
	if _, reason, err := v.Verify(context.Background(), m.mint(claims, "RS256", m.kid2, m.key2), nonce2); err != nil {
		t.Fatalf("a token signed by the rotated key should verify: %v (%s)", err, reason)
	}
}

func TestVerifyRejectsMalformedTokens(t *testing.T) {
	m := newMockIssuer(t)
	v := m.verifier(t, "apple", []string{"com.example.secman"})
	nonce, _ := NewNonce()

	for _, bad := range []string{"", "not.a.jwt.at.all", "onlyonepart", strings.Repeat("a", 9000)} {
		if _, _, err := v.Verify(context.Background(), bad, nonce); err == nil {
			t.Errorf("token %q should not verify", truncateForMsg(bad))
		}
	}
}

func TestNewVerifierRequiresAudience(t *testing.T) {
	m := newMockIssuer(t)
	_, err := NewVerifier(OIDCConfig{
		Provider: "apple",
		Issuers:  []string{"https://issuer.example.com"},
		JWKSURL:  m.server.URL + "/keys",
	}, m.server.Client())
	if err == nil {
		t.Fatal("a verifier with no audience would accept tokens minted for any app")
	}
}

// --- nonce / fingerprint -----------------------------------------------------

func TestHashNonceIsStableHex(t *testing.T) {
	h := HashNonce("abc")
	if len(h) != 64 {
		t.Fatalf("HashNonce returned %d characters, want 64 hex", len(h))
	}
	if h != HashNonce("abc") {
		t.Error("HashNonce must be deterministic")
	}
	if h == HashNonce("abd") {
		t.Error("HashNonce must depend on its input")
	}
}

func TestNewNonceIsUnpredictable(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 100; i++ {
		n, err := NewNonce()
		if err != nil {
			t.Fatalf("NewNonce: %v", err)
		}
		if len(n) != 64 {
			t.Fatalf("nonce length = %d, want 64 hex characters", len(n))
		}
		if seen[n] {
			t.Fatal("NewNonce produced a duplicate")
		}
		seen[n] = true
	}
}

// --- ephemeral store ---------------------------------------------------------

func TestEphemeralStoreIsSingleUse(t *testing.T) {
	s := NewEphemeralStore(time.Minute, 10)
	now := time.Now()

	key, err := s.Issue(Payload{DeviceKeyFingerprint: "abc"}, now)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	if _, err := s.Redeem(key, now); err != nil {
		t.Fatalf("first redeem should succeed: %v", err)
	}
	if _, err := s.Redeem(key, now); err == nil {
		t.Fatal("a value must not be redeemable twice")
	}
}

func TestEphemeralStoreExpires(t *testing.T) {
	s := NewEphemeralStore(time.Minute, 10)
	now := time.Now()
	key, _ := s.Issue(Payload{DeviceKeyFingerprint: "abc"}, now)

	if _, err := s.Redeem(key, now.Add(2*time.Minute)); err == nil {
		t.Fatal("an expired value must not be redeemable")
	}
}

func TestEphemeralStoreFailsClosedWhenFull(t *testing.T) {
	s := NewEphemeralStore(time.Minute, 2)
	now := time.Now()
	if _, err := s.Issue(Payload{}, now); err != nil {
		t.Fatalf("first: %v", err)
	}
	if _, err := s.Issue(Payload{}, now); err != nil {
		t.Fatalf("second: %v", err)
	}
	// Refusing beats evicting somebody else's in-flight login.
	if _, err := s.Issue(Payload{}, now); err == nil {
		t.Fatal("the store should fail closed rather than evict")
	}
}

// Key binding is what stops a stolen token or ticket registering another key.
func TestPayloadKeyBinding(t *testing.T) {
	keyA := []byte("device-key-a")
	keyB := []byte("device-key-b")
	p := Payload{DeviceKeyFingerprint: Fingerprint(keyA)}

	if !p.MatchesKey(keyA) {
		t.Error("the issuing key should match")
	}
	if p.MatchesKey(keyB) {
		t.Error("a different key must not match")
	}
	if (Payload{}).MatchesKey(keyA) {
		t.Error("an unbound payload must not match any key")
	}
}

// --- GitHub ------------------------------------------------------------------

type mockGitHub struct {
	t          *testing.T
	server     *httptest.Server
	wantSecret string
	failToken  bool
	userID     int64
}

func newMockGitHub(t *testing.T) *mockGitHub {
	t.Helper()
	m := &mockGitHub{t: t, wantSecret: "gh-client-secret", userID: 4242}
	m.server = httptest.NewTLSServer(http.HandlerFunc(m.route))
	t.Cleanup(m.server.Close)
	return m
}

func (m *mockGitHub) route(w http.ResponseWriter, r *http.Request) {
	switch r.URL.Path {
	case "/token":
		if err := r.ParseForm(); err != nil {
			m.t.Fatalf("parsing form: %v", err)
		}
		// The secret must be presented by the relay, never by the device.
		if got := r.PostForm.Get("client_secret"); got != m.wantSecret {
			m.t.Errorf("client_secret = %q, want the configured secret", got)
		}
		w.Header().Set("Content-Type", "application/json")
		if m.failToken {
			_, _ = w.Write([]byte(`{"error":"bad_verification_code"}`))
			return
		}
		_, _ = w.Write([]byte(`{"access_token":"gho_test","token_type":"bearer","scope":"read:user"}`))

	case "/user":
		if r.Header.Get("Authorization") != "Bearer gho_test" {
			m.t.Errorf("Authorization = %q", r.Header.Get("Authorization"))
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":4242,"login":"octocat","name":"The Octocat","email":"cat@example.com"}`))

	default:
		http.NotFound(w, r)
	}
}

func (m *mockGitHub) client(t *testing.T) *GitHubClient {
	t.Helper()
	c, err := NewGitHubClient(GitHubConfig{
		ClientID:          "gh-client-id",
		ClientSecret:      m.wantSecret,
		RedirectURI:       "https://relay.example.com/api/v1/auth/github/callback",
		AppCallbackScheme: "secman-relay",
	}, m.server.Client())
	if err != nil {
		t.Fatalf("NewGitHubClient: %v", err)
	}
	c.tokenURL = m.server.URL + "/token"
	c.userURL = m.server.URL + "/user"
	c.authorizeURL = m.server.URL + "/authorize"
	return c
}

func TestGitHubExchangeReturnsTheNumericID(t *testing.T) {
	m := newMockGitHub(t)
	c := m.client(t)

	identity, reason, err := c.Exchange(context.Background(), "the-code")
	if err != nil {
		t.Fatalf("Exchange: %v (%s)", err, reason)
	}
	// The login can be renamed and re-registered by somebody else; the numeric
	// id cannot. Using the login as the subject would silently transfer access.
	if identity.Subject != "4242" {
		t.Errorf("subject = %q, want the numeric account id 4242", identity.Subject)
	}
	if identity.Provider != "github" {
		t.Errorf("provider = %q", identity.Provider)
	}
	if identity.DisplayName != "The Octocat" {
		t.Errorf("displayName = %q", identity.DisplayName)
	}
}

func TestGitHubExchangeReportsRejection(t *testing.T) {
	m := newMockGitHub(t)
	m.failToken = true
	c := m.client(t)

	if _, reason, err := c.Exchange(context.Background(), "bad-code"); err == nil {
		t.Fatal("a rejected code must not yield an identity")
	} else if reason != "token_rejected" {
		t.Errorf("reason = %s, want token_rejected", reason)
	}
}

func TestGitHubAuthorizeURLRequestsTheNarrowestScope(t *testing.T) {
	m := newMockGitHub(t)
	c := m.client(t)

	u := c.AuthorizeURL("the-state")
	for _, want := range []string{"scope=read%3Auser", "state=the-state", "client_id=gh-client-id", "allow_signup=false"} {
		if !strings.Contains(u, want) {
			t.Errorf("authorize URL missing %q: %s", want, u)
		}
	}
}

func TestGitHubConfigValidation(t *testing.T) {
	base := GitHubConfig{
		ClientID:          "id",
		ClientSecret:      "secret",
		RedirectURI:       "https://relay.example.com/cb",
		AppCallbackScheme: "secman-relay",
	}
	if err := base.Validate(); err != nil {
		t.Fatalf("a well-formed config should validate: %v", err)
	}

	missingSecret := base
	missingSecret.ClientSecret = ""
	if err := missingSecret.Validate(); err == nil {
		t.Error("a confidential client without a secret is not confidential")
	}

	// The redirect URI is fetched by GitHub and must be a real https endpoint.
	for _, bad := range []string{"http://relay.example.com/cb", "https://127.0.0.1/cb", ""} {
		cfg := base
		cfg.RedirectURI = bad
		if err := cfg.Validate(); err == nil {
			t.Errorf("redirect URI %q should be refused", bad)
		}
	}
}

// The scheme is interpolated into a Location header.
func TestCallbackSchemeValidation(t *testing.T) {
	if err := ValidateCallbackScheme("secman-relay"); err != nil {
		t.Errorf("a normal custom scheme should be accepted: %v", err)
	}
	for _, bad := range []string{"", "https", "http", "1scheme", "scheme with space", "scheme\r\nX-Injected: 1", strings.Repeat("a", 100)} {
		if err := ValidateCallbackScheme(bad); err == nil {
			t.Errorf("scheme %q should be refused", truncateForMsg(bad))
		}
	}
}

func TestAppRedirectEscapesItsInput(t *testing.T) {
	m := newMockGitHub(t)
	c := m.client(t)

	got := c.AppRedirect("abc def&x=1")
	if strings.Contains(got, " ") || strings.Contains(got, "&x=1") {
		t.Errorf("the ticket must be escaped into the redirect: %s", got)
	}
}

// --- SSRF guards --------------------------------------------------------------

func TestProviderURLGuard(t *testing.T) {
	if err := ValidateProviderURL("https://appleid.apple.com/auth/keys"); err != nil {
		t.Errorf("a normal provider URL should be accepted: %v", err)
	}
	bad := []string{
		"", "http://appleid.apple.com/auth/keys", "https://127.0.0.1/keys",
		"https://169.254.169.254/latest/meta-data/", "https://10.0.0.1/keys",
		"https://appleid.apple.com:8443/keys", "https://user:pw@appleid.apple.com/keys",
	}
	for _, u := range bad {
		if err := ValidateProviderURL(u); err == nil {
			t.Errorf("%q should be refused", u)
		}
	}
}

func TestDialGuardBlocksInternalTargets(t *testing.T) {
	if err := checkDialAddress("tcp", "1.1.1.1:443"); err != nil {
		t.Errorf("a public address on 443 should be allowed: %v", err)
	}
	for name, addr := range map[string]string{
		"loopback": "127.0.0.1:443",
		"metadata": "169.254.169.254:443",
		"private":  "192.168.1.1:443",
		"port":     "1.1.1.1:8080",
	} {
		if err := checkDialAddress("tcp", addr); err == nil {
			t.Errorf("%s (%s) should be refused", name, addr)
		}
	}
}

func truncateForMsg(v string) string {
	if len(v) > 40 {
		return v[:40] + "..."
	}
	return v
}
