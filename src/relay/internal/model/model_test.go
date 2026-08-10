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
		Policy: map[string]SectionPolicy{
			"kpis": {RequiredRoles: []string{"ADMIN", "SECCHAMPION"}},
		},
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
			name := sectionName(i)
			s.Sections[name] = json.RawMessage(`{}`)
			s.Policy[name] = SectionPolicy{RequiredRoles: []string{"ADMIN"}}
		}
		if err := s.Validate(now); err == nil {
			t.Fatal("an unbounded number of sections must be refused")
		}
	})

	// A section nobody can read looks exactly like a broken app. Refusing the
	// push means secman finds out immediately instead of a user reporting it.
	t.Run("section without a policy is refused", func(t *testing.T) {
		s := validSnapshot()
		s.Sections["orphan"] = json.RawMessage(`{}`)
		if err := s.Validate(now); err == nil {
			t.Fatal("a section with no policy entry must be refused")
		}
	})

	t.Run("policy naming an absent section is refused", func(t *testing.T) {
		s := validSnapshot()
		s.Policy["ghost"] = SectionPolicy{RequiredRoles: []string{"ADMIN"}}
		if err := s.Validate(now); err == nil {
			t.Fatal("a policy entry for a section that is not present must be refused")
		}
	})

	t.Run("policy with no roles is refused", func(t *testing.T) {
		s := validSnapshot()
		s.Policy["kpis"] = SectionPolicy{}
		if err := s.Validate(now); err == nil {
			t.Fatal("a section must declare at least one required role")
		}
	})

	// A typo would otherwise produce a section that silently matches nobody.
	t.Run("unknown role is refused", func(t *testing.T) {
		s := validSnapshot()
		s.Policy["kpis"] = SectionPolicy{RequiredRoles: []string{"ADMINISTRATOR"}}
		if err := s.Validate(now); err == nil {
			t.Fatal("a role outside secman's vocabulary must be refused")
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

func TestRolesAllow(t *testing.T) {
	policy := SectionPolicy{RequiredRoles: []string{"ADMIN", "SECCHAMPION"}}

	if !RolesAllow(policy, []string{"SECCHAMPION"}) {
		t.Error("holding one of the required roles should allow the section")
	}
	if !RolesAllow(policy, []string{"USER", "ADMIN"}) {
		t.Error("any-of semantics: one match is enough")
	}
	if RolesAllow(policy, []string{"VULN"}) {
		t.Error("a role the policy does not name must not allow the section")
	}
	// Deny by default in every direction.
	if RolesAllow(policy, nil) {
		t.Error("no roles must allow nothing")
	}
	if RolesAllow(SectionPolicy{}, []string{"ADMIN"}) {
		t.Error("a policy with no roles must allow nobody, not everybody")
	}
}

// CanRead is the only correct way to authorize a read: both gates, every time.
func TestCanReadRequiresBothGates(t *testing.T) {
	policy := SectionPolicy{RequiredRoles: []string{"ADMIN"}}

	if !CanRead(policy, []string{"ADMIN"}, []string{ScopeAll}, "kpis") {
		t.Error("role and scope both satisfied should allow")
	}
	if CanRead(policy, []string{"ADMIN"}, []string{"status:other"}, "kpis") {
		t.Error("the scope gate must be able to deny what the role gate allows")
	}
	if CanRead(policy, []string{"USER"}, []string{ScopeAll}, "kpis") {
		t.Error("the role gate must be able to deny what the scope gate allows")
	}
	if CanRead(SectionPolicy{}, []string{"ADMIN"}, []string{ScopeAll}, "kpis") {
		t.Error("a missing policy must deny")
	}
}

func TestControlValidation(t *testing.T) {
	now := time.Now()
	valid := &Control{
		SchemaVersion:           ControlSchemaVersion,
		InstanceID:              "secman-prod",
		IssuedAt:                now,
		PrincipalsAuthoritative: true,
		Principals: []Principal{{
			Subject: "markus",
			Roles:   []string{"ADMIN"},
			Identities: []ExternalIdentity{
				{Provider: ProviderApple, Subject: "001234.abcdef"},
			},
		}},
		Enrollments: []EnrollmentGrant{{
			CodeSHA256: strings.Repeat("a", 64),
			Subject:    "markus",
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

	t.Run("unknown role on a principal is refused", func(t *testing.T) {
		c := *valid
		c.Principals = []Principal{{Subject: "markus", Roles: []string{"SUPERUSER"}}}
		if err := c.Validate(now); err == nil {
			t.Fatal("a role outside secman's vocabulary must be refused")
		}
	})

	t.Run("unknown identity provider is refused", func(t *testing.T) {
		c := *valid
		c.Principals = []Principal{{
			Subject:    "markus",
			Roles:      []string{"ADMIN"},
			Identities: []ExternalIdentity{{Provider: "facebook", Subject: "1"}},
		}}
		if err := c.Validate(now); err == nil {
			t.Fatal("an unsupported identity provider must be refused")
		}
	})

	t.Run("duplicate principals are refused", func(t *testing.T) {
		c := *valid
		c.Principals = []Principal{
			{Subject: "markus", Roles: []string{"ADMIN"}},
			{Subject: "markus", Roles: []string{"USER"}},
		}
		if err := c.Validate(now); err == nil {
			t.Fatal("two records for one subject are ambiguous and must be refused")
		}
	})

	// An authoritative push with an empty list would disable everyone at once.
	// Almost certainly a bug on the pushing side, so say so out loud.
	t.Run("authoritative push with no principals is refused", func(t *testing.T) {
		c := *valid
		c.Principals = nil
		c.PrincipalsAuthoritative = true
		if err := c.Validate(now); err == nil {
			t.Fatal("an authoritative push carrying no principals must be refused")
		}
	})
}

func TestIdentityKeyIsProviderScoped(t *testing.T) {
	// Two providers can legitimately issue the same subject string; the key
	// must keep them apart or one provider could impersonate the other.
	if IdentityKey(ProviderApple, "123") == IdentityKey(ProviderGitHub, "123") {
		t.Fatal("identity keys must be scoped by provider")
	}
}

func sectionName(i int) string {
	letters := "abcdefghijklmnopqrstuvwxyz"
	return string(letters[i%26]) + string(letters[(i/26)%26]) + string(letters[(i/7)%26])
}
