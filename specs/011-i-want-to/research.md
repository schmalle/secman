# Research: Release-Based Requirement Version Management

**Feature**: 011-i-want-to
**Date**: 2025-10-05
**Status**: Complete

## Research Summary

All technical decisions are resolved based on existing secman architecture and clarification session outcomes. No blocking unknowns remain.

## Decision 1: Snapshot Storage Mechanism

**Decision**: Create RequirementSnapshot entity as separate table with full requirement data denormalization

**Rationale**:
- **Immutability**: Copying all requirement fields at freeze time ensures snapshots cannot be altered by future requirement updates
- **Query Performance**: Denormalized data enables fast retrieval of historical requirement sets without complex joins across versioned tables
- **Simplicity**: Clear separation between current (Requirement table) and historical (RequirementSnapshot table) avoids temporal query complexity
- **Existing Pattern**: Requirement already extends VersionedEntity, designed for exactly this versioning use case

**Alternatives Considered**:
1. **Temporal Database Pattern** (Bitemporal tables with valid_from/valid_to)
   - Rejected: Adds significant query complexity for minimal benefit at current scale
   - Would require rewriting all requirement queries to filter by validity period
   - PostgreSQL has native support, but secman uses MariaDB

2. **Event Sourcing with Snapshots**
   - Rejected: Overkill for requirements that change infrequently (not event-driven domain)
   - Would require event store, replay logic, snapshot optimization
   - Adds architectural complexity without clear ROI

3. **Soft Deletes with Version Flags on Requirement Table**
   - Rejected: VersionedEntity.isCurrent already provides this, but mixing current and historical in same table hurts query performance
   - Indexes would need careful tuning to avoid full table scans on "current only" queries
   - Deletion logic becomes complex (soft delete vs version archive)

**Implementation Notes**:
- RequirementSnapshot mirrors all Requirement columns
- Foreign key to Release (ON DELETE CASCADE removes snapshots when release deleted)
- Foreign key to original Requirement for traceability (nullable - requirement may be deleted later)
- JSON columns for relationship snapshots (usecaseIds, normIds arrays)
- Indexes on release_id and original_requirement_id for fast lookups

## Decision 2: Semantic Versioning Enforcement

**Decision**: Enforce MAJOR.MINOR.PATCH format via regex `^\d+\.\d+\.\d+$`

**Rationale**:
- **Industry Standard**: SemVer is universally recognized, sortable, and communicates change impact
- **Validation Simplicity**: Regex validation is straightforward, no external dependencies
- **User Familiarity**: Developers and compliance managers understand 1.0.0 → 1.1.0 → 2.0.0 progression
- **Sorting**: Lexicographic sort works for same-length version strings (1.0.0 < 1.0.1 < 1.1.0)
- **Clarification**: User explicitly requested semantic versioning enforcement in clarification session

**Alternatives Considered**:
1. **Free-Form Text Versions** ("Q4-2024", "Winter Release", "Compliance-Rev-A")
   - Rejected per user clarification - enforced semantic versioning chosen
   - Would require custom sorting logic
   - Ambiguous progression (what comes after "Q4-2024"?)

2. **Calendar Versioning (CalVer)** (YYYY.MM.DD or YYYY.MM)
   - Rejected: Loses semantic meaning (is 2024.10 a major or minor change?)
   - Users prefer version semantics over temporal ordering
   - Doesn't align with existing software versioning in ecosystem

3. **Auto-Incrementing Integers** (1, 2, 3)
   - Rejected: No semantic meaning - can't communicate breaking vs non-breaking changes
   - Doesn't match industry conventions for versioned artifacts

**Implementation Notes**:
- Backend validation in ReleaseService before persistence
- Frontend validation in ReleaseManagement.tsx form (prevent bad submissions)
- Error message template: "Version must follow semantic versioning format (MAJOR.MINOR.PATCH, e.g., 1.0.0)"
- Uniqueness enforced at database level (unique constraint on Release.version)

## Decision 3: RELEASE_MANAGER Role Introduction

**Decision**: Add RELEASE_MANAGER to existing RBAC system alongside ADMIN and USER roles

**Rationale**:
- **Principle of Least Privilege**: Compliance managers need release creation/deletion without full system admin access
- **Separation of Concerns**: Release management is distinct from user management, system config, etc.
- **Existing Pattern**: secman already has multi-role RBAC (USER, ADMIN, VULN); adding RELEASE_MANAGER follows established architecture
- **Clarification**: User specified ADMIN + RELEASE_MANAGER permission model in clarification session

**Alternatives Considered**:
1. **ADMIN-Only Access**
   - Rejected per user clarification - too restrictive
   - Forces organizations to grant full admin to compliance managers
   - Violates least privilege principle

2. **USER-Level Access** (all authenticated users can create releases)
   - Rejected: Too permissive - version control should be restricted role
   - Could lead to release spam, unauthorized historical snapshots
   - Audit trail becomes cluttered

3. **Fine-Grained Permission Flags** (can_create_release, can_delete_release, can_publish_release)
   - Rejected: Adds complexity without clear benefit at current feature scale
   - Role-based model is simpler to reason about and explain to users
   - If fine-grained needs emerge, can migrate roles → permissions later

**Implementation Notes**:
- Add "RELEASE_MANAGER" to User.roles enum (ElementCollection in JPA)
- Controller authorization: `@Secured("ADMIN", "RELEASE_MANAGER")` on create/delete endpoints
- UI role checks: Show release creation button if user has ADMIN || RELEASE_MANAGER
- ADMIN implicitly has all permissions (existing pattern)

## Decision 4: Export API Integration Strategy

**Decision**: Extend existing export endpoints with optional `releaseId` query parameter

**Rationale**:
- **Backward Compatibility**: Existing export calls (no releaseId) default to current requirements - no breaking changes
- **Single Responsibility**: One endpoint handles both current and historical exports - no code duplication
- **RESTful Design**: Query parameter is standard way to filter/scope GET operations
- **Discoverability**: API documentation clearly shows optional parameter with description

**Alternatives Considered**:
1. **Separate Historical Export Endpoints** (/api/requirements/export/xlsx/historical?releaseId=X)
   - Rejected: Code duplication between current and historical export logic
   - Would require two sets of tests, two code paths for same fundamental operation
   - URL structure becomes unwieldy

2. **Version in URL Path** (/api/requirements/export/xlsx/release/1)
   - Rejected: Less RESTful - releaseId is a filter, not a resource identifier
   - Breaks backward compatibility - existing /api/requirements/export/xlsx URLs fail
   - Harder to make optional (would need default path route)

3. **Header-Based Release Selection** (X-Release-Id: 123 header)
   - Rejected: Less discoverable than query parameter
   - Not standard HTTP practice for filtering
   - Harder to test (need custom headers in all tools)

**Implementation Notes**:
- Add `@Nullable Long releaseId` parameter to export methods in RequirementController
- Service layer: if releaseId present → fetch from RequirementSnapshotRepository, else → fetch from RequirementRepository
- Filename generation: Include release version in filename when exporting historical (e.g., "requirements_v1.0.0_2025-10-05.xlsx")
- Export metadata: Add release info (version, name, date) to document header when exporting from release

## Decision 5: Comparison Algorithm

**Decision**: Field-level diff with categorization (added/deleted/modified) using standard diff algorithm

**Rationale**:
- **Comprehensive**: Shows exactly what changed between releases, not just "something is different"
- **Actionable**: Users can see specific field changes (shortreq, details, motivation, etc.)
- **Standard Approach**: Follows Git-style diff model familiar to technical users
- **Performance**: O(n*m) for n and m requirements in each release - acceptable for thousands of requirements

**Alternatives Considered**:
1. **Hash-Based Comparison** (compare requirement hashes to detect changes)
   - Rejected: Tells you *that* requirements changed, not *what* changed
   - Requires second query to fetch full requirement data for changed items
   - Less useful for compliance audits (need to know what fields changed)

2. **Binary Changed/Unchanged Flag**
   - Rejected: Too coarse-grained - doesn't show what actually changed
   - User explicitly requested field-level changes in clarification (FR-024c)

3. **External Diff Library** (java-diff-utils, diff-match-patch)
   - Considered but not necessary: Requirement fields are structured data, not free text
   - Custom diff logic is simpler: compare each field, record changes
   - Can add library later if text field diffing becomes complex

**Implementation Notes**:
- ComparisonResult DTO with: added (List<RequirementSnapshot>), deleted (List<RequirementSnapshot>), modified (List<RequirementDiff>), unchanged (count)
- RequirementDiff contains: requirement ID, shortreq (for display), changes (List<FieldChange>)
- FieldChange: fieldName (String), oldValue (String?), newValue (String?)
- Algorithm: 1) Build Map<originalRequirementId, Snapshot> for each release, 2) Iterate first release to find deleted, 3) Iterate second release to find added/modified
- UI displays color-coded: Green (added), Red (deleted), Yellow (modified with field-level detail expansion)

## Performance Considerations

**Snapshot Creation**:
- Bulk insert of snapshots during release creation (single transaction)
- Expected: ~1000 requirements → 1000 snapshot inserts in <1 second
- Uses PreparedStatement batch inserts via Hibernate

**Export Performance**:
- Historical export: Query RequirementSnapshot by release_id (indexed) - fast
- Current export: Existing query performance maintained (no regression)
- Expected: <200ms for release selection, <2s for export generation (within constitutional limits)

**Comparison Performance**:
- Two snapshot queries (indexed on release_id) + in-memory diff
- Expected: <500ms for 1000 vs 1000 requirement comparison
- Pagination not needed for current scale (future: add limit/offset if >10k requirements)

## Security Considerations

**Input Validation**:
- Version format: Regex validated before DB insertion
- Release name/description: Max length enforced (100/1000 chars), HTML sanitized
- Release ID in queries: Parameterized queries prevent SQL injection

**Authorization**:
- Release creation/deletion: @Secured("ADMIN", "RELEASE_MANAGER")
- Release viewing/export: @Secured(SecurityRule.IS_AUTHENTICATED)
- Comparison: @Secured(SecurityRule.IS_AUTHENTICATED)
- UI permission checks: Only show management UI if user has appropriate role

**Audit Trail**:
- Release.createdBy tracks who created release
- Release.createdAt tracks when created
- Deletion events logged (existing audit log infrastructure)

## Database Schema Impact

**New Tables**:
- `requirement_snapshot` (full requirement denormalization per release)

**Table Modifications**:
- User: Add RELEASE_MANAGER to roles enum (no schema change - ElementCollection)

**Indexes**:
- requirement_snapshot: (release_id), (original_requirement_id)
- Release.version already unique constraint

**Migration Strategy**:
- Hibernate auto-creates requirement_snapshot table on first deployment
- No data migration needed (no existing release snapshots)
- Backward compatible: New table, no modifications to existing tables

## Testing Strategy

**Unit Tests**:
- ReleaseService.createRelease() → verify all requirements frozen
- ReleaseService.deleteRelease() → verify snapshots cascade deleted
- RequirementComparisonService.compare() → verify diff algorithm correctness

**Contract Tests**:
- Each API endpoint → request/response schema validation
- Authorization rules → 403 for unauthorized, 200 for authorized

**Integration Tests**:
- End-to-end release creation → snapshot → export workflow
- Requirement update after release → snapshot unchanged
- Deletion prevention → error when requirement in release

**E2E Tests**:
- Create release via UI → verify snapshots in database
- Export with release selector → verify historical data in file
- Compare releases → verify UI shows correct diffs

## Research Artifacts

**Generated**:
- ✅ Decision documentation (this file)
- ✅ Alternatives analysis with rejection rationale
- ✅ Performance estimates
- ✅ Security considerations
- ✅ Database schema impact

**Outstanding**:
- ✅ No NEEDS CLARIFICATION remain
- ✅ All technical unknowns resolved
- ✅ Ready for Phase 1: Design & Contracts

---
*Research complete. All decisions documented with rationale. Ready to proceed to design phase.*
