// Package devices is the relay's device registry: which mobile devices are
// enrolled, what they may read, and which have been revoked.
//
// The registry is the one piece of relay state that must survive a restart —
// a phone cannot re-enrol itself, so losing it would lock every user out until
// an admin issued new codes. It is persisted as a 0600 JSON file inside a 0700
// directory.
//
// What it deliberately does not contain: any device *secret*. A device is
// identified by an ECDSA P-256 public key. The private half never leaves the
// phone's Secure Enclave, so the persisted file is not a credential store and
// a copy of it does not let anyone authenticate as a device.
package devices

import (
	"crypto/ecdsa"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/schmalle/secman/src/relay/internal/model"
)

// Errors returned by the registry. Callers map these to HTTP status codes; the
// text is written to be safe to return to an unauthenticated client, i.e. it
// never distinguishes "no such code" from "code already used" in a way that
// helps an attacker enumerate.
var (
	ErrEnrollmentRejected = errors.New("enrollment code is not valid")
	ErrUnknownDevice      = errors.New("device is not enrolled")
	ErrDeviceRevoked      = errors.New("device access has been revoked")
	ErrRegistryFull       = errors.New("device registry is full")
)

const (
	maxDeviceNameLength = 64
	deviceIDBytes       = 16
)

// Device is an enrolled mobile client.
type Device struct {
	ID string `json:"id"`
	// Name is operator-supplied text ("Markus iPhone"). Untrusted; sanitized
	// before it is logged and length-capped on the way in.
	Name string `json:"name"`
	// PublicKeyDER is the SPKI encoding of the device's ECDSA P-256 public key.
	PublicKeyDER []byte `json:"publicKeyDer"`
	// Subject is the secman identity the enrollment code was issued for. It is
	// carried for audit only — the relay never contacts secman to check it.
	Subject    string    `json:"subject"`
	Scopes     []string  `json:"scopes"`
	EnrolledAt time.Time `json:"enrolledAt"`
	LastSeenAt time.Time `json:"lastSeenAt,omitempty"`
	Revoked    bool      `json:"revoked,omitempty"`
	RevokedAt  time.Time `json:"revokedAt,omitempty"`
	// TokensValidAfter lets a revocation invalidate already-issued access
	// tokens: any token issued at or before this instant is refused even
	// though its HMAC is intact and it has not expired.
	TokensValidAfter time.Time `json:"tokensValidAfter,omitempty"`
}

// PublicKey parses the stored SPKI bytes.
func (d *Device) PublicKey() (*ecdsa.PublicKey, error) {
	pub, err := x509.ParsePKIXPublicKey(d.PublicKeyDER)
	if err != nil {
		return nil, fmt.Errorf("stored device key is unreadable: %w", err)
	}
	ec, ok := pub.(*ecdsa.PublicKey)
	if !ok {
		return nil, errors.New("stored device key is not an ECDSA key")
	}
	return ec, nil
}

type enrollment struct {
	CodeSHA256 string    `json:"codeSha256"`
	Subject    string    `json:"subject"`
	Scopes     []string  `json:"scopes"`
	ExpiresAt  time.Time `json:"expiresAt"`
	Label      string    `json:"label,omitempty"`
}

type persistedState struct {
	Version     int                    `json:"version"`
	Devices     map[string]*Device     `json:"devices"`
	Enrollments map[string]*enrollment `json:"enrollments"`
	// RevokeAllBefore implements Control.RevokeAll without having to enumerate
	// devices that have not enrolled yet.
	RevokeAllBefore time.Time `json:"revokeAllBefore,omitempty"`
}

// Registry is safe for concurrent use.
type Registry struct {
	mu         sync.RWMutex
	path       string
	maxDevices int
	state      persistedState
}

// Open loads the registry from stateDir, creating an empty one if absent.
func Open(stateDir string, maxDevices int) (*Registry, error) {
	if stateDir == "" {
		return nil, errors.New("state directory must not be empty")
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return nil, fmt.Errorf("creating state directory: %w", err)
	}
	// MkdirAll leaves an existing directory's mode alone; tighten it so an
	// upgrade from a looser deployment does not stay world-readable.
	if err := os.Chmod(stateDir, 0o700); err != nil {
		return nil, fmt.Errorf("tightening state directory permissions: %w", err)
	}

	r := &Registry{
		path:       filepath.Join(stateDir, "devices.json"),
		maxDevices: maxDevices,
		state: persistedState{
			Version:     1,
			Devices:     map[string]*Device{},
			Enrollments: map[string]*enrollment{},
		},
	}

	raw, err := os.ReadFile(r.path)
	switch {
	case errors.Is(err, os.ErrNotExist):
		return r, nil
	case err != nil:
		return nil, fmt.Errorf("reading device registry: %w", err)
	}
	var loaded persistedState
	if err := json.Unmarshal(raw, &loaded); err != nil {
		return nil, fmt.Errorf("device registry is corrupt: %w", err)
	}
	if loaded.Devices == nil {
		loaded.Devices = map[string]*Device{}
	}
	if loaded.Enrollments == nil {
		loaded.Enrollments = map[string]*enrollment{}
	}
	loaded.Version = 1
	r.state = loaded
	return r, nil
}

// ApplyControl folds a control document into the registry. Additive: grants are
// added, revocations are applied, and nothing already revoked is ever restored.
func (r *Registry) ApplyControl(c *model.Control, now time.Time) (added, revoked int, err error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	for _, g := range c.Enrollments {
		if !g.ExpiresAt.After(now) {
			continue // already expired on arrival; nothing to store
		}
		if _, exists := r.state.Enrollments[g.CodeSHA256]; exists {
			continue
		}
		r.state.Enrollments[g.CodeSHA256] = &enrollment{
			CodeSHA256: g.CodeSHA256,
			Subject:    g.Subject,
			Scopes:     append([]string(nil), g.Scopes...),
			ExpiresAt:  g.ExpiresAt,
			Label:      truncate(g.Label, maxDeviceNameLength),
		}
		added++
	}

	for _, rev := range c.Revocations {
		at := rev.RevokedAt
		if at.IsZero() {
			at = now
		}
		if rev.RevokeAll {
			if at.After(r.state.RevokeAllBefore) {
				r.state.RevokeAllBefore = at
			}
			for _, d := range r.state.Devices {
				if !d.Revoked {
					d.Revoked = true
					d.RevokedAt = at
					revoked++
				}
				if at.After(d.TokensValidAfter) {
					d.TokensValidAfter = at
				}
			}
			// A blanket revocation must also burn unredeemed codes, otherwise
			// the attacker who triggered it just enrols again.
			r.state.Enrollments = map[string]*enrollment{}
			continue
		}
		d, ok := r.state.Devices[rev.DeviceID]
		if !ok {
			continue
		}
		if !d.Revoked {
			d.Revoked = true
			d.RevokedAt = at
			revoked++
		}
		if at.After(d.TokensValidAfter) {
			d.TokensValidAfter = at
		}
	}

	r.pruneLocked(now)
	if err := r.persistLocked(); err != nil {
		return added, revoked, err
	}
	return added, revoked, nil
}

// Enroll redeems a plaintext enrollment code and registers a device.
//
// The code is single use: it is deleted whether or not the rest of the
// enrollment succeeds, so a failed attempt cannot be replayed with a different
// public key.
func (r *Registry) Enroll(code string, publicKeyDER []byte, name string, now time.Time) (*Device, error) {
	digest := sha256.Sum256([]byte(code))
	key := hex.EncodeToString(digest[:])

	r.mu.Lock()
	defer r.mu.Unlock()

	r.pruneLocked(now)

	grant, ok := r.state.Enrollments[key]
	if !ok {
		return nil, ErrEnrollmentRejected
	}
	// The map lookup already matched on the digest; this second constant-time
	// comparison costs nothing and keeps the invariant explicit for review.
	if subtle.ConstantTimeCompare([]byte(grant.CodeSHA256), []byte(key)) != 1 {
		return nil, ErrEnrollmentRejected
	}
	delete(r.state.Enrollments, key)

	if !grant.ExpiresAt.After(now) {
		_ = r.persistLocked()
		return nil, ErrEnrollmentRejected
	}
	if len(r.state.Devices) >= r.maxDevices {
		_ = r.persistLocked()
		return nil, ErrRegistryFull
	}

	pub, err := parseP256PublicKey(publicKeyDER)
	if err != nil {
		_ = r.persistLocked()
		return nil, err
	}
	// Store the re-marshalled key rather than the client's bytes: it normalises
	// the encoding and guarantees what we persist is exactly what we parsed.
	canonical, err := x509.MarshalPKIXPublicKey(pub)
	if err != nil {
		_ = r.persistLocked()
		return nil, fmt.Errorf("re-encoding device key: %w", err)
	}

	id, err := newDeviceID()
	if err != nil {
		_ = r.persistLocked()
		return nil, err
	}

	device := &Device{
		ID:           id,
		Name:         truncate(name, maxDeviceNameLength),
		PublicKeyDER: canonical,
		Subject:      grant.Subject,
		Scopes:       append([]string(nil), grant.Scopes...),
		EnrolledAt:   now,
	}
	// A device enrolled before a blanket revocation instant would otherwise
	// come back to life; anchor its token validity at enrollment time.
	if r.state.RevokeAllBefore.After(now) {
		device.Revoked = true
		device.RevokedAt = r.state.RevokeAllBefore
	}

	r.state.Devices[id] = device
	if err := r.persistLocked(); err != nil {
		delete(r.state.Devices, id)
		return nil, err
	}
	return device, nil
}

// Get returns an active device. A revoked or unknown device is an error, so no
// caller can accidentally treat "revoked" as "fine".
func (r *Registry) Get(id string) (*Device, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	d, ok := r.state.Devices[id]
	if !ok {
		return nil, ErrUnknownDevice
	}
	if d.Revoked {
		return nil, ErrDeviceRevoked
	}
	cp := *d
	cp.Scopes = append([]string(nil), d.Scopes...)
	cp.PublicKeyDER = append([]byte(nil), d.PublicKeyDER...)
	return &cp, nil
}

// TouchLastSeen records device activity. Failure to persist is not fatal: it is
// telemetry, and refusing an authenticated read because a disk write failed
// would turn a full disk into an outage.
func (r *Registry) TouchLastSeen(id string, now time.Time) {
	r.mu.Lock()
	defer r.mu.Unlock()
	d, ok := r.state.Devices[id]
	if !ok {
		return
	}
	// Persist at most once a minute per device to keep a polling app from
	// rewriting the registry file on every request.
	if now.Sub(d.LastSeenAt) < time.Minute {
		return
	}
	d.LastSeenAt = now
	_ = r.persistLocked()
}

// List returns every device, newest enrollment first, for the ops/ingest plane.
func (r *Registry) List() []Device {
	r.mu.RLock()
	defer r.mu.RUnlock()

	out := make([]Device, 0, len(r.state.Devices))
	for _, d := range r.state.Devices {
		cp := *d
		cp.Scopes = append([]string(nil), d.Scopes...)
		// The public key is not secret, but there is no reason for the listing
		// to carry it; drop it to keep the response small and boring.
		cp.PublicKeyDER = nil
		out = append(out, cp)
	}
	for i := 1; i < len(out); i++ {
		for j := i; j > 0 && out[j].EnrolledAt.After(out[j-1].EnrolledAt); j-- {
			out[j], out[j-1] = out[j-1], out[j]
		}
	}
	return out
}

// PendingEnrollments reports how many unredeemed, unexpired codes exist.
func (r *Registry) PendingEnrollments(now time.Time) int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	n := 0
	for _, e := range r.state.Enrollments {
		if e.ExpiresAt.After(now) {
			n++
		}
	}
	return n
}

// Prune drops expired enrollment codes. Called on a timer from main.
func (r *Registry) Prune(now time.Time) {
	r.mu.Lock()
	defer r.mu.Unlock()
	before := len(r.state.Enrollments)
	r.pruneLocked(now)
	if len(r.state.Enrollments) != before {
		_ = r.persistLocked()
	}
}

func (r *Registry) pruneLocked(now time.Time) {
	for k, e := range r.state.Enrollments {
		if !e.ExpiresAt.After(now) {
			delete(r.state.Enrollments, k)
		}
	}
}

// persistLocked writes the registry atomically: a temp file in the same
// directory, fsync, then rename. A crash mid-write must not leave a truncated
// registry that locks every device out.
func (r *Registry) persistLocked() error {
	raw, err := json.MarshalIndent(r.state, "", "  ")
	if err != nil {
		return fmt.Errorf("encoding device registry: %w", err)
	}
	dir := filepath.Dir(r.path)
	tmp, err := os.CreateTemp(dir, ".devices-*.json")
	if err != nil {
		return fmt.Errorf("creating temp registry file: %w", err)
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }() // no-op once the rename succeeded

	if err := tmp.Chmod(0o600); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("setting registry permissions: %w", err)
	}
	if _, err := tmp.Write(raw); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("writing device registry: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("syncing device registry: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("closing device registry: %w", err)
	}
	if err := os.Rename(tmpName, r.path); err != nil {
		return fmt.Errorf("replacing device registry: %w", err)
	}
	return nil
}

// ParsePublicKeyBase64 decodes a base64 SPKI key as sent by a device.
func ParsePublicKeyBase64(encoded string) ([]byte, error) {
	if encoded == "" {
		return nil, errors.New("device public key is required")
	}
	if len(encoded) > 4096 {
		return nil, errors.New("device public key is too large")
	}
	der, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, errors.New("device public key must be base64-encoded SPKI DER")
	}
	if _, err := parseP256PublicKey(der); err != nil {
		return nil, err
	}
	return der, nil
}

// parseP256PublicKey enforces the one curve the relay accepts. Pinning the
// curve keeps a client from downgrading itself to something weaker, and P-256
// is what iOS Secure Enclave keys are.
func parseP256PublicKey(der []byte) (*ecdsa.PublicKey, error) {
	pub, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, errors.New("device public key is not a valid SPKI structure")
	}
	ec, ok := pub.(*ecdsa.PublicKey)
	if !ok {
		return nil, errors.New("device public key must be an ECDSA key")
	}
	if ec.Curve == nil || ec.Curve.Params().Name != "P-256" {
		return nil, errors.New("device public key must use the P-256 curve")
	}
	return ec, nil
}

func newDeviceID() (string, error) {
	buf := make([]byte, deviceIDBytes)
	if _, err := rand.Read(buf); err != nil {
		return "", fmt.Errorf("generating device id: %w", err)
	}
	return "dev_" + hex.EncodeToString(buf), nil
}

func truncate(v string, max int) string {
	if len(v) <= max {
		return v
	}
	return v[:max]
}
