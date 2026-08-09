// Package model defines the wire contract between secman and the relay, and
// between the relay and a mobile client.
//
// Design note that matters more than it looks: the relay does not model
// secman's business data. A snapshot section is carried as json.RawMessage and
// re-served byte-for-byte. The relay therefore has no parser for asset names,
// CVE identifiers or KPI structures, cannot be broken by a new field, and does
// not need a release whenever secman adds a widget. The only things it
// validates are the envelope, the section names and the sizes.
package model

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

// SnapshotSchemaVersion is the envelope version this relay understands.
// A mismatch is refused rather than best-effort parsed: an ambiguous snapshot
// shown on a phone as if it were current is worse than no snapshot.
const SnapshotSchemaVersion = 1

// ControlSchemaVersion is the envelope version of the control document.
const ControlSchemaVersion = 1

const (
	maxSections          = 64
	maxSectionNameLength = 64
	maxInstanceIDLength  = 128
	maxScopeLength       = 64
	maxScopesPerGrant    = 32
	maxSubjectLength     = 254
	// maxFutureSkew bounds how far ahead of the relay's clock a snapshot may
	// claim to have been generated.
	maxFutureSkew = 5 * time.Minute
)

// Snapshot is the document secman pushes. Sections are opaque to the relay.
type Snapshot struct {
	SchemaVersion int                        `json:"schemaVersion"`
	InstanceID    string                     `json:"instanceId"`
	GeneratedAt   time.Time                  `json:"generatedAt"`
	Sections      map[string]json.RawMessage `json:"sections"`
}

// Validate checks the envelope. `now` is injected so the check is testable and
// so the caller decides which clock is authoritative.
func (s *Snapshot) Validate(now time.Time) error {
	if s == nil {
		return errors.New("snapshot is empty")
	}
	if s.SchemaVersion != SnapshotSchemaVersion {
		return fmt.Errorf("unsupported schemaVersion %d (this relay speaks %d)", s.SchemaVersion, SnapshotSchemaVersion)
	}
	if err := validateInstanceID(s.InstanceID); err != nil {
		return err
	}
	if s.GeneratedAt.IsZero() {
		return errors.New("generatedAt is required")
	}
	if s.GeneratedAt.After(now.Add(maxFutureSkew)) {
		return errors.New("generatedAt is too far in the future")
	}
	if len(s.Sections) == 0 {
		return errors.New("at least one section is required")
	}
	if len(s.Sections) > maxSections {
		return fmt.Errorf("too many sections (max %d)", maxSections)
	}
	for name, raw := range s.Sections {
		if err := ValidateSectionName(name); err != nil {
			return err
		}
		if len(raw) == 0 {
			return fmt.Errorf("section %q has an empty body", name)
		}
		if !json.Valid(raw) {
			return fmt.Errorf("section %q is not valid JSON", name)
		}
	}
	return nil
}

// SectionNames returns the section keys in a stable order.
func (s *Snapshot) SectionNames() []string {
	names := make([]string, 0, len(s.Sections))
	for name := range s.Sections {
		names = append(names, name)
	}
	sortStrings(names)
	return names
}

// ValidateSectionName constrains a section key to a conservative character
// class. Section names end up in URLs, in scope strings and in log lines, so
// anything outside [a-z0-9-] is refused at the door instead of escaped at each
// of those three places.
func ValidateSectionName(name string) error {
	if name == "" {
		return errors.New("section name must not be empty")
	}
	if len(name) > maxSectionNameLength {
		return fmt.Errorf("section name exceeds %d characters", maxSectionNameLength)
	}
	for _, r := range name {
		ok := (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '-'
		if !ok {
			return errors.New("section name may only contain lowercase letters, digits and '-'")
		}
	}
	if strings.HasPrefix(name, "-") || strings.HasSuffix(name, "-") {
		return errors.New("section name must not start or end with '-'")
	}
	return nil
}

func validateInstanceID(id string) error {
	if id == "" {
		return errors.New("instanceId is required")
	}
	if len(id) > maxInstanceIDLength {
		return fmt.Errorf("instanceId exceeds %d characters", maxInstanceIDLength)
	}
	for _, r := range id {
		ok := (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') ||
			r == '-' || r == '_' || r == '.'
		if !ok {
			return errors.New("instanceId may only contain letters, digits, '-', '_' and '.'")
		}
	}
	return nil
}

// Control is the second document secman pushes: the authorisation state the
// relay needs in order to enrol and revoke mobile devices.
//
// It is additive by design. Enrollment grants expire on their own, and a
// revocation must never be undone by a later push that happens to omit it —
// so the relay unions rather than replaces.
type Control struct {
	SchemaVersion int               `json:"schemaVersion"`
	InstanceID    string            `json:"instanceId"`
	IssuedAt      time.Time         `json:"issuedAt"`
	Enrollments   []EnrollmentGrant `json:"enrollments,omitempty"`
	Revocations   []Revocation      `json:"revocations,omitempty"`
}

// EnrollmentGrant authorises exactly one device enrollment.
//
// Only the SHA-256 of the enrollment code travels. The plaintext code is shown
// once to the admin in secman and typed into the app; the relay can verify a
// presented code but can never emit one, so a relay compromise does not hand
// the attacker a way to mint new devices.
type EnrollmentGrant struct {
	CodeSHA256 string    `json:"codeSha256"`
	Subject    string    `json:"subject"`
	Scopes     []string  `json:"scopes"`
	ExpiresAt  time.Time `json:"expiresAt"`
	// Label is a human-readable hint (who the code was issued to). Never a
	// credential; still treated as untrusted text on the way to a log.
	Label string `json:"label,omitempty"`
}

// Revocation removes a device's access. RevokeAll is the panic button.
type Revocation struct {
	DeviceID  string    `json:"deviceId,omitempty"`
	RevokeAll bool      `json:"revokeAll,omitempty"`
	RevokedAt time.Time `json:"revokedAt"`
	Reason    string    `json:"reason,omitempty"`
}

// Validate checks the control envelope.
func (c *Control) Validate(now time.Time) error {
	if c == nil {
		return errors.New("control document is empty")
	}
	if c.SchemaVersion != ControlSchemaVersion {
		return fmt.Errorf("unsupported schemaVersion %d (this relay speaks %d)", c.SchemaVersion, ControlSchemaVersion)
	}
	if err := validateInstanceID(c.InstanceID); err != nil {
		return err
	}
	if c.IssuedAt.IsZero() {
		return errors.New("issuedAt is required")
	}
	if c.IssuedAt.After(now.Add(maxFutureSkew)) {
		return errors.New("issuedAt is too far in the future")
	}
	if len(c.Enrollments) > 256 {
		return errors.New("too many enrollments in one control document (max 256)")
	}
	if len(c.Revocations) > 1024 {
		return errors.New("too many revocations in one control document (max 1024)")
	}
	for i := range c.Enrollments {
		if err := c.Enrollments[i].validate(); err != nil {
			return fmt.Errorf("enrollments[%d]: %w", i, err)
		}
	}
	for i := range c.Revocations {
		r := c.Revocations[i]
		if !r.RevokeAll && r.DeviceID == "" {
			return fmt.Errorf("revocations[%d]: either deviceId or revokeAll is required", i)
		}
		if len(r.DeviceID) > 128 {
			return fmt.Errorf("revocations[%d]: deviceId is too long", i)
		}
	}
	return nil
}

func (g EnrollmentGrant) validate() error {
	if len(g.CodeSHA256) != 64 {
		return errors.New("codeSha256 must be a 64-character hex SHA-256 digest")
	}
	for _, r := range g.CodeSHA256 {
		isHex := (r >= '0' && r <= '9') || (r >= 'a' && r <= 'f')
		if !isHex {
			return errors.New("codeSha256 must be lowercase hex")
		}
	}
	if g.Subject == "" {
		return errors.New("subject is required")
	}
	if len(g.Subject) > maxSubjectLength {
		return errors.New("subject is too long")
	}
	if g.ExpiresAt.IsZero() {
		return errors.New("expiresAt is required")
	}
	if len(g.Scopes) == 0 {
		return errors.New("at least one scope is required")
	}
	if len(g.Scopes) > maxScopesPerGrant {
		return fmt.Errorf("too many scopes (max %d)", maxScopesPerGrant)
	}
	for _, s := range g.Scopes {
		if err := ValidateScope(s); err != nil {
			return err
		}
	}
	return nil
}

// ScopeAll grants read access to every section in the snapshot.
const ScopeAll = "status:*"

// ValidateScope constrains a scope string. Scopes are either ScopeAll or
// "status:<section-name>".
func ValidateScope(s string) error {
	if s == ScopeAll {
		return nil
	}
	if len(s) > maxScopeLength {
		return errors.New("scope is too long")
	}
	rest, ok := strings.CutPrefix(s, "status:")
	if !ok {
		return fmt.Errorf("scope must be %q or \"status:<section>\"", ScopeAll)
	}
	return ValidateSectionName(rest)
}

// ScopeAllows reports whether a set of granted scopes permits reading a
// section. Deny by default: an unknown scope string grants nothing.
func ScopeAllows(scopes []string, section string) bool {
	for _, s := range scopes {
		if s == ScopeAll {
			return true
		}
		if rest, ok := strings.CutPrefix(s, "status:"); ok && rest == section {
			return true
		}
	}
	return false
}

// sortStrings is a tiny insertion sort; the slices are at most maxSections
// long, and avoiding the import keeps this package free of anything that could
// surprise on a hot path.
func sortStrings(s []string) {
	for i := 1; i < len(s); i++ {
		for j := i; j > 0 && s[j] < s[j-1]; j-- {
			s[j], s[j-1] = s[j-1], s[j]
		}
	}
}
