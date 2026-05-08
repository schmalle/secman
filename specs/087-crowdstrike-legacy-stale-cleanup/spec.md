# Feature Specification: CrowdStrike Legacy Stale-Asset Cleanup

**Feature Branch**: `087-crowdstrike-legacy-stale-cleanup`
**Created**: 2026-05-08
**Status**: Draft
**Input**: User description: "Extend CrowdStrike stale-asset cleanup to also identify and remove legacy CrowdStrike-imported assets where `crowdstrike_last_imported_at IS NULL`, gated behind an additive include-legacy flag."

## Clarifications

### Session 2026-05-08

- Q: When the legacy toggle is OFF, should the dry-run summary still show a preview of legacy candidates that would be selected if the toggle were enabled? → A: No — the toggle is a complete gate. Toggle OFF means the dry-run summary shows zero legacy candidates and no preview; real and dry runs with the same inputs always produce the same candidate set. Admins who want to see legacy candidates must turn the toggle ON for that one dry-run.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Admin reclaims stale legacy CrowdStrike rows (Priority: P1)

A platform admin opens the Falcon configuration page to clean up the asset inventory. They notice that many assets imported from CrowdStrike before the last import-tracking change still appear in reports as "stale" but have never been picked up by the existing cleanup job, because those rows have no record of when CrowdStrike last imported them. The admin enables a new opt-in toggle, runs a dry-run for a chosen staleness threshold, reviews the candidate list (each row labelled with the reason it was selected), and then executes the cleanup. Stale legacy rows are removed; manually-created and scan-uploaded assets are never touched.

**Why this priority**: Without this story the inventory cannot be brought to a clean baseline — the existing scheduled job will keep running but legacy rows remain invisible to it forever, causing operational reporting (outdated assets, vulnerability heatmap, MCP queries) to drift further from reality every month.

**Independent Test**: Seed the database with one legacy CrowdStrike row (no import timestamp, owner = "CrowdStrike Import", `last_seen` older than 30 days) and one manually-created asset with the same characteristics. Enable the toggle, run a dry-run with 30-day threshold. Result: only the legacy CrowdStrike row appears in candidates, labelled with the legacy reason. Run the actual cleanup. Result: the legacy row is deleted; the manually-created row is untouched.

**Acceptance Scenarios**:

1. **Given** a legacy CrowdStrike-imported asset with no `crowdstrike_last_imported_at`, owner literal of "CrowdStrike Import", no manual creator, no scan uploader, and a `last_seen` older than the threshold, **When** an admin runs the cleanup with the legacy toggle enabled, **Then** the asset is listed as a candidate with reason "legacy" and is deleted on a non-dry-run.
2. **Given** the same legacy row, **When** an admin runs the cleanup with the legacy toggle disabled, **Then** the asset is **not** listed as a candidate and is **not** deleted (existing behaviour preserved).
3. **Given** an asset matching the legacy pattern but with a manual creator OR a scan uploader recorded against it, **When** the cleanup runs with the toggle enabled, **Then** the asset is **not** selected — manual/scan ownership protects it.
4. **Given** a legacy row whose `last_seen` is null but whose `updated_at` is older than the threshold, **When** the cleanup runs with the toggle enabled, **Then** the asset is selected (the staleness check falls through to the next available timestamp).
5. **Given** two stale assets — one selected by the timestamp rule (its `crowdstrike_last_imported_at` is old) and one by the legacy rule (its `crowdstrike_last_imported_at` is null) — and noting that the two predicates are mutually exclusive on the import-timestamp column, **When** the cleanup runs with both rules active, **Then** each asset appears exactly once in the candidate list with the reason it was selected, and one delete is attempted per asset. (De-duplication by id is a defensive belt-and-braces step that has no practical effect under the current predicates but protects correctness if either predicate is widened in the future.)

---

### User Story 2 - Safety brake stays meaningful with widened scope (Priority: P1)

The scheduled cleanup runs nightly with a configurable safety brake — if the candidate set exceeds a chosen percentage of all CrowdStrike-tracked assets, the run is aborted and admins are notified. When the legacy rule is enabled, the candidate set grows; if the brake denominator stays the same (only counting timestamped rows), the brake will trip on perfectly normal cleanups.

**Why this priority**: A safety brake that fires false positives is worse than no brake — admins start ignoring it, then it fails to catch the real "we just deleted all the assets" incident it was designed to prevent.

**Independent Test**: Seed 1000 timestamped CrowdStrike assets and 50 legacy CrowdStrike assets, then make a small fraction of legacy assets stale. Run the scheduled job with the legacy toggle on and the brake at 10%. Result: brake does NOT trip when the true blast radius (numerator counting both rules / denominator counting both rule populations) is at or below 10%.

**Acceptance Scenarios**:

1. **Given** the legacy toggle is on and the candidate set includes both rule types, **When** the safety brake percentage is computed, **Then** the denominator includes both timestamped CrowdStrike-tracked rows AND legacy CrowdStrike-origin rows.
2. **Given** a run that aborts on the safety brake, **When** the audit row is persisted, **Then** the row records both the legacy candidate count and the legacy deletion count (zero in this case) so admins can see how the legacy rule contributed.

---

### User Story 3 - Admin can audit per-run blast radius of the legacy rule (Priority: P2)

After turning the legacy rule on, an admin needs to verify that nightly runs are doing the right thing — specifically, how many of the deleted rows came from the legacy rule versus the existing timestamp rule. They open the Stale Asset Cleanup history table and see a per-run breakdown.

**Why this priority**: Operational confidence after rollout. Without per-run visibility, the only way to answer "did the new rule misbehave last night?" is a manual database query, which means the rule will go un-audited and a bad day will hide in the noise.

**Independent Test**: Run two cleanups: one with the legacy toggle off (expects timestamp candidates only), one with it on (expects mixed). The history table shows both runs, and the on-run row clearly shows the legacy contribution.

**Acceptance Scenarios**:

1. **Given** a completed cleanup run that mixed both rules, **When** an admin opens the cleanup history panel, **Then** that run's row shows the count of legacy candidates and the count of legacy deletions distinct from the totals.
2. **Given** a run that used only the timestamp rule (toggle off or no legacy candidates existed), **When** the admin views history, **Then** the legacy columns show zero and the run's display is unchanged from prior behaviour.

---

### Edge Cases

- A row matches the legacy pattern but is also a member of an active workgroup or release — the cleanup must still respect cascade-delete behaviour and notification rules already in place for the timestamp rule. There is no separate path; the same asset cascade-delete service handles both.
- All three timestamp candidates for the staleness check (`last_seen`, `updated_at`, `created_at`) are null. This is theoretically impossible because `created_at` is auto-managed, but the rule MUST treat such a row as not stale (do not delete) rather than as infinitely stale.
- The owner literal "CrowdStrike Import" is renamed in a future release. The rule is keyed off a single shared constant — changing that constant in one place is the upgrade path. The spec deliberately does NOT broaden the matcher to "any owner string containing the word CrowdStrike", since that would sweep manually-named owners.
- A manual run requests `includeLegacy=true` while the configured default is `false`. The per-run override wins for that one run; the configured default is unchanged for the next scheduled run.
- The configured default is `true` but a manual run explicitly requests `includeLegacy=false`. The override wins for that run.
- Two runs are kicked off back-to-back. The audit-row writes are serialised through the existing run repository — no special concurrency handling is added.
- A manual cleanup run with `includeLegacy=true` does NOT apply the safety brake. Manual runs intentionally bypass the brake (the admin is the deliberate decision-maker, typically after a dry-run) and that behaviour is preserved when the legacy rule contributes candidates. The brake — with its widened denominator per FR-007 — applies only to the scheduled run.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The cleanup pipeline MUST identify legacy CrowdStrike-origin assets as cleanup candidates, using the four-part fence: owner literal "CrowdStrike Import" AND no recorded CrowdStrike import timestamp AND no manual creator AND no scan uploader.
- **FR-002**: The legacy rule MUST treat an asset as stale when the most recent of `last_seen`, `updated_at`, and `created_at` (in that fall-through order) is older than the configured threshold.
- **FR-003**: The legacy rule MUST be gated behind an additive configuration flag whose effective value defaults to "off" for the first release after which this feature ships.
- **FR-004**: The existing timestamp-based selection rule MUST remain unchanged in its query and its semantics — this feature is additive.
- **FR-005**: When both rules contribute candidates, candidate selection MUST de-duplicate by asset id so that an asset matching both rules is processed exactly once.
- **FR-006**: Each candidate MUST carry a machine-readable reason field that distinguishes "selected by timestamp rule" from "selected by legacy rule" so the dry-run UI and audit history can render it.
- **FR-007**: The safety brake's percentage calculation MUST use a denominator that includes both timestamped CrowdStrike-tracked assets AND legacy CrowdStrike-origin assets (whether stale or not).
- **FR-008**: The audit row persisted for each non-dry-run cleanup MUST record, in addition to existing fields, the count of legacy-rule candidates and the count of legacy-rule deletions for that run.
- **FR-009**: The cleanup-config endpoint MUST expose the effective default of the legacy toggle so the admin UI shows the current configured state rather than a hardcoded value.
- **FR-010**: A manual cleanup request MUST accept an optional per-run override for the legacy toggle; when omitted, the configured default applies.
- **FR-011**: The admin "Stale Asset Cleanup" panel MUST present a toggle for including legacy rows that initialises from the cleanup-config endpoint.
- **FR-012**: When the legacy toggle is enabled (per configured default or per-run override), the dry-run summary in the admin panel MUST display the candidate split (timestamp vs. legacy) so admins see the contribution of each rule before authorising a destructive run. When the legacy toggle is disabled, the dry-run summary MUST NOT include legacy candidates and MUST NOT show a "would-find-N-legacy" preview — the toggle is a complete gate and the dry-run summary always reflects exactly what a real run with the same inputs would produce.
- **FR-013**: The cleanup history table MUST display the legacy candidate and legacy deletion counts as distinct columns or a clearly labelled compound cell.
- **FR-014**: The owner literal "CrowdStrike Import" MUST be defined exactly once in code and referenced everywhere it is checked or written, so rule B's predicate cannot drift from how rows are created.
- **FR-015**: Manually-created assets (those with a recorded manual creator) and scan-uploaded assets (those with a recorded scan uploader) MUST never be selected by the legacy rule, regardless of any other field.
- **FR-016**: The system MUST NOT backfill the CrowdStrike import timestamp on legacy rows as part of this feature — cleanup is the chosen remediation.

### Key Entities

- **CrowdStrike-origin asset (legacy)**: An asset row whose `owner` field is the canonical CrowdStrike-import literal, whose CrowdStrike-import timestamp is unrecorded, and which has not been adopted by manual creation or scan upload. These rows pre-date the introduction of the import-timestamp column and are otherwise indistinguishable from timestamped CrowdStrike-origin assets except that the cleanup pipeline cannot currently see them.
- **Cleanup candidate**: A nominated asset for deletion in a given cleanup run. Each candidate now carries a "selection reason" (timestamp-rule vs. legacy-rule) so the audit trail and admin UI can attribute it.
- **Cleanup run (audit row)**: The persisted record of a non-dry-run cleanup execution. Gains two additional integer fields for legacy candidate count and legacy deletion count.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After enabling the legacy rule on a representative dataset, dry-run candidate counts increase by at least the number of legacy CrowdStrike-origin rows that are stale by `last_seen`/`updated_at`/`created_at` — i.e., the rule actually finds rows that the existing rule misses.
- **SC-002**: Zero manually-created or scan-uploaded assets are selected by the legacy rule across acceptance and integration tests covering every fence-violation case.
- **SC-003**: Across acceptance scenarios where an asset matches both rules, the candidate appears exactly once in the dry-run output and exactly one delete is attempted on a real run (de-duplication by id is enforced).
- **SC-004**: With the legacy rule on, the safety brake does NOT abort a *scheduled* run whose true blast-radius percentage (counting both rule types in numerator and denominator) is at or below the configured limit. (Manual runs continue to bypass the brake by design.)
- **SC-005**: Admins reviewing cleanup history can attribute every deletion to a rule (timestamp or legacy) without consulting the database directly — the history panel exposes the breakdown.
- **SC-006**: The admin UI's legacy toggle initial state matches the backend's configured default in 100% of page loads (no hardcoded UI defaults).
- **SC-007**: All mandatory project gates pass (build, backend dev startup, JS-error scan for both admin and normal-user roles, vulnerability-exception E2E) before the feature is considered done.

## Assumptions

These are the locked technical decisions captured from the user's input. They are intentionally specific because the matching code paths are already well-known.

- **Selection predicate (legacy rule, "rule B")** is exactly: `owner = <shared owner constant>` AND `crowdStrikeLastImportedAt IS NULL` AND `manualCreator IS NULL` AND `scanUploader IS NULL` AND `COALESCE(lastSeen, updatedAt, createdAt) < cutoff`. No other matcher is acceptable.
- **Selection predicate (timestamp rule, "rule A")** stays exactly as today: `crowdStrikeLastImportedAt < cutoff` via the existing `findByCrowdStrikeLastImportedAtBefore` query. No edits to that query.
- **Configuration key**: `secman.crowdstrike.cleanup.include-legacy` (env: `CROWDSTRIKE_CLEANUP_INCLUDE_LEGACY`). Default value `false` for the first shipping release.
- **Owner literal**: the canonical string is `"CrowdStrike Import"`, currently duplicated in `CrowdStrikeVulnerabilityImportService.createNewAsset` and `AssetController.getOwnerCandidates`. This feature introduces a single shared constant and makes both call sites reference it; rule B's predicate uses the same constant.
- **Reason enum** values: `TIMESTAMP_STALE`, `LEGACY_NULL_TIMESTAMP`. Added to the cleanup candidate DTO.
- **Audit-row schema delta**: two new integer columns on `crowdstrike_cleanup_run` — `legacy_candidate_count` and `legacy_deleted_count` — both `NOT NULL DEFAULT 0`, populated via Flyway migration plus matching Hibernate field updates on `CrowdStrikeCleanupRun`.
- **API delta**: the cleanup-config response gains an `includeLegacy` boolean. The manual-cleanup request body gains an optional `includeLegacy` (null = use configured default).
- **No description-based detection**: the `description` column is null on the legacy rows shown by the user; the owner literal is the durable contract.
- **No backfill**: legacy rows are NOT backfilled with a synthetic `crowdstrike_last_imported_at`; they are deleted via the new rule.
- **De-duplication strategy**: `.distinctBy { it.id }` after combining the two candidate lists; the order in which rules are evaluated does not change which candidate "wins" the reason since downstream code treats both identically for deletion.
- **Scheduled job uses the configured default**: the daily cleanup scheduler reads `secman.crowdstrike.cleanup.include-legacy` and does not accept a per-run override (only manual API runs do).
- **Mandatory gates** per project policy: `./gradlew build` clean, `./scripts/startbackenddev.sh` starts cleanly (then stop), `/e2ejs` reports 0 errors for admin and normal-user runs against `SECMAN_HOST`, `/e2evulnexception` runs with 0 failures.

## Dependencies

- Existing `AssetCascadeDeleteService` is reused unchanged — it already handles the cascade-delete semantics (workgroups, releases, vulnerabilities, etc.) for the timestamp rule. This feature does not introduce a separate delete path.
- Existing `CrowdStrikeCleanupAuditService` is reused unchanged in its orchestration — only its candidate-set assembly and brake denominator widen, and it gains the two new audit-row fields.
- Existing `CrowdStrikeStaleAssetCleanupScheduler` is reused unchanged — it continues to call `auditService.run(...)` and inherits the widened candidate set automatically once the new flag is on.
- The Falcon admin config page (`/admin/falcon-config`) is the only frontend surface that changes. No new pages or routes.
