// Package devices is the relay's authorization state: which secman users
// (principals) exist and what roles they hold, which mobile devices are bound
// to them, and which have been revoked.
//
// This is the one piece of relay state that must survive a restart — a phone
// cannot re-enrol itself, and a relay that forgot every principal would lock
// everyone out until secman's next control push. It is persisted as a 0600 JSON
// file inside a 0700 directory.
//
// What it deliberately does not contain: any device *secret*. A device is
// identified by an ECDSA P-256 public key whose private half never leaves the
// phone's Secure Enclave, so a copy of this file does not let anyone
// authenticate as a device.
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
	ErrUnknownPrincipal   = errors.New("no secman principal matches")
	ErrPrincipalDisabled  = errors.New("the secman principal is disabled")
	ErrProviderNotAllowed = errors.New("this login method is not permitted for the account")
)

const (
	maxDeviceNameLength = 64
	deviceIDBytes       = 16
)

// BindingMethod records how a device came to be trusted. It is retained for the
// life of the device because the privileged-provider rule is re-checked on
// every token issue, not only at binding: a user promoted to ADMIN tomorrow
// must lose a device that was bound by a weak method today.
type BindingMethod string

const (
	// BoundByCode is an admin-issued enrollment code.
	BoundByCode BindingMethod = "code"
	// BoundByIdentity means an identity provider proved who the user is; the
	// provider name is kept alongside in Device.Provider.
	BoundByIdentity BindingMethod = "identity"
)

// Device is an enrolled mobile client.
//
// Note what is absent: roles. A device never carries a copy of its user's
// roles, because a cached copy is a stale copy. Roles are resolved from the
// principal on every request, so a demotion in secman takes effect on the next
// control push and the next request, with nothing to invalidate.
type Device struct {
	ID string `json:"id"`
	// Name is operator-supplied text ("Markus iPhone"). Untrusted; sanitized
	// before it is logged and length-capped on the way in.
	Name string `json:"name"`
	// PublicKeyDER is the SPKI encoding of the device's ECDSA P-256 public key.
	PublicKeyDER []byte `json:"publicKeyDer"`
	// Subject is the secman principal this device acts for.
	Subject string `json:"subject"`
	// Scopes narrow what this particular device may read. Never widen: the
	// principal's roles are the ceiling.
	Scopes []string `json:"scopes"`

	BoundVia BindingMethod `json:"boundVia"`
	// Provider is the identity provider used when BoundVia is BoundByIdentity.
	Provider string `json:"provider,omitempty"`
	// IdentitySubject is the provider's stable user id, kept so a device can be
	// matched back to the identity that created it.
	IdentitySubject string `json:"identitySubject,omitempty"`

	EnrolledAt time.Time `json:"enrolledAt"`
	LastSeenAt time.Time `json:"lastSeenAt,omitempty"`
	// IdentityVerifiedAt is the last time an identity provider re-proved the
	// user. Used to force periodic re-attestation.
	IdentityVerifiedAt time.Time `json:"identityVerifiedAt,omitempty"`

	Revoked   bool      `json:"revoked,omitempty"`
	RevokedAt time.Time `json:"revokedAt,omitempty"`
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
	Version     int                         `json:"version"`
	Devices     map[string]*Device          `json:"devices"`
	Enrollments map[string]*enrollment      `json:"enrollments"`
	Principals  map[string]*model.Principal `json:"principals"`
	// RevokeAllBefore implements Control.RevokeAll without having to enumerate
	// devices that have not enrolled yet.
	RevokeAllBefore time.Time `json:"revokeAllBefore,omitempty"`
}

// Policy is the deployment's rule about which login methods are strong enough
// for which roles.
type Policy struct {
	// PrivilegedRoles are the roles that demand a strong provider.
	PrivilegedRoles []string
	// StrongProviders are the providers accepted for a privileged principal.
	StrongProviders []string
}

// RequiresStrongProvider reports whether any of `roles` is privileged.
func (p Policy) RequiresStrongProvider(roles []string) bool {
	for _, held := range roles {
		for _, privileged := range p.PrivilegedRoles {
			if held == privileged {
				return true
			}
		}
	}
	return false
}

// IsStrongProvider reports whether `provider` is on the strong list.
func (p Policy) IsStrongProvider(provider string) bool {
	for _, s := range p.StrongProviders {
		if s == provider {
			return true
		}
	}
	return false
}

// Registry is safe for concurrent use.
type Registry struct {
	mu         sync.RWMutex
	path       string
	maxDevices int
	policy     Policy
	state      persistedState
	// identityIndex maps "provider|subject" to a principal subject. Rebuilt
	// from Principals whenever they change, never persisted, so it cannot drift.
	identityIndex map[string]string
}

// Open loads the registry from stateDir, creating an empty one if absent.
func Open(stateDir string, maxDevices int, policy Policy) (*Registry, error) {
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
		policy:     policy,
		state: persistedState{
			Version:     2,
			Devices:     map[string]*Device{},
			Enrollments: map[string]*enrollment{},
			Principals:  map[string]*model.Principal{},
		},
	}

	raw, err := os.ReadFile(r.path)
	switch {
	case errors.Is(err, os.ErrNotExist):
		r.rebuildIndexLocked()
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
	if loaded.Principals == nil {
		loaded.Principals = map[string]*model.Principal{}
	}
	loaded.Version = 2
	r.state = loaded
	r.rebuildIndexLocked()
	return r, nil
}

// ApplyControl folds a control document into the registry.
func (r *Registry) ApplyControl(c *model.Control, now time.Time) (Applied, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	var applied Applied

	if len(c.Principals) > 0 {
		incoming := make(map[string]*model.Principal, len(c.Principals))
		for i := range c.Principals {
			p := c.Principals[i]
			cp := p
			cp.Roles = append([]string(nil), p.Roles...)
			cp.Identities = append([]model.ExternalIdentity(nil), p.Identities...)
			incoming[p.Subject] = &cp
		}

		if c.PrincipalsAuthoritative {
			// Replace, do not merge: roles must be able to shrink, and a
			// principal secman no longer knows must stop working here too.
			for subject, existing := range r.state.Principals {
				if _, still := incoming[subject]; !still {
					existing.Disabled = true
					incoming[subject] = existing
					applied.PrincipalsDisabled++
				}
			}
			r.state.Principals = incoming
		} else {
			for subject, p := range incoming {
				r.state.Principals[subject] = p
			}
		}
		applied.PrincipalsUpdated = len(c.Principals)
		r.rebuildIndexLocked()
	}

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
		applied.EnrollmentsAdded++
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
					applied.DevicesRevoked++
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
			applied.DevicesRevoked++
		}
		if at.After(d.TokensValidAfter) {
			d.TokensValidAfter = at
		}
	}

	r.pruneLocked(now)
	if err := r.persistLocked(); err != nil {
		return applied, err
	}
	return applied, nil
}

// Applied summarises what a control document changed.
type Applied struct {
	PrincipalsUpdated  int
	PrincipalsDisabled int
	EnrollmentsAdded   int
	DevicesRevoked     int
}

func (r *Registry) rebuildIndexLocked() {
	index := make(map[string]string, len(r.state.Principals)*2)
	for subject, p := range r.state.Principals {
		if p.Disabled {
			continue
		}
		for _, id := range p.Identities {
			// Last writer wins on a collision. secman validates uniqueness on
			// its side; here the important property is that a disabled
			// principal never contributes an entry.
			index[model.IdentityKey(id.Provider, id.Subject)] = subject
		}
	}
	r.identityIndex = index
}

// Principal returns an enabled principal.
func (r *Registry) Principal(subject string) (*model.Principal, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.principalLocked(subject)
}

func (r *Registry) principalLocked(subject string) (*model.Principal, error) {
	p, ok := r.state.Principals[subject]
	if !ok {
		return nil, ErrUnknownPrincipal
	}
	if p.Disabled {
		return nil, ErrPrincipalDisabled
	}
	cp := *p
	cp.Roles = append([]string(nil), p.Roles...)
	cp.Identities = append([]model.ExternalIdentity(nil), p.Identities...)
	return &cp, nil
}

// PrincipalForIdentity resolves a verified external identity to a principal.
//
// This is the step that keeps "signed in with Apple" from meaning "authorized".
// The identity provider proves who someone is; only a principal record that
// secman pushed says whether that person may see anything at all.
func (r *Registry) PrincipalForIdentity(provider, providerSubject string) (*model.Principal, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	subject, ok := r.identityIndex[model.IdentityKey(provider, providerSubject)]
	if !ok {
		return nil, ErrUnknownPrincipal
	}
	return r.principalLocked(subject)
}

// CheckProviderPolicy enforces the privileged-provider rule.
//
// `provider` is empty for a code-bound device, which never satisfies the rule:
// an admin account must be proved by a strong identity provider, and an
// enrollment code is a bearer secret typed off a screen.
func (r *Registry) CheckProviderPolicy(roles []string, provider string) error {
	if !r.policy.RequiresStrongProvider(roles) {
		return nil
	}
	if provider == "" || !r.policy.IsStrongProvider(provider) {
		return ErrProviderNotAllowed
	}
	return nil
}

// Policy exposes the configured rule, for the ops plane and for error text.
func (r *Registry) Policy() Policy { return r.policy }

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

	principal, err := r.principalLocked(grant.Subject)
	if err != nil {
		_ = r.persistLocked()
		return nil, err
	}
	// A code cannot bind an admin account: the privileged-provider rule applies
	// at binding as well as at every later token issue.
	if err := r.CheckProviderPolicy(principal.Roles, ""); err != nil {
		_ = r.persistLocked()
		return nil, err
	}

	device, err := r.registerLocked(principal.Subject, grant.Scopes, publicKeyDER, name, BoundByCode, "", "", now)
	if err != nil {
		_ = r.persistLocked()
		return nil, err
	}
	if err := r.persistLocked(); err != nil {
		delete(r.state.Devices, device.ID)
		return nil, err
	}
	return device, nil
}

// BindIdentity registers a device for a principal proved by an identity
// provider. The caller must already have verified the provider's assertion.
func (r *Registry) BindIdentity(provider, providerSubject string, publicKeyDER []byte, name string, scopes []string, now time.Time) (*Device, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	subject, ok := r.identityIndex[model.IdentityKey(provider, providerSubject)]
	if !ok {
		return nil, ErrUnknownPrincipal
	}
	principal, err := r.principalLocked(subject)
	if err != nil {
		return nil, err
	}
	if err := r.CheckProviderPolicy(principal.Roles, provider); err != nil {
		return nil, err
	}
	if len(scopes) == 0 {
		scopes = []string{model.ScopeAll}
	}

	device, err := r.registerLocked(principal.Subject, scopes, publicKeyDER, name, BoundByIdentity, provider, providerSubject, now)
	if err != nil {
		return nil, err
	}
	device.IdentityVerifiedAt = now
	if err := r.persistLocked(); err != nil {
		delete(r.state.Devices, device.ID)
		return nil, err
	}
	return device, nil
}

func (r *Registry) registerLocked(subject string, scopes []string, publicKeyDER []byte, name string,
	boundVia BindingMethod, provider, identitySubject string, now time.Time) (*Device, error) {

	if len(r.state.Devices) >= r.maxDevices {
		return nil, ErrRegistryFull
	}
	pub, err := parseP256PublicKey(publicKeyDER)
	if err != nil {
		return nil, err
	}
	// Store the re-marshalled key rather than the client's bytes: it normalises
	// the encoding and guarantees what we persist is exactly what we parsed.
	canonical, err := x509.MarshalPKIXPublicKey(pub)
	if err != nil {
		return nil, fmt.Errorf("re-encoding device key: %w", err)
	}
	id, err := newDeviceID()
	if err != nil {
		return nil, err
	}

	device := &Device{
		ID:              id,
		Name:            truncate(name, maxDeviceNameLength),
		PublicKeyDER:    canonical,
		Subject:         subject,
		Scopes:          append([]string(nil), scopes...),
		BoundVia:        boundVia,
		Provider:        provider,
		IdentitySubject: identitySubject,
		EnrolledAt:      now,
	}
	// A device created before a blanket revocation instant would otherwise come
	// back to life; anchor it at the revocation.
	if r.state.RevokeAllBefore.After(now) {
		device.Revoked = true
		device.RevokedAt = r.state.RevokeAllBefore
	}
	r.state.Devices[id] = device
	return device, nil
}

// Resolved is a device together with the live authorization state of the
// principal it acts for. Handlers work with this rather than with a Device, so
// there is no path that reads a device without also resolving current roles.
type Resolved struct {
	Device    *Device
	Principal *model.Principal
}

// Resolve returns an active device with its principal's current roles, and
// re-checks the privileged-provider rule.
//
// Every gate is applied here on purpose. Doing it once, at the point every
// authenticated request must pass through, is what stops a later handler from
// forgetting one.
func (r *Registry) Resolve(id string) (*Resolved, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	d, ok := r.state.Devices[id]
	if !ok {
		return nil, ErrUnknownDevice
	}
	if d.Revoked {
		return nil, ErrDeviceRevoked
	}
	principal, err := r.principalLocked(d.Subject)
	if err != nil {
		return nil, err
	}
	// Re-checked on every request, not only at binding: a user promoted to
	// ADMIN today must immediately lose a device that was bound by a weak
	// method yesterday.
	if err := r.CheckProviderPolicy(principal.Roles, d.Provider); err != nil {
		return nil, err
	}

	cp := *d
	cp.Scopes = append([]string(nil), d.Scopes...)
	cp.PublicKeyDER = append([]byte(nil), d.PublicKeyDER...)
	return &Resolved{Device: &cp, Principal: principal}, nil
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

// DeviceSummary is the device-listing shape for the ingest/ops plane. It
// deliberately omits key material and joins in the principal's current roles,
// which is what an admin actually needs to see.
type DeviceSummary struct {
	Device
	Roles           []string `json:"roles"`
	PrincipalActive bool     `json:"principalActive"`
}

// List returns every device, newest enrollment first.
func (r *Registry) List() []DeviceSummary {
	r.mu.RLock()
	defer r.mu.RUnlock()

	out := make([]DeviceSummary, 0, len(r.state.Devices))
	for _, d := range r.state.Devices {
		cp := *d
		cp.Scopes = append([]string(nil), d.Scopes...)
		// The public key is not secret, but the listing has no use for it.
		cp.PublicKeyDER = nil

		summary := DeviceSummary{Device: cp}
		if p, err := r.principalLocked(d.Subject); err == nil {
			summary.Roles = p.Roles
			summary.PrincipalActive = true
		}
		out = append(out, summary)
	}
	for i := 1; i < len(out); i++ {
		for j := i; j > 0 && out[j].EnrolledAt.After(out[j-1].EnrolledAt); j-- {
			out[j], out[j-1] = out[j-1], out[j]
		}
	}
	return out
}

// Counts reports registry sizes for the ops plane.
func (r *Registry) Counts(now time.Time) (devices, principals, pendingEnrollments int) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	for _, e := range r.state.Enrollments {
		if e.ExpiresAt.After(now) {
			pendingEnrollments++
		}
	}
	return len(r.state.Devices), len(r.state.Principals), pendingEnrollments
}

// PendingEnrollments reports how many unredeemed, unexpired codes exist.
func (r *Registry) PendingEnrollments(now time.Time) int {
	_, _, pending := r.Counts(now)
	return pending
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

// ParsePublicKeyDER parses and curve-checks a device key. Exported so callers
// that already hold the DER bytes do not reimplement the curve pin.
func ParsePublicKeyDER(der []byte) (*ecdsa.PublicKey, error) { return parseP256PublicKey(der) }
