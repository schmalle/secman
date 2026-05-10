---
name: e2evulnexception
description: >
  Run the full vulnerability + exception E2E test loop covering both MCP and
  Web UI. Sets up a clean testbed with two users, two assets, and three
  vulnerability rows; exercises the exception lifecycle (approve, reject,
  cancel) plus authorization negatives via MCP; then verifies the same state
  through the Astro/React UI with Playwright. Cleans up before and after.
  Use this skill when the user says "run vuln exception e2e", "e2evulnexception",
  "test vulnerability + exception workflow", or similar.
context: fork
---
# E2E Vulnerability + Exception Full Workflow — Iterative Fix Loop

You are an orchestration agent that brings up a full-stack environment, runs
the **combined MCP + Web UI** vulnerability and exception lifecycle test, and
**iteratively fixes every failure** until it passes or you've exhausted the
retry budget.

## Test Overview

The driver script is
`scripts/test/test-e2e-vuln-exception-full.sh`. It owns the testbed:

**Users** (USER + VULN + REQ — no ADMIN/SECCHAMPION so requests stay PENDING):
- `e2etestuser1` (`e2etestuser1@e2e.test`)
- `e2etestuser2` (`e2etestuser2@e2e.test`)

**Vulnerability-test assets** (owner is a plain string username):
- `testasset1` — owner `e2etestuser1`, ip `10.99.0.1`
- `testasset2` — owner `e2etestuser2`, ip `10.99.0.2`

**Vulnerabilities**:
- `vuln1` — `CVE-E2E-0001` CRITICAL, `daysOpen=40` on `testasset1` (overdue, threshold = 30d)
- `vuln2` — `CVE-E2E-0002` CRITICAL, `daysOpen=5` on **both** `testasset1` and `testasset2`

**AWS account sharing testbed** (Phase 8):
- AWS account `123456789012` (A) — mapped to `e2etestuser1`; later **shared (scoped) → user2**
- AWS account `876543210987` (B) — mapped to `e2etestuser2` (their own; never shared)
- AWS account `555555555555` (C) — mapped to `e2etestuser1` **after** the sharing rule
  is created. Tests prove the rule's per-account scope (`selectedAwsAccountIds=[A]`)
  prevents the new account from leaking to user2.
- Assets `testaws-a` / `testaws-b` / `testaws-c` carry those `cloudAccountId` values and
  use a non-user owner string (`awssharing-owner`) so the only access path is via
  `UserMapping` or `AwsAccountSharing` — not the owner rule.

**Phases inside the driver**:

| Phase | What it covers |
|------:|----------------|
| 0 | Pre-run cleanup (idempotent — direct SQL on `users`, `asset`, `vulnerability`, `vulnerability_exception_request`, `exception_request_audit`, `outdated_asset_materialized_view`, **`aws_account_sharing`**, **`user_mapping`**) |
| 1 | MCP setup: `add_user` × 2, `create_asset` × 2, `add_vulnerability` × 3, materialized-view refresh |
| 2 | MCP visibility/RBAC: `get_vulnerabilities` as user1, user2, admin |
| 3 | MCP overdue: `get_overdue_assets` as user1, user2, admin |
| 4 | MCP exception lifecycle — APPROVE path (user1 creates → admin approves → user1 sees APPROVED) |
| 5 | MCP exception lifecycle — REJECT path (user2 creates → admin rejects with comment) |
| 6 | MCP exception lifecycle — CANCEL path (user1 creates → user1 cancels) |
| 7 | MCP authorization negatives: user2 cannot approve, user1 cannot create on user2's asset, missing `X-MCP-User-Email` |
| 8 | **MCP AWS account sharing** — create `UserMapping`s + AWS-tagged assets, `create_aws_account_sharing` (scoped to one account), verify directional + scoped visibility, add a second mapping/asset to the source user and prove it does **not** leak to the target, `list_aws_account_sharing` as admin |
| 9 | Web UI (Playwright `tests/e2e/vuln-exception-full.spec.ts`): scoped visibility, my-requests states, approval dashboard, **admin AWS sharing dashboard, `/account-vulns` for user1 and user2 to verify scoped sharing in the UI** |
| 10 | **Exception import/export/delete-all** — REST `/export`, MCP `delete_all_vulnerability_exceptions`, MCP `list_vulnerability_exceptions`, REST `/import`. 17 steps including non-admin negatives, idempotency, and baseline restore. |
| (trap) | Post-run cleanup — runs even on failure |

The shell driver calls MCP via `curl`/`jq` and shells out to `npx playwright`
for Phase 8, passing the captured IDs/credentials through env vars.

## High-Level Loop

```
1. Start backend   (./scripts/startbackenddev.sh)
2. Start frontend  (./scripts/startfrontenddev.sh)
3. Wait for both ports to be bound (8080 / 4321)
4. Run the driver:
     pass-cli run --env-file ./secmanpp.env -- \
       ./scripts/test/test-e2e-vuln-exception-full.sh --verbose
5. IF all green → done, report success
6. IF failure →
   a. STOP both services (./scripts/stopbackenddev.sh, ./scripts/stopfrontenddev.sh)
   b. Diagnose backend vs frontend vs test
   c. Apply the fix
   d. RESTART both services
   e. Go to step 4
7. After 5 iterations without progress → stop and present summary
```

**CRITICAL RULE**: On any error, **stop both services first**, apply the fix,
then **restart both** before retrying. Never edit code while services are
running. (Frontend may hot-reload for trivial fixes, but the safe default is
full restart for this skill.)

## Detailed Instructions

### Phase 1 — Environment Setup

This skill is **pinned to Proton Pass** — start services via the wrapper
scripts that source secrets via `pass-cli`. Never hardcode `localhost:8080`
or `localhost:4321` in tests; the driver and Playwright config already
respect `BASE_URL` / `FRONTEND_URL` / `SECMAN_BASE_URL`.

| Setting                  | Default                           |
| ------------------------ | --------------------------------- |
| Backend start            | `./scripts/startbackenddev.sh`   |
| Backend port             | `8080` (liveness via port binding)|
| Backend wait timeout     | `120` seconds                     |
| Frontend start           | `./scripts/startfrontenddev.sh`  |
| Frontend port            | `4321` (liveness via port binding)|
| Frontend wait timeout    | `60` seconds                      |

**Starting services:**

1. Create `.e2e-logs/` if it doesn't exist.
2. **Check ports** — `lsof -iTCP:8080 -sTCP:LISTEN -n -P` and
   `lsof -iTCP:4321 -sTCP:LISTEN -n -P`. If something is listening,
   stop it via the canonical scripts (never `kill` inline).
3. Start backend in background:
   ```bash
   nohup ./scripts/startbackenddev.sh > .e2e-logs/backend.log 2>&1 &
   ```
4. Start frontend in background:
   ```bash
   nohup ./scripts/startfrontenddev.sh > .e2e-logs/frontend.log 2>&1 &
   ```
5. **Wait for liveness via port binding** (not HTTP probes against localhost):
   - Backend: poll `lsof -iTCP:8080 -sTCP:LISTEN -n -P` until non-empty (120s).
   - Frontend: poll `lsof -iTCP:4321 -sTCP:LISTEN -n -P` until non-empty (60s).
6. **Install Playwright deps** if missing:
   ```bash
   [ -d tests/e2e/node_modules ] || (cd tests/e2e && npm install)
   ```
   (Browsers should already be installed via
   `npx playwright install chrome msedge` per `AGENTS.md`.)

### Phase 2 — Run Tests

Run the driver with Proton Pass injection:

```bash
pass-cli run --env-file ./secmanpp.env -- \
    ./scripts/test/test-e2e-vuln-exception-full.sh --verbose 2>&1 \
    | tee .e2e-logs/e2e-vuln-exception-run-<N>.log
```

Where `<N>` is the iteration number (starting at 1).

The driver consumes from env (already in `secmanpp.env`):
- `SECMAN_MCP_KEY`, `SECMAN_ADMIN_EMAIL` — MCP auth + delegation
- `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS` — JWT for view refresh + UI login

**Required tools on the system**: `curl`, `jq`, `mariadb`, `pass-cli`,
`node`/`npx` (Playwright).

### Phase 2.5 — Error Classification

The driver emits structured `[PASS]` / `[FAIL]` / `[WARN]` / `[INFO]` lines.
Classify the most recent failure:

| Pattern                                                   | Category     | Action                                                           |
| --------------------------------------------------------- | ------------ | ---------------------------------------------------------------- |
| `MCP tool '...' failed`                                   | **backend**  | Fix tool handler in `src/backendng/.../mcp/tools/<Tool>.kt`      |
| `[FAIL] user1 visibility wrong` etc.                      | **backend**  | Asset filter / RBAC service logic                                |
| `[FAIL] admin overdue list mismatch`                      | **backend**  | Materialized view refresh, `OutdatedAssetService`                |
| `[FAIL] Expected APPROVED, got ...`                       | **backend**  | `VulnerabilityExceptionRequestService` state machine             |
| `[FAIL] Expected user2 approve to fail`                   | **backend**  | Role check missing in `ApproveExceptionRequestTool`              |
| `Failed to create test user`                              | **backend**  | `AddUserTool` / unique constraint / event listener crash         |
| `[FAIL] Baseline: user1 should see testaws-a`             | **backend**  | `AssetFilterService` not honoring `UserMapping` AWS account path |
| `[FAIL] user2 should see testaws-a via sharing`           | **backend**  | `AwsAccountSharingService.getSharedAwsAccountIdsByEmail` / `findSharedAwsAccountIdsByTargetUserId` query — empty selection should resolve to source's full mapping set, non-empty to listed IDs only |
| `[FAIL] SCOPE LEAK: user2 saw testaws-c`                  | **backend**  | Per-account scoping is broken — `aws_account_sharing_account` join not applied or repository SQL treats non-empty selection as "all". See V207 + `AwsAccountSharingRepository.findSharedAwsAccountIdsByTargetUserId` |
| `Failed to create user mapping`                           | **backend**  | `UserMappingController.createMapping` — admin role check, validation, or DB constraint |
| `Sharing rule create failed`                              | **backend**  | `CreateAwsAccountSharingTool` / `AwsAccountSharingService.createSharingRule` — typically delegation/admin-role enforcement or duplicate-rule conflict |
| `Cannot reach backend`                                    | **infra**    | Backend didn't start — read `.e2e-logs/backend.log`              |
| `Frontend not reachable`                                  | **infra**    | Frontend didn't start — read `.e2e-logs/frontend.log`            |
| Playwright `expect(body).toContain(CVE_*)` fails          | **frontend** | UI page didn't render — check page route, hydration, API call    |
| Playwright login redirect timeout                         | **frontend** | Login form / auth handler regression                             |
| Playwright `expected APPROVED|Approved`                   | **frontend** | `MyExceptionRequests.tsx` doesn't render status text             |
| `10.6 round-trip count != 1` | **backend** | Service `importFromJson` or `exportAll` mismatch. Check `VulnerabilityExceptionImportExportService.kt`. |
| `10.10 imported != 1` | **backend** | Asset resolution or fingerprint logic. Inspect `findListByName`, fingerprint match. |
| `10.13 skippedDup != 1` | **backend** | `existingFingerprints` set logic in service. |
| `10.14 ... returned 200, expected 403` | **backend** | Role check missing on `/export` endpoint. |
| `10.15 non-admin was NOT denied` | **backend** | `DeleteAllVulnerabilityExceptionsTool` not enforcing `context.isAdmin`. |
| `10.17 final count ... expected ...` | **backend** | Baseline restore import skipped rows it shouldn't. Check duplicate detection. |
| Playwright `exceptions UI shows zero rows` | **frontend** | `VulnerabilityExceptionsTable` not refreshing after delete-all, or admin-only buttons leaking to non-admins. |

### Phase 3 — Fix Loop (Stop-Fix-Restart)

#### Step 3a: Stop Both Services

```bash
./scripts/stopbackenddev.sh
./scripts/stopfrontenddev.sh
```

Wait 3 seconds for processes to terminate. Never call `kill` directly.

#### Step 3b: Diagnose and Fix

Fix priority: **backend first**, then frontend.

**Key files for this test:**

| Concern                                  | File                                                                                             |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------ |
| MCP tool registry                        | `src/backendng/src/main/kotlin/com/secman/mcp/McpToolRegistry.kt`                                |
| Add user tool                            | `src/backendng/src/main/kotlin/com/secman/mcp/tools/AddUserTool.kt`                              |
| Create asset tool                        | `src/backendng/src/main/kotlin/com/secman/mcp/tools/CreateAssetTool.kt`                          |
| Add vulnerability tool                   | `src/backendng/src/main/kotlin/com/secman/mcp/tools/AddVulnerabilityTool.kt`                     |
| Get vulnerabilities (RBAC filtering)     | `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetVulnerabilitiesTool.kt`                   |
| Get overdue assets                       | `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetOverdueAssetsTool.kt`                     |
| Get assets (RBAC + AWS sharing path)     | `src/backendng/src/main/kotlin/com/secman/mcp/tools/GetAssetsTool.kt`                            |
| AWS sharing MCP tools                    | `src/backendng/src/main/kotlin/com/secman/mcp/tools/{Create,List,Delete}AwsAccountSharingTool.kt` |
| AWS sharing service (scope resolution)   | `src/backendng/src/main/kotlin/com/secman/service/AwsAccountSharingService.kt`                   |
| AWS sharing repository (scope SQL)       | `src/backendng/src/main/kotlin/com/secman/repository/AwsAccountSharingRepository.kt`             |
| AWS sharing controller (UI REST)         | `src/backendng/src/main/kotlin/com/secman/controller/AwsAccountSharingController.kt`             |
| AWS sharing scope migration              | `src/backendng/src/main/resources/db/migration/V207__aws_account_sharing_selected_accounts.sql`  |
| User mapping controller (REST)           | `src/backendng/src/main/kotlin/com/secman/controller/UserMappingController.kt`                   |
| Account-vulns service (own + shared)     | `src/backendng/src/main/kotlin/com/secman/service/AccountVulnsService.kt`                        |
| AWS sharing UI                           | `src/frontend/src/components/AwsAccountSharingManager.tsx`, `src/frontend/src/pages/aws-account-sharing.astro` |
| Account-vulns UI                         | `src/frontend/src/components/AccountVulnsView.tsx`, `src/frontend/src/pages/account-vulns.astro` |
| Create / approve / reject / cancel       | `src/backendng/src/main/kotlin/com/secman/mcp/tools/{Create,Approve,Reject,Cancel}ExceptionRequestTool.kt` |
| Exception request service                | `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityExceptionRequestService.kt`       |
| Vulnerability service (cli-add)          | `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityService.kt`                       |
| Asset access filter                      | `src/backendng/src/main/kotlin/com/secman/service/AssetFilterService.kt`                         |
| Materialized view refresh                | `src/backendng/src/main/kotlin/com/secman/service/MaterializedViewRefreshService.kt`             |
| Vulnerability config (overdue threshold) | `src/backendng/src/main/kotlin/com/secman/domain/VulnerabilityConfig.kt`                         |
| Exception status enum                    | `src/backendng/src/main/kotlin/com/secman/domain/ExceptionRequestStatus.kt`                      |
| My exception requests UI                 | `src/frontend/src/components/MyExceptionRequests.tsx`                                            |
| Approval dashboard UI                    | `src/frontend/src/components/ExceptionApprovalDashboard.tsx`                                     |
| Account vulnerabilities UI               | `src/frontend/src/pages/account-vulns.astro`                                                     |
| Outdated assets UI                       | `src/frontend/src/pages/outdated-assets.astro`                                                   |
| Driver script                            | `scripts/test/test-e2e-vuln-exception-full.sh`                                                  |
| Playwright spec                          | `tests/e2e/vuln-exception-full.spec.ts`                                                          |
| Import/export service                      | `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityExceptionImportExportService.kt`                |
| Import/export DTOs                         | `src/backendng/src/main/kotlin/com/secman/dto/VulnerabilityExceptionImportExportDtos.kt`                       |
| Import/export REST endpoints               | `src/backendng/src/main/kotlin/com/secman/controller/VulnerabilityManagementController.kt` (`exportAllExceptions`, `importExceptions`, `deleteAllExceptions`) |
| MCP delete-all-exceptions tool             | `src/backendng/src/main/kotlin/com/secman/mcp/tools/DeleteAllVulnerabilityExceptionsTool.kt`                   |
| Frontend bulk admin buttons                | `src/frontend/src/components/VulnerabilityExceptionsTable.tsx`                                                 |
| Frontend service-layer fns                 | `src/frontend/src/services/vulnerabilityManagementService.ts` (`exportAllExceptions`, `importExceptions`, `deleteAllExceptions`) |

**Diagnosis steps:**

1. Read the latest log: `.e2e-logs/e2e-vuln-exception-run-<N>.log`. Find the
   first `[FAIL]` and the surrounding `[INFO]` / `[DEBUG]` context.
2. If backend-related, also read `.e2e-logs/backend.log` for stack traces near
   the timestamp of the failure.
3. Trace the MCP call: shell sends `tools/call` → `McpController` →
   `McpToolService` → tool class → service → repository.
4. For UI failures, the Playwright report is in `tests/e2e/playwright-report/`
   (auto-opened only if you run with `--reporter=html`). Screenshots and
   traces go to `tests/e2e/test-results/` (`screenshot: 'only-on-failure'`
   and `trace: 'retain-on-failure'` per `playwright.config.ts`).
5. Apply a **minimal** fix. Common categories:
   - Missing tool registration in `McpToolRegistry`
   - Null-pointer in service (missing `?.`/`?: default`)
   - DTO/Serdeable shape mismatch in tool result vs assertion
   - RBAC: delegation header not honored, role intersection wrong
   - Materialized view refresh: trigger endpoint returning 5xx → wait fails
     and overdue assertions break
   - Frontend: API endpoint URL mismatch, missing `await fetch(...)`,
     status text removed from rendering

#### Step 3c: Restart Both

```bash
nohup ./scripts/startbackenddev.sh > .e2e-logs/backend.log 2>&1 &
nohup ./scripts/startfrontenddev.sh > .e2e-logs/frontend.log 2>&1 &
```

Wait for ports `8080` and `4321` to be bound (same timeouts as Phase 1).

#### Step 3d: Re-run

Increment `<N>`, return to Phase 2.

#### Step 3e: Guard Rails

- Track which errors you've already attempted. If the **same error persists
  after two attempts**, flag it for the user and move on (or stop).
- After **5 total iterations**, stop and present a summary.
- If the backend **fails to start** (port never binds), open `.e2e-logs/backend.log`
  and fix compile/runtime errors before retrying. Don't loop on a broken backend.

### Phase 4 — Teardown & Report

The driver's `trap EXIT` handler already removes test data even on failure.
After the loop:

```bash
./scripts/stopbackenddev.sh
./scripts/stopfrontenddev.sh
```

Print a summary table:

```
| Iter | MCP phase failed | UI phase failed | Fix applied                                       |
|------|------------------|-----------------|---------------------------------------------------|
| 1    | Phase 4 (approve)| —               | VulnerabilityExceptionRequestService.kt:approve() |
| 2    | —                | —               | — (all green)                                     |
```

If you stop short of green, list each remaining failure with:
- The `[FAIL]` message
- The file/line where you believe the root cause is
- What you tried

## Idempotency Verification

Once green, **re-run the driver immediately** (same command) without doing any
cleanup yourself. The trap and pre-run cleanup should mean the second pass is
also green with zero fixes. If the second pass fails, treat it as a regression
in the cleanup logic and fix `cleanup()` in
`scripts/test/test-e2e-vuln-exception-full.sh`.

## Important Notes

- **Never commit or push** — only edit files locally. The user drives commits.
- **Secrets via Proton Pass** — both `scripts/startbackenddev.sh`,
  `scripts/startfrontenddev.sh`, and the test driver use `pass-cli run`.
  Never hardcode credentials.
- **No `localhost` literals in tests** — use `BASE_URL` / `FRONTEND_URL` /
  `SECMAN_BASE_URL`. Liveness checks use port binding, not HTTP probes.
- **Logs**: backend → `.e2e-logs/backend.log`; frontend → `.e2e-logs/frontend.log`;
  driver → `.e2e-logs/e2e-vuln-exception-run-<N>.log`. The directory is gitignored.
- **MariaDB**: cleanup uses `mariadb -h 127.0.0.1 -u secman -pCHANGEME secman`.
  MariaDB must be running.
- **Roles**: `e2etestuser1`/`e2etestuser2` MUST stay non-admin and non-secchampion
  so exception requests land as PENDING. If they ever get auto-approved, check
  the `roles` argument in Phase 1 and the auto-approve logic in
  `VulnerabilityExceptionRequestService`.
- **Overdue threshold** is `VulnerabilityConfig.reminderOneDays` (default 30).
  `vuln1` is 40d (overdue) and `vuln2` is 5d (not). If the threshold changes,
  update both this skill and the driver constants.
- **AWS sharing scope** — Phase 8 is the scope-leak guard. The sharing rule is
  created with `selectedAwsAccountIds=[A]` *while* user1 still only owns
  account A. After rule creation, account C is added to user1; the test
  asserts user2 still only sees A and B, never C. If that assertion ever
  flips, the scoping codepath in `AwsAccountSharingRepository`
  (`findSharedAwsAccountIdsByTargetUserId`) is broken and ALL existing scoped
  rules in production are silently leaking.
- **AWS sharing cleanup** — `cleanup()` deletes from `aws_account_sharing`
  (cascades to `aws_account_sharing_account` via V207's FK) and `user_mapping`
  by both `user_id` and `email` so future-user/PENDING mapping rows are also
  swept. Both run **before** the user delete because source/target user FKs
  are NOT NULL with no cascade.
- **Account IDs are 12 digits** — `UserMapping.awsAccountId` is validated by
  `@Pattern(regexp = "^\\d{12}$")`. The hard-coded test IDs
  (`123456789012` / `876543210987` / `555555555555`) satisfy that regex.
  If the constants change, keep them 12-digit numeric.
- **Phase 10 is destructive on real data**. Steps 10.2 and 10.7 issue
  `delete_all_vulnerability_exceptions`, which wipes every row in the
  DB — including any pre-existing real exceptions on the dev/test
  machine. Step 10.16 re-imports the baseline file captured at 10.1 to
  restore them. The trap cleanup then removes only test rows by
  `reason LIKE 'E2E TEST %'`. Never weaken the cleanup to match by
  `created_by` — that would nuke real admin-authored exceptions.
