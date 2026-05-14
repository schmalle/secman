# Error classification

The driver emits structured `[PASS]` / `[FAIL]` / `[WARN]` / `[INFO]` lines.
Find the **first** `[FAIL]` in `.e2e-logs/e2e-vuln-exception-run-<N>.log` and
match it against the table below.

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
| `10.6 round-trip count != 1`                              | **backend**  | Service `importFromJson` or `exportAll` mismatch. Check `VulnerabilityExceptionImportExportService.kt`. |
| `10.10 imported != 1`                                     | **backend**  | Asset resolution or fingerprint logic. Inspect `findListByName`, fingerprint match. |
| `10.13 skippedDup != 1`                                   | **backend**  | `existingFingerprints` set logic in service. |
| `10.14 ... returned 200, expected 403`                    | **backend**  | Role check missing on `/export` endpoint. |
| `10.15 non-admin was NOT denied`                          | **backend**  | `DeleteAllVulnerabilityExceptionsTool` not enforcing `context.isAdmin`. |
| `10.17 final count ... expected ...`                      | **backend**  | Baseline restore import skipped rows it shouldn't. Check duplicate detection. |
| Playwright `exceptions UI shows zero rows`                | **frontend** | `VulnerabilityExceptionsTable` not refreshing after delete-all, or admin-only buttons leaking to non-admins. |
