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

func codeAndDigest(code string) (string, string) {
	sum := sha256.Sum256([]byte(code))
	return code, hex.EncodeToString(sum[:])
}

func newRegistry(t *testing.T) (*Registry, string) {
	t.Helper()
	dir := t.TempDir()
	r, err := Open(dir, 10)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	return r, dir
}

func grantFor(code string, scopes []string, expires time.Time) *model.Control {
	_, digest := codeAndDigest(code)
	return &model.Control{
		SchemaVersion: model.ControlSchemaVersion,
		InstanceID:    "secman-prod",
		IssuedAt:      time.Now(),
		Enrollments: []model.EnrollmentGrant{{
			CodeSHA256: digest,
			Subject:    "admin@example.com",
			Scopes:     scopes,
			ExpiresAt:  expires,
		}},
	}
}

func TestEnrollHappyPath(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()

	if _, _, err := r.ApplyControl(grantFor("code-1", []string{model.ScopeAll}, now.Add(time.Hour)), now); err != nil {
		t.Fatalf("ApplyControl: %v", err)
	}
	device, err := r.Enroll("code-1", newKeyDER(t), "Test iPhone", now)
	if err != nil {
		t.Fatalf("Enroll: %v", err)
	}
	if device.Subject != "admin@example.com" {
		t.Errorf("subject = %q, want admin@example.com", device.Subject)
	}
	if len(device.Scopes) != 1 || device.Scopes[0] != model.ScopeAll {
		t.Errorf("scopes = %v, want [%s]", device.Scopes, model.ScopeAll)
	}
	if _, err := r.Get(device.ID); err != nil {
		t.Errorf("the enrolled device should be retrievable: %v", err)
	}
}

// A code that has been redeemed once must be worthless afterwards.
func TestEnrollmentCodeIsSingleUse(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	_, _, _ = r.ApplyControl(grantFor("code-1", []string{model.ScopeAll}, now.Add(time.Hour)), now)

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
	_, _, _ = r.ApplyControl(grantFor("code-1", []string{model.ScopeAll}, now.Add(time.Hour)), now)

	if _, err := r.Enroll("code-1", []byte("not a key"), "bad", now); err == nil {
		t.Fatal("an unparseable key should fail the enrollment")
	}
	if _, err := r.Enroll("code-1", newKeyDER(t), "retry", now); !errors.Is(err, ErrEnrollmentRejected) {
		t.Fatalf("the code must not be reusable after a failed attempt, got %v", err)
	}
}

func TestExpiredCodeRejected(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	_, _, _ = r.ApplyControl(grantFor("code-1", []string{model.ScopeAll}, now.Add(time.Minute)), now)

	if _, err := r.Enroll("code-1", newKeyDER(t), "late", now.Add(2*time.Minute)); !errors.Is(err, ErrEnrollmentRejected) {
		t.Fatalf("an expired code must be rejected, got %v", err)
	}
}

func TestUnknownCodeRejected(t *testing.T) {
	r, _ := newRegistry(t)
	if _, err := r.Enroll("never-issued", newKeyDER(t), "x", time.Now()); !errors.Is(err, ErrEnrollmentRejected) {
		t.Fatalf("an unknown code must be rejected, got %v", err)
	}
}

// The curve is pinned: a device cannot present a weaker or different key type.
func TestOnlyP256KeysAccepted(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()

	p384, _ := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	p384DER, _ := x509.MarshalPKIXPublicKey(&p384.PublicKey)
	pub, _, _ := ed25519.GenerateKey(rand.Reader)
	edDER, _ := x509.MarshalPKIXPublicKey(pub)

	for name, der := range map[string][]byte{"P-384": p384DER, "ed25519": edDER} {
		_, _, _ = r.ApplyControl(grantFor("code-"+name, []string{model.ScopeAll}, now.Add(time.Hour)), now)
		if _, err := r.Enroll("code-"+name, der, name, now); err == nil {
			t.Errorf("a %s key should have been refused", name)
		}
	}
}

func TestRevocationIsImmediateAndPermanent(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	_, _, _ = r.ApplyControl(grantFor("code-1", []string{model.ScopeAll}, now.Add(time.Hour)), now)
	device, err := r.Enroll("code-1", newKeyDER(t), "phone", now)
	if err != nil {
		t.Fatalf("Enroll: %v", err)
	}

	revoke := &model.Control{
		SchemaVersion: model.ControlSchemaVersion,
		InstanceID:    "secman-prod",
		IssuedAt:      now,
		Revocations:   []model.Revocation{{DeviceID: device.ID, RevokedAt: now}},
	}
	if _, revoked, err := r.ApplyControl(revoke, now); err != nil || revoked != 1 {
		t.Fatalf("ApplyControl: revoked=%d err=%v", revoked, err)
	}
	if _, err := r.Get(device.ID); !errors.Is(err, ErrDeviceRevoked) {
		t.Fatalf("a revoked device must not be retrievable, got %v", err)
	}

	// A later control document that simply omits the revocation must not
	// resurrect the device.
	later := &model.Control{
		SchemaVersion: model.ControlSchemaVersion,
		InstanceID:    "secman-prod",
		IssuedAt:      now.Add(time.Minute),
	}
	if _, _, err := r.ApplyControl(later, now.Add(time.Minute)); err != nil {
		t.Fatalf("ApplyControl: %v", err)
	}
	if _, err := r.Get(device.ID); !errors.Is(err, ErrDeviceRevoked) {
		t.Fatal("a revocation must never be undone by a later push that omits it")
	}
}

// The panic button must also invalidate codes that have not been redeemed yet.
func TestRevokeAllBurnsPendingCodes(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	_, _, _ = r.ApplyControl(grantFor("code-1", []string{model.ScopeAll}, now.Add(time.Hour)), now)
	enrolled, _ := r.Enroll("code-1", newKeyDER(t), "phone", now)
	_, _, _ = r.ApplyControl(grantFor("code-2", []string{model.ScopeAll}, now.Add(time.Hour)), now)

	revokeAll := &model.Control{
		SchemaVersion: model.ControlSchemaVersion,
		InstanceID:    "secman-prod",
		IssuedAt:      now,
		Revocations:   []model.Revocation{{RevokeAll: true, RevokedAt: now, Reason: "lost laptop"}},
	}
	if _, _, err := r.ApplyControl(revokeAll, now); err != nil {
		t.Fatalf("ApplyControl: %v", err)
	}
	if _, err := r.Get(enrolled.ID); !errors.Is(err, ErrDeviceRevoked) {
		t.Error("revokeAll should revoke every enrolled device")
	}
	if _, err := r.Enroll("code-2", newKeyDER(t), "new", now); !errors.Is(err, ErrEnrollmentRejected) {
		t.Error("revokeAll should also burn unredeemed enrollment codes")
	}
}

func TestRegistryCapIsEnforced(t *testing.T) {
	dir := t.TempDir()
	r, err := Open(dir, 1)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	now := time.Now()
	_, _, _ = r.ApplyControl(grantFor("code-1", []string{model.ScopeAll}, now.Add(time.Hour)), now)
	_, _, _ = r.ApplyControl(grantFor("code-2", []string{model.ScopeAll}, now.Add(time.Hour)), now)

	if _, err := r.Enroll("code-1", newKeyDER(t), "one", now); err != nil {
		t.Fatalf("first enrollment: %v", err)
	}
	if _, err := r.Enroll("code-2", newKeyDER(t), "two", now); !errors.Is(err, ErrRegistryFull) {
		t.Fatalf("the cap should fail closed, got %v", err)
	}
}

// A phone cannot re-enrol itself, so the registry must survive a restart.
func TestRegistryPersistsAcrossRestart(t *testing.T) {
	dir := t.TempDir()
	r, err := Open(dir, 10)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	now := time.Now()
	_, _, _ = r.ApplyControl(grantFor("code-1", []string{"status:kpis"}, now.Add(time.Hour)), now)
	device, err := r.Enroll("code-1", newKeyDER(t), "phone", now)
	if err != nil {
		t.Fatalf("Enroll: %v", err)
	}

	reopened, err := Open(dir, 10)
	if err != nil {
		t.Fatalf("reopening: %v", err)
	}
	restored, err := reopened.Get(device.ID)
	if err != nil {
		t.Fatalf("the device should survive a restart: %v", err)
	}
	if len(restored.Scopes) != 1 || restored.Scopes[0] != "status:kpis" {
		t.Errorf("scopes did not survive: %v", restored.Scopes)
	}
	if _, err := restored.PublicKey(); err != nil {
		t.Errorf("the public key did not survive: %v", err)
	}
}

// The registry file names devices and subjects; it must not be world-readable.
func TestRegistryFilePermissions(t *testing.T) {
	r, dir := newRegistry(t)
	now := time.Now()
	_, _, _ = r.ApplyControl(grantFor("code-1", []string{model.ScopeAll}, now.Add(time.Hour)), now)

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
	_, _, _ = r.ApplyControl(grantFor("code-1", []string{model.ScopeAll}, now.Add(time.Minute)), now)
	if r.PendingEnrollments(now) != 1 {
		t.Fatalf("expected 1 pending code, got %d", r.PendingEnrollments(now))
	}
	r.Prune(now.Add(2 * time.Minute))
	if r.PendingEnrollments(now.Add(2*time.Minute)) != 0 {
		t.Error("expired codes should be pruned")
	}
}

// The listing feeds secman's admin view; it must not carry key material it does
// not need to.
func TestListOmitsKeyMaterial(t *testing.T) {
	r, _ := newRegistry(t)
	now := time.Now()
	_, _, _ = r.ApplyControl(grantFor("code-1", []string{model.ScopeAll}, now.Add(time.Hour)), now)
	if _, err := r.Enroll("code-1", newKeyDER(t), "phone", now); err != nil {
		t.Fatalf("Enroll: %v", err)
	}
	list := r.List()
	if len(list) != 1 {
		t.Fatalf("expected 1 device, got %d", len(list))
	}
	if list[0].PublicKeyDER != nil {
		t.Error("the listing should not carry the device key")
	}
}

func TestParsePublicKeyBase64(t *testing.T) {
	der := newKeyDER(t)
	encoded := base64.StdEncoding.EncodeToString(der)
	if _, err := ParsePublicKeyBase64(encoded); err != nil {
		t.Fatalf("a valid P-256 SPKI key should parse: %v", err)
	}
	for _, bad := range []string{"", "not base64 !!", base64.StdEncoding.EncodeToString([]byte("junk"))} {
		if _, err := ParsePublicKeyBase64(bad); err == nil {
			t.Errorf("%q should be rejected", bad)
		}
	}
}
