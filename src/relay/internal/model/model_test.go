package model

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func validSnapshot() *Snapshot {
	return &Snapshot{
		SchemaVersion: SnapshotSchemaVersion,
		InstanceID:    "secman-prod",
		GeneratedAt:   time.Now(),
		Sections:      map[string]json.RawMessage{"kpis": json.RawMessage(`{"a":1}`)},
	}
}

func TestSnapshotValidation(t *testing.T) {
	now := time.Now()
	if err := validSnapshot().Validate(now); err != nil {
		t.Fatalf("a well-formed snapshot should validate: %v", err)
	}

	t.Run("schema version is refused, not coerced", func(t *testing.T) {
		s := validSnapshot()
		s.SchemaVersion = 99
		if err := s.Validate(now); err == nil {
			t.Fatal("an unknown schema version must be refused")
		}
	})

	t.Run("future timestamps rejected", func(t *testing.T) {
		s := validSnapshot()
		s.GeneratedAt = now.Add(time.Hour)
		if err := s.Validate(now); err == nil {
			t.Fatal("a snapshot generated an hour in the future must be refused")
		}
	})

	t.Run("empty section body rejected", func(t *testing.T) {
		s := validSnapshot()
		s.Sections["kpis"] = json.RawMessage("")
		if err := s.Validate(now); err == nil {
			t.Fatal("an empty section body must be refused")
		}
	})

	t.Run("invalid json in a section rejected", func(t *testing.T) {
		s := validSnapshot()
		s.Sections["kpis"] = json.RawMessage(`{"a":`)
		if err := s.Validate(now); err == nil {
			t.Fatal("a section that is not valid JSON must be refused")
		}
	})

	t.Run("section flood rejected", func(t *testing.T) {
		s := validSnapshot()
		for i := 0; i < 100; i++ {
			s.Sections[sectionName(i)] = json.RawMessage(`{}`)
		}
		if err := s.Validate(now); err == nil {
			t.Fatal("an unbounded number of sections must be refused")
		}
	})
}

// Section names reach URLs, scope strings and log lines, so they are locked to
// a conservative character class at the door.
func TestSectionNameValidation(t *testing.T) {
	valid := []string{"kpis", "vuln-summary", "a", "x1-y2"}
	for _, n := range valid {
		if err := ValidateSectionName(n); err != nil {
			t.Errorf("%q should be valid: %v", n, err)
		}
	}
	invalid := []string{
		"", "UPPER", "with space", "with_underscore", "-lead", "trail-",
		"../etc/passwd", "sec/tion", "sec\ntion", strings.Repeat("a", 100),
	}
	for _, n := range invalid {
		if err := ValidateSectionName(n); err == nil {
			t.Errorf("%q should be rejected", n)
		}
	}
}

func TestScopeValidationAndMatching(t *testing.T) {
	if err := ValidateScope(ScopeAll); err != nil {
		t.Errorf("%q should be valid: %v", ScopeAll, err)
	}
	if err := ValidateScope("status:kpis"); err != nil {
		t.Errorf("status:kpis should be valid: %v", err)
	}
	for _, s := range []string{"kpis", "admin", "status:", "status:BAD", "*", "status:*extra"} {
		if err := ValidateScope(s); err == nil {
			t.Errorf("scope %q should be rejected", s)
		}
	}

	if !ScopeAllows([]string{ScopeAll}, "anything") {
		t.Error("status:* should allow any section")
	}
	if !ScopeAllows([]string{"status:kpis"}, "kpis") {
		t.Error("an exact scope should allow its section")
	}
	// Deny by default is the property that matters here.
	if ScopeAllows([]string{"status:kpis"}, "vulns") {
		t.Error("a scope must not allow a section it does not name")
	}
	if ScopeAllows(nil, "kpis") {
		t.Error("no scopes must allow nothing")
	}
	if ScopeAllows([]string{"nonsense"}, "kpis") {
		t.Error("an unrecognised scope string must grant nothing")
	}
}

func TestControlValidation(t *testing.T) {
	now := time.Now()
	valid := &Control{
		SchemaVersion: ControlSchemaVersion,
		InstanceID:    "secman-prod",
		IssuedAt:      now,
		Enrollments: []EnrollmentGrant{{
			CodeSHA256: strings.Repeat("a", 64),
			Subject:    "admin@example.com",
			Scopes:     []string{ScopeAll},
			ExpiresAt:  now.Add(time.Hour),
		}},
	}
	if err := valid.Validate(now); err != nil {
		t.Fatalf("a well-formed control document should validate: %v", err)
	}

	t.Run("code digest must be hex sha256", func(t *testing.T) {
		for _, digest := range []string{"", "short", strings.Repeat("A", 64), strings.Repeat("z", 64)} {
			c := *valid
			c.Enrollments = []EnrollmentGrant{{
				CodeSHA256: digest,
				Subject:    "admin",
				Scopes:     []string{ScopeAll},
				ExpiresAt:  now.Add(time.Hour),
			}}
			if err := c.Validate(now); err == nil {
				t.Errorf("digest %q should be rejected", digest)
			}
		}
	})

	t.Run("a grant needs at least one scope", func(t *testing.T) {
		c := *valid
		c.Enrollments = []EnrollmentGrant{{
			CodeSHA256: strings.Repeat("a", 64),
			Subject:    "admin",
			ExpiresAt:  now.Add(time.Hour),
		}}
		if err := c.Validate(now); err == nil {
			t.Fatal("a grant with no scope must be rejected")
		}
	})

	t.Run("a revocation needs a target", func(t *testing.T) {
		c := *valid
		c.Enrollments = nil
		c.Revocations = []Revocation{{RevokedAt: now}}
		if err := c.Validate(now); err == nil {
			t.Fatal("a revocation naming nothing must be rejected")
		}
	})
}

func sectionName(i int) string {
	letters := "abcdefghijklmnopqrstuvwxyz"
	return string(letters[i%26]) + string(letters[(i/26)%26]) + string(letters[(i/7)%26])
}
