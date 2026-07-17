# Race-Condition Analysis — Business Logic & SQL

Systematic review of the backend for concurrency defects: check-then-act windows,
lost updates, transaction-boundary mistakes, and SQL-level races. Each finding lists
the location, the interleaving that triggers it, the consequence, and the fix —
**Fixed** items were remediated in the change that introduced this document;
**Deferred** items are documented with a recommended follow-up.

Environment facts that frame the analysis:

- MariaDB / InnoDB with the connection pool at **READ COMMITTED**
  (see `CrowdStrikeVulnerabilityImportService` and `application.yml`). Uncommitted
  concurrent writes are invisible, so *pre-insert* count/exists checks can never
  see a racing insert.
- Only two entities carry `@Version` optimistic locking: `Workgroup` and
  `VulnerabilityExceptionRequest`. All other mutable shared entities have no
  lost-update protection.
- `hbm2ddl.auto: update` does **not** retro-add a `@Column(unique = true)` to a
  column that already existed — annotated uniqueness may be unenforced on
  long-lived databases (see Deferred D5).
- Micronaut AOP is bypassed on direct self-invocation: a `@Transactional` /
  `@Async` method called on `this` from the same class runs **without** its
  advice. The in-repo cure is the `selfProvider` pattern
  (`jakarta.inject.Provider<Self>`, see `CrowdStrikeVulnerabilityImportService`).

## Recurring root-cause patterns and their canonical fixes

| Pattern | Canonical fix (in-repo example) |
|---|---|
| Check-then-act (find→create, count→insert, read-flag→send) | DB unique constraint + catch-violation-and-refetch; or atomic guarded `UPDATE … WHERE <precondition>` checking affected rows; or insert-then-recheck with deterministic ranking |
| Read-modify-write without `@Version` | `@Version` + `DeadlockRetry` (see `VulnerabilityExceptionRequest` approval flow) |
| Self-invocation bypassing `@Transactional`/`@Async` | `selfProvider.get().method()` (`CrowdStrikeVulnerabilityImportService`) |
| `@Scheduled` jobs with no mutual exclusion | Per-row claim via guarded `UPDATE` in `REQUIRES_NEW` (this change); optionally a `GET_LOCK`-based scheduler mutex (deferred) |
| Side effects racing the publishing transaction | `@TransactionalEventListener(AFTER_COMMIT)` (`ExceptionRequestNotificationListener`) — but only when every publisher runs inside a transaction |

---

## HIGH severity

### H1 — Asset find-or-create races (no unique constraint on `asset.name`) — *Mitigated, constraint Deferred (D1)*

**Locations:** `AssetMergeService.findOrCreateAsset` / `importAsset`,
`CrowdStrikeVulnerabilityImportService.createNewAsset`,
`AwsAccountRiskAssessmentService.findOrCreateAccountAsset`,
`VulnerabilityService.addVulnerabilityFromCliLocked`.

**Race:** every path is `findByName(…)` → conditional `save(…)`. Two concurrent
imports of the same brand-new hostname both see "absent" and both insert.
`asset.name` has only a non-unique index (`idx_asset_name`), so the result is
**two asset rows with the same name** — split vulnerability history, inflated
dashboard counts. The CrowdStrike importer's `PESSIMISTIC_WRITE` lock only covers
assets that already exist; it cannot lock a row that hasn't been inserted yet.
Concurrent importers are the norm (the CrowdStrike CLI runs 3 workers).

**Fixed (mitigation):** `AssetMergeService.findOrCreateAsset` and
`AwsAccountRiskAssessmentService.findOrCreateAccountAsset` now catch a failed
create and re-fetch/merge into the winner's row. The CLI path is serialized by the
repaired striped lock (H5). **Deferred:** the real fix is a DB unique constraint —
see D1 for why that migration is not auto-generated here.

### H2 — Requirement-ID sequence read without a lock — *Fixed*

**Location:** `RequirementIdService.getNextId` / `resetSequence`;
`RequirementIdSequenceRepository`.

**Race:** the repository method was *named* `findByIdForUpdate` but its explicit
`@Query` was a plain JPQL `SELECT` with no `@Lock` — no `FOR UPDATE` was ever
emitted. Two concurrent requirement creations read the same `next_value`, both
format `REQ-N`, and the loser dies on the `uk_requirement_internal_id` unique
constraint (and the sequence advances by 1 instead of 2).

**Fix:** `getNextId`/`resetSequence` now lock the sequence row via
`entityManager.find(…, LockModeType.PESSIMISTIC_WRITE)` (a real
`SELECT … FOR UPDATE`) inside their `@Transactional`; the misleading repository
method was removed.

### H3 — Materialized-view swap not atomic (self-invocation) — *Fixed*

**Location:** `MaterializedViewRefreshService.executeRefresh` →
`swapMaterializedView`.

**Race:** `swapMaterializedView` is `@Transactional` and was written to make the
`deleteAll()` + `saveAll()` replacement atomic — but it was invoked on `this`
from `executeRefresh`, so Micronaut's AOP proxy was bypassed and the two
repository calls committed **separately**. Any reader (user dashboard "Overdue
Patching", Outdated Assets page) between the two commits saw an empty or
half-built view — exactly the "false 0 overdue assets" bug the method's comment
claims to have fixed. `updateJob` had the same latent issue.

**Fix:** injected `Provider<MaterializedViewRefreshService>` and routed
`swapMaterializedView`, `updateJob`, and `executeRefreshAsync` through
`selfProvider.get()` (the established Feature-053 pattern).

### H4 — Materialized-view refresh double-start — *Fixed*

**Location:** `MaterializedViewRefreshService.triggerAsyncRefresh`.

**Race:** the "already running?" guard was `findRunningJob()` (plain read) →
`save(new RUNNING job)`. Two concurrent triggers (manual endpoint + CLI-import
trigger, or two import batches) both read `null` and both started a full refresh.
Two concurrent refreshes make even a correctly-atomic swap meaningless — the view
can end up doubled. The stale-recovery branch also started a second refresh on
top of a slow-but-alive 60-minute job.

**Fix:** insert-then-recheck. After committing its job row, each trigger re-reads
all RUNNING jobs; only the lowest job id proceeds — younger duplicates mark
themselves FAILED ("Duplicate trigger") and return the winner. Deterministic under
any interleaving because every contender sees all committed rows post-insert.

### H5 — CLI add-vulnerability striped lock broken — *Fixed*

**Location:** `VulnerabilityService.addVulnerabilityFromCli`.

**Race:** the per-hostname lock was removed from the map in `finally`
(`cliAddLocks.remove(lockKey, lock)`). Interleaving: A holds `lock1`, B blocks on
`lock1`, A finishes and removes the entry, C creates a fresh `lock2` and enters —
then B acquires the now-orphaned `lock1` and runs **concurrently with C** for the
same hostname. That defeats the exact duplicate-asset/lost-update protection the
lock was built for.

**Fix:** entries are no longer removed — the map is bounded by the number of
distinct hostnames per JVM lifetime (one `Any` each), which is the standard
correct form of this idiom.

### H6 — GitHub Dependabot import replace unguarded — *Fixed (+ V243)*

**Location:** `GithubRepoImportService.importRepositories` / `persistRepo`.

**Race (three defects):**
1. `persistRepo` (`@Transactional`) was self-invoked → advice bypassed → the
   snapshot insert, alert delete, and alert reinsert each committed separately.
2. No lock: two concurrent imports (CLI + UI "Import now") interleave
   delete₁/delete₂/insert₁/insert₂ → **duplicated alert rows** per repo.
3. No unique constraint on `(github_repository_id, alert_number)` to backstop it.

**Fix:** `persistRepo` is now called through `selfProvider` inside
`DeadlockRetry.withRetry`; it takes a `PESSIMISTIC_WRITE` lock on the repo row
(mirroring the CrowdStrike import); migration **V243** dedupes existing rows and
adds `uk_ghalert_repo_alert UNIQUE (github_repository_id, alert_number)` (also
declared on the entity).

### H7 — Assessment token double-use — *Fixed*

**Location:** `ResponseController.submitAssessment`; `AssessmentToken`.

**Race:** the one-time token was consumed by `read isUsed==false` →
`markAsUsed()` → `update()`. Two concurrent submits both passed `isValid()` and
both completed the assessment; the "used" state was never claimed atomically
(no `@Version`, no guarded update).

**Fix:** `AssessmentTokenRepository.claimToken` — a guarded
`UPDATE … SET is_used = true WHERE token = ? AND is_used = false` returning the
affected-row count. Exactly one submit wins; the loser gets the
`TOKEN_EXPIRED` response.

### H8 — User-created event listener vs. publishing transaction — *Hardened; dead listener documented*

**Locations:** `UserMappingService.onUserCreated` (`@EventListener @Async`),
publishers in `UserService.createUser`, `AddUserTool`, `OAuthService`.

**Race:** an `@Async` `@EventListener` fires immediately on `publishEvent`, on
another thread. If a publisher ever runs inside a transaction, the listener races
the commit: at READ COMMITTED it cannot see the user row (silently skips mapping
application) or links mappings to an entity whose transaction may still roll back.
Today's live publishers happen to publish **after** their save has committed, so
the window is latent, not active — but nothing enforces that. Converting the
listener to `@TransactionalEventListener(AFTER_COMMIT)` would be wrong here:
Micronaut *drops* such events when no transaction is active, which would break all
current (non-transactional) publishers.

**Fix:** `applyFutureUserMapping` now re-loads the user by id inside its own
transaction and skips gracefully when the row isn't visible, instead of writing
FKs against a detached, possibly-uncommitted instance.

**Discovered defect (out of scope, flagged):**
`UserMappingApplicationService.onUserCreated` listens to a shadow event class
`com.secman.service.UserCreatedEvent` defined at the bottom of its own file, while
every publisher publishes `com.secman.event.UserCreatedEvent` — the listener
**never fires**, so Feature 049's PENDING-mapping auto-application is dead code.
Enabling it would race `UserMappingService.onUserCreated` over the same rows, so
this needs a deliberate consolidation, not a one-line fix. Also:
`UserController.create` (`@Transactional`) creates users **without publishing the
event at all**, so admin-UI-created users never get pending mappings applied.

### H9 — Safety brakes: count in one snapshot, delete in another — *Partially fixed (asset-match-clear); rest Deferred (D4)*

**Locations:** `AssetMatchClearService.clear`,
`CrowdStrikeCleanupAuditService.run`/`checkSafetyBrake`,
`CrowdStrikeVulnerabilityImportService.reconcileStaleCrowdStrikeImports`.

**Race:** candidate lists, brake denominators, and the actual deletes are separate
statements at READ COMMITTED with no shared snapshot. Worst case in
asset-match-clear: an asset re-imported (its `cloudInstanceId` re-matched) between
the scan and the delete loop was still deleted from the stale candidate list —
**a live asset destroyed**. Two overlapping cleanup runs can also each stay under
`maxDeletePercent` while their union exceeds it.

**Fix (asset-match-clear):** each candidate is now re-verified immediately before
deletion (row still exists, instance id still absent from the snapshot, account
still in scope); re-matched assets are skipped and counted as
`revalidationSkips`. **Deferred:** single-transaction brake recomputation and a
run-level mutex for the CrowdStrike cleanup (D4).

---

## MEDIUM severity

### M1 — Export-job concurrency caps count-then-insert — *Fixed*

**Location:** `ExportJobService.startExport`.

**Race:** `count running < cap` → `save` is not atomic; N concurrent requests all
pass before any insert is visible, so `maxConcurrentPerUser` (default 1) and
`maxConcurrentGlobal` (default 5) were advisory. Memory-bound Excel exports could
pile past the ceiling the caps exist to enforce.

**Fix:** the job row is inserted and committed first (`createJobRow`,
REQUIRES_NEW via self-proxy), then re-checked against committed state: jobs are
ranked `(createdAt, id)` and only ranks within the cap survive; losers fail their
own job and throw the same user-facing error as the pre-check. The pre-checks
remain for the fast common path.

### M2 — AI-job caps (per-assessment + global) count-then-insert — *Fixed*

**Location:** `AiSuggestionJobService.startJob` / `runJobInBackground`.

**Race:** same shape as M1. Two concurrent starts for one assessment both passed
the `findByRiskAssessmentIdAndStatusIn` guard → two active AI jobs per assessment
(double LLM spend, racing suggestion writes); the global cap
(`maxConcurrentJobsGlobal`) was likewise exceedable — defeating the operator's
cost-concurrency budget.

**Fix:** `checkStartRace` runs at the top of the background runner, **after** the
job row is committed and **before any paid LLM call**: the lowest active job id
per assessment wins; globally only the oldest `cap` active jobs proceed; losers
mark themselves FAILED with a clear reason.

### M3 — Unguarded job status transitions — *Fixed*

**Locations:** `ExportJobService.markJobAsProcessing`/`markJobAsCompleted`/
`markJobAsFailed`; `AiSuggestionJobService.markRunning`/`markFailed`/
`applySuccess`/`applyFailure`/`incrementFailed`.

**Race:** all transitions blindly `findById` → set status → `update`. A stale-job
auto-reset (5-min heartbeat threshold, which a long COUNTING query can exceed) or
a cancel could mark a job FAILED/CANCELLED while the worker was still running —
and the worker's `markJobAsCompleted` then **resurrected** it to COMPLETED. AI
jobs kept accruing cost/counters and writing `Response` rows after cancellation.

**Fix:** every transition now verifies the current status first
(PENDING/PROCESSING → terminal only; terminal states are never overwritten);
`applySuccess`/`applyFailure` drop results for terminal jobs (a cancelled job's
in-flight requirement no longer writes suggestions); an orphaned export file is
deleted when completion loses the race.

### M4 — Scheduler double-runs: expiration + reminder double-sends — *Fixed*

**Locations:** `ExceptionExpirationScheduler.processExpirations` /
`sendExpirationReminders`; `AwsAccountRiskAssessmentService.processDeadlineReminders`.

**Race:** with two app instances (or a run overlapping a slow previous one), both
runs loaded the same APPROVED-expired requests and both sent expiration emails and
wrote audit rows; the reminder dedup (`reminderSentAt == null` → send → stamp) was
read-then-write, so both runs sent. The AWS risk-assessment 2-day/1-day reminders
had the identical pattern.

**Fix:** claim-before-send everywhere. Expirations: atomic
`UPDATE … SET status='EXPIRED' WHERE id=? AND status='APPROVED'` in a
REQUIRES_NEW transaction (bumping the `@Version`); only the winner runs the side
effects. Reminders: atomic `SET …_sent_at = ? WHERE …_sent_at IS NULL`; only the
winner emails; a failed send releases the claim (best-effort) so the next run
retries — the documented trade-off is that a lost release skips a reminder rather
than duplicating it. Loaded entities are deliberately never mutated in the outer
session so the claims' version bumps can't trigger optimistic-lock rollbacks.

### M5 — OAuth state not atomically consumed — *Fixed*

**Locations:** `OAuthController.callback`/`callbackApi`; `OAuthService`.

**Race:** the state row was deleted only at the **end** of `handleCallback`, so
for the whole token-exchange window two callbacks carrying the same state
(browser double-submit, provider retry) both passed the read-only lookup and both
entered the flow. Only the IdP's single-use authorization code prevented a double
login; for a brand-new user both callbacks raced `findOrCreateUser`, and the loser
turned a valid login into "Could not create your account". (The existing
`findStateByValueWithRetry` addresses the *opposite* race — a fast Azure callback
arriving before the state-save commit is visible — and is unchanged.)

**Fix:** the controller now claims the state atomically before processing —
`deleteByStateToken` returns its row count and `claimOAuthState` (REQUIRES_NEW)
lets exactly one callback win; the loser is rejected up front. Additionally,
`findOrCreateUser` recovers from a lost provisioning race by re-fetching the
winner's committed user instead of failing the login.

### M6 — Duplicate AWS-account risk assessments — *Fixed (app-level)*

**Location:** `AwsAccountRiskAssessmentService.createAssessment`;
`aws_account_risk_assessment` (V237, no unique constraint).

**Race/defect:** nothing prevented a re-run (or two overlapping runs) of the
mapping import from creating a second `RiskAssessment` + tracking row for the same
`(account, owner)` — duplicate owner emails and a duplicate reminder stream.

**Fix:** creation is now idempotent — skipped (with the existing assessment id
reported) when an **open** (STARTED) assessment is already tracked for the pair.
A hard DB unique on `(aws_account_id, owner_email)` was deliberately not added: a
*new* assessment for the same pair is legitimate once the previous one completed;
only concurrent/repeated creation of open ones is a defect. The narrow
two-overlapping-imports window that remains is bounded by the sequential
processing inside each import run.

### M7 — find-then-save "upserts" on unique keys — *Fixed*

**Locations:** `VulnerabilityStatisticsCacheService.upsertCache`,
`AwsCleanServerKpiService.upsertCache`,
`CrowdStrikeVulnerabilityImportService.upsertAndUnionSeverityHistory`.

**Race:** each was `findByKey` → insert-or-update. Two concurrent writers both saw
"absent" and both inserted; the loser hit the unique/PK constraint. For the
severity history this **rolled back the loser's entire reconcile transaction**;
for the statistics cache it failed a refresh.

**Fix:** native `INSERT … ON DUPLICATE KEY UPDATE` repository methods
(`upsertByCacheKey`, `upsertSeverity`) — conflict-free under any interleaving,
using the constraints that already exist (`cache_key` unique, severity PK).

---

## LOW severity / informational (no code change)

- **No `@Version` on hot mutable entities** — `Asset`, `UserMapping`,
  `AppSettings`, `RiskAssessment`, `Vulnerability`, `ExportJob`,
  `AiSuggestionJob`, `AssessmentToken`. Concurrent read-modify-write flows
  (e.g. `AssetMergeService.mergeAssetData` vs. CrowdStrike `updateAsset`) are
  last-writer-wins: a group-append computed on a stale snapshot can drop the other
  importer's groups. Recommended: selective `@Version` rollout combined with
  `DeadlockRetry`-style retries, starting with `Asset`.
- **`AppSettings` singleton by convention only** — `getOrCreateSettings` is
  find-all-or-create with no constraint; two concurrent first calls can create two
  rows and `findAll().first()` becomes nondeterministic. Low traffic; fix together
  with D5.
- **Shared SSE progress sink** — `MaterializedViewRefreshService` uses one
  process-wide multicast sink for all refresh jobs and has no replay; subscribers
  see other jobs' events (filterable by `jobId`) and late subscribers miss early
  events. `ExceptionBadgeUpdateHandler`'s KDoc claims `Replay(1)` but builds a
  plain multicast sink (the initial count is delivered via `Flux.concat`, so
  behavior is correct — the comment is wrong).
- **`EmailNotificationEventListener`** — not transaction-phased and runs on a
  detached coroutine scope; in-flight sends are silently dropped on shutdown.
  No delivery guarantee is currently claimed, so documented only.
- **`AiSuggestionJobRepository.findStaleByStatus`** treats a QUEUED job with a
  `NULL` heartbeat as immediately stale; the hourly watchdog could kill a job in
  the sub-second window before `markRunning` stamps the first heartbeat. The new
  status guards prevent any resurrection weirdness; consider adding
  `createdAt < threshold` to the staleness predicate.
- **Correct-by-design examples worth copying:** exception-request approval
  (`@Version` + `DeadlockRetry`), CrowdStrike per-asset replace
  (`PESSIMISTIC_WRITE` + deadlock retry), `ExceptionRequestNotificationListener`
  (`AFTER_COMMIT`), `ExceptionMaterializationService` (all races biased toward
  the safe direction + hourly/daily reconciliation sweeps),
  `AccessibleAssetIdsCache` (`@RequestScope`, no sharing).

---

## Deferred items (recommended follow-ups)

- **D1 — `UNIQUE` constraint on `asset.name`.** The definitive fix for H1. Not
  auto-generated here because production databases may already contain duplicate
  names, and the dedup migration must merge rows while re-pointing FKs
  (vulnerabilities, scan_results, workgroup links, tags) — a data-shape-dependent
  operation that should be run deliberately: (1) report duplicates, (2) merge
  keeping the row with relations/lowest id, (3) `ALTER TABLE asset ADD CONSTRAINT
  uk_asset_name UNIQUE (name)`. Until then the app-level mitigations from H1/H5
  bound the exposure.
- **D2 — Distributed scheduler mutex.** The per-row claims (M4) remove the
  harmful double-sends, but every `@Scheduled` method still *runs* on every
  instance. A MariaDB `GET_LOCK`-based mutex (or a claim table) would skip
  redundant runs wholesale. Architectural choice — one advisory-lock helper could
  serve all schedulers.
- **D3 — `@Version` rollout** (see LOW section).
- **D4 — CrowdStrike cleanup brake in one transaction** + run-level mutual
  exclusion between the scheduled and manual cleanup paths; same for the
  reconcile sweep's refreshed/stale counters (currently three separate statements
  at READ COMMITTED).
- **D5 — Schema audit for annotated-but-unenforced unique constraints.** Because
  `hbm2ddl.auto: update` won't add unique indexes to pre-existing columns, verify
  in production that `users.username`, `users.email`, and other
  `@Column(unique = true)` columns actually carry unique indexes; add explicit
  Flyway constraints where missing (with dedup pre-steps).
- **D6 — Consolidate the user-created listeners** (H8): delete or rewire the dead
  `UserMappingApplicationService` listener and its shadow event class, and decide
  whether `UserController.create` should publish `UserCreatedEvent`.
