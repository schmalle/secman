// Package model defines the wire contract between secman and the relay, and
// between the relay and a mobile client.
//
// Two design decisions shape everything here.
//
// **Section bodies are opaque.** A snapshot section is carried as
// json.RawMessage and re-served byte for byte. The relay has no parser for
// asset names, CVE identifiers or KPI structures, cannot be broken by a new
// field, and needs no release when secman adds a widget. What it does validate
// is the envelope, the section names, the sizes — and the policy below.
//
// **Authorization is secman's, mirrored.** The relay does not invent an access
// model. secman pushes, per section, the same roles its own controllers demand
// (`@Secured("ADMIN","SECCHAMPION")` becomes `requiredRoles: [ADMIN,
// SECCHAMPION]`), and pushes each principal's actual secman roles. The relay's
// job is to enforce that mapping, not to interpret it. A user therefore sees on
// a phone exactly what they would see in the web UI — never more.
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
const SnapshotSchemaVersion = 2

// ControlSchemaVersion is the envelope version of the control document.
const ControlSchemaVersion = 2

const (
	maxSections          = 64
	maxSectionNameLength = 64
	maxInstanceIDLength  = 128
	maxScopeLength       = 64
	maxScopesPerGrant    = 32
	maxSubjectLength     = 254
	maxRolesPerPrincipal = 32
	maxRoleLength        = 64
	// maxFutureSkew bounds how far ahead of the relay's clock a snapshot may
	// claim to have been generated.
	maxFutureSkew = 5 * time.Minute
)

// SecmanRoles is the role vocabulary secman uses. The relay validates against
// it so a typo in a pushed role becomes a rejected document rather than a
// permission that silently never matches — or, worse, a section that silently
// never becomes visible to anyone.
//
// Keep in step with CLAUDE.md §Roles (RBAC).
var SecmanRoles = map[string]struct{}{
	"USER":            {},
	"ADMIN":           {},
	"VULN":            {},
	"RELEASE_MANAGER": {},
	"REQ":             {},
	"REQADMIN":        {},
	"RISK":            {},
	"SECCHAMPION":     {},
	"REPORT":          {},
}

// Snapshot is the document secman pushes.
type Snapshot struct {
	SchemaVersion int       `json:"schemaVersion"`
	InstanceID    string    `json:"instanceId"`
	GeneratedAt   time.Time `json:"generatedAt"`
	// Sections holds the opaque payloads, keyed by section name.
	Sections map[string]json.RawMessage `json:"sections"`
	// Policy holds the role gate for each section, keyed by the same names.
	// A section with no policy entry is unreadable by anyone: the relay fails
	// closed rather than guessing that "no policy" means "public".
	Policy map[string]SectionPolicy `json:"policy"`
}

// SectionPolicy mirrors the `@Secured` annotation on the secman controller the
// section's data came from.
type SectionPolicy struct {
	// RequiredRoles is an any-of list. A principal holding at least one of
	// these secman roles may read the section.
	RequiredRoles []string `json:"requiredRoles"`
	// Description is operator-facing text explaining what the section holds.
	// Shown in the app's "why can't I see this" affordance and in the relay's
	// device listing; never used in an authorization decision.
	Description string `json:"description,omitempty"`
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
		// Refusing the push is the right failure here rather than silently
		// storing an unreadable section: a section nobody can see looks
		// identical to a broken app, and this way secman finds out immediately.
		policy, ok := s.Policy[name]
		if !ok {
			return fmt.Errorf("section %q has no policy entry; every section must declare requiredRoles", name)
		}
		if err := policy.validate(name); err != nil {
			return err
		}
	}
	for name := range s.Policy {
		if _, ok := s.Sections[name]; !ok {
			return fmt.Errorf("policy names section %q, which is not in the snapshot", name)
		}
	}
	return nil
}

func (p SectionPolicy) validate(section string) error {
	if len(p.RequiredRoles) == 0 {
		return fmt.Errorf("section %q must declare at least one required role", section)
	}
	if len(p.RequiredRoles) > maxRolesPerPrincipal {
		return fmt.Errorf("section %q declares too many roles", section)
	}
	for _, r := range p.RequiredRoles {
		if err := ValidateRole(r); err != nil {
			return fmt.Errorf("section %q: %w", section, err)
		}
	}
	if len(p.Description) > 200 {
		return fmt.Errorf("section %q has an over-long description", section)
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

// RolesAllow reports whether a principal holding `roles` may read a section
// governed by `policy`.
//
// Deny by default in every direction: no policy, no required roles, or no
// overlap all mean no.
func RolesAllow(policy SectionPolicy, roles []string) bool {
	if len(policy.RequiredRoles) == 0 || len(roles) == 0 {
		return false
	}
	for _, required := range policy.RequiredRoles {
		for _, held := range roles {
			if held == required {
				return true
			}
		}
	}
	return false
}

// ValidateRole checks a role against secman's vocabulary.
func ValidateRole(role string) error {
	if role == "" {
		return errors.New("role must not be empty")
	}
	if len(role) > maxRoleLength {
		return errors.New("role is too long")
	}
	if _, ok := SecmanRoles[role]; !ok {
		return fmt.Errorf("unknown secman role %q", sanitizeRole(role))
	}
	return nil
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

// --- control document -------------------------------------------------------

// Control is the second document secman pushes: the authorisation state the
// relay needs in order to enrol devices, authenticate users and revoke both.
//
// Its three parts have deliberately different merge semantics:
//
//   - Principals are **replace-by-push** when PrincipalsAuthoritative is set.
//     Roles must be able to *shrink* — a user demoted in secman must lose the
//     matching sections on their phone — and a union would make that impossible.
//   - Enrollment grants are additive and expire on their own.
//   - Revocations are additive and permanent. A revocation must never be undone
//     by a later push that happens to omit it.
type Control struct {
	SchemaVersion int       `json:"schemaVersion"`
	InstanceID    string    `json:"instanceId"`
	IssuedAt      time.Time `json:"issuedAt"`

	// PrincipalsAuthoritative marks Principals as the complete set. Any
	// principal the relay knows and this document omits is disabled. Requiring
	// the flag stops a partial or truncated push from silently locking
	// everybody out.
	PrincipalsAuthoritative bool        `json:"principalsAuthoritative,omitempty"`
	Principals              []Principal `json:"principals,omitempty"`

	Enrollments []EnrollmentGrant `json:"enrollments,omitempty"`
	Revocations []Revocation      `json:"revocations,omitempty"`
}

// Principal is a secman user as far as the relay is concerned: a stable
// subject, the roles secman says they hold, and the external identities they
// may authenticate with.
//
// The relay never derives roles from anything else. In particular, signing in
// with Apple or GitHub proves *who* someone is; it grants nothing on its own.
// Authorization comes from this list, which only secman writes.
type Principal struct {
	// Subject is the secman username. Stable, and the key for replacement.
	Subject     string `json:"subject"`
	DisplayName string `json:"displayName,omitempty"`
	// Roles are the secman roles this user actually holds.
	Roles []string `json:"roles"`
	// Identities are the external logins that map to this principal.
	Identities []ExternalIdentity `json:"identities,omitempty"`
	// Disabled turns a principal off without removing the record, so the
	// reason survives in the relay's state for an operator to look at.
	Disabled bool `json:"disabled,omitempty"`
}

// ExternalIdentity binds an identity-provider subject to a secman principal.
type ExternalIdentity struct {
	// Provider is "apple" or "github".
	Provider string `json:"provider"`
	// Subject is the provider's stable user identifier: Apple's `sub` claim,
	// or GitHub's numeric account id. Never the email or the login name —
	// both are mutable and both can be re-registered by someone else.
	Subject string `json:"subject"`
	// Label is a human hint ("markus@github"), shown in listings only.
	Label string `json:"label,omitempty"`
}

// Providers the relay accepts in an ExternalIdentity.
const (
	ProviderApple  = "apple"
	ProviderGitHub = "github"
)

// EnrollmentGrant authorises exactly one device enrollment by code.
//
// Only the SHA-256 of the enrollment code travels. The plaintext is shown once
// to the admin in secman and typed into the app; the relay can verify a
// presented code but can never emit one, so a relay compromise does not hand
// the attacker a way to mint new devices.
type EnrollmentGrant struct {
	CodeSHA256 string `json:"codeSha256"`
	// Subject names the principal this code enrols a device for. The device
	// inherits that principal's roles — the grant cannot confer roles itself.
	Subject   string    `json:"subject"`
	Scopes    []string  `json:"scopes"`
	ExpiresAt time.Time `json:"expiresAt"`
	// Label is a human-readable hint. Never a credential; still treated as
	// untrusted text on the way to a log.
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
	if len(c.Principals) > 10_000 {
		return errors.New("too many principals in one control document (max 10000)")
	}
	if len(c.Enrollments) > 256 {
		return errors.New("too many enrollments in one control document (max 256)")
	}
	if len(c.Revocations) > 1024 {
		return errors.New("too many revocations in one control document (max 1024)")
	}
	if c.PrincipalsAuthoritative && len(c.Principals) == 0 {
		// Almost certainly a bug on the pushing side, and the consequence is
		// every device losing access at once. Make it say so out loud.
		return errors.New("principalsAuthoritative is set but no principals were supplied")
	}

	seen := make(map[string]struct{}, len(c.Principals))
	for i := range c.Principals {
		p := c.Principals[i]
		if err := p.validate(); err != nil {
			return fmt.Errorf("principals[%d]: %w", i, err)
		}
		if _, dup := seen[p.Subject]; dup {
			return fmt.Errorf("principals[%d]: duplicate subject", i)
		}
		seen[p.Subject] = struct{}{}
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

func (p Principal) validate() error {
	if err := ValidateSubject(p.Subject); err != nil {
		return err
	}
	if len(p.DisplayName) > maxSubjectLength {
		return errors.New("displayName is too long")
	}
	if len(p.Roles) > maxRolesPerPrincipal {
		return fmt.Errorf("too many roles (max %d)", maxRolesPerPrincipal)
	}
	for _, r := range p.Roles {
		if err := ValidateRole(r); err != nil {
			return err
		}
	}
	if len(p.Identities) > 8 {
		return errors.New("too many external identities (max 8)")
	}
	for _, id := range p.Identities {
		if err := id.validate(); err != nil {
			return err
		}
	}
	return nil
}

func (e ExternalIdentity) validate() error {
	switch e.Provider {
	case ProviderApple, ProviderGitHub:
	default:
		return fmt.Errorf("unknown identity provider %q", sanitizeRole(e.Provider))
	}
	if e.Subject == "" || len(e.Subject) > 255 {
		return errors.New("identity subject has an implausible length")
	}
	for _, r := range e.Subject {
		// Provider subjects are opaque identifiers; both Apple and GitHub use
		// a conservative character set, and anything else here is a red flag.
		ok := (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') ||
			r == '.' || r == '-' || r == '_' || r == '|' || r == '@'
		if !ok {
			return errors.New("identity subject contains an unexpected character")
		}
	}
	if len(e.Label) > 128 {
		return errors.New("identity label is too long")
	}
	return nil
}

// IdentityKey is the registry lookup key for an external identity.
func IdentityKey(provider, subject string) string { return provider + "|" + subject }

// ValidateSubject checks a secman username used as a principal key.
func ValidateSubject(subject string) error {
	if subject == "" {
		return errors.New("subject is required")
	}
	if len(subject) > maxSubjectLength {
		return errors.New("subject is too long")
	}
	for _, r := range subject {
		if r < 0x20 || r == 0x7f {
			return errors.New("subject must not contain control characters")
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
	if err := ValidateSubject(g.Subject); err != nil {
		return err
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

// --- device scopes ----------------------------------------------------------

// ScopeAll grants a device access to every section its principal's roles allow.
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

// ScopeAllows reports whether a device's scopes permit reading a section.
//
// This is the *second* gate, not the only one. A scope can narrow what a device
// may read; it can never widen it beyond what the principal's secman roles
// already allow. See CanRead.
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

// CanRead is the single authorization decision for a section read, and the one
// place both gates are applied:
//
//	role gate   the principal holds a role the section's policy requires —
//	            the same check secman's own controller makes
//	scope gate  the device was granted this section — a per-device narrowing
//
// Both must pass. Calling either one alone is a bug, which is why handlers call
// this rather than the two helpers above.
func CanRead(policy SectionPolicy, roles []string, scopes []string, section string) bool {
	return RolesAllow(policy, roles) && ScopeAllows(scopes, section)
}

// sanitizeRole bounds and de-fangs an untrusted value that is about to appear
// in an error message which may be logged.
func sanitizeRole(v string) string {
	v = strings.NewReplacer("\r", "", "\n", "", "\t", " ").Replace(v)
	if len(v) > 40 {
		return v[:40] + "..."
	}
	return v
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
