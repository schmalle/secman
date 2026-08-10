package idp

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"sync"
	"time"
)

// EphemeralStore holds short-lived, single-use values: login nonces, OAuth
// state parameters and binding tickets.
//
// In memory only, and bounded. Losing the set on restart costs a user one
// retry, which is a far better trade than persisting authentication material to
// a disk in a DMZ.
type EphemeralStore struct {
	mu      sync.Mutex
	ttl     time.Duration
	maxSize int
	entries map[string]entry
}

type entry struct {
	value     Payload
	expiresAt time.Time
}

// Payload is what an ephemeral key carries.
type Payload struct {
	// DeviceKeyFingerprint binds the value to one device public key.
	//
	// This is the control that stops a stolen ID token or a stolen OAuth
	// callback from registering an attacker's key: the nonce, the state and
	// the ticket are all issued against a specific key, and the completing
	// request must present the same one.
	DeviceKeyFingerprint string
	// Identity is set on a binding ticket, once the provider has been verified.
	Identity *Identity
	// DeviceName is carried through the browser round trip so the user does not
	// have to type it twice.
	DeviceName string
}

// ErrNotFound is returned when a key is unknown, expired or already used.
var ErrNotFound = errors.New("value is not valid")

// NewEphemeralStore builds a store.
func NewEphemeralStore(ttl time.Duration, maxSize int) *EphemeralStore {
	if maxSize <= 0 {
		maxSize = 10_000
	}
	return &EphemeralStore{ttl: ttl, maxSize: maxSize, entries: map[string]entry{}}
}

// Issue mints a new random key carrying `payload`.
func (s *EphemeralStore) Issue(payload Payload, now time.Time) (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", errors.New("idp: generating value failed")
	}
	key := hex.EncodeToString(buf)

	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepLocked(now)
	if len(s.entries) >= s.maxSize {
		// Fail closed rather than evicting somebody else's in-flight login.
		return "", errors.New("idp: too many outstanding logins")
	}
	s.entries[key] = entry{value: payload, expiresAt: now.Add(s.ttl)}
	return key, nil
}

// Redeem consumes a key. Single use: the entry is removed whether or not the
// rest of the caller's checks pass.
func (s *EphemeralStore) Redeem(key string, now time.Time) (Payload, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepLocked(now)

	e, ok := s.entries[key]
	if !ok {
		return Payload{}, ErrNotFound
	}
	delete(s.entries, key)
	if !e.expiresAt.After(now) {
		return Payload{}, ErrNotFound
	}
	return e.value, nil
}

// Sweep drops expired entries. Called on a timer.
func (s *EphemeralStore) Sweep(now time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepLocked(now)
}

// Len reports the outstanding count, for the ops plane.
func (s *EphemeralStore) Len() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.entries)
}

func (s *EphemeralStore) sweepLocked(now time.Time) {
	for k, e := range s.entries {
		if !e.expiresAt.After(now) {
			delete(s.entries, k)
		}
	}
}

// Fingerprint reduces a device public key to a comparable tag. The key itself
// is not secret; this exists so the ephemeral entries stay small and so a log
// line can name a key without printing it.
func Fingerprint(publicKeyDER []byte) string {
	sum := sha256.Sum256(publicKeyDER)
	return hex.EncodeToString(sum[:])
}

// MatchesKey reports whether a payload was issued for this device key.
func (p Payload) MatchesKey(publicKeyDER []byte) bool {
	return p.DeviceKeyFingerprint != "" &&
		constantTimeEqual(p.DeviceKeyFingerprint, Fingerprint(publicKeyDER))
}
