// Package store holds the current snapshot.
//
// It is memory-only on purpose. The relay lives in a DMZ; the less of secman's
// data that exists on its disk, the less a stolen volume or a forgotten backup
// is worth. A restart simply serves 503 until the next push arrives, which is
// a self-healing state, not an outage to engineer around.
package store

import (
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/schmalle/secman/src/relay/internal/model"
)

// ErrStale is returned when a snapshot exists but is older than the configured
// maximum age.
var ErrStale = errors.New("snapshot is stale")

// ErrEmpty is returned before the first push.
var ErrEmpty = errors.New("no snapshot has been received yet")

// Store keeps exactly one snapshot per relay process.
type Store struct {
	mu         sync.RWMutex
	snapshot   *model.Snapshot
	receivedAt time.Time
	// pushCount and rejectedCount are exposed on the ops plane so an operator
	// can tell "secman never connected" from "secman connected and was refused".
	pushCount     uint64
	rejectedCount uint64
}

// New returns an empty store.
func New() *Store { return &Store{} }

// Put replaces the current snapshot.
//
// A snapshot whose GeneratedAt is not strictly newer than the stored one is
// rejected. That closes the gap the HMAC replay window leaves open: within the
// (default 5 minute) clock-skew allowance an attacker who captured a valid
// push could otherwise re-send it with a fresh nonce and pin the phone to a
// stale "everything is fine" picture.
func (s *Store) Put(snap *model.Snapshot, now time.Time) error {
	if snap == nil {
		return errors.New("snapshot is nil")
	}
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.snapshot != nil {
		if !snap.GeneratedAt.After(s.snapshot.GeneratedAt) {
			s.rejectedCount++
			return errors.New("snapshot is not newer than the one already stored")
		}
		if s.snapshot.InstanceID != snap.InstanceID {
			// Two secman instances pushing to one relay would silently
			// interleave two different worlds onto the same phone screen.
			s.rejectedCount++
			return errors.New("snapshot instanceId differs from the instance this relay is serving")
		}
	}

	s.snapshot = snap
	s.receivedAt = now
	s.pushCount++
	return nil
}

// Meta describes the stored snapshot without exposing its contents.
type Meta struct {
	SchemaVersion int       `json:"schemaVersion"`
	InstanceID    string    `json:"instanceId"`
	GeneratedAt   time.Time `json:"generatedAt"`
	ReceivedAt    time.Time `json:"receivedAt"`
	AgeSeconds    int64     `json:"ageSeconds"`
	Stale         bool      `json:"stale"`
	Sections      []string  `json:"sections"`
}

// Metadata returns the envelope facts. It never returns ErrStale: a client that
// asks "how old is the data" must get an answer even when the answer is "too
// old".
func (s *Store) Metadata(now time.Time, maxAge time.Duration) (Meta, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if s.snapshot == nil {
		return Meta{}, ErrEmpty
	}
	age := now.Sub(s.snapshot.GeneratedAt)
	return Meta{
		SchemaVersion: s.snapshot.SchemaVersion,
		InstanceID:    s.snapshot.InstanceID,
		GeneratedAt:   s.snapshot.GeneratedAt,
		ReceivedAt:    s.receivedAt,
		AgeSeconds:    int64(age.Seconds()),
		Stale:         age > maxAge,
		Sections:      s.snapshot.SectionNames(),
	}, nil
}

// Section returns one section's raw JSON.
//
// A stale snapshot is still returned, together with ErrStale, so the caller can
// decide: the mobile API serves it with an explicit `stale: true` marker rather
// than hiding it, because "the last known state, clearly labelled as N minutes
// old" is more useful to an on-call admin than an empty screen — and far less
// likely to be misread than silently stale data.
func (s *Store) Section(name string, now time.Time, maxAge time.Duration) (json.RawMessage, bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if s.snapshot == nil {
		return nil, false, ErrEmpty
	}
	raw, ok := s.snapshot.Sections[name]
	if !ok {
		return nil, false, nil
	}
	stale := now.Sub(s.snapshot.GeneratedAt) > maxAge
	// Copy: the caller must not be able to mutate the stored bytes.
	out := make(json.RawMessage, len(raw))
	copy(out, raw)
	if stale {
		return out, true, ErrStale
	}
	return out, true, nil
}

// Sections returns every section the caller's scopes permit, plus the metadata.
func (s *Store) Sections(scopes []string, now time.Time, maxAge time.Duration) (Meta, map[string]json.RawMessage, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if s.snapshot == nil {
		return Meta{}, nil, ErrEmpty
	}
	out := make(map[string]json.RawMessage, len(s.snapshot.Sections))
	for name, raw := range s.snapshot.Sections {
		if !model.ScopeAllows(scopes, name) {
			continue
		}
		cp := make(json.RawMessage, len(raw))
		copy(cp, raw)
		out[name] = cp
	}
	age := now.Sub(s.snapshot.GeneratedAt)
	meta := Meta{
		SchemaVersion: s.snapshot.SchemaVersion,
		InstanceID:    s.snapshot.InstanceID,
		GeneratedAt:   s.snapshot.GeneratedAt,
		ReceivedAt:    s.receivedAt,
		AgeSeconds:    int64(age.Seconds()),
		Stale:         age > maxAge,
		Sections:      s.snapshot.SectionNames(),
	}
	if meta.Stale {
		return meta, out, ErrStale
	}
	return meta, out, nil
}

// Stats reports push counters for the ops plane.
func (s *Store) Stats() (accepted, rejected uint64, hasSnapshot bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.pushCount, s.rejectedCount, s.snapshot != nil
}

// NoteRejected records a push that failed before it reached Put (bad auth, bad
// envelope), so the ops counters describe the whole ingest path.
func (s *Store) NoteRejected() {
	s.mu.Lock()
	s.rejectedCount++
	s.mu.Unlock()
}
