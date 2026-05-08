---

description: "Task list for Feature 087 — CrowdStrike Legacy Stale-Asset Cleanup"
---

# Tasks: CrowdStrike Legacy Stale-Asset Cleanup

**Input**: Design documents from `/specs/087-crowdstrike-legacy-stale-cleanup/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: User explicitly requested unit + integration tests in the originating brief (see spec FR-001..016 mapped to acceptance scenarios). Per Constitution Principle IV, tests are therefore in scope. Tests use TDD — write them first and confirm they FAIL before implementing.

**Organization**: Tasks are grouped by user story (US1 P1, US2 P1, US3 P2 from spec.md). Setup and Foundational phases unblock all stories. Polish phase covers mandatory project gates.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Maps task to a user story (US1, US2, US3) — required only on user-story phases
- All file paths are absolute or relative to the repo root

## Path Conventions

- Backend Kotlin: `src/backendng/src/main/kotlin/com/secman/...`
- Backend tests: `src/backendng/src/test/kotlin/com/secman/...`
- Backend resources: `src/backendng/src/main/resources/`
- Frontend: `src/frontend/src/...`
- Docs: `docs/`, `CLAUDE.md`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Wire the new flag into the configuration surface and the schema layer so all downstream tasks can compile/run.

- [ ] T001 Create Flyway migration `src/backendng/src/main/resources/db/migration/V210__crowdstrike_cleanup_run_legacy_columns.sql` with body: `ALTER TABLE crowdstrike_cleanup_run ADD COLUMN legacy_candidate_count INT NOT NULL DEFAULT 0, ADD COLUMN legacy_deleted_count INT NOT NULL DEFAULT 0;`
- [ ] T002 Add `secman.crowdstrike.cleanup.include-legacy: false` (under the existing `secman.crowdstrike.cleanup` block) in `src/backendng/src/main/resources/application.yml`
- [ ] T003 [P] Document `CROWDSTRIKE_CLEANUP_INCLUDE_LEGACY` (default `false`) in the existing CrowdStrike section of `docs/ENVIRONMENT.md` — describe the toggle, the rule-B fence, and that it gates both the scheduled job and the configured default of the manual-run override

**Checkpoint**: Schema migration ready; backend can pick up the new flag on startup.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Establish the shared constant, DTO shape changes, and entity field additions that every user story depends on. **No user-story work begins until this phase is complete.**

**⚠️ CRITICAL**: After T007 lands, the existing `CrowdStrikeAssetCleanupService` will not compile because it constructs `CrowdStrikeAssetCleanupCandidateDto` without a `reason` field — that breakage is intentionally fixed inside US1 (T012). Plan to land Phase 2 + US1 in one merge cycle, or temporarily keep the service compiling by passing `reason = CleanupCandidateReason.TIMESTAMP_STALE` as part of T007.

- [ ] T004 Create `src/backendng/src/main/kotlin/com/secman/constants/AssetOwners.kt` with a top-level `object AssetOwners { const val CROWDSTRIKE_IMPORT = "CrowdStrike Import" }` (FR-014 single-source-of-truth)
- [ ] T005 [P] Refactor `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt` line 376 to reference `AssetOwners.CROWDSTRIKE_IMPORT` instead of the literal `"CrowdStrike Import"` (depends on T004)
- [ ] T006 [P] Refactor `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt` line 202 (`getOwnerCandidates`) to reference `AssetOwners.CROWDSTRIKE_IMPORT` instead of the literal (depends on T004)
- [ ] T007 Mutate DTOs in `src/backendng/src/main/kotlin/com/secman/dto/CrowdStrikeAssetCleanupDto.kt`: (a) add `enum class CleanupCandidateReason { TIMESTAMP_STALE, LEGACY_NULL_TIMESTAMP }` with `@Serdeable`; (b) `CrowdStrikeAssetCleanupRequest` gains `val includeLegacy: Boolean? = null`; (c) `CrowdStrikeAssetCleanupCandidateDto` field `crowdStrikeLastImportedAt` becomes `LocalDateTime?` (nullable) and gains `val reason: CleanupCandidateReason`; (d) `CrowdStrikeAssetCleanupResponse` gains `val legacyCandidateCount: Int = 0, val legacyDeletedCount: Int = 0`. Update existing call site at `CrowdStrikeAssetCleanupService.kt:37-41` to pass `reason = CleanupCandidateReason.TIMESTAMP_STALE` so the project keeps compiling
- [ ] T008 Add fields to `src/backendng/src/main/kotlin/com/secman/domain/CrowdStrikeCleanupRun.kt`: `@Column(name = "legacy_candidate_count", nullable = false) var legacyCandidateCount: Int = 0` and `@Column(name = "legacy_deleted_count", nullable = false) var legacyDeletedCount: Int = 0` — placed after `errorCount` to mirror the SQL column order

**Checkpoint**: Codebase compiles. Existing tests still pass (rule A unchanged). Foundation ready for parallel user-story work.

---

## Phase 3: User Story 1 - Admin reclaims stale legacy CrowdStrike rows (Priority: P1) 🎯 MVP

**Goal**: Implement rule B (legacy fence) end-to-end: repository query, service combine logic with `includeLegacy` gating, controller per-run override, and admin UI toggle + dry-run summary split.

**Independent Test**: Seed one legacy CrowdStrike asset (no import timestamp, owner = `AssetOwners.CROWDSTRIKE_IMPORT`, no manual creator, no scan uploader, `lastSeen` 60 days old) and one manually-created asset with the same staleness. Run a dry-run with `includeLegacy=true` and 30-day threshold via the manual API or the admin UI. Result: only the legacy row appears as a candidate, with `reason = "LEGACY_NULL_TIMESTAMP"`. Repeat with `includeLegacy=false` — zero legacy candidates. Real run with `includeLegacy=true` deletes only the legacy row.

### Tests for User Story 1 ⚠️

> **Write these tests FIRST. Confirm they FAIL before implementing T010–T014.**

- [ ] T009 [US1] Extend `src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeAssetCleanupServiceTest.kt` with five unit tests parameterized over a helper `buildLegacyCrowdStrikeAsset()` in `TestDataFactory`: (1) legacy row picked when flag on / ignored when flag off; (2) `manualCreator` set → never picked; (3) `scanUploader` set → never picked; (4) `lastSeen` null but `updatedAt` 60 days old → still picked (COALESCE fall-through); (5) mixed batch (one timestamp-stale + one legacy) → both present, deduped by id, correct reason on each. Run `./gradlew :backendng:test --tests "*CrowdStrikeAssetCleanupServiceTest*"` and confirm all five FAIL with NotImplementedError or assertion mismatch
- [ ] T010 [P] [US1] Create `src/backendng/src/test/kotlin/com/secman/repository/AssetRepositoryLegacyStaleTest.kt` extending `BaseIntegrationTest`: assert `findLegacyCrowdStrikeStale` returns rows that match the four-part fence and excludes rows that violate any one condition; assert `countLegacyCrowdStrikeTotal` counts the same population minus the cutoff filter. Confirm tests FAIL before T011 lands

### Implementation for User Story 1

- [ ] T011 [US1] Add `findLegacyCrowdStrikeStale(ownerLiteral: String, cutoff: LocalDateTime): List<Asset>` and `countLegacyCrowdStrikeTotal(ownerLiteral: String): Long` to `src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt` using `@io.micronaut.data.annotation.Query` with the JPQL from data-model.md §6. Verifies T010
- [ ] T012 [US1] Update `cleanup()` in `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeAssetCleanupService.kt`: (a) change signature to accept `includeLegacy: Boolean` parameter (no default — caller resolves); (b) call `assetRepository.findLegacyCrowdStrikeStale(AssetOwners.CROWDSTRIKE_IMPORT, cutoff)` when `includeLegacy` is true; (c) tag rule-A candidates with `reason = TIMESTAMP_STALE`, rule-B with `LEGACY_NULL_TIMESTAMP`; (d) combine via `(timestampCandidates + legacyCandidates).distinctBy { it.id }`; (e) compute `legacyCandidateCount` and `legacyDeletedCount` from the result; (f) populate the new response fields. Verifies T009
- [ ] T013 [US1] Update `run()` in `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeCleanupAuditService.kt` to accept an optional `includeLegacy: Boolean? = null` parameter, resolve `effective = override ?: configuredDefault` (inject the default via `@Value("${secman.crowdstrike.cleanup.include-legacy:false}")`), and forward to `cleanupService.cleanup(..., includeLegacy = effective)`. The scheduler call path passes no override so it always reads the configured default
- [ ] T014 [US1] Update `deleteNotSeenByCrowdStrike()` in `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt` to forward `request.includeLegacy` to `crowdStrikeCleanupAuditService.run(..., includeLegacy = request.includeLegacy)` (still passes `maxDeletePercent = null` per existing manual-run-no-brake convention; spec edge case + SC-004)
- [ ] T015 [US1] Frontend: in `src/frontend/src/components/admin/StaleAssetCleanup.tsx` (or the equivalent component rendered by `src/frontend/src/pages/admin/falcon-config.astro`) — (a) add an "Include legacy CrowdStrike rows" toggle next to "Stale days for manual run" whose initial state comes from `config.includeLegacy` fetched from `/api/crowdstrike/cleanup/config` (no hardcoded default per SC-006); (b) include the toggle state in the request body sent to `POST /api/assets/delete-not-seen-by-crowdstrike` as `includeLegacy`; (c) update the dry-run summary banner to render `candidates: N (timestamp: X, legacy: Y, deleted: Z)` using `response.candidateCount`, `response.legacyCandidateCount`, derived timestamp count, and `response.deletedCount` — but only show the `(timestamp / legacy / deleted)` split when the toggle is ON (toggle OFF means zero legacy by gate semantics — FR-012)

**Checkpoint**: User Story 1 complete. Run `./gradlew :backendng:test --tests "*CrowdStrikeAssetCleanupServiceTest*"` (T009 passes), `./gradlew :backendng:test --tests "*AssetRepositoryLegacyStaleTest*"` (T010 passes), and the quickstart.md Steps 0–4 manually. Stop here for an MVP merge if desired.

---

## Phase 4: User Story 2 - Safety brake stays meaningful with widened scope (Priority: P1)

**Goal**: Widen the safety-brake denominator to include legacy CrowdStrike-origin rows and persist the legacy-rule contribution to the audit row across all four terminal statuses.

**Independent Test**: Seed 1000 timestamped CrowdStrike assets and 50 legacy assets with a small fraction stale. Run the scheduled job (or trigger via `CrowdStrikeStaleAssetCleanupScheduler.runScheduledCleanup`) with `includeLegacy=true` and brake at 10%. Confirm: (a) the run is NOT aborted when blast radius using both populations is ≤10%; (b) the persisted `crowdstrike_cleanup_run` row has correct `legacyCandidateCount`/`legacyDeletedCount`; (c) on a contrived high-blast-radius case the brake DOES trip and the audit row's legacy fields still record the would-have-been counts.

### Tests for User Story 2 ⚠️

- [ ] T016 [US2] Add an integration test to `src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeCleanupAuditServiceIntegrationTest.kt` (extending `BaseIntegrationTest`) named `testSafetyBrakeUsesWidenedDenominator`: seed 100 timestamped CrowdStrike assets + 20 legacy CrowdStrike assets, make 8 of the legacy assets stale (cutoff 30d), call `auditService.run(days = 30, dryRun = false, triggeredBy = "test", includeLegacy = true, maxDeletePercent = 10)`. Assert run status == SUCCESS (not ABORTED — 8 of 120 = 6.7% < 10%). Confirm test FAILS without T018
- [ ] T017 [P] [US2] Add an integration test `testAuditRowPersistsLegacyCounts` in the same file with three sub-cases (SUCCESS, PARTIAL via simulated mid-run failure, ABORTED_SAFETY_BRAKE via tight brake): for each, run cleanup with `includeLegacy=true` and assert the persisted `CrowdStrikeCleanupRun` has the expected `legacyCandidateCount` and `legacyDeletedCount` (zero for ABORTED; equal for SUCCESS; partial for PARTIAL). Confirm test FAILS without T018

### Implementation for User Story 2

- [ ] T018 [US2] Update `CrowdStrikeCleanupAuditService` in `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeCleanupAuditService.kt`: (a) widen the safety-brake numerator to include the legacy candidate count when `includeLegacy=true`; (b) widen the denominator from `countCrowdStrikeTracked()` alone to `countCrowdStrikeTracked() + countLegacyCrowdStrikeTotal(AssetOwners.CROWDSTRIKE_IMPORT)`; (c) populate `run.legacyCandidateCount` and `run.legacyDeletedCount` from the response (counted via `candidates.count { it.reason == LEGACY_NULL_TIMESTAMP }` and the corresponding successful-deletes subset) before persisting in `persistRun(...)`; (d) keep the existing `total_crowdstrike_tracked` semantics (now naturally widens since denominator widens). Verifies T016 + T017

**Checkpoint**: User Story 2 complete. Brake stays meaningful when rule B fires. Audit history truthfully records the rule-B contribution. The scheduler is unchanged — it inherits the new behaviour transparently because it already calls `auditService.run(...)`.

---

## Phase 5: User Story 3 - Admin can audit per-run blast radius of the legacy rule (Priority: P2)

**Goal**: Expose the configured `includeLegacy` default through the cleanup-config endpoint (so the UI initializes the toggle correctly) and render the per-run legacy contribution in the cleanup history table.

**Independent Test**: Open `/admin/falcon-config`, verify the legacy toggle initial state matches the backend's configured default. Trigger a run with the legacy rule contributing deletions, then refresh the history panel. The new run row shows `legacy_candidate_count` and `legacy_deleted_count` distinct from the totals; older rows display zero in those fields.

### Tests for User Story 3 ⚠️

- [ ] T019 [US3] Add a controller test in `src/backendng/src/test/kotlin/com/secman/controller/CrowdStrikeCleanupControllerTest.kt` (or extend the existing one) that asserts `GET /api/crowdstrike/cleanup/config` returns a `CleanupConfigDto` JSON body with the new `includeLegacy` boolean field reflecting the `@Value` injection. Confirm test FAILS without T020

### Implementation for User Story 3

- [ ] T020 [US3] Update `src/backendng/src/main/kotlin/com/secman/controller/CrowdStrikeCleanupController.kt`: (a) add `@Value("${secman.crowdstrike.cleanup.include-legacy:false}") private val includeLegacy: Boolean` as a constructor parameter; (b) extend the inner `CleanupConfigDto` with `val includeLegacy: Boolean`; (c) populate it in `getConfig()`. Verifies T019
- [ ] T021 [US3] Frontend: in `src/frontend/src/components/admin/StaleAssetCleanup.tsx` (or wherever the run history table is rendered), add a "Legacy / Timestamp" column to the recent-runs table that renders `${run.legacyCandidateCount}/${run.legacyDeletedCount}` (or a clearly labelled compound like `legacy 12/12 of 38`). Historical rows persisted before V210 read both fields as 0; render that as `—` or `0/0` consistently — do NOT treat 0 as "definitely no legacy candidates existed at that time"

**Checkpoint**: All three user stories independently functional. Toggle state is configuration-driven; admins can attribute every deletion to a rule from the history panel.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final documentation sync, mandatory project gates per `CLAUDE.md`, and security review per Constitution Principle I.

- [ ] T022 [P] Update `CLAUDE.md` Patterns section (or add a Feature 087 reference in the appropriate place) noting: (a) the `AssetOwners` constant lives at `com.secman.constants.AssetOwners.CROWDSTRIKE_IMPORT` and rule-B's predicate keys off it; (b) `secman.crowdstrike.cleanup.include-legacy` flag toggles rule B; (c) audit row gains `legacy_candidate_count` / `legacy_deleted_count`. Note: the agent-context script already auto-appended a "Recent Changes" entry — verify it's accurate and tighten if needed
- [ ] T023 Run `./gradlew build` from the repo root and confirm a clean build (mandatory gate per CLAUDE.md Hard Principle 5)
- [ ] T024 Run `./scriptpp/startbackenddev.sh`, confirm Micronaut starts cleanly (Flyway V210 applies, beans wire, no SessionFactory errors), then stop the backend (mandatory gate per CLAUDE.md Hard Principle 5)
- [ ] T025 Run the `/e2ejs` skill against `SECMAN_HOST` for both admin and normal-user roles. Confirm 0 JS errors (mandatory gate per CLAUDE.md Hard Principle 7)
- [ ] T026 Run the `/e2evulnexception` skill. Confirm 0 failures across the full lifecycle (mandatory gate per CLAUDE.md Hard Principle 7)
- [ ] T027 Manually walk through `specs/087-crowdstrike-legacy-stale-cleanup/quickstart.md` Steps 0–6 against a deployed instance with seed legacy data. Confirm each expected outcome
- [ ] T028 Security review per Constitution Principle I: confirm no new external auth surface; confirm `request.includeLegacy` Boolean does not reach SQL by reflection; confirm no sensitive data added to logs in any of the new code paths; confirm RBAC unchanged at the controller level

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — can start immediately.
- **Phase 2 (Foundational)**: Depends on Phase 1 completion (V210 must exist before T008 entity field addition is safe to deploy). Foundational BLOCKS all user-story phases.
- **Phase 3 (US1, P1, MVP)**: Depends on Phase 2. Independent of US2 and US3.
- **Phase 4 (US2, P1)**: Depends on Phase 2. Logically follows US1 because it consumes US1's `legacyCandidateCount` count from the response, but the audit-service work itself can begin in parallel with US1's frontend work (T015) once US1's service work (T012) is complete.
- **Phase 5 (US3, P2)**: Depends on Phase 2. Can start immediately after Foundational (controller + frontend changes are independent of US1/US2 service code).
- **Phase 6 (Polish)**: Depends on US1 + US2 + US3. The mandatory gates (T023–T026) MUST pass before merging.

### Within Each User Story

- Tests (T009/T010 for US1; T016/T017 for US2; T019 for US3) MUST be written and FAIL before implementation tasks within the same story land.
- Repository/queries before service updates that consume them.
- Backend before frontend within a story (frontend depends on the response shape).

### Parallel Opportunities

- **T005 + T006** can run in parallel after T004 (different files).
- **T009 + T010** can run in parallel (different test files, no shared state).
- **T016 + T017** can run in parallel (different test methods in the same file but no shared seed state — both build their own asset fixtures).
- **US1 frontend (T015) + US2 backend (T016–T018)** can run in parallel once T012 has merged. US3 (T019–T021) can run in parallel with both.
- **T022 (CLAUDE.md update)** can run in parallel with the mandatory gates (T023–T026) since it touches a different file.

### Suggested Execution Order (single developer)

1. T001–T003 (Setup)
2. T004 → T005 + T006 (parallel) → T007 → T008 (Foundational)
3. T009 + T010 (parallel TDD) → T011 → T012 → T013 → T014 → T015 (US1 — MVP)
4. **STOP, validate via quickstart Steps 0–4, demo if desired**
5. T016 + T017 (parallel TDD) → T018 (US2)
6. T019 → T020 → T021 (US3)
7. T022 + T023 + T024 + T025 + T026 + T027 + T028 (Polish)

---

## Parallel Example: User Story 1

```bash
# After T004 lands, refactor the two literal call-sites in parallel:
Task: "Refactor CrowdStrikeVulnerabilityImportService.kt:376 to AssetOwners.CROWDSTRIKE_IMPORT"
Task: "Refactor AssetController.kt:202 to AssetOwners.CROWDSTRIKE_IMPORT"

# After T008 lands, write the TDD tests in parallel:
Task: "Add 5 unit tests to CrowdStrikeAssetCleanupServiceTest.kt"
Task: "Create AssetRepositoryLegacyStaleTest.kt with direct query coverage"
```

## Parallel Example: User Story 2

```bash
# After T012 (US1 service) lands, the integration tests can run concurrently:
Task: "Add testSafetyBrakeUsesWidenedDenominator integration test"
Task: "Add testAuditRowPersistsLegacyCounts integration test"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

Stop after T015 and demo:
- Admin enables the toggle, runs a dry-run, sees only legacy candidates.
- Admin runs the cleanup and the legacy rows are removed.
- Manually-created and scan-uploaded assets remain.

That alone is a shippable increment if rollout speed matters more than per-run audit visibility.

### Incremental Delivery

1. **Setup + Foundational** (T001–T008) → schema and shape ready.
2. **US1 (T009–T015)** → MVP. Manual cleanup with rule B works end-to-end. Demo-ready.
3. **US2 (T016–T018)** → safety brake stays meaningful for the scheduled job. Production-ready (still soft-launched behind flag).
4. **US3 (T019–T021)** → audit visibility for ops teams. Full feature.
5. **Polish (T022–T028)** → docs sync + mandatory gates + security review → merge to `main`.

### Parallel Team Strategy

With three developers after Foundational:

- Dev A: US1 backend (T009 + T011 + T012 + T013 + T014).
- Dev B: US1 frontend (T015), then US3 frontend (T021).
- Dev C: US2 (T016 + T017 + T018) and US3 backend (T019 + T020).

US1 backend MUST land first because every downstream story consumes the new DTO fields.

---

## Notes

- `[P]` tasks operate on different files with no incomplete-task dependencies.
- `[Story]` label maps each task to a spec.md user story for traceability.
- Each user story should be independently completable and testable per its Independent Test instructions in spec.md.
- Tests are written first, must FAIL on the unmodified codebase, and pass after the matching implementation task lands.
- Commit per logical group — at minimum once per story checkpoint. Conventional Commits (`feat(crowdstrike): …`, `test(crowdstrike): …`, `docs(crowdstrike): …`) per CLAUDE.md and Constitution Principle 6.
- Avoid: editing rule A's existing query (FR-004); inferring CrowdStrike-ness from `description` (spec out-of-scope); applying the safety brake to manual runs (spec edge case + SC-004); hardcoding the toggle initial state in the React component (SC-006).
