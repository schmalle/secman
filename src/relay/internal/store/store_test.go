package store

import (
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/schmalle/secman/src/relay/internal/model"
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
	}
}

func TestEmptyStore(t *testing.T) {
	s := New()
	if _, err := s.Metadata(time.Now(), time.Minute); !errors.Is(err, ErrEmpty) {
		t.Fatalf("expected ErrEmpty, got %v", err)
	}
	if _, _, err := s.Section("kpis", time.Now(), time.Minute); !errors.Is(err, ErrEmpty) {
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
	if len(meta.Sections) != 2 || meta.Sections[0] != "kpis" {
		t.Errorf("sections should be sorted and complete, got %v", meta.Sections)
	}

	raw, found, err := s.Section("kpis", now, 15*time.Minute)
	if err != nil || !found {
		t.Fatalf("Section: found=%v err=%v", found, err)
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

	raw, found, err := s.Section("kpis", now, 15*time.Minute)
	if !errors.Is(err, ErrStale) {
		t.Errorf("expected ErrStale, got %v", err)
	}
	if !found || len(raw) == 0 {
		t.Error("stale data should still be returned so the app can label it")
	}
}

func TestSectionsAreScoped(t *testing.T) {
	s := New()
	now := time.Now()
	if err := s.Put(snapshotAt(now, "prod"), now); err != nil {
		t.Fatalf("Put: %v", err)
	}

	_, sections, err := s.Sections([]string{"status:kpis"}, now, 15*time.Minute)
	if err != nil {
		t.Fatalf("Sections: %v", err)
	}
	if len(sections) != 1 {
		t.Fatalf("a narrow scope should yield one section, got %d", len(sections))
	}
	if _, ok := sections["vulns"]; ok {
		t.Error("an out-of-scope section leaked into the response")
	}

	_, all, err := s.Sections([]string{model.ScopeAll}, now, 15*time.Minute)
	if err != nil {
		t.Fatalf("Sections: %v", err)
	}
	if len(all) != 2 {
		t.Errorf("status:* should yield every section, got %d", len(all))
	}

	_, none, err := s.Sections(nil, now, 15*time.Minute)
	if err != nil {
		t.Fatalf("Sections: %v", err)
	}
	if len(none) != 0 {
		t.Error("no scopes must mean no data")
	}
}

// The stored bytes must not be reachable through a returned slice.
func TestReturnedSectionIsACopy(t *testing.T) {
	s := New()
	now := time.Now()
	if err := s.Put(snapshotAt(now, "prod"), now); err != nil {
		t.Fatalf("Put: %v", err)
	}
	raw, _, _ := s.Section("kpis", now, 15*time.Minute)
	raw[0] = 'X'

	again, _, _ := s.Section("kpis", now, 15*time.Minute)
	if again[0] == 'X' {
		t.Fatal("mutating a returned section changed the stored snapshot")
	}
}

func TestStats(t *testing.T) {
	s := New()
	now := time.Now()
	if _, _, has := stats(s); has {
		t.Error("a new store should report no snapshot")
	}
	_ = s.Put(snapshotAt(now, "prod"), now)
	s.NoteRejected()

	accepted, rejected, has := stats(s)
	if accepted != 1 || rejected != 1 || !has {
		t.Errorf("stats = (%d, %d, %v), want (1, 1, true)", accepted, rejected, has)
	}
}

func stats(s *Store) (uint64, uint64, bool) { return s.Stats() }
