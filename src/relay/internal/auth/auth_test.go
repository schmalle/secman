package auth

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"
)

const (
	testToken = "an-ingest-token-long-enough-here"
	testNonce = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
)

var testHMACKey = []byte("an-ingest-hmac-key-long-enough-1")

func signedHeaders(t *testing.T, key []byte, ts time.Time, nonce string, body []byte) http.Header {
	t.Helper()
	h := http.Header{}
	h.Set("Authorization", "Bearer "+testToken)
	h.Set(HeaderTimestamp, strconv.FormatInt(ts.Unix(), 10))
	h.Set(HeaderNonce, nonce)
	h.Set(HeaderSignature, SignPayload(key, ts.Unix(), nonce, body))
	return h
}

func newVerifier(t *testing.T) *IngestVerifier {
	t.Helper()
	v, err := NewIngestVerifier(testToken, testHMACKey, 5*time.Minute)
	if err != nil {
		t.Fatalf("NewIngestVerifier: %v", err)
	}
	return v
}

func TestIngestAcceptsAWellFormedPush(t *testing.T) {
	v := newVerifier(t)
	now := time.Now()
	body := []byte(`{"schemaVersion":1}`)

	if reason, err := v.Verify(signedHeaders(t, testHMACKey, now, testNonce, body), body, now); err != nil {
		t.Fatalf("a correctly signed push should be accepted, got %v (%s)", err, reason)
	}
}

// A stolen bearer token alone must not be enough: the body signature is the
// second factor of the ingest channel.
func TestIngestRejectsTokenWithoutValidSignature(t *testing.T) {
	v := newVerifier(t)
	now := time.Now()
	body := []byte(`{"schemaVersion":1}`)

	headers := signedHeaders(t, []byte("a-completely-different-hmac-key!"), now, testNonce, body)
	reason, err := v.Verify(headers, body, now)
	if err == nil {
		t.Fatal("a push signed with the wrong key must be rejected")
	}
	if reason != "bad_signature" {
		t.Errorf("reason = %q, want bad_signature", reason)
	}
}

func TestIngestRejectsWrongToken(t *testing.T) {
	v := newVerifier(t)
	now := time.Now()
	body := []byte(`{}`)

	headers := signedHeaders(t, testHMACKey, now, testNonce, body)
	headers.Set("Authorization", "Bearer not-the-configured-token-value")
	if reason, err := v.Verify(headers, body, now); err == nil || reason != "bad_token" {
		t.Fatalf("wrong token should be rejected as bad_token, got reason=%q err=%v", reason, err)
	}
}

// The signature covers a digest of the body, so any mutation in flight breaks it.
func TestIngestDetectsBodyTampering(t *testing.T) {
	v := newVerifier(t)
	now := time.Now()
	body := []byte(`{"vulnerabilities":42}`)
	headers := signedHeaders(t, testHMACKey, now, testNonce, body)

	tampered := []byte(`{"vulnerabilities":0}`)
	if reason, err := v.Verify(headers, tampered, now); err == nil || reason != "bad_signature" {
		t.Fatalf("a modified body must fail the signature check, got reason=%q err=%v", reason, err)
	}
}

func TestIngestRejectsReplay(t *testing.T) {
	v := newVerifier(t)
	now := time.Now()
	body := []byte(`{}`)
	headers := signedHeaders(t, testHMACKey, now, testNonce, body)

	if _, err := v.Verify(headers, body, now); err != nil {
		t.Fatalf("first delivery should succeed: %v", err)
	}
	reason, err := v.Verify(headers, body, now.Add(time.Second))
	if err == nil {
		t.Fatal("the identical request replayed must be rejected")
	}
	if reason != "replayed_nonce" {
		t.Errorf("reason = %q, want replayed_nonce", reason)
	}
}

func TestIngestRejectsTimestampOutsideWindow(t *testing.T) {
	v := newVerifier(t)
	now := time.Now()
	body := []byte(`{}`)

	old := now.Add(-10 * time.Minute)
	if reason, err := v.Verify(signedHeaders(t, testHMACKey, old, testNonce, body), body, now); err == nil || reason != "timestamp_outside_window" {
		t.Fatalf("a stale timestamp must be rejected, got reason=%q err=%v", reason, err)
	}
	future := now.Add(10 * time.Minute)
	if reason, err := v.Verify(signedHeaders(t, testHMACKey, future, "ffffffffffffffffffff", body), body, now); err == nil || reason != "timestamp_outside_window" {
		t.Fatalf("a far-future timestamp must be rejected, got reason=%q err=%v", reason, err)
	}
}

// A short nonce would make the replay cache trivially collidable.
func TestIngestRejectsWeakNonce(t *testing.T) {
	v := newVerifier(t)
	now := time.Now()
	body := []byte(`{}`)

	for _, nonce := range []string{"", "abc", strings.Repeat("a", 200), "not hex at all!!!!"} {
		headers := signedHeaders(t, testHMACKey, now, nonce, body)
		if _, err := v.Verify(headers, body, now); err == nil {
			t.Errorf("nonce %q should have been rejected", nonce)
		}
	}
}

// A failing request must not burn the nonce, or an attacker could deny a
// legitimate push by racing it with a bad signature and the same nonce.
func TestFailedRequestDoesNotConsumeNonce(t *testing.T) {
	v := newVerifier(t)
	now := time.Now()
	body := []byte(`{}`)

	bad := signedHeaders(t, []byte("wrong-key-wrong-key-wrong-key-01"), now, testNonce, body)
	if _, err := v.Verify(bad, body, now); err == nil {
		t.Fatal("expected the bad signature to be rejected")
	}
	good := signedHeaders(t, testHMACKey, now, testNonce, body)
	if _, err := v.Verify(good, body, now); err != nil {
		t.Fatalf("the nonce should still be usable after a rejected attempt: %v", err)
	}
}

func TestNonceCacheIsSwept(t *testing.T) {
	v := newVerifier(t)
	now := time.Now()
	body := []byte(`{}`)
	if _, err := v.Verify(signedHeaders(t, testHMACKey, now, testNonce, body), body, now); err != nil {
		t.Fatalf("setup push failed: %v", err)
	}
	if v.PendingNonces() != 1 {
		t.Fatalf("expected 1 cached nonce, got %d", v.PendingNonces())
	}
	v.SweepNonces(now.Add(time.Hour))
	if v.PendingNonces() != 0 {
		t.Errorf("expected the cache to be swept, %d entries remain", v.PendingNonces())
	}
}

// --- access tokens ---------------------------------------------------------

func newIssuer(t *testing.T) *TokenIssuer {
	t.Helper()
	iss, err := NewTokenIssuer([]byte("a-token-signing-key-long-enough!"), 15*time.Minute)
	if err != nil {
		t.Fatalf("NewTokenIssuer: %v", err)
	}
	return iss
}

func TestTokenRoundTrip(t *testing.T) {
	iss := newIssuer(t)
	now := time.Now()

	token, claims, err := iss.Issue("dev_abc", []string{"status:kpis"}, now)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	got, err := iss.Verify(token, now.Add(time.Minute), time.Time{})
	if err != nil {
		t.Fatalf("Verify: %v", err)
	}
	if got.DeviceID != "dev_abc" || len(got.Scopes) != 1 || got.Scopes[0] != "status:kpis" {
		t.Errorf("claims round-tripped incorrectly: %+v", got)
	}
	if got.JTI != claims.JTI {
		t.Error("the token id should survive the round trip")
	}
}

func TestTokenRejectsTampering(t *testing.T) {
	iss := newIssuer(t)
	now := time.Now()
	token, _, err := iss.Issue("dev_abc", []string{"status:kpis"}, now)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}

	parts := strings.Split(token, ".")
	// Re-encode the claims with a wider scope and keep the original signature.
	forged := parts[0] + "." + base64.RawURLEncoding.EncodeToString(
		[]byte(`{"d":"dev_abc","s":["status:*"],"iat":1,"exp":9999999999,"jti":"x"}`)) + "." + parts[2]

	if _, err := iss.Verify(forged, now, time.Time{}); err == nil {
		t.Fatal("a token with swapped claims must not verify")
	}
}

func TestTokenExpires(t *testing.T) {
	iss := newIssuer(t)
	now := time.Now()
	token, _, _ := iss.Issue("dev_abc", nil, now)
	if _, err := iss.Verify(token, now.Add(16*time.Minute), time.Time{}); err == nil {
		t.Fatal("an expired token must not verify")
	}
}

// Revocation has to bite before the token would have expired on its own.
func TestTokenRefusedAfterRevocationInstant(t *testing.T) {
	iss := newIssuer(t)
	now := time.Now()
	token, _, _ := iss.Issue("dev_abc", nil, now)

	revokedAt := now.Add(time.Second)
	if _, err := iss.Verify(token, now.Add(time.Minute), revokedAt); err == nil {
		t.Fatal("a token issued before the revocation instant must be refused")
	}
	if _, err := iss.Verify(token, now.Add(time.Minute), now.Add(-time.Minute)); err != nil {
		t.Fatalf("a token issued after the revocation instant should still work: %v", err)
	}
}

func TestTokenRejectsGarbage(t *testing.T) {
	iss := newIssuer(t)
	for _, bad := range []string{"", "not-a-token", "smrt1.aaa", "xxxx1.aaa.bbb", strings.Repeat("a", 500)} {
		if _, err := iss.Verify(bad, time.Now(), time.Time{}); err == nil {
			t.Errorf("token %q should not verify", bad)
		}
	}
}

// --- device challenge/response ---------------------------------------------

func TestDeviceSignatureRoundTrip(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generating key: %v", err)
	}
	store := NewChallengeStore(2 * time.Minute)
	now := time.Now()

	c, err := store.Issue("dev_1", now)
	if err != nil {
		t.Fatalf("Issue: %v", err)
	}
	digest := sha256.Sum256(DeviceSigningInput("dev_1", c.Nonce))
	sig, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil {
		t.Fatalf("signing: %v", err)
	}
	encoded := base64.StdEncoding.EncodeToString(sig)

	if err := store.Redeem("dev_1", c.Nonce, now); err != nil {
		t.Fatalf("Redeem: %v", err)
	}
	if err := VerifyDeviceSignature(&key.PublicKey, "dev_1", c.Nonce, encoded); err != nil {
		t.Fatalf("VerifyDeviceSignature: %v", err)
	}
}

func TestChallengeIsSingleUse(t *testing.T) {
	store := NewChallengeStore(2 * time.Minute)
	now := time.Now()
	c, _ := store.Issue("dev_1", now)

	if err := store.Redeem("dev_1", c.Nonce, now); err != nil {
		t.Fatalf("first redeem should succeed: %v", err)
	}
	if err := store.Redeem("dev_1", c.Nonce, now); err == nil {
		t.Fatal("a challenge must not be redeemable twice")
	}
}

// One device must not be able to answer another device's challenge.
func TestChallengeIsBoundToItsDevice(t *testing.T) {
	store := NewChallengeStore(2 * time.Minute)
	now := time.Now()
	c, _ := store.Issue("dev_1", now)

	if err := store.Redeem("dev_2", c.Nonce, now); err == nil {
		t.Fatal("a challenge issued to dev_1 must not be redeemable by dev_2")
	}
}

func TestChallengeExpires(t *testing.T) {
	store := NewChallengeStore(time.Minute)
	now := time.Now()
	c, _ := store.Issue("dev_1", now)
	if err := store.Redeem("dev_1", c.Nonce, now.Add(2*time.Minute)); err == nil {
		t.Fatal("an expired challenge must not be redeemable")
	}
}

// A signature over a different nonce is worthless, which is what stops a
// captured signature from being replayed.
func TestSignatureIsBoundToNonceAndDevice(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	digest := sha256.Sum256(DeviceSigningInput("dev_1", "nonce-one"))
	sig, _ := ecdsa.SignASN1(rand.Reader, key, digest[:])
	encoded := base64.StdEncoding.EncodeToString(sig)

	if err := VerifyDeviceSignature(&key.PublicKey, "dev_1", "nonce-two", encoded); err == nil {
		t.Error("a signature over a different nonce must not verify")
	}
	if err := VerifyDeviceSignature(&key.PublicKey, "dev_2", "nonce-one", encoded); err == nil {
		t.Error("a signature naming a different device must not verify")
	}

	other, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err := VerifyDeviceSignature(&other.PublicKey, "dev_1", "nonce-one", encoded); err == nil {
		t.Error("a signature must not verify against another device's key")
	}
}
