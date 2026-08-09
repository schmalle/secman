package devices

import (
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/schmalle/secman/src/relay/internal/model"
)

// defaultPolicy is the deployment rule this relay was built for: an ADMIN
// account may only be bound through Apple or Google.
func defaultPolicy() Policy {
	return Policy{
		PrivilegedRoles: []string{"ADMIN"},
		StrongProviders: []string{"apple", "google"},
	}
}

func newKeyDER(t *testing.T) []byte {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generating key: %v", err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatalf("marshalling key: %v", err)
	}
	return der
}

func digestOf(code string) string {
	sum := sha256.Sum256([]byte(code))
	return hex.EncodeToString(sum[:])
}

func newRegistry(t *testing.T) (*Registry, string) {
	t.Helper()
	dir := t.TempDir()
	r, err := Open(dir, 10, defaultPolicy())
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	return r, dir
}

// control builds an authoritative control document with one principal.
func control(subject string, roles []string, identities []model.ExternalIdentity) *model.Control {
	return &model.Control{
		SchemaVersion:           model.ControlSchemaVersion,
		InstanceID:              "secman-prod",
		IssuedAt:                time.Now(),
		PrincipalsAuthoritative: true,
		Principals: []model.Principal{{
			Subject:    subject,
			Roles:      roles,
			Identities: identities,
		}},
	}
}

func grant(code, subject string, scopes []string, expires time.Time) model.EnrollmentGrant {
	return model.EnrollmentGrant{
		CodeSHA256: digestOf(code),
		Subject:    subject,
		Scopes:     scopes,
		ExpiresAt:  expires,
	}
}

func seedUser(t *testing.T, r *Registry, subject string, roles []string, identities []model.ExternalIdentity) {
	t.Helper()
	if _, err := r.ApplyControl(control(subject, roles, identities), time.Now()); err != nil {
		t.Fatalf("seeding principal: %v", err)
	}
}

// --- enrollment codes -------------------------------------------------------

func TestEnrollByCode(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	seedUser(t, r, "vulnuser", []string{"VULN"}, nil)

	c := control("vulnuser", []string{"VULN"}, nil)
	c.Enrollments = []model.EnrollmentGrant{grant("code-1", "vulnuser", []string{model.ScopeAll}, now.Add(time.Hour))}
	if _, err := r.ApplyControl(c, now); err != nil {
		t.Fatalf("ApplyControl: %v", err)
	}

	device, err := r.Enroll("code-1", newKeyDER(t), "Test iPhone", now)
	if err != nil {
		t.Fatalf("Enroll: %v", err)
	}
	if device.Subject != "vulnuser" {
		t.Errorf("subject = %q, want vulnuser", device.Subject)
	}
	if device.BoundVia != BoundByCode {
		t.Errorf("boundVia = %q, want code", device.BoundVia)
	}

	resolved, err := r.Resolve(device.ID)
	if err != nil {
		t.Fatalf("Resolve: %v", err)
	}
	// Roles come from the principal, never from the device record.
	if len(resolved.Principal.Roles) != 1 || resolved.Principal.Roles[0] != "VULN" {
		t.Errorf("roles = %v, want [VULN]", resolved.Principal.Roles)
	}
}

// This is the rule the deployment asked for: an admin cannot be bound by a
// typed code, only by a strong identity provider.
func TestAdminCannotEnrollByCode(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()

	c := control("boss", []string{"ADMIN"}, nil)
	c.Enrollments = []model.EnrollmentGrant{grant("code-1", "boss", []string{model.ScopeAll}, now.Add(time.Hour))}
	if _, err := r.ApplyControl(c, now); err != nil {
		t.Fatalf("ApplyControl: %v", err)
	}

	if _, err := r.Enroll("code-1", newKeyDER(t), "boss phone", now); !errors.Is(err, ErrProviderNotAllowed) {
		t.Fatalf("an ADMIN must not be bindable by code, got %v", err)
	}
}

func TestEnrollmentCodeIsSingleUse(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	c := control("vulnuser", []string{"VULN"}, nil)
	c.Enrollments = []model.EnrollmentGrant{grant("code-1", "vulnuser", []string{model.ScopeAll}, now.Add(time.Hour))}
	_, _ = r.ApplyControl(c, now)

	if _, err := r.Enroll("code-1", newKeyDER(t), "first", now); err != nil {
		t.Fatalf("first enrollment should succeed: %v", err)
	}
	if _, err := r.Enroll("code-1", newKeyDER(t), "second", now); !errors.Is(err, ErrEnrollmentRejected) {
		t.Fatalf("a reused code must be rejected, got %v", err)
	}
}

// A failed enrollment must also burn the code: otherwise an attacker who
// guessed it once could retry with a key they control.
func TestFailedEnrollmentStillBurnsTheCode(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	c := control("vulnuser", []string{"VULN"}, nil)
	c.Enrollments = []model.EnrollmentGrant{grant("code-1", "vulnuser", []string{model.ScopeAll}, now.Add(time.Hour))}
	_, _ = r.ApplyControl(c, now)

	if _, err := r.Enroll("code-1", []byte("not a key"), "bad", now); err == nil {
		t.Fatal("an unparseable key should fail the enrollment")
	}
	if _, err := r.Enroll("code-1", newKeyDER(t), "retry", now); !errors.Is(err, ErrEnrollmentRejected) {
		t.Fatalf("the code must not be reusable after a failed attempt, got %v", err)
	}
}

func TestExpiredAndUnknownCodesRejected(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	c := control("vulnuser", []string{"VULN"}, nil)
	c.Enrollments = []model.EnrollmentGrant{grant("code-1", "vulnuser", []string{model.ScopeAll}, now.Add(time.Minute))}
	_, _ = r.ApplyControl(c, now)

	if _, err := r.Enroll("code-1", newKeyDER(t), "late", now.Add(2*time.Minute)); !errors.Is(err, ErrEnrollmentRejected) {
		t.Fatalf("an expired code must be rejected, got %v", err)
	}
	if _, err := r.Enroll("never-issued", newKeyDER(t), "x", now); !errors.Is(err, ErrEnrollmentRejected) {
		t.Fatalf("an unknown code must be rejected, got %v", err)
	}
}

// A grant naming a principal the relay does not know grants nothing.
func TestEnrollmentForUnknownPrincipalRejected(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	seedUser(t, r, "someoneelse", []string{"USER"}, nil)

	c := control("someoneelse", []string{"USER"}, nil)
	c.Enrollments = []model.EnrollmentGrant{grant("code-1", "ghost", []string{model.ScopeAll}, now.Add(time.Hour))}
	_, _ = r.ApplyControl(c, now)

	if _, err := r.Enroll("code-1", newKeyDER(t), "x", now); !errors.Is(err, ErrUnknownPrincipal) {
		t.Fatalf("a grant for an unknown principal must be refused, got %v", err)
	}
}

// --- identity binding -------------------------------------------------------

func TestBindIdentity(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	seedUser(t, r, "boss", []string{"ADMIN"}, []model.ExternalIdentity{
		{Provider: model.ProviderApple, Subject: "001234.abc"},
	})

	device, err := r.BindIdentity(model.ProviderApple, "001234.abc", newKeyDER(t), "boss iPad", nil, now)
	if err != nil {
		t.Fatalf("BindIdentity: %v", err)
	}
	if device.Subject != "boss" || device.Provider != model.ProviderApple {
		t.Errorf("device = %+v, want subject=boss provider=apple", device)
	}
	if device.BoundVia != BoundByIdentity {
		t.Errorf("boundVia = %q, want identity", device.BoundVia)
	}
}

// Signing in with Apple proves who you are. It does not say you may see
// anything: only a principal record that secman pushed does that.
func TestVerifiedIdentityWithoutAPrincipalGrantsNothing(t *testing.T) {
	r, _ := newRegistry(t)
	seedUser(t, r, "boss", []string{"ADMIN"}, []model.ExternalIdentity{
		{Provider: model.ProviderApple, Subject: "001234.abc"},
	})

	if _, err := r.BindIdentity(model.ProviderApple, "999999.stranger", newKeyDER(t), "x", nil, time.Now()); !errors.Is(err, ErrUnknownPrincipal) {
		t.Fatalf("an unmapped identity must be refused, got %v", err)
	}
}

// A GitHub identity is fine for a non-privileged user and refused for an admin.
func TestPrivilegedRoleRequiresStrongProvider(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()

	c := &model.Control{
		SchemaVersion:           model.ControlSchemaVersion,
		InstanceID:              "secman-prod",
		IssuedAt:                now,
		PrincipalsAuthoritative: true,
		Principals: []model.Principal{
			{
				Subject: "boss", Roles: []string{"ADMIN"},
				Identities: []model.ExternalIdentity{{Provider: model.ProviderGitHub, Subject: "111"}},
			},
			{
				Subject: "dev", Roles: []string{"VULN"},
				Identities: []model.ExternalIdentity{{Provider: model.ProviderGitHub, Subject: "222"}},
			},
		},
	}
	if _, err := r.ApplyControl(c, now); err != nil {
		t.Fatalf("ApplyControl: %v", err)
	}

	if _, err := r.BindIdentity(model.ProviderGitHub, "111", newKeyDER(t), "boss", nil, now); !errors.Is(err, ErrProviderNotAllowed) {
		t.Fatalf("an ADMIN must not be bindable via GitHub, got %v", err)
	}
	if _, err := r.BindIdentity(model.ProviderGitHub, "222", newKeyDER(t), "dev", nil, now); err != nil {
		t.Fatalf("a non-privileged user may use GitHub: %v", err)
	}
}

// The rule is re-checked on every resolve, so promoting a user immediately
// invalidates a device that was bound by a method now too weak for them.
func TestPromotionInvalidatesWeaklyBoundDevice(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	seedUser(t, r, "dev", []string{"VULN"}, []model.ExternalIdentity{
		{Provider: model.ProviderGitHub, Subject: "222"},
	})

	device, err := r.BindIdentity(model.ProviderGitHub, "222", newKeyDER(t), "dev phone", nil, now)
	if err != nil {
		t.Fatalf("BindIdentity: %v", err)
	}
	if _, err := r.Resolve(device.ID); err != nil {
		t.Fatalf("the device should work before the promotion: %v", err)
	}

	// secman promotes the user to ADMIN.
	seedUser(t, r, "dev", []string{"VULN", "ADMIN"}, []model.ExternalIdentity{
		{Provider: model.ProviderGitHub, Subject: "222"},
	})

	if _, err := r.Resolve(device.ID); !errors.Is(err, ErrProviderNotAllowed) {
		t.Fatalf("promotion must invalidate a GitHub-bound device, got %v", err)
	}
}

// --- principals -------------------------------------------------------------

// Roles must be able to shrink, or a demotion in secman would never reach the
// phone.
func TestAuthoritativePushShrinksRoles(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	seedUser(t, r, "dev", []string{"VULN", "REPORT"}, []model.ExternalIdentity{
		{Provider: model.ProviderApple, Subject: "aaa"},
	})
	device, err := r.BindIdentity(model.ProviderApple, "aaa", newKeyDER(t), "dev", nil, now)
	if err != nil {
		t.Fatalf("BindIdentity: %v", err)
	}

	seedUser(t, r, "dev", []string{"VULN"}, []model.ExternalIdentity{
		{Provider: model.ProviderApple, Subject: "aaa"},
	})

	resolved, err := r.Resolve(device.ID)
	if err != nil {
		t.Fatalf("Resolve: %v", err)
	}
	if len(resolved.Principal.Roles) != 1 || resolved.Principal.Roles[0] != "VULN" {
		t.Errorf("roles = %v, want the reduced set [VULN]", resolved.Principal.Roles)
	}
}

// A principal secman no longer lists is disabled, not silently retained.
func TestAuthoritativePushDisablesOmittedPrincipal(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	seedUser(t, r, "leaver", []string{"VULN"}, []model.ExternalIdentity{
		{Provider: model.ProviderApple, Subject: "bbb"},
	})
	device, err := r.BindIdentity(model.ProviderApple, "bbb", newKeyDER(t), "leaver", nil, now)
	if err != nil {
		t.Fatalf("BindIdentity: %v", err)
	}

	// Next authoritative push lists somebody else entirely.
	seedUser(t, r, "stayer", []string{"VULN"}, nil)

	if _, err := r.Resolve(device.ID); !errors.Is(err, ErrPrincipalDisabled) {
		t.Fatalf("a device for an omitted principal must stop working, got %v", err)
	}
	// The identity must also stop resolving, so they cannot simply re-bind.
	if _, err := r.PrincipalForIdentity(model.ProviderApple, "bbb"); !errors.Is(err, ErrUnknownPrincipal) {
		t.Fatalf("a disabled principal's identity must not resolve, got %v", err)
	}
}

// A non-authoritative push merges, so an incremental update cannot accidentally
// disable everybody.
func TestNonAuthoritativePushMerges(t *testing.T) {
	r, _ := newRegistry(t)
	seedUser(t, r, "first", []string{"VULN"}, nil)

	merge := &model.Control{
		SchemaVersion: model.ControlSchemaVersion,
		InstanceID:    "secman-prod",
		IssuedAt:      time.Now(),
		Principals:    []model.Principal{{Subject: "second", Roles: []string{"REPORT"}}},
	}
	if _, err := r.ApplyControl(merge, time.Now()); err != nil {
		t.Fatalf("ApplyControl: %v", err)
	}
	if _, err := r.Principal("first"); err != nil {
		t.Errorf("the earlier principal should survive a merge push: %v", err)
	}
	if _, err := r.Principal("second"); err != nil {
		t.Errorf("the new principal should be present: %v", err)
	}
}

// --- revocation -------------------------------------------------------------

func TestRevocationIsImmediateAndPermanent(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	seedUser(t, r, "dev", []string{"VULN"}, []model.ExternalIdentity{
		{Provider: model.ProviderApple, Subject: "ccc"},
	})
	device, err := r.BindIdentity(model.ProviderApple, "ccc", newKeyDER(t), "phone", nil, now)
	if err != nil {
		t.Fatalf("BindIdentity: %v", err)
	}

	revoke := &model.Control{
		SchemaVersion: model.ControlSchemaVersion,
		InstanceID:    "secman-prod",
		IssuedAt:      now,
		Revocations:   []model.Revocation{{DeviceID: device.ID, RevokedAt: now}},
	}
	applied, err := r.ApplyControl(revoke, now)
	if err != nil || applied.DevicesRevoked != 1 {
		t.Fatalf("ApplyControl: revoked=%d err=%v", applied.DevicesRevoked, err)
	}
	if _, err := r.Resolve(device.ID); !errors.Is(err, ErrDeviceRevoked) {
		t.Fatalf("a revoked device must not resolve, got %v", err)
	}

	// A later push that simply omits the revocation must not resurrect it.
	later := &model.Control{
		SchemaVersion: model.ControlSchemaVersion,
		InstanceID:    "secman-prod",
		IssuedAt:      now.Add(time.Minute),
	}
	if _, err := r.ApplyControl(later, now.Add(time.Minute)); err != nil {
		t.Fatalf("ApplyControl: %v", err)
	}
	if _, err := r.Resolve(device.ID); !errors.Is(err, ErrDeviceRevoked) {
		t.Fatal("a revocation must never be undone by a later push that omits it")
	}
}

// The panic button must also invalidate codes that have not been redeemed yet.
func TestRevokeAllBurnsPendingCodes(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()

	c := control("dev", []string{"VULN"}, []model.ExternalIdentity{{Provider: model.ProviderApple, Subject: "ddd"}})
	c.Enrollments = []model.EnrollmentGrant{grant("code-2", "dev", []string{model.ScopeAll}, now.Add(time.Hour))}
	if _, err := r.ApplyControl(c, now); err != nil {
		t.Fatalf("ApplyControl: %v", err)
	}
	enrolled, err := r.BindIdentity(model.ProviderApple, "ddd", newKeyDER(t), "phone", nil, now)
	if err != nil {
		t.Fatalf("BindIdentity: %v", err)
	}

	revokeAll := &model.Control{
		SchemaVersion: model.ControlSchemaVersion,
		InstanceID:    "secman-prod",
		IssuedAt:      now,
		Revocations:   []model.Revocation{{RevokeAll: true, RevokedAt: now, Reason: "lost laptop"}},
	}
	if _, err := r.ApplyControl(revokeAll, now); err != nil {
		t.Fatalf("ApplyControl: %v", err)
	}
	if _, err := r.Resolve(enrolled.ID); !errors.Is(err, ErrDeviceRevoked) {
		t.Error("revokeAll should revoke every bound device")
	}
	if _, err := r.Enroll("code-2", newKeyDER(t), "new", now); !errors.Is(err, ErrEnrollmentRejected) {
		t.Error("revokeAll should also burn unredeemed enrollment codes")
	}
}

// --- keys, limits, persistence ----------------------------------------------

// The curve is pinned: a device cannot present a weaker or different key type.
func TestOnlyP256KeysAccepted(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	seedUser(t, r, "dev", []string{"VULN"}, []model.ExternalIdentity{
		{Provider: model.ProviderApple, Subject: "eee"},
	})

	p384, _ := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	p384DER, _ := x509.MarshalPKIXPublicKey(&p384.PublicKey)
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	edDER, _ := x509.MarshalPKIXPublicKey(pub)

	for name, der := range map[string][]byte{"P-384": p384DER, "ed25519": edDER} {
		if _, err := r.BindIdentity(model.ProviderApple, "eee", der, name, nil, now); err == nil {
			t.Errorf("a %s key should have been refused", name)
		}
	}
}

func TestRegistryCapIsEnforced(t *testing.T) {
	dir := t.TempDir()
	r, err := Open(dir, 1, defaultPolicy())
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	now := time.Now()
	seedUser(t, r, "dev", []string{"VULN"}, []model.ExternalIdentity{
		{Provider: model.ProviderApple, Subject: "fff"},
	})

	if _, err := r.BindIdentity(model.ProviderApple, "fff", newKeyDER(t), "one", nil, now); err != nil {
		t.Fatalf("first binding: %v", err)
	}
	if _, err := r.BindIdentity(model.ProviderApple, "fff", newKeyDER(t), "two", nil, now); !errors.Is(err, ErrRegistryFull) {
		t.Fatalf("the cap should fail closed, got %v", err)
	}
}

// A phone cannot re-bind itself unattended, so the registry must survive a
// restart — including the principals, or every device would be locked out
// until secman's next push.
func TestRegistryPersistsAcrossRestart(t *testing.T) {
	dir := t.TempDir()
	r, err := Open(dir, 10, defaultPolicy())
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	now := time.Now()
	seedUser(t, r, "dev", []string{"VULN"}, []model.ExternalIdentity{
		{Provider: model.ProviderApple, Subject: "ggg"},
	})
	device, err := r.BindIdentity(model.ProviderApple, "ggg", newKeyDER(t), "phone", []string{"status:kpis"}, now)
	if err != nil {
		t.Fatalf("BindIdentity: %v", err)
	}

	reopened, err := Open(dir, 10, defaultPolicy())
	if err != nil {
		t.Fatalf("reopening: %v", err)
	}
	resolved, err := reopened.Resolve(device.ID)
	if err != nil {
		t.Fatalf("the device should survive a restart: %v", err)
	}
	if len(resolved.Device.Scopes) != 1 || resolved.Device.Scopes[0] != "status:kpis" {
		t.Errorf("scopes did not survive: %v", resolved.Device.Scopes)
	}
	if len(resolved.Principal.Roles) != 1 || resolved.Principal.Roles[0] != "VULN" {
		t.Errorf("roles did not survive: %v", resolved.Principal.Roles)
	}
	if _, err := resolved.Device.PublicKey(); err != nil {
		t.Errorf("the public key did not survive: %v", err)
	}
	// The identity index is rebuilt, never persisted, so it cannot drift.
	if _, err := reopened.PrincipalForIdentity(model.ProviderApple, "ggg"); err != nil {
		t.Errorf("the identity index should be rebuilt on open: %v", err)
	}
}

// The registry file names users, roles and devices; it must not be world-readable.
func TestRegistryFilePermissions(t *testing.T) {
	r, dir := newRegistry(t)
	seedUser(t, r, "dev", []string{"VULN"}, nil)

	info, err := os.Stat(filepath.Join(dir, "devices.json"))
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("registry file mode = %o, want 600", perm)
	}
	dirInfo, err := os.Stat(dir)
	if err != nil {
		t.Fatalf("stat dir: %v", err)
	}
	if perm := dirInfo.Mode().Perm(); perm != 0o700 {
		t.Errorf("state directory mode = %o, want 700", perm)
	}
}

func TestPruneDropsExpiredCodes(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	c := control("dev", []string{"VULN"}, nil)
	c.Enrollments = []model.EnrollmentGrant{grant("code-1", "dev", []string{model.ScopeAll}, now.Add(time.Minute))}
	_, _ = r.ApplyControl(c, now)

	if r.PendingEnrollments(now) != 1 {
		t.Fatalf("expected 1 pending code, got %d", r.PendingEnrollments(now))
	}
	r.Prune(now.Add(2 * time.Minute))
	if r.PendingEnrollments(now.Add(2*time.Minute)) != 0 {
		t.Error("expired codes should be pruned")
	}
}

// The listing feeds secman's admin view; it must carry current roles and no key
// material.
func TestListJoinsRolesAndOmitsKeyMaterial(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	seedUser(t, r, "dev", []string{"VULN"}, []model.ExternalIdentity{
		{Provider: model.ProviderApple, Subject: "hhh"},
	})
	if _, err := r.BindIdentity(model.ProviderApple, "hhh", newKeyDER(t), "phone", nil, now); err != nil {
		t.Fatalf("BindIdentity: %v", err)
	}

	list := r.List()
	if len(list) != 1 {
		t.Fatalf("expected 1 device, got %d", len(list))
	}
	if list[0].PublicKeyDER != nil {
		t.Error("the listing should not carry the device key")
	}
	if len(list[0].Roles) != 1 || list[0].Roles[0] != "VULN" {
		t.Errorf("the listing should join current roles, got %v", list[0].Roles)
	}
	if !list[0].PrincipalActive {
		t.Error("the principal should be reported active")
	}
}

func TestParsePublicKeyBase64(t *testing.T) {
	der := newKeyDER(t)
	if _, err := ParsePublicKeyBase64(base64.StdEncoding.EncodeToString(der)); err != nil {
		t.Fatalf("a valid P-256 SPKI key should parse: %v", err)
	}
	for _, bad := range []string{"", "not base64 !!", base64.StdEncoding.EncodeToString([]byte("junk"))} {
		if _, err := ParsePublicKeyBase64(bad); err == nil {
			t.Errorf("%q should be rejected", bad)
		}
	}
}

func TestPolicyHelpers(t *testing.T) {
	p := defaultPolicy()
	if !p.RequiresStrongProvider([]string{"USER", "ADMIN"}) {
		t.Error("ADMIN should require a strong provider")
	}
	if p.RequiresStrongProvider([]string{"VULN", "REPORT"}) {
		t.Error("a non-privileged role set should not require a strong provider")
	}
	if !p.IsStrongProvider("apple") || !p.IsStrongProvider("google") {
		t.Error("apple and google should be strong")
	}
	if p.IsStrongProvider("github") || p.IsStrongProvider("") {
		t.Error("github and the code path should not be strong")
	}
}
