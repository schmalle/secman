# Implementation Plan: CrowdStrike Legacy Stale-Asset Cleanup

**Branch**: `087-crowdstrike-legacy-stale-cleanup` | **Date**: 2026-05-08 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/087-crowdstrike-legacy-stale-cleanup/spec.md`

## Summary

Extend the CrowdStrike stale-asset cleanup pipeline to find a second, additive class of cleanup candidate: legacy CrowdStrike-imported assets that pre-date the `crowdstrike_last_imported_at` column. The new "rule B" identifies rows where `owner = "CrowdStrike Import"` AND `crowdstrike_last_imported_at IS NULL` AND `manualCreator IS NULL` AND `scanUploader IS NULL` AND `COALESCE(lastSeen, updatedAt, createdAt) < cutoff`. The rule is gated behind a new flag `secman.crowdstrike.cleanup.include-legacy` (default `false` for the first shipping release) and is exposed through the existing manual API and scheduled job. Each cleanup candidate now carries a `reason` enum so dry-run output and audit history attribute deletions to the correct rule. The audit-row schema gains two integer columns (`legacy_candidate_count`, `legacy_deleted_count`) populated for every non-dry-run, and the safety-brake denominator widens to include the legacy population so percentage calculations stay meaningful. The admin Falcon-config page exposes a per-run override toggle and renders the candidate split in dry-run summaries plus a legacy-vs-timestamp column in the run-history table. Rule A remains untouched.

## Technical Context

**Language/Version**: Kotlin 2.3.21 / Java 21 (backend); TypeScript with Astro 6.2 + React 19 islands (frontend).
**Primary Dependencies**: Micronaut 4.10 (DI, scheduling, security), Hibernate JPA, Micronaut Data, Flyway, Apache POI 5.3 (unused for this feature), Bootstrap 5.3, Axios.
**Storage**: MariaDB 11.4 in production; H2 in-memory for unit tests; Testcontainers MariaDB 11.4 for integration tests. Both H2 and MariaDB support `COALESCE` in JPQL.
**Testing**: JUnit 6, Mockk, AssertJ, `@MicronautTest`, Testcontainers (`BaseIntegrationTest` helper). User has explicitly requested unit + integration tests for this feature (see spec FR-001..016 mapped to acceptance scenarios), so test prep is in scope under Constitution Principle IV.
**Target Platform**: Linux server (backend), modern evergreen browsers (frontend admin page only — no public-facing surface).
**Project Type**: Web application — backend (`src/backendng`) + frontend (`src/frontend`) + CLI (`src/cli`, untouched by this feature).
**Performance Goals**: Cleanup is a low-frequency operation (daily 02:30 cron, occasional manual). Target: complete one cleanup run on a database with ≤200,000 assets in <60s. No new index required for v1; legacy rows are by definition a one-time backlog that drains to zero. Add an index later only if observed latency exceeds the budget.
**Constraints**: Backward-compatible API only — request/response shapes gain optional fields; no removed/renamed fields. Rule A's existing query MUST stay byte-identical (spec FR-004). Manual cleanup runs continue to bypass the safety brake (spec edge case + SC-004).
**Scale/Scope**: Production database has ≤5,000 timestamped CrowdStrike assets and ≤2,000 legacy CrowdStrike rows estimated. Scheduled run touches both populations; manual run typically targets a smaller filtered cutoff.

## Constitution Check

*Gate evaluated against `.specify/memory/constitution.md` v2.0.0. Re-evaluated post-Phase-1 below.*

| Principle | Status | Evidence |
|---|---|---|
| **I. Security-First** | ✅ Pass | No new external surface. Reuses existing `@Secured("ADMIN")` on `/api/crowdstrike/cleanup/*` and `/api/assets/delete-not-seen-by-crowdstrike`. New `includeLegacy` request field accepts only Boolean; no injection vector. No sensitive data added to logs. JWT continues in sessionStorage (no change). Security review will run before merge per the manual gate. |
| **III. API-First** | ⚠️ Inherited gap | All deltas are additive: response gains `includeLegacy` (config endpoint), `legacyCandidateCount`/`legacyDeletedCount` (response & history items), candidate `reason` enum. Request gains optional nullable `includeLegacy`. Existing clients ignore unknown fields. Status codes unchanged. Backward compatible within major version. **OpenAPI/Swagger note**: this repository does NOT currently maintain an OpenAPI/Swagger spec — no `io.micronaut.openapi` plugin in `src/backendng/build.gradle.kts`, no static spec under `docs/` or `src/backendng/src/main/resources/`. Constitution Principle III's mandate is unmet codebase-wide, not by this feature. Feature 087 does NOT introduce OpenAPI infrastructure — that scope belongs to a separate constitution-compliance initiative. The HTTP contracts in `contracts/*.md` document the JSON shapes for Feature 087 in the interim. See research.md Decision 11. |
| **IV. User-Requested Testing** | ✅ Pass | User's input explicitly enumerates required unit and integration tests. Therefore test tasks ARE in scope and will be marked as user-requested in `tasks.md`. Following TDD per Principle (write tests before implementation where mechanically possible). |
| **V. RBAC** | ✅ Pass | All endpoints touched are already `@Secured("ADMIN")` at the controller. No new endpoints. Frontend admin Falcon-config page already gated to admins. No workgroup-filtered data is added or removed. |
| **VI. Schema Evolution** | ✅ Pass | Two new columns on `crowdstrike_cleanup_run` (`legacy_candidate_count INT NOT NULL DEFAULT 0`, `legacy_deleted_count INT NOT NULL DEFAULT 0`) shipped via Flyway migration `V210__crowdstrike_cleanup_run_legacy_columns.sql` and matching Hibernate entity fields with default values. No data loss. Indexes unchanged. CLAUDE.md updated to reference the new flag. Documentation in `docs/ENVIRONMENT.md` updated for the new env var. |

**Gate result**: All five principles pass. No "Complexity Tracking" entries required.

## Project Structure

### Documentation (this feature)

```text
specs/087-crowdstrike-legacy-stale-cleanup/
├── plan.md              # This file
├── research.md          # Phase 0 — locked decisions and alternatives considered
├── data-model.md        # Phase 1 — entity/DTO deltas and migration
├── quickstart.md        # Phase 1 — manual verification recipe
├── contracts/           # Phase 1 — affected HTTP endpoints with request/response shapes
│   ├── GET-cleanup-config.md
│   ├── POST-cleanup-manual.md
│   └── GET-cleanup-runs.md
├── checklists/
│   └── requirements.md  # Already produced by /speckit.specify
└── tasks.md             # Phase 2 — produced by /speckit.tasks (NOT this command)
```

### Source Code (repository root)

The repo is an established multi-module web application; no new top-level directories.

```text
src/
├── backendng/                                                # Kotlin / Micronaut
│   └── src/main/
│       ├── kotlin/com/secman/
│       │   ├── constants/
│       │   │   └── AssetOwners.kt                            # NEW — single shared "CrowdStrike Import" constant
│       │   ├── domain/
│       │   │   └── CrowdStrikeCleanupRun.kt                  # MODIFY — add legacyCandidateCount, legacyDeletedCount
│       │   ├── dto/
│       │   │   └── CrowdStrikeAssetCleanupDto.kt             # MODIFY — request gets includeLegacy?, response gets legacy counts, candidate gets reason + nullable timestamp
│       │   ├── repository/
│       │   │   └── AssetRepository.kt                        # MODIFY — add findLegacyCrowdStrikeStale, countLegacyCrowdStrikeTotal
│       │   ├── service/
│       │   │   ├── CrowdStrikeAssetCleanupService.kt         # MODIFY — combine timestamp + legacy candidates, tag reason, gate on includeLegacy
│       │   │   ├── CrowdStrikeCleanupAuditService.kt         # MODIFY — widen brake denominator, persist legacy counts
│       │   │   └── CrowdStrikeVulnerabilityImportService.kt  # MODIFY — reference AssetOwners.CROWDSTRIKE_IMPORT instead of literal
│       │   ├── controller/
│       │   │   ├── CrowdStrikeCleanupController.kt           # MODIFY — getConfig response gains includeLegacy
│       │   │   ├── AssetController.kt                        # MODIFY — accept request includeLegacy override; reference AssetOwners constant
│       │   │   └── (no new controllers)
│       │   └── scheduler/
│       │       └── CrowdStrikeStaleAssetCleanupScheduler.kt  # NO CHANGE — reads new flag transparently via auditService
│       ├── resources/
│       │   ├── application.yml                               # MODIFY — add secman.crowdstrike.cleanup.include-legacy: false
│       │   └── db/migration/
│       │       └── V210__crowdstrike_cleanup_run_legacy_columns.sql  # NEW
│       └── test/kotlin/com/secman/
│           ├── service/
│           │   ├── CrowdStrikeAssetCleanupServiceTest.kt              # MODIFY — add 5 unit tests per spec FR-001..006, FR-014, FR-015
│           │   └── CrowdStrikeCleanupAuditServiceIntegrationTest.kt   # MODIFY — add brake-denominator + audit-row tests
│           └── repository/
│               └── AssetRepositoryLegacyStaleTest.kt          # NEW (small) — direct query coverage for findLegacyCrowdStrikeStale
└── frontend/
    └── src/
        ├── pages/admin/falcon-config.astro                   # MODIFY — pass includeLegacy default to React island
        └── components/admin/StaleAssetCleanup.tsx            # MODIFY — toggle, dry-run split, history legacy column

docs/
└── ENVIRONMENT.md                                            # MODIFY — document CROWDSTRIKE_CLEANUP_INCLUDE_LEGACY

CLAUDE.md                                                     # MODIFY — note the new flag and Feature 087 reference
```

**Structure Decision**: Web-application layout (Option 2) is already in place. This feature touches three modules (backend, frontend, docs); the CLI module (`src/cli`) is unaffected. No new top-level directories. The single new package `com.secman.constants` is added to host `AssetOwners` (FR-014's "single shared constant" requirement). One new Flyway migration `V210` follows the existing numbering and naming convention. The frontend changes are scoped to a single admin page and its React island — no new routes or layouts.

## Complexity Tracking

> **Empty — no constitutional violations require justification.**

The closest near-violation worth noting: introducing a tiny new `constants` package for a single Kotlin `object` is mild over-engineering for a 1-line constant. The justification (FR-014) is correctness, not speculative reuse — without a single source of truth, rule B's predicate WILL drift from the row-creation literal during future refactors and the rule will silently stop matching. That class of bug is observable only in production. The constants package is the smallest mechanism that prevents it.
