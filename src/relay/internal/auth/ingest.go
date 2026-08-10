// Package auth holds the relay's three authentication mechanisms:
//
//   - ingest.go   secman -> relay: bearer token + HMAC body signature + replay window
//   - token.go    relay -> device: short-lived, relay-issued access tokens
//   - device.go   device -> relay: ECDSA proof-of-possession challenge/response
//
// None of them trusts the network. That is the whole point of the relay: being
// inside a VPC, behind an ALB, or on the ingest port is never sufficient to do
// anything.
package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Ingest header names. Prefixed so a reverse proxy that strips or rewrites
// unknown X- headers is easy to spot in a diff.
const (
	HeaderTimestamp = "X-Secman-Timestamp"
	HeaderNonce     = "X-Secman-Nonce"
	HeaderSignature = "X-Secman-Signature"
	// SignatureVersion prefixes the signature value so the scheme can be
	// rotated later without ambiguity.
	SignatureVersion = "v1"
)

// ErrIngestUnauthorized is the single error every ingest failure collapses to
// on the wire. The specific cause goes to the log with the actor and outcome
// (A09) but never to the caller: telling an attacker whether the token was
// wrong, the signature was wrong or the nonce was replayed is free
// reconnaissance.
var ErrIngestUnauthorized = errors.New("ingest request is not authorized")

const (
	maxNonceLength = 128
	minNonceLength = 16
)

// IngestVerifier authenticates a push from secman.
type IngestVerifier struct {
	tokenDigest [32]byte
	hmacKey     []byte
	maxSkew     time.Duration
	nonces      *nonceCache
}

// NewIngestVerifier builds a verifier. The bearer token is stored only as a
// digest so a heap dump of the running relay does not hand over the credential
// in plaintext.
func NewIngestVerifier(token string, hmacKey []byte, maxSkew time.Duration) (*IngestVerifier, error) {
	if token == "" {
		return nil, errors.New("ingest token must not be empty")
	}
	if len(hmacKey) == 0 {
		return nil, errors.New("ingest HMAC key must not be empty")
	}
	if maxSkew <= 0 {
		return nil, errors.New("ingest clock skew must be positive")
	}
	return &IngestVerifier{
		tokenDigest: sha256.Sum256([]byte(token)),
		hmacKey:     append([]byte(nil), hmacKey...),
		maxSkew:     maxSkew,
		nonces:      newNonceCache(maxSkew),
	}, nil
}

// Verify authenticates a push. `body` is the exact bytes that were read from
// the request; the caller must pass what it will parse, not a re-encoding, or
// the signature check becomes decorative.
//
// The returned error is always ErrIngestUnauthorized-wrapped for the client;
// `reason` is a short, non-secret string for the server-side log.
func (v *IngestVerifier) Verify(h http.Header, body []byte, now time.Time) (reason string, err error) {
	presented, ok := bearerToken(h.Get("Authorization"))
	if !ok {
		return "missing_bearer", ErrIngestUnauthorized
	}
	presentedDigest := sha256.Sum256([]byte(presented))
	if subtle.ConstantTimeCompare(presentedDigest[:], v.tokenDigest[:]) != 1 {
		return "bad_token", ErrIngestUnauthorized
	}

	tsRaw := h.Get(HeaderTimestamp)
	if tsRaw == "" {
		return "missing_timestamp", ErrIngestUnauthorized
	}
	tsUnix, convErr := strconv.ParseInt(tsRaw, 10, 64)
	if convErr != nil {
		return "bad_timestamp", ErrIngestUnauthorized
	}
	ts := time.Unix(tsUnix, 0)
	delta := now.Sub(ts)
	if delta < 0 {
		delta = -delta
	}
	if delta > v.maxSkew {
		return "timestamp_outside_window", ErrIngestUnauthorized
	}

	nonce := h.Get(HeaderNonce)
	if len(nonce) < minNonceLength || len(nonce) > maxNonceLength || !isHexLike(nonce) {
		return "bad_nonce", ErrIngestUnauthorized
	}

	sig := h.Get(HeaderSignature)
	expected := SignPayload(v.hmacKey, tsUnix, nonce, body)
	if !constantTimeEqualString(sig, expected) {
		return "bad_signature", ErrIngestUnauthorized
	}

	// Replay check goes last: a nonce is only burned once everything else about
	// the request is already known-good, so a flood of bogus requests cannot
	// evict legitimate nonces from the cache.
	if !v.nonces.Add(nonce, now) {
		return "replayed_nonce", ErrIngestUnauthorized
	}
	return "", nil
}

// SignPayload produces the value of the X-Secman-Signature header.
//
// The signed string binds three things together: when the request was made,
// a unique nonce, and a digest of the exact body. Signing the digest rather
// than the body keeps the construction identical for a 200-byte control
// document and a multi-megabyte snapshot, and the explicit separators make the
// encoding unambiguous (no length-extension between fields).
func SignPayload(key []byte, unixSeconds int64, nonce string, body []byte) string {
	bodyDigest := sha256.Sum256(body)
	canonical := SignatureVersion + ":" + strconv.FormatInt(unixSeconds, 10) + ":" + nonce + ":" + hex.EncodeToString(bodyDigest[:])
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(canonical))
	return SignatureVersion + "=" + hex.EncodeToString(mac.Sum(nil))
}

// PendingNonces reports the replay-cache size, for the ops plane.
func (v *IngestVerifier) PendingNonces() int { return v.nonces.Len() }

// SweepNonces drops expired nonces. Called on a timer from main.
func (v *IngestVerifier) SweepNonces(now time.Time) { v.nonces.sweep(now) }

// --- nonce cache -----------------------------------------------------------

// nonceCache remembers nonces for exactly as long as the clock-skew window, so
// it is bounded by the push rate rather than by uptime. Entries older than the
// window can be forgotten safely: a replay carrying them would already fail the
// timestamp check.
type nonceCache struct {
	mu    sync.Mutex
	ttl   time.Duration
	seen  map[string]time.Time
	limit int
}

func newNonceCache(ttl time.Duration) *nonceCache {
	return &nonceCache{
		ttl:  ttl,
		seen: make(map[string]time.Time),
		// A hard ceiling in case a legitimate-but-runaway pusher fills the map
		// faster than the sweep drains it. Reaching it fails closed.
		limit: 100_000,
	}
}

// Add records a nonce and reports whether it was previously unseen.
func (c *nonceCache) Add(nonce string, now time.Time) bool {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.sweepLocked(now)
	if _, exists := c.seen[nonce]; exists {
		return false
	}
	if len(c.seen) >= c.limit {
		return false
	}
	c.seen[nonce] = now
	return true
}

func (c *nonceCache) sweep(now time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.sweepLocked(now)
}

func (c *nonceCache) sweepLocked(now time.Time) {
	cutoff := now.Add(-c.ttl * 2)
	for k, seenAt := range c.seen {
		if seenAt.Before(cutoff) {
			delete(c.seen, k)
		}
	}
}

func (c *nonceCache) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.seen)
}

// --- shared helpers --------------------------------------------------------

func bearerToken(header string) (string, bool) {
	const prefix = "Bearer "
	if len(header) <= len(prefix) || !strings.EqualFold(header[:len(prefix)], prefix) {
		return "", false
	}
	tok := strings.TrimSpace(header[len(prefix):])
	if tok == "" {
		return "", false
	}
	return tok, true
}

// constantTimeEqualString compares two strings without leaking their contents
// through timing. The length check is unavoidable and harmless here: both
// values are fixed-length hex signatures.
func constantTimeEqualString(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

func isHexLike(s string) bool {
	for _, r := range s {
		ok := (r >= '0' && r <= '9') || (r >= 'a' && r <= 'f') || (r >= 'A' && r <= 'F') || r == '-'
		if !ok {
			return false
		}
	}
	return true
}

// FormatAuthFailure builds a log-safe description of an ingest rejection.
func FormatAuthFailure(reason, clientIP string) string {
	return fmt.Sprintf("ingest rejected reason=%s peer=%s", reason, clientIP)
}
