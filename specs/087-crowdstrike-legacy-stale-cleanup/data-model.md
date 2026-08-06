# Phase 1 — Data Model & Schema Delta

**Feature**: 087-crowdstrike-legacy-stale-cleanup
**Date**: 2026-05-08
**Spec source**: spec.md FR-001..016, Assumptions section, Clarifications session 2026-05-08
**Research source**: research.md Decisions 4 (DTO nullability), 7 (de-dup), 8 (migration)

This feature has minimal schema impact: two integer columns added to one existing audit table. All other changes are DTO-level (compile-time only) and a new constant.

---

## 1. Database — `crowdstrike_cleanup_run` (modified)

### Existing columns (unchanged)

```text
id                          BIGINT          PK, AUTO_INCREMENT
status                      VARCHAR(30)     NOT NULL  -- enum CrowdStrikeCleanupStatus
triggered_by                VARCHAR(100)    NOT NULL
stale_days                  INT             NOT NULL
cutoff                      DATETIME        NOT NULL
candidate_count             INT             NOT NULL  -- includes both rule A + rule B post-merge
deleted_count               INT             NOT NULL  -- includes both rules
error_count                 INT             NOT NULL
total_crowdstrike_tracked   BIGINT          NOT NULL  -- new semantics: now = countCrowdStrikeTracked + countLegacyCrowdStrikeTotal
started_at                  DATETIME        NOT NULL
completed_at                DATETIME        NULL
duration_ms                 BIGINT          NULL
error_message               VARCHAR(1000)   NULL

-- Indexes
idx_cs_cleanup_started      (started_at)
idx_cs_cleanup_status       (status)
```

### New columns

```text
legacy_candidate_count      INT             NOT NULL    DEFAULT 0
legacy_deleted_count        INT             NOT NULL    DEFAULT 0
```

### Migration: `V210__crowdstrike_cleanup_run_legacy_columns.sql`

```sql
ALTER TABLE crowdstrike_cleanup_run
  ADD COLUMN legacy_candidate_count INT NOT NULL DEFAULT 0,
  ADD COLUMN legacy_deleted_count   INT NOT NULL DEFAULT 0;
```

No data backfill — historical runs (which never used rule B) correctly read `0` for both new columns.

### Semantics

- `candidate_count` and `deleted_count` continue to mean "total across both rules". Existing dashboards that aggregate these stay correct.
- `legacy_candidate_count` ≤ `candidate_count` always. Same for deleted.
- `total_crowdstrike_tracked` now reflects rule A population + rule B population. Existing rows wrote it as rule A only; that is acceptable because old rows never had a meaningful rule B contribution.

---

## 2. Domain entity — `CrowdStrikeCleanupRun` (modified)

File: `src/backendng/src/main/kotlin/com/secman/domain/CrowdStrikeCleanupRun.kt`

Add two fields to the data class:

```kotlin
@Column(name = "legacy_candidate_count", nullable = false)
var legacyCandidateCount: Int = 0,

@Column(name = "legacy_deleted_count", nullable = false)
var legacyDeletedCount: Int = 0,
```

Default values of `0` ensure existing call sites that construct a run without specifying these fields keep compiling and produce zero-contribution audit rows (correct semantics).

---

## 3. Domain entity — `Asset` (unchanged)

No schema or entity changes. The query keys off existing columns:
- `owner` (existing String)
- `crowdstrike_last_imported_at` (existing nullable LocalDateTime — Feature 030)
- `manual_creator_id` (existing nullable FK — manual creation feature)
- `scan_uploader_id` (existing nullable FK — scan upload feature)
- `last_seen`, `updated_at`, `created_at` (existing timestamps)

---

## 4. DTOs — `src/backendng/.../dto/CrowdStrikeAssetCleanupDto.kt` (modified)

### `CrowdStrikeAssetCleanupRequest` — add optional override

```kotlin
@Serdeable
data class CrowdStrikeAssetCleanupRequest(
    val days: Int,
    val dryRun: Boolean = false,
    val includeLegacy: Boolean? = null    // NEW: null = use configured default
)
```

### `CleanupCandidateReason` — new enum

```kotlin
@Serdeable
enum class CleanupCandidateReason {
    TIMESTAMP_STALE,        // Rule A: crowdstrike_last_imported_at < cutoff
    LEGACY_NULL_TIMESTAMP   // Rule B: NULL timestamp + owner literal + no manual/scan + COALESCE stale
}
```

### `CrowdStrikeAssetCleanupCandidateDto` — make timestamp nullable, add reason

```kotlin
@Serdeable
data class CrowdStrikeAssetCleanupCandidateDto(
    val assetId: Long,
    val name: String,
    val crowdStrikeLastImportedAt: LocalDateTime?,   // CHANGED: now nullable; null on legacy candidates
    val reason: CleanupCandidateReason               // NEW
)
```

### `CrowdStrikeAssetCleanupResponse` — add legacy counts

```kotlin
@Serdeable
data class CrowdStrikeAssetCleanupResponse(
    val days: Int,
    val cutoff: LocalDateTime,
    val dryRun: Boolean,
    val candidateCount: Int,
    val deletedCount: Int,
    val skippedCount: Int,
    val candidates: List<CrowdStrikeAssetCleanupCandidateDto>,
    val errors: List<CrowdStrikeAssetCleanupErrorDto>,
    val status: String? = null,
    val runId: Long? = null,
    val legacyCandidateCount: Int = 0,    // NEW
    val legacyDeletedCount: Int = 0       // NEW
)
```

### `CleanupConfigDto` (in `CrowdStrikeCleanupController`) — add `includeLegacy`

```kotlin
@Serdeable
data class CleanupConfigDto(
    val enabled: Boolean,
    val staleDays: Int,
    val maxDeletePercent: Int,
    val cron: String,
    val includeLegacy: Boolean    // NEW: configured default of secman.crowdstrike.cleanup.include-legacy
)
```

---

## 5. New file — `src/backendng/.../constants/AssetOwners.kt`

```kotlin
package com.secman.constants

object AssetOwners {
    /**
     * Canonical owner literal written by CrowdStrike auto-import (Feature 030).
     * Single source of truth — referenced by the import service (writer), the
     * controller's owner-candidates dropdown, and the legacy stale-cleanup
     * predicate. See spec FR-014.
     */
    const val CROWDSTRIKE_IMPORT = "CrowdStrike Import"
}
```

---

## 6. Repository delta — `AssetRepository`

Two new query methods:

```kotlin
@io.micronaut.data.annotation.Query("""
    SELECT a FROM Asset a
    WHERE a.owner = :ownerLiteral
      AND a.crowdStrikeLastImportedAt IS NULL
      AND a.manualCreator IS NULL
      AND a.scanUploader IS NULL
      AND COALESCE(a.lastSeen, a.updatedAt, a.createdAt) < :cutoff
""")
fun findLegacyCrowdStrikeStale(ownerLiteral: String, cutoff: LocalDateTime): List<Asset>

@io.micronaut.data.annotation.Query("""
    SELECT COUNT(a) FROM Asset a
    WHERE a.owner = :ownerLiteral
      AND a.crowdStrikeLastImportedAt IS NULL
      AND a.manualCreator IS NULL
      AND a.scanUploader IS NULL
""")
fun countLegacyCrowdStrikeTotal(ownerLiteral: String): Long
```

The owner literal is bound by the service layer with `AssetOwners.CROWDSTRIKE_IMPORT`. The repository does not depend on the constants package — it only sees the parameter.

---

## 7. State transitions

No new state transitions. The cleanup run lifecycle (`SUCCESS | PARTIAL | ABORTED_SAFETY_BRAKE | FAILED`) is unchanged. The new columns are populated by the service immediately before persisting the run row, regardless of terminal status.

---

## 8. Validation rules

- `legacy_candidate_count >= 0` — enforced at column level (`INT NOT NULL DEFAULT 0`) and at Kotlin field level (default `0`).
- `legacy_deleted_count <= legacy_candidate_count` — invariant enforced by service logic, not at the DB level (matches the existing `deleted_count <= candidate_count` invariant which is also service-enforced).
- `legacy_candidate_count <= candidate_count` — invariant enforced by service logic.
- `crowdStrikeLastImportedAt` on candidate DTO is `null` if and only if `reason == LEGACY_NULL_TIMESTAMP` — invariant enforced by the service when constructing the DTO.
