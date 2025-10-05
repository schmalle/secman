# Research: Workgroup-Based Access Control

**Feature**: 008-create-an-additional
**Date**: 2025-10-04
**Status**: Complete

## Research Questions & Decisions

### 1. JPA Many-to-Many Relationship Patterns

**Question**: How should we implement ManyToMany relationships between User/Asset and Workgroup?

**Research Findings**:
Examined existing ManyToMany patterns in codebase:
- `Requirement` ↔ `UseCase` (requirement_usecase join table)
- `Requirement` ↔ `Norm` (requirement_norm join table)

Pattern used:
```kotlin
@ManyToMany(fetch = FetchType.EAGER)
@JoinTable(
    name = "requirement_norm",
    joinColumns = [JoinColumn(name = "requirement_id")],
    inverseJoinColumns = [JoinColumn(name = "norm_id")]
)
var norms: MutableSet<Norm> = mutableSetOf()
```

**Decision**: Use explicit `@JoinTable` annotations with descriptive names
- `user_workgroups`: join table for User ↔ Workgroup
- `asset_workgroups`: join table for Asset ↔ Workgroup
- Use `MutableSet` for collections (prevents duplicates)
- Use `FetchType.EAGER` for workgroups (small collection, frequently accessed for filtering)
- No cascade operations (workgroup deletion handled explicitly)

**Rationale**:
- Consistency with existing codebase patterns
- EAGER fetch justified: workgroup membership needed for every access control check
- Explicit join table names aid database inspection and troubleshooting
- Sets prevent duplicate assignments

**Alternatives Considered**:
- LAZY fetch: Rejected - would cause N+1 queries during filtering operations
- List instead of Set: Rejected - allows duplicates, no business need for ordering

---

### 2. Dual Ownership Foreign Keys

**Question**: How to implement dual ownership (manual creator + scan uploader) for Asset?

**Research Findings**:
Examined existing FK patterns in codebase:
- `ScanResult.asset`: ManyToOne with nullable=true by default
- `Vulnerability.asset`: ManyToOne with nullable=false
- `Asset.scanResults`: OneToMany with orphanRemoval=true

**Decision**: Add two nullable FK columns to Asset entity
```kotlin
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "manual_creator_id", nullable = true)
var manualCreator: User? = null

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "scan_uploader_id", nullable = true)
var scanUploader: User? = null
```

**Rationale**:
- Nullable FKs allow user deletion without cascade (FR-027: ownership becomes null but assets persist)
- LAZY fetch: creator objects not needed in most asset list operations
- Separate columns enable independent tracking of both creation paths
- Join columns explicitly named for clarity in database

**Alternatives Considered**:
- Single "owner" FK with type enum: Rejected - spec requires tracking BOTH creators simultaneously
- NOT NULL with sentinel "deleted user": Rejected - violates referential integrity principle, adds complexity
- CASCADE DELETE: Rejected - contradicts FR-027 requirement that assets persist after user deletion

---

### 3. Workgroup-Aware Filtering Strategy

**Question**: Where should workgroup filtering logic be implemented - repository or service layer?

**Research Findings**:
Examined existing filtering patterns:
- `AssetRepository.findByGroupsContaining(group: String)`: Simple method naming
- `VulnerabilityRepository.findByCvssSeverityIn(severities: List<String>, pageable: Pageable)`: Multi-value filtering
- No custom `@Query` annotations found in repositories - relies on Micronaut Data method naming

Current access control pattern:
- `VulnRoleAuthorizationTest`: Authorization checks in controller layer
- No centralized filtering service found

**Decision**: Hybrid approach
1. **Repository Layer**: Add workgroup-aware query methods using Micronaut Data method naming
   ```kotlin
   // AssetRepository
   fun findByWorkgroupsIdIn(workgroupIds: List<Long>): List<Asset>
   fun findByWorkgroupsIdInOrManualCreatorIdOrScanUploaderId(
       workgroupIds: List<Long>,
       manualCreatorId: Long?,
       scanUploaderId: Long?
   ): List<Asset>
   ```

2. **Service Layer**: Create `AssetFilterService` for centralized filtering logic
   - Determines user's accessible workgroup IDs
   - Handles ADMIN bypass (all access)
   - Handles VULN/USER role filtering
   - Combines workgroup access + ownership access (FR-016)

**Rationale**:
- Repository methods leverage Micronaut Data's query derivation (type-safe, no SQL strings)
- Service layer centralizes complex access control logic (DRY principle)
- Separation of concerns: repository=data access, service=business rules
- Reusable across Asset/Vulnerability/Scan controllers

**Alternatives Considered**:
- Controller-level filtering: Rejected - duplicates logic across 3+ controllers
- Custom `@Query` with JPQL: Rejected - method naming handles this complexity, type-safe
- Aspect-Oriented Programming (AOP) filter: Rejected - adds complexity, harder to test, no existing AOP patterns in codebase

---

### 4. Case-Insensitive Uniqueness Constraints

**Question**: How to enforce case-insensitive unique workgroup names in MariaDB?

**Research Findings**:
Examined existing uniqueness patterns:
- `User.username`: `@Column(unique = true)` - case-sensitive uniqueness
- `User.email`: `@Column(unique = true)` - case-sensitive uniqueness
- No examples of case-insensitive uniqueness in domain layer

MariaDB options:
1. Column-level collation: `VARCHAR(100) COLLATE utf8mb4_unicode_ci UNIQUE`
2. Functional index: `CREATE UNIQUE INDEX idx_workgroup_name_lower ON workgroup(LOWER(name))`
3. Application-level validation before save

**Decision**: Application-level validation in `WorkgroupService`
```kotlin
fun createWorkgroup(name: String, description: String?): Workgroup {
    if (workgroupRepository.existsByNameIgnoreCase(name)) {
        throw DuplicateWorkgroupException("Workgroup name already exists (case-insensitive): $name")
    }
    // ... create workgroup
}
```

Repository method:
```kotlin
// WorkgroupRepository
fun existsByNameIgnoreCase(name: String): Boolean
fun findByNameIgnoreCase(name: String): Optional<Workgroup>
```

**Rationale**:
- Micronaut Data supports `IgnoreCase` suffix in method names (no custom SQL needed)
- Application-level check provides clear error messages (better UX)
- Avoids database-specific collation/index syntax (more portable)
- Consistent with Hibernate auto-migration approach (constitution principle VI)
- Transaction-safe: check + insert in same `@Transactional` method

**Alternatives Considered**:
- Database collation: Rejected - requires manual migration, MariaDB-specific, breaks Hibernate auto-create
- Functional index: Rejected - same reasons as collation, plus index management complexity
- Unique constraint on LOWER(): Rejected - not supported by Hibernate auto-create, breaks constitution principle IV

**Risk Mitigation**:
- Race condition: Unlikely in admin-only operations (low concurrency)
- If needed: Add pessimistic lock via `@Lock(LockModeType.PESSIMISTIC_WRITE)` in repository

---

## Summary of Decisions

| Topic | Decision | Key Files Affected |
|-------|----------|-------------------|
| **ManyToMany Mapping** | Explicit `@JoinTable` with EAGER fetch | `Workgroup.kt`, `User.kt`, `Asset.kt` |
| **Dual Ownership** | Two nullable FK columns, LAZY fetch | `Asset.kt`, migration auto-creates columns |
| **Filtering Strategy** | Repository methods + `AssetFilterService` | `AssetRepository.kt`, `AssetFilterService.kt` |
| **Uniqueness** | Application-level validation via `IgnoreCase` methods | `WorkgroupService.kt`, `WorkgroupRepository.kt` |

---

## Implementation Readiness

✅ All research questions resolved
✅ Decisions align with constitutional principles (TDD, API-First, Schema Evolution)
✅ No blocking unknowns remaining
✅ Ready to proceed to Phase 1 (Design & Contracts)
