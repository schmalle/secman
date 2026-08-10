package auth

import (
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"sync"
	"time"
)

// ErrChallenge is the single failure returned for every challenge/response
// problem.
var ErrChallenge = errors.New("device authentication failed")

// deviceAuthContext is mixed into every signed message. Domain separation:
// without it, a signature produced for some other purpose by the same Secure
// Enclave key could be replayed here as an authentication.
const deviceAuthContext = "secman-relay-device-auth-v1"

// Challenge is a one-shot nonce bound to a single device.
type Challenge struct {
	DeviceID  string
	Nonce     string
	ExpiresAt time.Time
}

// ChallengeStore issues and redeems device authentication challenges.
//
// In memory only: a challenge lives for a couple of minutes and losing the set
// on restart costs a device one extra round trip.
type ChallengeStore struct {
	mu         sync.Mutex
	ttl        time.Duration
	maxPending int
	pending    map[string]Challenge
}

// NewChallengeStore builds a store.
func NewChallengeStore(ttl time.Duration) *ChallengeStore {
	return &ChallengeStore{
		ttl:        ttl,
		maxPending: 10_000,
		pending:    make(map[string]Challenge),
	}
}

// Issue creates a challenge for a device.
func (s *ChallengeStore) Issue(deviceID string, now time.Time) (Challenge, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return Challenge{}, errors.New("generating challenge failed")
	}
	c := Challenge{
		DeviceID:  deviceID,
		Nonce:     hex.EncodeToString(buf),
		ExpiresAt: now.Add(s.ttl),
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepLocked(now)
	if len(s.pending) >= s.maxPending {
		// Fail closed rather than evicting: silently dropping other devices'
		// challenges under load would be a denial-of-service amplifier.
		return Challenge{}, errors.New("too many outstanding challenges")
	}
	s.pending[c.Nonce] = c
	return c, nil
}

// Redeem consumes a challenge. A nonce is valid exactly once, for exactly the
// device it was issued to.
func (s *ChallengeStore) Redeem(deviceID, nonce string, now time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepLocked(now)

	c, ok := s.pending[nonce]
	if !ok {
		return ErrChallenge
	}
	delete(s.pending, nonce)

	if c.DeviceID != deviceID {
		return ErrChallenge
	}
	if !c.ExpiresAt.After(now) {
		return ErrChallenge
	}
	return nil
}

// Sweep drops expired challenges. Called on a timer from main.
func (s *ChallengeStore) Sweep(now time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepLocked(now)
}

// Pending reports the outstanding challenge count, for the ops plane.
func (s *ChallengeStore) Pending() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.pending)
}

func (s *ChallengeStore) sweepLocked(now time.Time) {
	for k, c := range s.pending {
		if !c.ExpiresAt.After(now) {
			delete(s.pending, k)
		}
	}
}

// DeviceSigningInput is the exact byte string a device must sign. Exported so
// the iOS client (and the test suite) can reproduce it without guessing.
func DeviceSigningInput(deviceID, nonce string) []byte {
	return []byte(deviceAuthContext + "|" + deviceID + "|" + nonce)
}

// VerifyDeviceSignature checks an ASN.1 DER ECDSA signature over the canonical
// signing input.
func VerifyDeviceSignature(pub *ecdsa.PublicKey, deviceID, nonce, signatureB64 string) error {
	if pub == nil {
		return ErrChallenge
	}
	if len(signatureB64) > 512 {
		return ErrChallenge
	}
	sig, err := base64.StdEncoding.DecodeString(signatureB64)
	if err != nil {
		return ErrChallenge
	}
	digest := sha256.Sum256(DeviceSigningInput(deviceID, nonce))
	if !ecdsa.VerifyASN1(pub, digest[:], sig) {
		return ErrChallenge
	}
	return nil
}

// deviceBindingContext separates a binding signature from the per-session
// authentication signature above. Two purposes, two prefixes: a signature
// produced for one must never be replayable as the other.
const deviceBindingContext = "secman-relay-device-bind-v1"

// DeviceBindingInput is the byte string a device signs when it binds itself to
// a principal, proving it holds the private half of the key it is registering.
//
// Without this, an attacker who captured a login nonce and an identity token
// could register a key they do not control — or, worse, someone else's public
// key, silently attaching their own session to another person's device record.
func DeviceBindingInput(nonce, keyFingerprint string) []byte {
	return []byte(deviceBindingContext + "|" + nonce + "|" + keyFingerprint)
}

// VerifyBindingSignature checks the proof of possession offered at binding.
func VerifyBindingSignature(pub *ecdsa.PublicKey, nonce, keyFingerprint, signatureB64 string) error {
	if pub == nil {
		return ErrChallenge
	}
	if len(signatureB64) > 512 {
		return ErrChallenge
	}
	sig, err := base64.StdEncoding.DecodeString(signatureB64)
	if err != nil {
		return ErrChallenge
	}
	digest := sha256.Sum256(DeviceBindingInput(nonce, keyFingerprint))
	if !ecdsa.VerifyASN1(pub, digest[:], sig) {
		return ErrChallenge
	}
	return nil
}
