package acme

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log/slog"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func testLogger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

// --- JWS / JWK -------------------------------------------------------------

// RFC 7638 fixes the member order for the thumbprint input. Go emits struct
// fields in declaration order, so this test pins the declaration.
func TestJWKCanonicalOrdering(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	k, err := newJWK(&key.PublicKey)
	if err != nil {
		t.Fatalf("newJWK: %v", err)
	}
	raw, err := json.Marshal(k)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	s := string(raw)
	if !strings.HasPrefix(s, `{"crv":"P-256","kty":"EC","x":"`) {
		t.Fatalf("JWK member order is not RFC 7638 canonical: %s", s)
	}
	if strings.Contains(s, " ") {
		t.Errorf("the thumbprint input must not contain whitespace: %s", s)
	}
}

// A coordinate with a leading zero byte must still encode to 32 bytes, or the
// JWK — and every thumbprint derived from it — is wrong.
func TestCoordinatePaddingIsFixedWidth(t *testing.T) {
	small := big.NewInt(1)
	padded := padCoordinate(small)
	if len(padded) != 32 {
		t.Fatalf("padCoordinate produced %d bytes, want 32", len(padded))
	}
	if padded[31] != 1 {
		t.Error("the value should be right-aligned big-endian")
	}
	for _, b := range padded[:31] {
		if b != 0 {
			t.Fatal("the leading bytes should be zero")
		}
	}
}

func TestThumbprintIsStableAndKeyBound(t *testing.T) {
	k1, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	k2, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	j1, _ := newJWK(&k1.PublicKey)
	j2, _ := newJWK(&k2.PublicKey)

	a, err := j1.thumbprint()
	if err != nil {
		t.Fatalf("thumbprint: %v", err)
	}
	b, _ := j1.thumbprint()
	if a != b {
		t.Error("the thumbprint of one key must be stable")
	}
	c, _ := j2.thumbprint()
	if a == c {
		t.Error("two different keys must not share a thumbprint")
	}
	if _, err := base64.RawURLEncoding.DecodeString(a); err != nil {
		t.Errorf("the thumbprint must be unpadded base64url: %v", err)
	}
}

func TestNonP256AccountKeyRejected(t *testing.T) {
	k, _ := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	if _, err := newJWK(&k.PublicKey); err == nil {
		t.Fatal("only P-256 account keys are supported")
	}
	if _, err := NewClient("https://acme.example.com/directory", "a@b.c", k, testLogger()); err == nil {
		t.Fatal("NewClient should refuse a non-P-256 account key")
	}
}

// The signature the CA will verify must actually verify.
func TestSignJWSProducesAVerifiableSignature(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	k, _ := newJWK(&key.PublicKey)

	raw, err := signJWS(key, protectedHeader{Nonce: "n1", URL: "https://ca.example.com/x", JWK: &k}, []byte(`{"a":1}`))
	if err != nil {
		t.Fatalf("signJWS: %v", err)
	}
	var body jwsBody
	if err := json.Unmarshal(raw, &body); err != nil {
		t.Fatalf("the JWS is not valid JSON: %v", err)
	}

	headerJSON, err := base64.RawURLEncoding.DecodeString(body.Protected)
	if err != nil {
		t.Fatalf("protected header is not base64url: %v", err)
	}
	var header protectedHeader
	if err := json.Unmarshal(headerJSON, &header); err != nil {
		t.Fatalf("protected header is not JSON: %v", err)
	}
	if header.Alg != "ES256" {
		t.Errorf("alg = %q, want ES256", header.Alg)
	}
	if header.KID != "" {
		t.Error("a jwk-form header must not also carry a kid")
	}

	sig, err := base64.RawURLEncoding.DecodeString(body.Signature)
	if err != nil {
		t.Fatalf("signature is not base64url: %v", err)
	}
	if len(sig) != 64 {
		t.Fatalf("ES256 signature must be 64 raw bytes, got %d", len(sig))
	}
	digest := sha256.Sum256([]byte(body.Protected + "." + body.Payload))
	r := new(big.Int).SetBytes(sig[:32])
	s := new(big.Int).SetBytes(sig[32:])
	if !ecdsa.Verify(&key.PublicKey, digest[:], r, s) {
		t.Fatal("the signature does not verify against the account key")
	}
}

// POST-as-GET carries an empty payload, which is distinct from "{}".
func TestPostAsGetHasEmptyPayload(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	raw, err := signJWS(key, protectedHeader{Nonce: "n", URL: "https://ca.example.com/x", KID: "https://ca.example.com/acct/1"}, nil)
	if err != nil {
		t.Fatalf("signJWS: %v", err)
	}
	var body jwsBody
	_ = json.Unmarshal(raw, &body)
	if body.Payload != "" {
		t.Errorf("payload = %q, want the empty string", body.Payload)
	}
}

func TestKeyAuthorizationFormat(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	k, _ := newJWK(&key.PublicKey)
	tp, _ := k.thumbprint()

	ka, err := keyAuthorization("tok123", k)
	if err != nil {
		t.Fatalf("keyAuthorization: %v", err)
	}
	if ka != "tok123."+tp {
		t.Errorf("key authorization = %q, want token.thumbprint", ka)
	}
}

// --- guards ----------------------------------------------------------------

func TestValidateACMEURL(t *testing.T) {
	ok := []string{"https://acme-v02.api.letsencrypt.org/directory", "https://ca.example.com:443/x"}
	for _, u := range ok {
		if err := validateACMEURL(u); err != nil {
			t.Errorf("%q should be accepted: %v", u, err)
		}
	}
	bad := []string{
		"", "http://ca.example.com/x", "https://ca.example.com:8443/x",
		"https://user:pw@ca.example.com/x", "https://127.0.0.1/x",
		"https://169.254.169.254/latest/meta-data/", "https://10.0.0.1/x",
		"ftp://ca.example.com/x", "https:///nohost",
	}
	for _, u := range bad {
		if err := validateACMEURL(u); err == nil {
			t.Errorf("%q should be rejected", u)
		}
	}
}

// The dial guard runs after DNS resolution, which is what stops rebinding.
func TestCheckDialAddress(t *testing.T) {
	if err := checkDialAddress("tcp", "1.1.1.1:443"); err != nil {
		t.Errorf("a public address on 443 should be allowed: %v", err)
	}
	bad := map[string]string{
		"loopback":  "127.0.0.1:443",
		"metadata":  "169.254.169.254:443",
		"private":   "10.2.3.4:443",
		"wrongPort": "1.1.1.1:8080",
		"unix":      "1.1.1.1:443",
	}
	for name, addr := range bad {
		network := "tcp"
		if name == "unix" {
			network = "unix"
		}
		if err := checkDialAddress(network, addr); err == nil {
			t.Errorf("%s (%s %s) should be refused", name, network, addr)
		}
	}
}

// The token becomes part of a served URL path.
func TestValidateChallengeToken(t *testing.T) {
	if err := validateChallengeToken("abc-DEF_123"); err != nil {
		t.Errorf("a base64url token should be accepted: %v", err)
	}
	for _, tok := range []string{"", "../../etc/passwd", "with/slash", "with space", "tok\n", strings.Repeat("a", 300)} {
		if err := validateChallengeToken(tok); err == nil {
			t.Errorf("token %q should be rejected", tok)
		}
	}
}

// --- HTTP-01 solver --------------------------------------------------------

func TestHTTP01SolverServesAndWithdraws(t *testing.T) {
	s := NewHTTP01Solver()
	s.Present("tok1", "tok1.thumb")

	h := s.Handler("relay.example.com")

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, ChallengePath+"tok1", nil))
	if rec.Code != http.StatusOK || rec.Body.String() != "tok1.thumb" {
		t.Fatalf("challenge should be served: status=%d body=%q", rec.Code, rec.Body.String())
	}

	s.CleanUp("tok1")
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, ChallengePath+"tok1", nil))
	if rec.Code != http.StatusNotFound {
		t.Errorf("a withdrawn challenge should 404, got %d", rec.Code)
	}
}

func TestHTTP01SolverRedirectsEverythingElse(t *testing.T) {
	s := NewHTTP01Solver()
	h := s.Handler("relay.example.com")

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/v1/status", nil))
	if rec.Code != http.StatusMovedPermanently {
		t.Fatalf("status = %d, want 301", rec.Code)
	}
	if loc := rec.Header().Get("Location"); loc != "https://relay.example.com/api/v1/status" {
		t.Errorf("Location = %q, want the canonical https URL", loc)
	}
}

// The port-80 listener must not become a second, weaker API surface.
func TestHTTP01SolverRefusesWrites(t *testing.T) {
	h := NewHTTP01Solver().Handler("relay.example.com")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/v1/enroll", strings.NewReader("{}")))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("status = %d, want 405", rec.Code)
	}
}

func TestHTTP01SolverRejectsTraversalToken(t *testing.T) {
	s := NewHTTP01Solver()
	s.Present("tok1", "tok1.thumb")
	h := s.Handler("relay.example.com")

	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, ChallengePath+"..%2F..%2Fetc%2Fpasswd", nil))
	if rec.Code == http.StatusOK {
		t.Error("a traversal-shaped token must not be served")
	}
}

func TestAccountKeyPEMRoundTrip(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	raw, err := EncodePrivateKeyPEM(key)
	if err != nil {
		t.Fatalf("EncodePrivateKeyPEM: %v", err)
	}
	back, err := ParsePrivateKeyPEM(raw)
	if err != nil {
		t.Fatalf("ParsePrivateKeyPEM: %v", err)
	}
	if back.D.Cmp(key.D) != 0 {
		t.Error("the key did not survive the round trip")
	}
	if _, err := ParsePrivateKeyPEM([]byte("not pem")); err == nil {
		t.Error("garbage should not parse as a key")
	}
}

// --- full issuance against a mock CA ---------------------------------------

// mockCA implements just enough of RFC 8555 to run a complete order, and
// verifies the client's JWS on every request rather than accepting it blindly.
type mockCA struct {
	t          *testing.T
	server     *httptest.Server
	caKey      *ecdsa.PrivateKey
	caCert     *x509.Certificate
	accountKey *ecdsa.PublicKey
	kid        string

	challengeTriggered bool
	nonceCount         int
	badNonceOnce       bool
	servedBadNonce     bool
	issuedPEM          []byte
}

func newMockCA(t *testing.T) *mockCA {
	t.Helper()
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generating CA key: %v", err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "Mock CA"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(24 * time.Hour),
		IsCA:                  true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &caKey.PublicKey, caKey)
	if err != nil {
		t.Fatalf("creating CA certificate: %v", err)
	}
	caCert, _ := x509.ParseCertificate(der)

	ca := &mockCA{t: t, caKey: caKey, caCert: caCert}
	ca.server = httptest.NewTLSServer(http.HandlerFunc(ca.route))
	t.Cleanup(ca.server.Close)
	return ca
}

// client returns an ACME client pointed at the mock, with the two production
// guards relaxed exactly as far as talking to a local test server requires.
func (ca *mockCA) client(accountKey *ecdsa.PrivateKey) *Client {
	c, err := NewClient(ca.server.URL+"/directory", "ops@example.com", accountKey, testLogger())
	if err != nil {
		ca.t.Fatalf("NewClient: %v", err)
	}
	c.http = ca.server.Client()
	c.validateURL = func(string) error { return nil }
	return c
}

func (ca *mockCA) issueNonce(w http.ResponseWriter) {
	ca.nonceCount++
	w.Header().Set("Replay-Nonce", fmt.Sprintf("nonce-%d", ca.nonceCount))
}

// verifyJWS checks that the request really is a well-formed, correctly signed
// ES256 JWS for this URL — the point of the mock is to catch a client that
// merely *looks* right.
func (ca *mockCA) verifyJWS(r *http.Request) []byte {
	ca.t.Helper()
	if ct := r.Header.Get("Content-Type"); ct != "application/jose+json" {
		ca.t.Fatalf("Content-Type = %q, want application/jose+json", ct)
	}
	raw, _ := io.ReadAll(r.Body)
	var body jwsBody
	if err := json.Unmarshal(raw, &body); err != nil {
		ca.t.Fatalf("request is not a flattened JWS: %v", err)
	}
	headerJSON, err := base64.RawURLEncoding.DecodeString(body.Protected)
	if err != nil {
		ca.t.Fatalf("protected header is not base64url: %v", err)
	}
	var header protectedHeader
	if err := json.Unmarshal(headerJSON, &header); err != nil {
		ca.t.Fatalf("protected header is not JSON: %v", err)
	}
	if header.Alg != "ES256" {
		ca.t.Fatalf("alg = %q, want ES256", header.Alg)
	}
	if header.Nonce == "" {
		ca.t.Fatal("every ACME request must carry a nonce")
	}
	wantURL := ca.server.URL + r.URL.Path
	if header.URL != wantURL {
		ca.t.Fatalf("protected url = %q, want %q", header.URL, wantURL)
	}

	switch {
	case header.JWK != nil:
		xb, _ := base64.RawURLEncoding.DecodeString(header.JWK.X)
		yb, _ := base64.RawURLEncoding.DecodeString(header.JWK.Y)
		ca.accountKey = &ecdsa.PublicKey{
			Curve: elliptic.P256(),
			X:     new(big.Int).SetBytes(xb),
			Y:     new(big.Int).SetBytes(yb),
		}
	case header.KID != "":
		if header.KID != ca.kid {
			ca.t.Fatalf("kid = %q, want %q", header.KID, ca.kid)
		}
	default:
		ca.t.Fatal("a JWS must carry either a jwk or a kid")
	}

	sig, _ := base64.RawURLEncoding.DecodeString(body.Signature)
	if len(sig) != 64 {
		ca.t.Fatalf("signature must be 64 raw bytes, got %d", len(sig))
	}
	digest := sha256.Sum256([]byte(body.Protected + "." + body.Payload))
	rInt := new(big.Int).SetBytes(sig[:32])
	sInt := new(big.Int).SetBytes(sig[32:])
	if !ecdsa.Verify(ca.accountKey, digest[:], rInt, sInt) {
		ca.t.Fatal("the JWS signature does not verify")
	}

	payload, _ := base64.RawURLEncoding.DecodeString(body.Payload)
	return payload
}

func (ca *mockCA) route(w http.ResponseWriter, r *http.Request) {
	base := ca.server.URL
	w.Header().Set("Content-Type", "application/json")

	switch r.URL.Path {
	case "/directory":
		ca.issueNonce(w)
		_ = json.NewEncoder(w).Encode(directory{
			NewNonce:   base + "/new-nonce",
			NewAccount: base + "/new-account",
			NewOrder:   base + "/new-order",
		})

	case "/new-nonce":
		ca.issueNonce(w)
		w.WriteHeader(http.StatusOK)

	case "/new-account":
		payload := ca.verifyJWS(r)
		var acct struct {
			TermsOfServiceAgreed bool     `json:"termsOfServiceAgreed"`
			Contact              []string `json:"contact"`
		}
		_ = json.Unmarshal(payload, &acct)
		if !acct.TermsOfServiceAgreed {
			ca.t.Error("the client must agree to the terms of service")
		}
		if len(acct.Contact) != 1 || acct.Contact[0] != "mailto:ops@example.com" {
			ca.t.Errorf("contact = %v, want the configured mailto", acct.Contact)
		}
		ca.kid = base + "/acct/1"
		ca.issueNonce(w)
		w.Header().Set("Location", ca.kid)
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"status":"valid"}`))

	case "/new-order":
		payload := ca.verifyJWS(r)
		var req struct {
			Identifiers []identifier `json:"identifiers"`
		}
		_ = json.Unmarshal(payload, &req)
		if len(req.Identifiers) != 1 || req.Identifiers[0].Value != "relay.example.com" {
			ca.t.Errorf("identifiers = %v, want relay.example.com", req.Identifiers)
		}
		ca.issueNonce(w)
		w.Header().Set("Location", base+"/order/1")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(order{
			Status:         "pending",
			Identifiers:    req.Identifiers,
			Authorizations: []string{base + "/authz/1"},
			Finalize:       base + "/finalize/1",
		})

	case "/authz/1":
		ca.verifyJWS(r)
		// Serve one badNonce to exercise the retry path exactly once.
		if ca.badNonceOnce && !ca.servedBadNonce {
			ca.servedBadNonce = true
			ca.issueNonce(w)
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(`{"type":"urn:ietf:params:acme:error:badNonce","detail":"stale nonce"}`))
			return
		}
		status := "pending"
		if ca.challengeTriggered {
			status = "valid"
		}
		ca.issueNonce(w)
		_ = json.NewEncoder(w).Encode(authorization{
			Status:     status,
			Identifier: identifier{Type: "dns", Value: "relay.example.com"},
			Challenges: []challenge{
				{Type: "dns-01", URL: base + "/challenge/dns", Token: "dnstoken", Status: "pending"},
				{Type: "http-01", URL: base + "/challenge/1", Token: "http01token", Status: "pending"},
			},
		})

	case "/challenge/1":
		ca.verifyJWS(r)
		ca.challengeTriggered = true
		ca.issueNonce(w)
		_, _ = w.Write([]byte(`{"status":"processing"}`))

	case "/finalize/1":
		payload := ca.verifyJWS(r)
		var req struct {
			CSR string `json:"csr"`
		}
		if err := json.Unmarshal(payload, &req); err != nil {
			ca.t.Fatalf("finalize payload is not JSON: %v", err)
		}
		csrDER, err := base64.RawURLEncoding.DecodeString(req.CSR)
		if err != nil {
			ca.t.Fatalf("csr is not base64url: %v", err)
		}
		csr, err := x509.ParseCertificateRequest(csrDER)
		if err != nil {
			ca.t.Fatalf("csr does not parse: %v", err)
		}
		if err := csr.CheckSignature(); err != nil {
			ca.t.Fatalf("csr signature is invalid: %v", err)
		}
		if len(csr.DNSNames) != 1 || csr.DNSNames[0] != "relay.example.com" {
			ca.t.Fatalf("csr SANs = %v, want [relay.example.com]", csr.DNSNames)
		}
		ca.signCSR(csr)
		ca.issueNonce(w)
		_ = json.NewEncoder(w).Encode(order{Status: "valid", Certificate: base + "/cert/1"})

	case "/order/1":
		ca.verifyJWS(r)
		ca.issueNonce(w)
		_ = json.NewEncoder(w).Encode(order{Status: "valid", Certificate: base + "/cert/1"})

	case "/cert/1":
		ca.verifyJWS(r)
		ca.issueNonce(w)
		w.Header().Set("Content-Type", "application/pem-certificate-chain")
		_, _ = w.Write(ca.issuedPEM)

	default:
		http.NotFound(w, r)
	}
}

func (ca *mockCA) signCSR(csr *x509.CertificateRequest) {
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      csr.Subject,
		DNSNames:     csr.DNSNames,
		NotBefore:    time.Now().Add(-time.Minute),
		NotAfter:     time.Now().Add(90 * 24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, ca.caCert, csr.PublicKey, ca.caKey)
	if err != nil {
		ca.t.Fatalf("signing the CSR: %v", err)
	}
	leaf := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	root := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: ca.caCert.Raw})
	ca.issuedPEM = append(leaf, root...)
}

func TestObtainCompletesAFullOrder(t *testing.T) {
	ca := newMockCA(t)
	accountKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	client := ca.client(accountKey)
	solver := NewHTTP01Solver()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	certPEM, keyPEM, err := client.Obtain(ctx, []string{"relay.example.com"}, solver)
	if err != nil {
		t.Fatalf("Obtain: %v", err)
	}

	// The issued pair must be directly usable by the TLS listener.
	pair, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		t.Fatalf("the issued certificate and key do not form a usable pair: %v", err)
	}
	leaf, err := x509.ParseCertificate(pair.Certificate[0])
	if err != nil {
		t.Fatalf("parsing the leaf: %v", err)
	}
	if err := leaf.VerifyHostname("relay.example.com"); err != nil {
		t.Errorf("the certificate does not cover the requested name: %v", err)
	}
	if !ca.challengeTriggered {
		t.Error("the client should have triggered the http-01 challenge")
	}
	// The solver must clean up after itself: a stale key authorization left on
	// port 80 is a small but needless disclosure.
	if solver.Pending() != 0 {
		t.Errorf("the solver should be empty after issuance, %d entries remain", solver.Pending())
	}
}

// badNonce is the one error RFC 8555 says to retry.
func TestObtainRetriesOnBadNonce(t *testing.T) {
	ca := newMockCA(t)
	ca.badNonceOnce = true
	accountKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if _, _, err := ca.client(accountKey).Obtain(ctx, []string{"relay.example.com"}, NewHTTP01Solver()); err != nil {
		t.Fatalf("Obtain should have recovered from a single badNonce: %v", err)
	}
	if !ca.servedBadNonce {
		t.Error("the mock never served the badNonce it was configured for")
	}
}

func TestObtainRequiresDomainsAndSolver(t *testing.T) {
	accountKey, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	c, _ := NewClient("https://acme.example.com/directory", "a@b.c", accountKey, testLogger())

	if _, _, err := c.Obtain(context.Background(), nil, NewHTTP01Solver()); err == nil {
		t.Error("Obtain with no domains should fail")
	}
	if _, _, err := c.Obtain(context.Background(), []string{"a.example.com"}, nil); err == nil {
		t.Error("Obtain with no solver should fail")
	}
}
