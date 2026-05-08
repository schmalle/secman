---

description: "Task list for Feature 087 — CrowdStrike Legacy Stale-Asset Cleanup"
---

# Tasks: CrowdStrike Legacy Stale-Asset Cleanup

**Input**: Design documents from `/specs/087-crowdstrike-legacy-stale-cleanup/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: User explicitly requested unit + integration tests in the originating brief (see spec FR-001..016 mapped to acceptance scenarios). Per Constitution Principle IV, tests are therefore in scope. Tests use TDD — write them first and confirm they FAIL before implementing.

**Organization**: Tasks are grouped by user story (US1 P1, US2 P1, US3 P2 from spec.md). Setup and Foundational phases unblock all stories. Polish phase covers mandatory project gates.

**Revision note**: This file was revised after `/speckit.analyze` found four issues (F1 HIGH, C1/F2/F3+F4 MEDIUM). The config-endpoint extension was promoted from US3 to Foundational so US1's frontend has a working initialiser; an audit-service override-resolution test task was added; the audit-row integration test was expanded to cover the manual-run-no-brake invariant; and the User Story Dependencies section now reflects the file-level coupling between US1 and US2 service work.

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

- [X] T001 Create Flyway migration `src/backendng/src/main/resources/db/migration/V210__crowdstrike_cleanup_run_legacy_columns.sql` with body: `ALTER TABLE crowdstrike_cleanup_run ADD COLUMN legacy_candidate_count INT NOT NULL DEFAULT 0, ADD COLUMN legacy_deleted_count INT NOT NULL DEFAULT 0;`
- [X] T002 Add `secman.crowdstrike.cleanup.include-legacy: false` (under the existing `secman.crowdstrike.cleanup` block) in `src/backendng/src/main/resources/application.yml`
- [X] T003 [P] Document `CROWDSTRIKE_CLEANUP_INCLUDE_LEGACY` (default `false`) in the existing CrowdStrike section of `docs/ENVIRONMENT.md` — describe the toggle, the rule-B fence, and that it gates both the scheduled job and the configured default of the manual-run override

**Checkpoint**: Schema migration ready; backend can pick up the new flag on startup.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Establish the shared constant, DTO shape changes, entity field additions, AND the cleanup-config endpoint surface that every user story depends on. **No user-story work begins until this phase is complete.**

**⚠️ CRITICAL**: After T007 lands, the existing `CrowdStrikeAssetCleanupService` will not compile because it constructs `CrowdStrikeAssetCleanupCandidateDto` without a `reason` field — that breakage is intentionally fixed inside US1 (T015). Plan to land Phase 2 + US1 in one merge cycle, or temporarily keep the service compiling by passing `reason = CleanupCandidateReason.TIMESTAMP_STALE` as part of T007.

- [X] T004 Create `src/backendng/src/main/kotlin/com/secman/constants/AssetOwners.kt` with a top-level `object AssetOwners { const val CROWDSTRIKE_IMPORT = "CrowdStrike Import" }` (FR-014 single-source-of-truth)
- [X] T005 [P] Refactor `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt` line 376 to reference `AssetOwners.CROWDSTRIKE_IMPORT` instead of the literal `"CrowdStrike Import"` (depends on T004)
- [X] T006 [P] Refactor `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt` line 202 (`getOwnerCandidates`) to reference `AssetOwners.CROWDSTRIKE_IMPORT` instead of the literal (depends on T004)
- [X] T007 Mutate DTOs in `src/backendng/src/main/kotlin/com/secman/dto/CrowdStrikeAssetCleanupDto.kt`: (a) add `enum class CleanupCandidateReason { TIMESTAMP_STALE, LEGACY_NULL_TIMESTAMP }` with `@Serdeable`; (b) `CrowdStrikeAssetCleanupRequest` gains `val includeLegacy: Boolean? = null`; (c) `CrowdStrikeAssetCleanupCandidateDto` field `crowdStrikeLastImportedAt` becomes `LocalDateTime?` (nullable) and gains `val reason: CleanupCandidateReason`; (d) `CrowdStrikeAssetCleanupResponse` gains `val legacyCandidateCount: Int = 0, val legacyDeletedCount: Int = 0`. Update existing call site at `CrowdStrikeAssetCleanupService.kt:37-41` to pass `reason = CleanupCandidateReason.TIMESTAMP_STALE` so the project keeps compiling
- [X] T008 Add fields to `src/backendng/src/main/kotlin/com/secman/domain/CrowdStrikeCleanupRun.kt`: `@Column(name = "legacy_candidate_count", nullable = false) var legacyCandidateCount: Int = 0` and `@Column(name = "legacy_deleted_count", nullable = false) var legacyDeletedCount: Int = 0` — placed after `errorCount` to mirror the SQL column order
- [X] T009 Update `src/backendng/src/main/kotlin/com/secman/controller/CrowdStrikeCleanupController.kt`: (a) add `@Value("${secman.crowdstrike.cleanup.include-legacy:false}") private val includeLegacy: Boolean` as a constructor parameter; (b) extend its inner `CleanupConfigDto` with `val includeLegacy: Boolean`; (c) populate it in `getConfig()`. **Promoted from US3 to Foundational (analyze finding F1)** because US1's frontend (T018) reads `config.includeLegacy` and would otherwise see `undefined` if US1 ships before US3 in the MVP plan — see spec SC-006
- [X] T010 [P] Add a controller test in `src/backendng/src/test/kotlin/com/secman/controller/CrowdStrikeCleanupControllerTest.kt` (or extend the existing one) that asserts `GET /api/crowdstrike/cleanup/config` returns a `CleanupConfigDto` JSON body with the new `includeLegacy` boolean field reflecting the `@Value` injection. Confirm test FAILS before T009 lands (depends on T009 only at compile-time of the assertion; the test should be drafted first per TDD)

**Checkpoint**: Codebase compiles. Existing tests still pass (rule A unchanged). `GET /api/crowdstrike/cleanup/config` exposes `includeLegacy`. Foundation ready for parallel user-story work.

---

## Phase 3: User Story 1 - Admin reclaims stale legacy CrowdStrike rows (Priority: P1) 🎯 MVP

**Goal**: Implement rule B (legacy fence) end-to-end: repository query, service combine logic with `includeLegacy` gating, audit-service override resolution, controller per-run override, and admin UI toggle + dry-run summary split.

**Independent Test**: Seed one legacy CrowdStrike asset (no import timestamp, owner = `AssetOwners.CROWDSTRIKE_IMPORT`, no manual creator, no scan uploader, `lastSeen` 60 days old) and one manually-created asset with the same staleness. Run a dry-run with `includeLegacy=true` and 30-day threshold via the manual API or the admin UI. Result: only the legacy row appears as a candidate, with `reason = "LEGACY_NULL_TIMESTAMP"`. Repeat with `includeLegacy=false` — zero legacy candidates. Real run with `includeLegacy=true` deletes only the legacy row.

### Tests for User Story 1 ⚠️

> **Write these tests FIRST. Confirm they FAIL before implementing T014–T018.**

- [ ] T011 [US1] Extend `src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeAssetCleanupServiceTest.kt` with five unit tests parameterized over a helper `buildLegacyCrowdStrikeAsset()` in `TestDataFactory`: (1) legacy row picked when flag on / ignored when flag off; (2) `manualCreator` set → never picked; (3) `scanUploader` set → never picked; (4) `lastSeen` null but `updatedAt` 60 days old → still picked (COALESCE fall-through); (5) mixed batch (one timestamp-stale + one legacy) → both present, deduped by id, correct reason on each. Run `./gradlew :backendng:test --tests "*CrowdStrikeAssetCleanupServiceTest*"` and confirm all five FAIL with NotImplementedError or assertion mismatch
- [ ] T012 [US1] Add three audit-service-level unit tests in `src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeCleanupAuditServiceTest.kt` covering FR-010 override-vs-default resolution (analyze finding F3): (a) `auditService.run(includeLegacy = true)` with configured default `false` → `cleanupService.cleanup` is invoked with `includeLegacy = true`; (b) `auditService.run(includeLegacy = false)` with configured default `true` → cleanup invoked with `includeLegacy = false`; (c) `auditService.run(includeLegacy = null)` falls back to whichever value the configured default holds (parameterize over both directions, asserting `includeLegacy = false` and `= true` respectively). Use Mockk to verify the cleanupService.cleanup signature was called with the resolved Boolean. Confirm tests FAIL before T016 (override-resolution impl) lands
- [ ] T013 [P] [US1] Create `src/backendng/src/test/kotlin/com/secman/repository/AssetRepositoryLegacyStaleTest.kt` extending `BaseIntegrationTest`: assert `findLegacyCrowdStrikeStale` returns rows that match the four-part fence and excludes rows that violate any one condition; assert `countLegacyCrowdStrikeTotal` counts the same population minus the cutoff filter. Confirm tests FAIL before T014 lands

### Implementation for User Story 1

- [ ] T014 [US1] Add `findLegacyCrowdStrikeStale(ownerLiteral: String, cutoff: LocalDateTime): List<Asset>` and `countLegacyCrowdStrikeTotal(ownerLiteral: String): Long` to `src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt` using `@io.micronaut.data.annotation.Query` with the JPQL from data-model.md §6. Verifies T013
- [ ] T015 [US1] Update `cleanup()` in `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeAssetCleanupService.kt`: (a) change signature to accept `includeLegacy: Boolean` parameter (no default — caller resolves); (b) call `assetRepository.findLegacyCrowdStrikeStale(AssetOwners.CROWDSTRIKE_IMPORT, cutoff)` when `includeLegacy` is true; (c) tag rule-A candidates with `reason = TIMESTAMP_STALE`, rule-B with `LEGACY_NULL_TIMESTAMP`; (d) combine via `(timestampCandidates + legacyCandidates).distinctBy { it.id }`; (e) compute `legacyCandidateCount` and `legacyDeletedCount` from the result; (f) populate the new response fields. Verifies T011
- [ ] T016 [US1] Update `run()` in `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeCleanupAuditService.kt` to accept an optional `includeLegacy: Boolean? = null` parameter, resolve `effective = override ?: configuredDefault` (inject the default via `@Value("${secman.crowdstrike.cleanup.include-legacy:false}")`), and forward to `cleanupService.cleanup(..., includeLegacy = effective)`. The scheduler call path passes no override so it always reads the configured default. Verifies T012
- [ ] T017 [US1] Update `deleteNotSeenByCrowdStrike()` in `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt` to forward `request.includeLegacy` to `crowdStrikeCleanupAuditService.run(..., includeLegacy = request.includeLegacy)` (still passes `maxDeletePercent = null` per existing manual-run-no-brake convention; spec edge case + SC-004)
- [ ] T018 [US1] Frontend: in `src/frontend/src/components/admin/StaleAssetCleanup.tsx` (or the equivalent component rendered by `src/frontend/src/pages/admin/falcon-config.astro`) — (a) add an "Include legacy CrowdStrike rows" toggle next to "Stale days for manual run" whose initial state comes from `config.includeLegacy` fetched from `/api/crowdstrike/cleanup/config` (no hardcoded default per SC-006); (b) include the toggle state in the request body sent to `POST /api/assets/delete-not-seen-by-crowdstrike` as `includeLegacy`; (c) update the dry-run summary banner to render `candidates: N (timestamp: X, legacy: Y, deleted: Z)` using `response.candidateCount`, `response.legacyCandidateCount`, derived timestamp count, and `response.deletedCount` — but only show the `(timestamp / legacy / deleted)` split when the toggle is ON (toggle OFF means zero legacy by gate semantics — FR-012)

**Checkpoint**: User Story 1 complete. Run `./gradlew :backendng:test --tests "*CrowdStrikeAssetCleanupServiceTest*"` (T011 passes), `./gradlew :backendng:test --tests "*CrowdStrikeCleanupAuditServiceTest*"` (T012 passes), `./gradlew :backendng:test --tests "*AssetRepositoryLegacyStaleTest*"` (T013 passes), and the quickstart.md Steps 0–4 manually. Stop here for an MVP merge if desired.

---

## Phase 4: User Story 2 - Safety brake stays meaningful with widened scope (Priority: P1)

**Goal**: Widen the safety-brake denominator to include legacy CrowdStrike-origin rows and persist the legacy-rule contribution to the audit row across all four terminal statuses.

**Independent Test**: Seed 100 timestamped CrowdStrike assets and 20 legacy CrowdStrike assets, make 8 of the legacy assets stale (cutoff 30 days). Run the scheduled job (or trigger via `CrowdStrikeStaleAssetCleanupScheduler.runScheduledCleanup`) with `includeLegacy=true` and brake at 10%. Confirm: (a) the run is NOT aborted (8 of 120 = 6.7% < 10%); (b) the persisted `crowdstrike_cleanup_run` row has correct `legacyCandidateCount`/`legacyDeletedCount`; (c) on a contrived high-blast-radius case the brake DOES trip and the audit row's legacy fields still record the would-have-been counts.

### Tests for User Story 2 ⚠️

- [ ] T019 [US2] Add an integration test to `src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeCleanupAuditServiceIntegrationTest.kt` (extending `BaseIntegrationTest`) named `testSafetyBrakeUsesWidenedDenominator`: seed 100 timestamped CrowdStrike assets + 20 legacy CrowdStrike assets, make 8 of the legacy assets stale (cutoff 30d), call `auditService.run(days = 30, dryRun = false, triggeredBy = "test", includeLegacy = true, maxDeletePercent = 10)`. Assert run status == SUCCESS (not ABORTED — 8 of 120 = 6.7% < 10%). Confirm test FAILS without T021
- [ ] T020 [P] [US2] Add four integration tests in the same file:
    - `testAuditRowPersistsLegacyCountsOnSuccess`, `...OnPartial`, `...OnAbortedSafetyBrake` — for each terminal status, run cleanup with `includeLegacy=true` and assert the persisted `CrowdStrikeCleanupRun` carries the expected `legacyCandidateCount` and `legacyDeletedCount` (zero deletions for ABORTED; equal for SUCCESS; partial for PARTIAL).
    - `testManualRunBypassesBrakeWhenIncludeLegacyTrue` (analyze finding F4) — seed enough legacy stale rows that a configured brake at 1% would trip on a scheduled run. Call `auditService.run(triggeredBy = "user-X", includeLegacy = true, maxDeletePercent = null)` (the manual-API path). Assert run status ∈ {SUCCESS, PARTIAL} — NOT `ABORTED_SAFETY_BRAKE` — confirming spec edge case + SC-004 ("Manual runs continue to bypass the brake by design").
    Confirm the four tests FAIL without T021 (the manual-run test additionally requires T017's controller wiring of `maxDeletePercent = null`)

### Implementation for User Story 2

- [ ] T021 [US2] Update `CrowdStrikeCleanupAuditService` in `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeCleanupAuditService.kt`: (a) widen the safety-brake numerator to include the legacy candidate count when `includeLegacy=true`; (b) widen the denominator from `countCrowdStrikeTracked()` alone to `countCrowdStrikeTracked() + countLegacyCrowdStrikeTotal(AssetOwners.CROWDSTRIKE_IMPORT)`; (c) populate `run.legacyCandidateCount` and `run.legacyDeletedCount` from the response (counted via `candidates.count { it.reason == LEGACY_NULL_TIMESTAMP }` and the corresponding successful-deletes subset) before persisting in `persistRun(...)`; (d) keep the existing `total_crowdstrike_tracked` semantics (now naturally widens since denominator widens). Verifies T019 + T020. **Note**: this task edits the same file as T016 (US1's override resolution); T021 must land AFTER T016 has merged — see User Story Dependencies below

**Checkpoint**: User Story 2 complete. Brake stays meaningful when rule B fires. Audit history truthfully records the rule-B contribution. The scheduler is unchanged — it inherits the new behaviour transparently because it already calls `auditService.run(...)`.

---

## Phase 5: User Story 3 - Admin can audit per-run blast radius of the legacy rule (Priority: P2)

**Goal**: Render the per-run legacy contribution in the cleanup history table. (The cleanup-config endpoint exposure was promoted to Foundational T009 — see analyze finding F1.)

**Independent Test**: After a run with the legacy rule contributing deletions, refresh the history panel. The new run row shows `legacy_candidate_count` and `legacy_deleted_count` distinct from the totals; older rows display zero in those fields.

### Implementation for User Story 3

- [ ] T022 [US3] Frontend: in `src/frontend/src/components/admin/StaleAssetCleanup.tsx` (or wherever the run history table is rendered), add a "Legacy / Timestamp" column to the recent-runs table that renders `${run.legacyCandidateCount}/${run.legacyDeletedCount}` (or a clearly labelled compound like `legacy 12/12 of 38`). Historical rows persisted before V210 read both fields as 0; render that as `—` or `0/0` consistently — do NOT treat 0 as "definitely no legacy candidates existed at that time". US3 acceptance scenarios are verified manually via quickstart Step 5 (analyze finding F5 — no automated frontend test added)

**Checkpoint**: All three user stories independently functional. Toggle state is configuration-driven; admins can attribute every deletion to a rule from the history panel.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final documentation sync, mandatory project gates per `CLAUDE.md`, and security review per Constitution Principle I.

- [ ] T023 [P] Update `CLAUDE.md` Patterns section (or add a Feature 087 reference in the appropriate place) noting: (a) the `AssetOwners` constant lives at `com.secman.constants.AssetOwners.CROWDSTRIKE_IMPORT` and rule-B's predicate keys off it; (b) `secman.crowdstrike.cleanup.include-legacy` flag toggles rule B; (c) audit row gains `legacy_candidate_count` / `legacy_deleted_count`. Note: the agent-context script already auto-appended a "Recent Changes" entry — verify it's accurate and tighten if needed
- [ ] T024 Run `./gradlew build` from the repo root and confirm a clean build (mandatory gate per CLAUDE.md Hard Principle 5)
- [ ] T025 Run `./scriptpp/startbackenddev.sh`, confirm Micronaut starts cleanly (Flyway V210 applies, beans wire, no SessionFactory errors), then stop the backend (mandatory gate per CLAUDE.md Hard Principle 5)
- [ ] T026 Run the `/e2ejs` skill against `SECMAN_HOST` for both admin and normal-user roles. Confirm 0 JS errors (mandatory gate per CLAUDE.md Hard Principle 7)
- [ ] T027 Run the `/e2evulnexception` skill. Confirm 0 failures across the full lifecycle (mandatory gate per CLAUDE.md Hard Principle 7)
- [ ] T028 Manually walk through `specs/087-crowdstrike-legacy-stale-cleanup/quickstart.md` Steps 0–6 against a deployed instance with seed legacy data. Confirm each expected outcome
- [ ] T029 Security review per Constitution Principle I: confirm no new external auth surface; confirm `request.includeLegacy` Boolean does not reach SQL by reflection; confirm no sensitive data added to logs in any of the new code paths; confirm RBAC unchanged at the controller level

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — can start immediately.
- **Phase 2 (Foundational)**: Depends on Phase 1 completion (V210 must exist before T008 entity field addition is safe to deploy). Foundational BLOCKS all user-story phases. T009 + T010 (cleanup-config endpoint extension) live here so US1's frontend has a working `config.includeLegacy` initialiser when it lands.
- **Phase 3 (US1, P1, MVP)**: Depends on Phase 2. Independent of US3. **Has a file-level coupling with US2** — see User Story Dependencies below.
- **Phase 4 (US2, P1)**: Depends on Phase 2 AND on US1's audit-service override resolution (T016) because T021 edits the same file. US2 tests (T019/T020) can be drafted in parallel with US1 implementation; US2 service-layer impl (T021) sequentially follows T016.
- **Phase 5 (US3, P2)**: Depends on Phase 2. Frontend-only at this point (the controller-side config endpoint moved to Foundational). Can start immediately after Foundational and runs in parallel with US1/US2.
- **Phase 6 (Polish)**: Depends on US1 + US2 + US3. The mandatory gates (T024–T027) MUST pass before merging.

### User Story Dependencies (analyze finding F2)

- **US1 (P1)**: Can start immediately after Foundational. Tests (T011/T012/T013) can be drafted in parallel; implementation tasks (T014–T018) follow TDD-fail-then-pass within the story.
- **US2 (P1)**: Tests (T019/T020) can be drafted in parallel with US1 implementation. **Service-layer implementation (T021) sequentially follows US1's audit-service override resolution (T016)** because both tasks edit `src/backendng/.../service/CrowdStrikeCleanupAuditService.kt` — they cannot be merged in parallel at the file level. Integration of T021 lands after T016 has merged.
- **US3 (P2)**: Frontend-only. Independent of US1 and US2. Can start anytime after Foundational and merges without waiting for US1/US2.

### Within Each User Story

- Tests (T011/T012/T013 for US1; T019/T020 for US2) MUST be written and FAIL before implementation tasks within the same story land.
- Repository/queries before service updates that consume them.
- Backend before frontend within a story (frontend depends on the response shape).

### Parallel Opportunities

- **T005 + T006** can run in parallel after T004 (different files).
- **T010** (config-endpoint test) is parallel-safe — it touches a new test file.
- **T011 + T013** can run in parallel (different test files).
- **T012** is sequential to T011 in story order but lives in a different test file (`CrowdStrikeCleanupAuditServiceTest.kt`), so it can be drafted while T011 is in flight.
- **T019 + T020** can run in parallel (different test methods in the same file but no shared seed state — both build their own asset fixtures).
- **US1 frontend (T018) + US2 tests (T019/T020) + US3 frontend (T022)** can run in parallel once US1 backend (T015–T017) has landed.
- **T023 (CLAUDE.md update)** can run in parallel with the mandatory gates (T024–T027) since it touches a different file.

### Suggested Execution Order (single developer)

1. T001–T003 (Setup)
2. T004 → T005 + T006 (parallel) → T007 → T008 → T009 → T010 (Foundational)
3. T011 + T012 + T013 (parallel TDD) → T014 → T015 → T016 → T017 → T018 (US1 — MVP)
4. **STOP, validate via quickstart Steps 0–4, demo if desired**
5. T019 + T020 (parallel TDD) → T021 (US2)
6. T022 (US3)
7. T023 + T024 + T025 + T026 + T027 + T028 + T029 (Polish)

---

## Parallel Example: User Story 1

```bash
# After T008 lands, refactor the two literal call-sites in parallel:
Task: "Refactor CrowdStrikeVulnerabilityImportService.kt:376 to AssetOwners.CROWDSTRIKE_IMPORT"
Task: "Refactor AssetController.kt:202 to AssetOwners.CROWDSTRIKE_IMPORT"

# After T010 lands, write the TDD tests in parallel:
Task: "Add 5 unit tests to CrowdStrikeAssetCleanupServiceTest.kt"
Task: "Add 3 override-resolution tests to CrowdStrikeCleanupAuditServiceTest.kt"
Task: "Create AssetRepositoryLegacyStaleTest.kt with direct query coverage"
```

## Parallel Example: User Story 2

```bash
# After T015 (US1 service) lands, the integration tests can be drafted concurrently:
Task: "Add testSafetyBrakeUsesWidenedDenominator integration test"
Task: "Add 4 audit-row + manual-run-no-brake integration tests"

# T021 (audit service brake widening) sequentially follows T016 (audit override
# resolution) because they edit the same file.
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

Stop after T018 and demo:
- Admin opens `/admin/falcon-config`, sees the toggle initialised from the configured default (Foundational T009 wires this).
- Admin enables the toggle, runs a dry-run, sees only legacy candidates.
- Admin runs the cleanup and the legacy rows are removed.
- Manually-created and scan-uploaded assets remain.

That alone is a shippable increment if rollout speed matters more than per-run audit visibility.

### Incremental Delivery

1. **Setup + Foundational** (T001–T010) → schema, shape, AND config endpoint ready.
2. **US1 (T011–T018)** → MVP. Manual cleanup with rule B works end-to-end. Demo-ready.
3. **US2 (T019–T021)** → safety brake stays meaningful for the scheduled job. Production-ready (still soft-launched behind flag).
4. **US3 (T022)** → audit visibility for ops teams. Full feature.
5. **Polish (T023–T029)** → docs sync + mandatory gates + security review → merge to `main`.

### Parallel Team Strategy

With three developers after Foundational:

- Dev A: US1 backend (T011 + T012 + T014 + T015 + T016 + T017).
- Dev B: US1 frontend (T018), then US3 frontend (T022).
- Dev C: US2 tests (T019 + T020) drafted in parallel with Dev A's work; US2 impl (T021) lands AFTER Dev A's T016 due to same-file coupling on `CrowdStrikeCleanupAuditService.kt`.

US1 backend MUST land first because every downstream story consumes the new DTO fields and (for US2) edits the same audit-service file.

---

## Notes

- `[P]` tasks operate on different files with no incomplete-task dependencies.
- `[Story]` label maps each task to a spec.md user story for traceability.
- Each user story should be independently completable and testable per its Independent Test instructions in spec.md.
- Tests are written first, must FAIL on the unmodified codebase, and pass after the matching implementation task lands.
- Commit per logical group — at minimum once per story checkpoint. Conventional Commits (`feat(crowdstrike): …`, `test(crowdstrike): …`, `docs(crowdstrike): …`) per CLAUDE.md and Constitution Principle 6.
- Avoid: editing rule A's existing query (FR-004); inferring CrowdStrike-ness from `description` (spec out-of-scope); applying the safety brake to manual runs (spec edge case + SC-004); hardcoding the toggle initial state in the React component (SC-006).
