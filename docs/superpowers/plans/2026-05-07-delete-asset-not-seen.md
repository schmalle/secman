# Delete Asset Not Seen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `secman delete-asset-not-seen <days> [--dry-run]` to delete CrowdStrike-managed assets that have not appeared in CrowdStrike imports for more than N days.

**Architecture:** Store a CrowdStrike-specific per-asset timestamp instead of reusing generic `lastSeen`. CrowdStrike imports update that timestamp on every imported asset, while a new ADMIN-only backend API previews or deletes stale assets through the existing cascade deletion service. The CLI authenticates with the backend and calls that API.

**Tech Stack:** Kotlin 2.3, Micronaut 4, Micronaut Data JPA, Flyway SQL, Picocli, JUnit 6, Mockk.

---

### Task 1: Persist CrowdStrike Import Timestamp

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`
- Create: `src/backendng/src/main/resources/db/migration/V209__asset_crowdstrike_last_imported_at.sql`
- Test: `src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportServiceTest.kt`

- [ ] **Step 1: Write failing tests**

Add tests that import an existing asset with unchanged metadata and assert `crowdStrikeLastImportedAt` is refreshed, and import a new asset and assert the field is set.

- [ ] **Step 2: Verify red**

Run: `./gradlew :backendng:test --tests "com.secman.service.CrowdStrikeVulnerabilityImportServiceTest"`

Expected: compilation failure or assertion failure because `crowdStrikeLastImportedAt` does not exist.

- [ ] **Step 3: Implement minimal schema/entity/import update**

Add nullable column and index, add nullable `LocalDateTime` field to `Asset`, set it on create and update, and ensure `updateAsset` persists even when only the timestamp changed.

- [ ] **Step 4: Verify green**

Run: `./gradlew :backendng:test --tests "com.secman.service.CrowdStrikeVulnerabilityImportServiceTest"`

Expected: tests pass.

### Task 2: Add Backend Cleanup Service and API

**Files:**
- Modify: `src/backendng/src/main/kotlin/com/secman/repository/AssetRepository.kt`
- Create: `src/backendng/src/main/kotlin/com/secman/dto/CrowdStrikeAssetCleanupDto.kt`
- Create: `src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeAssetCleanupService.kt`
- Modify: `src/backendng/src/main/kotlin/com/secman/controller/AssetController.kt`
- Test: `src/backendng/src/test/kotlin/com/secman/service/CrowdStrikeAssetCleanupServiceTest.kt`

- [ ] **Step 1: Write failing service tests**

Cover: rejects non-positive days, dry-run returns candidates without deleting, non-dry-run deletes candidates through `AssetCascadeDeleteService`, assets with null CrowdStrike timestamp are excluded, and blocked deletions are reported without aborting the whole run.

- [ ] **Step 2: Verify red**

Run: `./gradlew :backendng:test --tests "com.secman.service.CrowdStrikeAssetCleanupServiceTest"`

Expected: failure because service/DTO/repository methods do not exist.

- [ ] **Step 3: Implement minimal backend**

Add repository query for `crowdstrikeLastImportedAt < cutoff`, DTOs for request/response/candidate/error, service logic, and `POST /api/assets/delete-not-seen-by-crowdstrike` secured to ADMIN.

- [ ] **Step 4: Verify green**

Run: `./gradlew :backendng:test --tests "com.secman.service.CrowdStrikeAssetCleanupServiceTest"`

Expected: tests pass.

### Task 3: Add CLI Command

**Files:**
- Create: `src/cli/src/main/kotlin/com/secman/cli/commands/DeleteAssetNotSeenCommand.kt`
- Modify: `src/cli/src/main/kotlin/com/secman/cli/SecmanCli.kt`
- Test: `src/cli/src/test/kotlin/com/secman/cli/SecmanCliTest.kt`

- [ ] **Step 1: Write failing CLI tests**

Assert top-level help includes `delete-asset-not-seen`, command help returns 0, invalid days are rejected before backend work, and routing exists.

- [ ] **Step 2: Verify red**

Run: `./gradlew :cli:test --tests "com.secman.cli.SecmanCliTest"`

Expected: help assertions fail because the command is absent.

- [ ] **Step 3: Implement minimal CLI command**

Use Picocli with one positional `<days>`, `--dry-run`, `--backend-url`, `--username`, `--password`, and `--verbose`. Authenticate with `CliHttpClient`, POST to the new backend endpoint, print candidate/deletion summary, and return non-zero on validation or backend errors.

- [ ] **Step 4: Verify green**

Run: `./gradlew :cli:test --tests "com.secman.cli.SecmanCliTest"`

Expected: tests pass.

### Task 4: Document and Run Focused Verification

**Files:**
- Modify: `docs/CLI.md`

- [ ] **Step 1: Update docs**

Document command usage, dry-run behavior, null timestamp/backfill guidance, and cron example.

- [ ] **Step 2: Run focused checks**

Run:
`./gradlew :backendng:test --tests "com.secman.service.CrowdStrikeVulnerabilityImportServiceTest" --tests "com.secman.service.CrowdStrikeAssetCleanupServiceTest"`
`./gradlew :cli:test --tests "com.secman.cli.SecmanCliTest"`

- [ ] **Step 3: Run broader verification if feasible**

Run `./gradlew build`. If time or environment blocks mandatory E2E gates, report that clearly and do not claim them.

### Self-Review

- Spec coverage: command, dry-run, import timestamp, existing-object backfill guidance, and running-system edge cases are covered.
- Placeholder scan: no placeholders remain.
- Type consistency: planned DTO/service names are consistent across tasks.
