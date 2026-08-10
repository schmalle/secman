// Package idp verifies external logins.
//
// # Why the relay verifies identity at all
//
// The device key (ECDSA P-256 in the Secure Enclave) proves *which device* is
// calling. It says nothing about *who* is holding it. For a security dashboard
// that distinction matters: an admin's phone that is handed to someone else,
// or a device provisioned by an attacker who intercepted an enrollment code,
// both present a perfectly valid device key.
//
// So the relay layers two independent proofs:
//
//	identity  Apple / Google / GitHub prove who the human is
//	device    a Secure Enclave key proves which device, on every request
//
// and then asks a third question that neither provider can answer:
// *is this person allowed anything here?* That answer only ever comes from the
// principal list secman pushes. Signing in with Apple grants nothing on its own.
//
// # Why an ID token is not used as the API credential
//
// It would be simpler to send Apple's identity token on every request. It is
// also wrong: it is a long-lived bearer token sitting on the device with no
// proof of possession, it cannot be revoked between issue and expiry, and it
// would make every single API call depend on the relay being able to reach
// Apple. The ID token is used exactly once — to bind a device — and then never
// again until re-attestation falls due.
//
// # Providers
//
//	apple   OIDC ID token (RS256), verified against Apple's JWKS. No secret.
//	google  OIDC ID token (RS256), verified against Google's JWKS. No secret.
//	github  No ID token exists, so the relay runs the authorization-code flow
//	        as a confidential client (see github.go). The client secret stays
//	        on the relay and never reaches the app.
package idp

import (
	"context"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Well-known provider endpoints.
const (
	AppleIssuer   = "https://appleid.apple.com"
	AppleJWKSURL  = "https://appleid.apple.com/auth/keys"
	GoogleJWKSURL = "https://www.googleapis.com/oauth2/v3/certs"
)

// GoogleIssuers are both spellings Google uses in the `iss` claim.
var GoogleIssuers = []string{"https://accounts.google.com", "accounts.google.com"}

// ErrVerification is the single error every ID-token failure collapses to.
// The specific cause goes to the server log; the client learns only "no".
var ErrVerification = errors.New("identity could not be verified")

const (
	maxJWTLength      = 8192
	maxJWKSBytes      = 256 << 10
	jwksCacheTTL      = 6 * time.Hour
	jwksMinRefetchGap = time.Minute
	// clockSkew tolerates a small difference between the relay's clock and the
	// provider's when checking exp/iat.
	clockSkew = 2 * time.Minute
)

// Identity is the verified result of an external login.
type Identity struct {
	Provider string
	// Subject is the provider's stable identifier — Apple's `sub`, Google's
	// `sub`, GitHub's numeric account id. Never an email or a login name: both
	// are mutable and both can be re-registered by somebody else.
	Subject string
	// Email is carried for display and audit only. It is never used to match a
	// principal, for exactly the reason above.
	Email         string
	EmailVerified bool
	DisplayName   string
}

// OIDCConfig describes one ID-token-issuing provider.
type OIDCConfig struct {
	Provider string
	// Issuers are the acceptable `iss` values.
	Issuers []string
	// Audiences are the acceptable `aud` values: the iOS bundle identifier for
	// Apple, the OAuth client id for Google. An ID token minted for a
	// *different* app is a valid token — checking `aud` is what stops it being
	// accepted here.
	Audiences []string
	JWKSURL   string
}

// Verifier validates OIDC ID tokens for one provider.
type Verifier struct {
	cfg    OIDCConfig
	http   *http.Client
	mu     sync.Mutex
	keys   map[string]*rsa.PublicKey
	loaded time.Time
	// lastAttempt rate-limits JWKS refetches so an unknown `kid` cannot be used
	// to make the relay hammer the provider.
	lastAttempt time.Time
	// minRefetchGap is that rate limit. A field only so the test suite can
	// exercise key rotation without sleeping; production always uses the
	// constant below.
	minRefetchGap time.Duration
}

// NewVerifier builds a verifier. The HTTP client must already carry the
// SSRF-safe transport (see httpclient.go); it is passed in rather than built
// here so every outbound call in the process shares one policy.
func NewVerifier(cfg OIDCConfig, client *http.Client) (*Verifier, error) {
	if cfg.Provider == "" {
		return nil, errors.New("idp: provider name is required")
	}
	if len(cfg.Issuers) == 0 {
		return nil, fmt.Errorf("idp: %s needs at least one acceptable issuer", cfg.Provider)
	}
	if len(cfg.Audiences) == 0 {
		return nil, fmt.Errorf("idp: %s needs at least one acceptable audience (the app's client id)", cfg.Provider)
	}
	if cfg.JWKSURL == "" {
		return nil, fmt.Errorf("idp: %s needs a JWKS URL", cfg.Provider)
	}
	if client == nil {
		return nil, errors.New("idp: an HTTP client is required")
	}
	return &Verifier{cfg: cfg, http: client, keys: map[string]*rsa.PublicKey{}, minRefetchGap: jwksMinRefetchGap}, nil
}

// Provider names the provider this verifier handles.
func (v *Verifier) Provider() string { return v.cfg.Provider }

// Verify checks an ID token and returns the identity it asserts.
//
// `expectedNonce` is the raw nonce the relay issued. Both Apple and Google put
// the *hash* the client supplied into the token, and the iOS convention is to
// pass SHA-256 hex of the raw nonce — so the comparison is against
// [HashNonce](expectedNonce). Without this binding, an ID token captured from
// any other context could be replayed here to bind an attacker's device.
func (v *Verifier) Verify(ctx context.Context, idToken, expectedNonce string) (*Identity, string, error) {
	if len(idToken) == 0 || len(idToken) > maxJWTLength {
		return nil, "token_length", ErrVerification
	}
	parts := strings.Split(idToken, ".")
	if len(parts) != 3 {
		return nil, "not_a_jws", ErrVerification
	}

	var header struct {
		Alg string `json:"alg"`
		Kid string `json:"kid"`
	}
	headerJSON, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil || json.Unmarshal(headerJSON, &header) != nil {
		return nil, "bad_header", ErrVerification
	}
	// Pinned, not negotiated. Accepting whatever `alg` the token asks for is
	// the classic JWT vulnerability; "none" and HS256-with-the-public-key both
	// die here.
	if header.Alg != "RS256" {
		return nil, "unsupported_alg", ErrVerification
	}
	if header.Kid == "" {
		return nil, "missing_kid", ErrVerification
	}

	key, err := v.keyFor(ctx, header.Kid)
	if err != nil {
		return nil, "unknown_kid", ErrVerification
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return nil, "bad_signature_encoding", ErrVerification
	}
	if err := verifyRS256(key, parts[0]+"."+parts[1], signature); err != nil {
		return nil, "bad_signature", ErrVerification
	}

	// Only now is the payload trustworthy enough to parse.
	payloadJSON, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, "bad_payload_encoding", ErrVerification
	}
	var claims struct {
		Iss           string      `json:"iss"`
		Sub           string      `json:"sub"`
		Aud           interface{} `json:"aud"`
		Exp           int64       `json:"exp"`
		Iat           int64       `json:"iat"`
		Nonce         string      `json:"nonce"`
		Email         string      `json:"email"`
		EmailVerified interface{} `json:"email_verified"`
		Name          string      `json:"name"`
	}
	if err := json.Unmarshal(payloadJSON, &claims); err != nil {
		return nil, "bad_payload", ErrVerification
	}

	if !contains(v.cfg.Issuers, claims.Iss) {
		return nil, "issuer_mismatch", ErrVerification
	}
	if !audienceMatches(claims.Aud, v.cfg.Audiences) {
		return nil, "audience_mismatch", ErrVerification
	}
	if claims.Sub == "" || len(claims.Sub) > 255 {
		return nil, "bad_subject", ErrVerification
	}

	now := time.Now()
	if claims.Exp == 0 || now.After(time.Unix(claims.Exp, 0).Add(clockSkew)) {
		return nil, "expired", ErrVerification
	}
	if claims.Iat != 0 && now.Add(clockSkew).Before(time.Unix(claims.Iat, 0)) {
		return nil, "issued_in_the_future", ErrVerification
	}

	// The nonce is mandatory, not best-effort. A verifier that accepts a token
	// without one accepts a replayed token.
	if expectedNonce == "" {
		return nil, "no_expected_nonce", ErrVerification
	}
	if claims.Nonce == "" {
		return nil, "token_carries_no_nonce", ErrVerification
	}
	if !constantTimeEqual(claims.Nonce, HashNonce(expectedNonce)) && !constantTimeEqual(claims.Nonce, expectedNonce) {
		// Both forms are accepted because the two SDKs differ: the Apple
		// convention is to send SHA-256 hex of the raw nonce, while some
		// Google SDK paths pass it through verbatim. Both are compared in
		// constant time and both are still a single-use, relay-issued value.
		return nil, "nonce_mismatch", ErrVerification
	}

	return &Identity{
		Provider:      v.cfg.Provider,
		Subject:       claims.Sub,
		Email:         claims.Email,
		EmailVerified: truthy(claims.EmailVerified),
		DisplayName:   claims.Name,
	}, "", nil
}

// keyFor returns the signing key for a `kid`, refreshing the JWKS if needed.
func (v *Verifier) keyFor(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	v.mu.Lock()
	key, ok := v.keys[kid]
	fresh := time.Since(v.loaded) < jwksCacheTTL
	recentlyTried := time.Since(v.lastAttempt) < v.minRefetchGap
	v.mu.Unlock()

	if ok && fresh {
		return key, nil
	}
	if !ok && recentlyTried {
		// An unknown kid must not turn into a request-per-attempt against the
		// provider. Providers rotate rarely; a minute of staleness is fine.
		return nil, errors.New("idp: unknown key id")
	}

	if err := v.refresh(ctx); err != nil {
		// Fall back to a cached key if we have one: a provider outage should
		// not stop devices that present a token signed by a key we already
		// hold.
		if ok {
			return key, nil
		}
		return nil, err
	}

	v.mu.Lock()
	defer v.mu.Unlock()
	key, ok = v.keys[kid]
	if !ok {
		return nil, errors.New("idp: unknown key id")
	}
	return key, nil
}

func (v *Verifier) refresh(ctx context.Context) error {
	v.mu.Lock()
	v.lastAttempt = time.Now()
	v.mu.Unlock()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, v.cfg.JWKSURL, nil)
	if err != nil {
		return fmt.Errorf("idp: building JWKS request: %w", err)
	}
	req.Header.Set("Accept", "application/json")

	resp, err := v.http.Do(req)
	if err != nil {
		return fmt.Errorf("idp: fetching JWKS: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("idp: JWKS endpoint returned HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxJWKSBytes))
	if err != nil {
		return fmt.Errorf("idp: reading JWKS: %w", err)
	}

	var jwks struct {
		Keys []struct {
			Kty string `json:"kty"`
			Kid string `json:"kid"`
			Use string `json:"use"`
			Alg string `json:"alg"`
			N   string `json:"n"`
			E   string `json:"e"`
		} `json:"keys"`
	}
	if err := json.Unmarshal(body, &jwks); err != nil {
		return fmt.Errorf("idp: JWKS is not valid JSON: %w", err)
	}

	parsed := make(map[string]*rsa.PublicKey, len(jwks.Keys))
	for _, k := range jwks.Keys {
		if k.Kty != "RSA" || k.Kid == "" {
			continue
		}
		if k.Use != "" && k.Use != "sig" {
			continue
		}
		if k.Alg != "" && k.Alg != "RS256" {
			continue
		}
		nBytes, err := base64.RawURLEncoding.DecodeString(k.N)
		if err != nil {
			continue
		}
		eBytes, err := base64.RawURLEncoding.DecodeString(k.E)
		if err != nil {
			continue
		}
		n := new(big.Int).SetBytes(nBytes)
		e := new(big.Int).SetBytes(eBytes)
		if !e.IsInt64() || e.Int64() < 3 {
			continue
		}
		// Reject implausibly small moduli rather than trusting the provider to
		// only ever publish sane ones.
		if n.BitLen() < 2048 {
			continue
		}
		parsed[k.Kid] = &rsa.PublicKey{N: n, E: int(e.Int64())}
	}
	if len(parsed) == 0 {
		return errors.New("idp: JWKS contained no usable RS256 keys")
	}

	v.mu.Lock()
	v.keys = parsed
	v.loaded = time.Now()
	v.mu.Unlock()
	return nil
}

func contains(haystack []string, needle string) bool {
	for _, v := range haystack {
		if v == needle {
			return true
		}
	}
	return false
}

// audienceMatches handles `aud` being either a string or an array of strings,
// both of which are legal in a JWT.
func audienceMatches(aud interface{}, accepted []string) bool {
	switch v := aud.(type) {
	case string:
		return contains(accepted, v)
	case []interface{}:
		for _, item := range v {
			if s, ok := item.(string); ok && contains(accepted, s) {
				return true
			}
		}
	}
	return false
}

// truthy handles `email_verified` arriving as a bool or as the string "true",
// which Apple has historically done.
func truthy(v interface{}) bool {
	switch t := v.(type) {
	case bool:
		return t
	case string:
		return t == "true"
	}
	return false
}
