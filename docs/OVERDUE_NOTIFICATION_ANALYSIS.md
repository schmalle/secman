# Overdue Vulnerability Notifications — When Are Users Informed?

*Analysis date: 2026-10-26 · Basis: static code reading on `dev` @ `faa414a1c` · Not runtime-verified*

## 1. Summary

There are **two independent pipelines** that email users about overdue vulnerabilities. They share
only the recipient resolver and the 72 h Falcon window; thresholds, data sources, deduplication and
audit behaviour all differ.

| | **Pipeline A — Outdated-asset reminders** | **Pipeline B — AWS-account vulnerability alerts** |
|---|---|---|
| CLI | `send-notifications` | `send-notification-users`, `send-patch-notifications <prefix>` |
| MCP | `send_outdated_notifications` | `send_vulnerability_notifications`, `send_patch_notifications` |
| REST | `POST /api/cli/notifications/send-outdated` | `POST /api/cli/user-vulnerability-notifications/send` |
| Backend | `CliController.queryOutdatedAssets` → `NotificationService.processOutdatedAssets` | `UserVulnerabilityNotificationService.sendUserVulnerabilityNotifications` |
| Data source | `outdated_asset_materialized_view` (precomputed) | Live native SQL over `vulnerability` |
| Overdue threshold | Global config `reminderOneDays` (default 30, set in UI) — fixed at MV refresh time | `--days` per run (default 30) |
| Unit of email | One email per recipient, listing **assets** | One email per recipient, listing **AWS accounts** |
| Dedup / rate limit | Per-asset "once per UTC day" + level 1→2 escalation | **None** — every run re-sends |
| Audit (`notification_log`) | Yes (success per asset, failure for first asset) | **No** |
| Extra recipients | All `REPORT`-role users get everything | — |

**No scheduler exists.** Neither pipeline has an `@Scheduled` trigger. Users are informed **only**
when an operator (cron, script, or MCP agent) invokes one of the commands above.

---

## 2. Trigger conditions — when is a user informed?

A user receives an email only if **every** condition in the relevant column holds.

### 2.1 Pipeline A — `send-notifications`

| # | Condition | Code |
|---|---|---|
| A1 | Operator runs the command (ADMIN credentials) | `CliController` is `@Secured("ADMIN")` |
| A2 | Asset is in the MV: has ≥1 vuln with `excepted = 0`, `product_class <> 'INSTALLER_ARTIFACT'`, `sla_anchor_at < now − reminderOneDays`, asset is "current" (`AssetCurrentnessSql`) | `OutdatedAssetMaterializedViewRepository.rebuildFromOverdueVulnerabilities` |
| A3 | Vulnerability is visible: CrowdStrike rows **only HIGH/CRITICAL**; other sources all severities; snapshot must be active | `CrowdStrikeSnapshotSql.VISIBLE` |
| A4 | MV has been refreshed since the vuln became overdue (refresh only after CrowdStrike import or manual trigger) | `MaterializedViewRefreshService` |
| A5 | `asset.crowdStrikeAgentSeenAt` within the last 72 h (UTC, inclusive) | `CliController.kt:128` |
| A6 | Asset has ≥1 recipient (see §3) | `CliController.kt:131-147` |
| A7 | Asset not already sent today (UTC day of `asset_reminder_state.last_sent_at`) | `ReminderStateService.shouldSendToday` |
| A8 | SMTP send succeeds | `EmailSender` |

Level: `OUTDATED_LEVEL1` ("Action Requested"), escalates to `OUTDATED_LEVEL2` ("URGENT") once the
reminder state is ≥ 7 days old (`ESCALATION_THRESHOLD_DAYS`). An email uses the highest level among
its assets.

`REPORT` users: receive **all** rows each run, regardless of A7.

### 2.2 Pipeline B — `send-notification-users` / `send-patch-notifications`

| # | Condition | Code |
|---|---|---|
| B1 | Operator runs the command (ADMIN) | `CliController` |
| B2 | Asset has non-blank `cloud_account_id` (**AWS assets only**) | native SQL |
| B3 | `asset.crowdstrike_agent_seen_at` within the last 72 h | native SQL |
| B4 | ≥1 visible vuln (A3) with `excepted = 0` and `sla_anchor_at < now − --days` | native SQL |
| B5 | Recipient resolves for the account (global mode, §3) **or** is the `--notification-user` | `resolveAccountRecipients` |
| B6 | `send-patch-notifications`: email starts with `<prefix>` (case-insensitive) | Step 2c |
| B7 | SMTP send succeeds | `EmailService` |

### 2.3 Run modes of Pipeline B

| Invocation | Scope | Accounts covered | Who gets mail |
|---|---|---|---|
| no `--notification-user` | `GLOBAL_AWS_OVERDUE` | all affected | every resolved recipient (§3) |
| `--notification-user X`, X is **ADMIN** | `GLOBAL_AWS_OVERDUE` | all affected | X only — but only if X is a resolved recipient of ≥1 account, otherwise **nothing** (see G-B4) |
| `--notification-user X`, X not ADMIN | `USER_SCOPED_AWS_OVERDUE` | X's direct mappings + received shares | X only |
| `--notification-user X --notall` | `RESTRICTED_AWS_OVERDUE` | X's direct mappings + workgroup account grants + received shares | X only |
| `--notification-user X` where X does not exist | — | — | nobody; CLI exits 2 |
| `send-patch-notifications a` | `GLOBAL_AWS_OVERDUE` | all affected | resolved recipients whose email starts with `a` |

---

## 3. Recipient resolution vs. the Unified Asset Access model

`AwsAccountRecipientResolver.resolveAwsAccountRecipients(cloudAccountId)` is the shared resolver.
Pipeline A additionally adds `UserMapping` rows whose `awsAccountId == asset.owner` (legacy).

| Access path (CLAUDE.md §Unified Asset Access) | Can view the asset? | Pipeline A (CLI) | Pipeline A (MCP) | Pipeline B global | B user-scoped | B `--notall` |
|---|---|---|---|---|---|---|
| 1. ADMIN / SECCHAMPION | ✅ | ❌ | ❌ | ❌ (unless mapped) | — | — |
| 2. Explicit asset → workgroup membership | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 3a. Workgroup AWS-account grant | ✅ | ✅ | ❌ | ✅ | ❌ | ✅ |
| 3b. Workgroup AD-domain grant | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 4a. Personal AWS mapping (`UserMapping`) | ✅ | ✅ | ⚠️ first mapping of `asset.owner` only | ✅ | ✅ | ✅ |
| 4b. Personal AD-domain mapping | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| 5. Received AWS-account sharing | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| `REPORT` role (not an access path) | — | ✅ all assets | ✅ all assets | ❌ | — | — |

Not checked anywhere: whether a recipient email belongs to an existing, enabled user account
(`UserMapping` emails can be dangling).

---

## 4. Gap catalogue

Severity: **H** = users with overdue vulns are not informed, or are informed wrongly · **M** =
inconsistent / noisy / misleading · **L** = cosmetic / hygiene.

### 4.1 Coverage gaps — who is never informed

| ID | Sev | Gap | Evidence |
|---|---|---|---|
| G-C1 | **H** | **Non-Falcon assets are never notified.** Both pipelines require `crowdstrike_agent_seen_at` within 72 h. `docs/CURRENT_ASSETS.md` says non-Falcon assets use `verified_observed_at`; the MV already honours that, but the notification layer re-filters on the Falcon timestamp only. Nmap/XLSX/manual/integration findings never trigger mail. | `CliController.kt:128`, `SendOutdatedNotificationsTool.kt:90`, Pipeline B SQL |
| G-C2 | **H** | **Non-AWS assets have no recipients.** On-prem / AD-domain assets have no `cloudAccountId`; the only fallback is `UserMapping.awsAccountId == asset.owner`, which only matches if someone stored an account id in `owner`. AD-domain grants and personal AD mappings are never consulted. | `AwsAccountRecipientResolver`, `CliController.kt:138` |
| G-C3 | **H** | **Explicit workgroup asset membership is not a recipient path.** Users who see an asset via direct workgroup assignment get nothing. The KDoc at `UserVulnerabilityNotificationService.kt:375` claims they do. | resolver queries `WorkgroupAwsAccount` only |
| G-C4 | **H** | **No automatic trigger.** There is no scheduler; if the operator cron stops, nobody is informed and nothing alerts. | no `@Scheduled` references either service |
| G-C5 | **H** | **Newly overdue assets are suppressed on the first run.** `getOrCreateReminderState` creates the state with `lastSentAt = now()`; `shouldSendToday` is evaluated right after and returns `false`. First mail arrives on the next UTC day's run (or never, if the asset drops out first). | `ReminderStateService.kt` (new state), `NotificationService.kt:58-60` |
| G-C6 | **H** | **Dry run suppresses the real run.** Reminder state is created/escalated before the `dryRun` check, so a dry run of A on day N makes every new asset ineligible for a real run on day N (combined with G-C5). | `NotificationService.kt:57-63` |
| G-C7 | M | **Unmapped accounts / assets are silently dropped.** B lists `unmappedAccounts` on the console only; A logs at WARN only under `--verbose`. No fallback recipient (e.g. SECCHAMPION) and no REPORT digest of unowned assets — A's REPORT mail only contains assets that already had a recipient. | `CliController.kt:141-146` |
| G-C8 | M | **MCP `send_outdated_notifications` diverges from the CLI.** It uses only `mappings.first()` of `asset.owner` and skips the shared resolver — no workgroup or sharing recipients, and only one owner. | `SendOutdatedNotificationsTool.kt:91-93` |
| G-C9 | M | **SECCHAMPION treated as a normal user in B.** `resolveScope` checks only `ADMIN`; the CLI help says "ADMIN/SECCHAMPION". A targeted SECCHAMPION run gets user-scope instead of global. | `UserVulnerabilityNotificationService.kt:408` |
| G-C10 | M | **Targeted vs global scope disagree.** Workgroup AWS-account grants are recipients in global mode but excluded in `USER_SCOPED` (no `--notall`) mode — the same user sees different accounts depending on how they are addressed. | `getAccessibleAwsAccountIds` vs resolver |
| G-C11 | M | **ADMIN `--notification-user` without mapping gets nothing.** Scope is GLOBAL, but the user filter keeps only accounts where the admin is a resolved recipient; an unmapped admin receives zero mail, returned as SUCCESS. | Step 2b |
| G-C12 | M | **MV staleness.** A uses the MV, refreshed only after CrowdStrike imports or manual trigger. Changes from XLSX import, `cli-add`, exception expiry or threshold change are invisible until the next refresh; the command does not refresh or report the MV age. | `MaterializedViewRefreshService` callers |

### 4.2 Correctness / consistency gaps

| ID | Sev | Gap | Evidence |
|---|---|---|---|
| G-K1 | **H** | **Two independent thresholds.** A uses `reminderOneDays` (UI-configured); B uses `--days` (CLI, default 30). Changing the UI setting does not affect B, and the two mails can disagree on what is "overdue". | `VulnerabilityConfigService.getReminderOneDays`, `--days` |
| G-K2 | M | **No severity-based SLA.** One age threshold for every severity; CRITICAL and LOW are overdue at the same age. | both pipelines |
| G-K3 | M | **Installer artifacts included in B.** The MV excludes `product_class = 'INSTALLER_ARTIFACT'`; Pipeline B's SQL does not, so patch alerts count rows that A and the UI hide. | `findAffectedAwsAccounts` |
| G-K4 | M | **Reminder state never resets.** `getOrCreateReminderState(…, false)` and `resetState` are never called. A remediated asset keeps `level = 2` and an old `outdatedSince`; when it becomes overdue again it is immediately "URGENT". | grep: only call site passes `true` |
| G-K5 | M | **Escalation measures job age, not vuln age.** Level 2 triggers 7 days after the job first saw the asset, not 7 days past the SLA, so a 400-day-old vuln seen for the first time starts at Level 1. | `ReminderStateService` |
| G-K6 | M | **Partial delivery marks the asset sent for everyone.** If recipient X succeeds and Y fails, the asset is marked sent; Y gets no retry until the next day. | `NotificationService.kt` `sentAssetIds` |
| G-K7 | M | **REPORT mails duplicate assets.** `sendToReportUsers` receives the per-(recipient, asset) list, so an asset with N recipients appears N times; `totalCount` and the subject line are inflated. Also not subject to daily dedup. | `NotificationService.kt:128` |
| G-K8 | L | **`assetsProcessed` counts rows, not assets** (same N× inflation). | `NotificationService.kt:133` |
| G-K9 | L | **Time-zone mixing in B.** `thresholdDate` is converted to `ZoneId.systemDefault()` while the 72 h window uses UTC; `DATEDIFF(CURDATE(), …)` uses the DB session zone. On a non-UTC host the cut-off moves by the UTC offset. | `UserVulnerabilityNotificationService.kt:113` |
| G-K10 | L | **Exception visibility.** Both rely on the write-time `excepted` column; between an exception expiring and the hourly sweep / MV refresh, an expired exception still suppresses mail. | `ExceptionMaterializationService` |

### 4.3 Operability / audit gaps

| ID | Sev | Gap | Evidence |
|---|---|---|---|
| G-O1 | **H** | **Pipeline B has no dedup and no audit trail.** Running it twice sends twice; nothing is written to `notification_log`, so "was user X informed about account Y, and when?" is unanswerable. | no `notificationLogService` use |
| G-O2 | M | **No opt-out / preferences.** `NotificationPreference.enableNewVulnNotifications` exists but is read by nothing; users cannot unsubscribe or choose a channel. | `NotificationService.processNewVulnerabilityNotifications` is a TODO stub |
| G-O3 | M | **No chat (Slack/Telegram) channel.** `NotificationEventType` has no overdue event, so the chat-notification framework is not used. | `NotificationEventType` |
| G-O4 | M | **Dead CLI flag / misleading help.** `send-notifications --outdated-only` is never sent to the backend; header text still mentions "new vulnerability notifications" (not implemented). | `SendNotificationsCommand.kt:28` |
| G-O5 | M | **`--notall` help text is wrong.** It promises creator/uploader/owner and workgroup-asset membership; the code deliberately excludes them (and CLAUDE.md says those grant no access). | `SendNotificationUsersCommand.kt:34` vs `getRestrictedAwsAccountIds` |
| G-O6 | M | **Broken dashboard link in A.** `DEFAULT_DASHBOARD_URL = "http://localhost:4321/assets"` is hard-coded into production mails (B uses `appConfig.backend.baseUrl`). | `NotificationService.kt:28` |
| G-O7 | L | **Recipient name missing** (`recipientName = null // TODO`). | `NotificationService.kt:214` |
| G-O8 | L | **`send-patch-notifications` prefix not validated.** Help says "first character", backend accepts any string; no check that batches `a`…`z`, `0`…`9` cover every email (e.g. `_` or non-ASCII). | Step 2c |
| G-O9 | L | **DB transaction held across SMTP.** B is `@Transactional` and calls `.get()` on each send — the transaction stays open for the whole mail loop. | `UserVulnerabilityNotificationService.kt:103` |
| G-O10 | L | **Unescaped values in B's HTML.** `awsAccountId` and the email address are inserted into HTML without escaping (low risk: values come from imports, not end users). | `renderAccountTableHtml` |

---

## 5. Decision flow (as implemented)

```
Operator runs CLI / MCP (ADMIN)                       ← G-C4: nothing runs automatically
│
├─ Pipeline A: send-notifications
│   MV row exists? (reminderOneDays, current asset, not excepted, not installer)
│   └─ no ─► silent                                   ← G-C12 stale MV
│   Falcon contact ≤ 72h? ── no ─► silent             ← G-C1
│   recipients = account owners ∪ account-WG members ∪ share targets ∪ owner-mapping
│   └─ empty ─► silent                                ← G-C2, G-C3, G-C7
│   first time seen? ── yes ─► state created, lastSentAt=now ─► SKIPPED today   ← G-C5/G-C6
│   sent today already? ── yes ─► skipped
│   └─ send L1 / L2 (L2 if state ≥ 7d old)            ← G-K4, G-K5
│   + REPORT users: everything, every run             ← G-K7
│
└─ Pipeline B: send-notification-users / send-patch-notifications
    AWS asset? ── no ─► silent                         ← G-C2
    Falcon contact ≤ 72h? ── no ─► silent              ← G-C1
    visible, non-excepted vuln older than --days?      ← G-K1, G-K3
    recipients (scope-dependent, §2.3)                 ← G-C9, G-C10, G-C11
    prefix filter (patch variant)
    └─ send, no dedup, no log                          ← G-O1
```

---

## 6. Suggested priorities

1. **G-C5 / G-C6** — first-run suppression and dry-run side effects (small, contained fix in `ReminderStateService` / `NotificationService`).
2. **G-C1 / G-C2 / G-C3** — align recipient resolution and the observation window with `AssetFilterService` / `docs/CURRENT_ASSETS.md` (one shared "who should hear about asset X" resolver).
3. **G-K1** — single source of truth for the overdue threshold (ideally severity-aware, G-K2).
4. **G-O1** — dedup + `notification_log` for Pipeline B.
5. **G-C8** — make the MCP outdated tool reuse the controller logic.
6. **G-C4** — documented schedule (or backend scheduler) with a staleness alert.

## 7. Existing tests touching this area

`NotificationServiceTest`, `NotificationAssetSeenWindowTest`, `UserVulnerabilityNotificationServiceTest`,
`UserVulnerabilityNotificationRecencyIntegrationTest` (under `src/backendng/src/test/kotlin/com/secman/service/`).
`NotificationServiceTest` mocks `ReminderStateService` (`relaxed = true`, `shouldSendToday` stubbed
to `true`), so it cannot catch G-C5 / G-C6. A regression test needs the real `ReminderStateService`
over a repository.
