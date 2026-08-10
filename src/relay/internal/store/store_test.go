package store

import (
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/schmalle/secman/src/relay/internal/model"
)

var (
	adminOnly    = model.SectionPolicy{RequiredRoles: []string{"ADMIN"}}
	adminOrChamp = model.SectionPolicy{RequiredRoles: []string{"ADMIN", "SECCHAMPION"}}
	vulnOnly     = model.SectionPolicy{RequiredRoles: []string{"VULN"}}
	allScopes    = []string{model.ScopeAll}
)

func snapshotAt(generated time.Time, instance string) *model.Snapshot {
	return &model.Snapshot{
		SchemaVersion: model.SnapshotSchemaVersion,
		InstanceID:    instance,
		GeneratedAt:   generated,
		Sections: map[string]json.RawMessage{
			"kpis":  json.RawMessage(`{"edrCoverage":97.5}`),
			"vulns": json.RawMessage(`{"critical":3}`),
		},
		Policy: map[string]model.SectionPolicy{
			"kpis":  adminOrChamp,
			"vulns": vulnOnly,
		},
	}
}

func TestEmptyStore(t *testing.T) {
	s := New()
	if _, err := s.Metadata(time.Now(), time.Minute); !errors.Is(err, ErrEmpty) {
		t.Fatalf("expected ErrEmpty, got %v", err)
	}
	if _, _, err := s.Section("kpis", []string{"ADMIN"}, allScopes, time.Now(), time.Minute); !errors.Is(err, ErrEmpty) {
		t.Fatalf("expected ErrEmpty, got %v", err)
	}
}

func TestPutAndRead(t *testing.T) {
	s := New()
	now := time.Now()
	if err := s.Put(snapshotAt(now, "prod"), now); err != nil {
		t.Fatalf("Put: %v", err)
	}

	meta, err := s.Metadata(now, 15*time.Minute)
	if err != nil {
		t.Fatalf("Metadata: %v", err)
	}
	if meta.Stale {
		t.Error("a fresh snapshot must not be reported stale")
	}

	raw, allowed, err := s.Section("kpis", []string{"SECCHAMPION"}, allScopes, now, 15*time.Minute)
	if err != nil || !allowed {
		t.Fatalf("Section: allowed=%v err=%v", allowed, err)
	}
	if string(raw) != `{"edrCoverage":97.5}` {
		t.Errorf("section bytes changed in transit: %s", raw)
	}
}

// An older snapshot must never overwrite a newer one. Within the ingest clock
// skew window a captured push could otherwise be replayed with a fresh nonce to
// pin the app to a stale picture.
func TestPutRejectsNonMonotonicSnapshot(t *testing.T) {
	s := New()
	now := time.Now()
	if err := s.Put(snapshotAt(now, "prod"), now); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if err := s.Put(snapshotAt(now.Add(-time.Minute), "prod"), now); err == nil {
		t.Fatal("an older snapshot must be refused")
	}
	if err := s.Put(snapshotAt(now, "prod"), now); err == nil {
		t.Fatal("a snapshot with the identical timestamp must be refused")
	}
	if err := s.Put(snapshotAt(now.Add(time.Minute), "prod"), now); err != nil {
		t.Fatalf("a newer snapshot should be accepted: %v", err)
	}
}

// Two secman instances pushing to one relay would interleave two different
// worlds onto one phone screen.
func TestPutRejectsForeignInstance(t *testing.T) {
	s := New()
	now := time.Now()
	if err := s.Put(snapshotAt(now, "prod"), now); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if err := s.Put(snapshotAt(now.Add(time.Minute), "staging"), now); err == nil {
		t.Fatal("a snapshot from another instance must be refused")
	}
}

func TestStaleIsReportedNotHidden(t *testing.T) {
	s := New()
	generated := time.Now().Add(-time.Hour)
	if err := s.Put(snapshotAt(generated, "prod"), generated); err != nil {
		t.Fatalf("Put: %v", err)
	}
	now := time.Now()

	meta, err := s.Metadata(now, 15*time.Minute)
	if err != nil {
		t.Fatalf("Metadata: %v", err)
	}
	if !meta.Stale {
		t.Error("an hour-old snapshot should be flagged stale")
	}

	raw, allowed, err := s.Section("kpis", []string{"ADMIN"}, allScopes, now, 15*time.Minute)
	if !errors.Is(err, ErrStale) {
		t.Errorf("expected ErrStale, got %v", err)
	}
	if !allowed || len(raw) == 0 {
		t.Error("stale data should still be returned so the app can label it")
	}
}

// The role gate mirrors secman's own `@Secured`: a VULN user does not see the
// admin KPI section, and an ADMIN does not automatically see a VULN-only one.
func TestRoleGateMirrorsSecman(t *testing.T) {
	s := New()
	now := time.Now()
	if err := s.Put(snapshotAt(now, "prod"), now); err != nil {
		t.Fatalf("Put: %v", err)
	}

	cases := []struct {
		roles       []string
		section     string
		wantAllowed bool
	}{
		{[]string{"ADMIN"}, "kpis", true},
		{[]string{"SECCHAMPION"}, "kpis", true},
		{[]string{"VULN"}, "kpis", false},
		{[]string{"USER"}, "kpis", false},
		{[]string{"VULN"}, "vulns", true},
		{[]string{"ADMIN"}, "vulns", false},
		{nil, "kpis", false},
	}
	for _, tc := range cases {
		_, allowed, _ := s.Section(tc.section, tc.roles, allScopes, now, 15*time.Minute)
		if allowed != tc.wantAllowed {
			t.Errorf("roles=%v section=%s allowed=%v, want %v", tc.roles, tc.section, allowed, tc.wantAllowed)
		}
	}
}

// A section with no policy entry is readable by nobody. The store must fail
// closed even if a snapshot somehow got past envelope validation.
func TestSectionWithoutPolicyIsUnreadable(t *testing.T) {
	s := New()
	now := time.Now()
	snap := snapshotAt(now, "prod")
	snap.Sections["orphan"] = json.RawMessage(`{"secret":true}`)

	if err := s.Put(snap, now); err != nil {
		t.Fatalf("Put: %v", err)
	}
	if _, allowed, _ := s.Section("orphan", []string{"ADMIN"}, allScopes, now, 15*time.Minute); allowed {
		t.Fatal("a section with no declared policy must not be readable")
	}
	_, sections, _ := s.Sections([]string{"ADMIN"}, allScopes, now, 15*time.Minute)
	if _, leaked := sections["orphan"]; leaked {
		t.Fatal("an unpoliced section leaked into the aggregate read")
	}
}

// A device scope narrows; it can never widen past the role gate.
func TestScopeNarrowsButNeverWidens(t *testing.T) {
	s := New()
	now := time.Now()
	if err := s.Put(snapshotAt(now, "prod"), now); err != nil {
		t.Fatalf("Put: %v", err)
	}

	// Role allows, scope excludes -> denied.
	if _, allowed, _ := s.Section("kpis", []string{"ADMIN"}, []string{"status:vulns"}, now, 15*time.Minute); allowed {
		t.Error("a scope that omits the section must deny it")
	}
	// Scope allows everything, role does not -> still denied.
	if _, allowed, _ := s.Section("vulns", []string{"ADMIN"}, allScopes, now, 15*time.Minute); allowed {
		t.Error("status:* must not grant a section the role gate refuses")
	}
	if _, allowed, _ := s.Section("kpis", []string{"USER"}, []string{"status:kpis"}, now, 15*time.Minute); allowed {
		t.Error("naming a section in a scope must not confer the role for it")
	}
}

func TestSectionsAggregateRespectsBothGates(t *testing.T) {
	s := New()
	now := time.Now()
	if err := s.Put(snapshotAt(now, "prod"), now); err != nil {
		t.Fatalf("Put: %v", err)
	}

	_, sections, err := s.Sections([]string{"ADMIN"}, allScopes, now, 15*time.Minute)
	if err != nil {
		t.Fatalf("Sections: %v", err)
	}
	if len(sections) != 1 {
		t.Fatalf("an ADMIN should see exactly the admin section, got %d", len(sections))
	}
	if _, ok := sections["kpis"]; !ok {
		t.Error("kpis should be visible to ADMIN")
	}

	_, none, err := s.Sections([]string{"USER"}, allScopes, now, 15*time.Minute)
	if err != nil {
		t.Fatalf("Sections: %v", err)
	}
	if len(none) != 0 {
		t.Error("a plain USER must see nothing in this snapshot")
	}
}

// The metadata listing must not tell a low-privilege device what it is missing.
func TestMetadataNamesOnlyVisibleSections(t *testing.T) {
	s := New()
	now := time.Now()
	if err := s.Put(snapshotAt(now, "prod"), now); err != nil {
		t.Fatalf("Put: %v", err)
	}

	meta, _, err := s.Sections([]string{"VULN"}, allScopes, now, 15*time.Minute)
	if err != nil {
		t.Fatalf("Sections: %v", err)
	}
	for _, name := range meta.Sections {
		if name == "kpis" {
			t.Fatal("the section listing leaked a section the caller cannot read")
		}
	}

	visible := s.VisibleSections([]string{"VULN"}, allScopes)
	if len(visible) != 1 || visible[0] != "vulns" {
		t.Errorf("VisibleSections = %v, want [vulns]", visible)
	}
}

// The stored bytes must not be reachable through a returned slice.
func TestReturnedSectionIsACopy(t *testing.T) {
	s := New()
	now := time.Now()
	if err := s.Put(snapshotAt(now, "prod"), now); err != nil {
		t.Fatalf("Put: %v", err)
	}
	raw, _, _ := s.Section("kpis", []string{"ADMIN"}, allScopes, now, 15*time.Minute)
	raw[0] = 'X'

	again, _, _ := s.Section("kpis", []string{"ADMIN"}, allScopes, now, 15*time.Minute)
	if again[0] == 'X' {
		t.Fatal("mutating a returned section changed the stored snapshot")
	}
}

func TestStats(t *testing.T) {
	s := New()
	now := time.Now()
	if _, _, has := s.Stats(); has {
		t.Error("a new store should report no snapshot")
	}
	_ = s.Put(snapshotAt(now, "prod"), now)
	s.NoteRejected()

	accepted, rejected, has := s.Stats()
	if accepted != 1 || rejected != 1 || !has {
		t.Errorf("stats = (%d, %d, %v), want (1, 1, true)", accepted, rejected, has)
	}
}
