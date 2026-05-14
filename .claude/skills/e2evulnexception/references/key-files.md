# Key files for fixes

Fix priority: **backend first**, then frontend.

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
| Import/export service                    | `src/backendng/src/main/kotlin/com/secman/service/VulnerabilityExceptionImportExportService.kt`                |
| Import/export DTOs                       | `src/backendng/src/main/kotlin/com/secman/dto/VulnerabilityExceptionImportExportDtos.kt`                       |
| Import/export REST endpoints             | `src/backendng/src/main/kotlin/com/secman/controller/VulnerabilityManagementController.kt` (`exportAllExceptions`, `importExceptions`, `deleteAllExceptions`) |
| MCP delete-all-exceptions tool           | `src/backendng/src/main/kotlin/com/secman/mcp/tools/DeleteAllVulnerabilityExceptionsTool.kt`                   |
| Frontend bulk admin buttons              | `src/frontend/src/components/VulnerabilityExceptionsTable.tsx`                                                 |
| Frontend service-layer fns               | `src/frontend/src/services/vulnerabilityManagementService.ts` (`exportAllExceptions`, `importExceptions`, `deleteAllExceptions`) |

## Diagnosis steps

1. Read latest log: `.e2e-logs/e2e-vuln-exception-run-<N>.log`. Find the first
   `[FAIL]` and surrounding `[INFO]`/`[DEBUG]` context.
2. Backend-related → also read `.e2e-logs/backend.log` for stack traces near
   the failure timestamp.
3. Trace the MCP call: shell → `tools/call` → `McpController` → `McpToolService`
   → tool class → service → repository.
4. UI failures → Playwright artifacts in `tests/e2e/test-results/`
   (`screenshot: 'only-on-failure'`, `trace: 'retain-on-failure'`).
5. Apply a **minimal** fix. Common categories:
   - Missing tool registration in `McpToolRegistry`
   - Null-pointer in service (missing `?.`/`?: default`)
   - DTO/Serdeable shape mismatch in tool result vs assertion
   - RBAC: delegation header not honored, role intersection wrong
   - Materialized view refresh: trigger endpoint returning 5xx → wait fails
   - Frontend: API endpoint URL mismatch, missing `await fetch(...)`, status text removed
