# MCP Integration

Streamable HTTP / JSON-RPC 2.0 endpoint at `POST /mcp`. **85 tools** spanning requirements, releases, alignment, assets, the application register, vulnerabilities, exception requests, scans, workgroups, user mappings, AWS account sharing, users, the vulnerability heatmap, GitHub repositories, notifications, and maintenance jobs.

Every tool is listed in [Tool reference](#tool-reference) below — that section is exhaustive and is checked against the source by the commands in [Keeping this document in sync](#keeping-this-document-in-sync).

## Required headers

| Header | Required for |
|---|---|
| `X-MCP-API-Key: sk-...` | every request |
| `X-MCP-User-Email: user@domain` | **every** `tools/list` and `tools/call` (mandatory). Exempt: `initialize`, `ping`. |
| `Content-Type: application/json` | every request |

`Origin` is validated per spec — non-browser clients without `Origin` are allowed; localhost always allowed; configure others under `secman.mcp.transport.allowed-origins` in `application.yml`.

## Effective permissions

```
effective = api_key.permissions  ∩  delegated_user.role_implied_permissions
```

Implied per role:

| Role | Implied permissions |
|---|---|
| `USER` | `REQUIREMENTS_READ`, `ASSETS_READ`, `VULNERABILITIES_READ`, `TAGS_READ` |
| `ADMIN` | all |
| `VULN` | `VULNERABILITIES_READ`, `SCANS_READ`, `ASSETS_READ` |
| `RELEASE_MANAGER` | `REQUIREMENTS_READ`, `ASSESSMENTS_READ` |
| `REQ` | `REQUIREMENTS_READ/WRITE`, `FILES_READ`, `TAGS_READ` |
| `REQADMIN` | `REQUIREMENTS_READ/WRITE` (also enables release create/delete + alignment) |
| `RISK` | `ASSESSMENTS_READ/WRITE/EXECUTE` |
| `SECCHAMPION` | `REQUIREMENTS_READ`, `ASSESSMENTS_READ`, `ASSETS_READ`, `VULNERABILITIES_READ`, `SCANS_READ` |

## Quick start

```bash
# 1. Get JWT
TOKEN=$(curl -s -XPOST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"adminuser","password":"…"}' | jq -r '.token')

# 2. Create API key
curl -X POST http://localhost:8080/api/mcp/admin/api-keys \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "name":"claude",
    "permissions":["REQUIREMENTS_READ","ASSETS_READ","VULNERABILITIES_READ"],
    "delegationEnabled":true,
    "allowedDelegationDomains":"@company.com",
    "expiresAt":"2027-03-31T23:59:59"
  }'
# → save the returned `apiKey` (only shown once)
```

## Client setup

### Claude Code (recommended, native HTTP)

```bash
claude mcp add --transport http secman http://localhost:8080/mcp \
  --header "X-MCP-API-Key: sk-..." \
  --header "X-MCP-User-Email: you@company.com"
```

### Claude Desktop (native `url`)

`~/Library/Application Support/Claude/claude_desktop_config.json` (macOS), `%APPDATA%\Claude\…` (Win), `~/.config/Claude/…` (Linux):

```json
{ "mcpServers": { "secman": {
  "url": "http://localhost:8080/mcp",
  "headers": {
    "X-MCP-API-Key": "sk-...",
    "X-MCP-User-Email": "you@company.com"
  }
} } }
```

### Claude Desktop fallback (`mcp-remote` stdio→HTTP proxy)

Use only if your Desktop version lacks native `url`:

```json
{ "mcpServers": { "secman": {
  "command": "npx",
  "args": ["-y","mcp-remote","http://localhost:8080/mcp",
           "--header","X-MCP-API-Key: sk-...",
           "--header","X-MCP-User-Email: you@company.com"]
} } }
```

## Smoke test

```bash
# initialize (no email required)
curl -XPOST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'X-MCP-API-Key: sk-...' \
  -d '{"jsonrpc":"2.0","id":"1","method":"initialize",
       "params":{"protocolVersion":"2024-11-05","capabilities":{},
       "clientInfo":{"name":"test","version":"1"}}}'

# tools/list (email required)
curl -XPOST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'X-MCP-API-Key: sk-...' -H 'X-MCP-User-Email: you@company.com' \
  -d '{"jsonrpc":"2.0","id":"2","method":"tools/list"}'
```

## Permission groups → tools

This is the **API-key** permission each tool is gated on. It is only half the check — see [Effective permissions](#effective-permissions): the delegated user's role-implied permissions are intersected with the key's, and most tools additionally re-check a concrete role inside `execute()` (the "Roles" column in the reference below).

| API-key permission | Tools |
|---|---|
| `REQUIREMENTS_READ` | `get_requirements`, `export_requirements`, `list_releases`, `get_release`, `create_release`, `delete_release`, `set_release_status`, `compare_releases`, `start_alignment`, `submit_review`, `get_alignment_status`, `finalize_alignment` |
| `REQUIREMENTS_WRITE` | `add_requirement`, `delete_all_requirements` |
| `ASSETS_READ` | `get_assets`, `get_all_assets_detail`, `get_asset_profile`, `get_asset_complete_profile`, `delete_asset`, `delete_all_assets`, `delete_asset_not_seen`, `asset_match_clear` |
| `ASSETS_WRITE` | `create_asset`, `update_asset` |
| `SCANS_READ` | `get_scans`, `get_asset_scan_results`, `search_products` |
| `VULNERABILITIES_READ` | `get_vulnerabilities`, `get_all_vulnerabilities_detail`, `get_all_accessible_vulnerabilities`, `get_asset_most_vulnerabilities`, `get_overdue_assets`, `add_vulnerability`, `deduplicate_vulnerabilities`, `list_products`, all `*_exception_request*` tools, `list_vulnerability_exceptions`, `delete_all_vulnerability_exceptions`, `get_vulnerability_heatmap`, `refresh_vulnerability_heatmap`, `get_top_accounts_by_finding_age`, `get_crowdstrike_last_import`, `import_github_repos`, `*_github_owner_email_mapping*` |
| `ASSESSMENTS_READ` | `list_aws_account_risk_assessments`, `list_account_onboarding_rules`, `preview_account_onboarding_rules` |
| `USER_ACTIVITY` | `list_users`, `add_user`, `delete_user`, `import_user_mappings`, `list_user_mappings`, `list_/create_/delete_aws_account_sharing`, `simulate_account_onboarding` |
| `WORKGROUPS_WRITE` | `create_workgroup`, `delete_workgroup`, `assign_assets_to_workgroup`, `assign_users_to_workgroup`, `list_/add_/remove_workgroup_aws_account`, `list_/add_/remove_workgroup_ad_domain` |
| `NOTIFICATIONS_SEND` | `send_admin_summary`, `send_patch_notifications`, `send_exception_expiry_reminders`, `send_outdated_notifications`, `send_vulnerability_notifications`, `send_application_register_reminders`, `notify_new_accounts`, `send_github_repo_alerts` |
| *(unmapped)* | `application_register` — see [Two permission maps](#two-permission-maps) |

`get_asset_most_vulnerabilities` and `get_overdue_assets` accept `VULNERABILITIES_READ` **or** `ASSETS_READ`.

### Two permission maps

A tool call passes through **two independent name→permission maps**, and a tool must appear in both to be usable. Both live in `src/backendng/.../mcp/McpToolPermissions.kt`:

| Map | Read by | Governs |
|---|---|---|
| `McpToolPermissions.LISTING` | `McpToolRegistry.getAuthorizedTools()` | which tools `tools/list` returns |
| `McpToolPermissions.CALLING` | `McpToolPermissionService.checkPermissionSetForTool()` | whether `tools/call` is allowed |

A tool missing from either map silently disappears (absent from `tools/list`) or is rejected with `PERMISSION_DENIED` on call, regardless of the caller's roles. Registration itself is automatic — every `@Singleton` `McpTool` bean is discovered by Micronaut — so adding a tool means adding it to **both** maps and to this document. The drift check is in [Keeping this document in sync](#keeping-this-document-in-sync).

Within a map, a tool is authorized when the caller holds **any** of the permissions listed for it.

## Tool reference

`*` marks a required argument. Roles are the ones re-checked inside the tool's `execute()`; "any" means no role gate beyond the permission intersection. "Deleg." marks tools that hard-fail with `DELEGATION_REQUIRED` when the API key has delegation disabled — note that `X-MCP-User-Email` itself is mandatory on every `tools/list` and `tools/call` regardless.

### Requirements

| Tool | Arguments | Roles | Deleg. |
|---|---|---|---|
| `get_requirements` | `search`, `usecase`, `norm`, `chapter`, `detailed`, `limit` (default 50), `offset` | any | — |
| `export_requirements` | `format`* (`xlsx\|docx`) | ADMIN, REQ, SECCHAMPION | — |
| `add_requirement` | `shortreq`*, `details`, `motivation`, `example`, `norm`, `usecase`, `chapter` | any | — |
| `delete_all_requirements` | `confirm`* (boolean `true`) | ADMIN | — |

- `get_requirements` — `search` is full-text across title, description, usecase, example, chapter and norm (case-insensitive). `usecase`/`norm` match both the linked entity names and the legacy free-text fields. `detailed: true` adds the legacy free-text usecase/norm fields and timestamps.

### Releases

| Tool | Arguments | Roles | Deleg. |
|---|---|---|---|
| `list_releases` | `status` (`PREPARATION\|ALIGNMENT\|ACTIVE\|ARCHIVED`) | ADMIN / RELEASE_MANAGER | ✓ |
| `get_release` | `releaseId`*, `includeRequirements` (default false) | ADMIN / RELEASE_MANAGER | ✓ |
| `create_release` | `version`* (`MAJOR.MINOR.PATCH`), `name`*, `description` | ADMIN / REQADMIN | ✓ |
| `delete_release` | `releaseId`* | ADMIN / REQADMIN | ✓ |
| `set_release_status` | `releaseId`*, `status`* (`ACTIVE` only) | ADMIN / RELEASE_MANAGER | ✓ |
| `compare_releases` | `fromReleaseId`*, `toReleaseId`* | ADMIN / RELEASE_MANAGER | ✓ |

- `create_release` snapshots all current requirements; the new release starts in `PREPARATION`.
- `delete_release` refuses ACTIVE releases — activate another release first.
- `set_release_status` only accepts `ACTIVE`, and only from `PREPARATION` or `ALIGNMENT`. The previously ACTIVE release auto-archives; `ARCHIVED` is terminal. Use `start_alignment` to reach `ALIGNMENT`.
- `compare_releases` returns `summary{added,deleted,modified,unchanged}` plus per-requirement diffs.

### Alignment

| Tool | Arguments | Roles | Deleg. |
|---|---|---|---|
| `start_alignment` | `release_id`*, `send_notifications` (default true), `reviewer_user_ids[]` | ADMIN / RELEASE_MANAGER | ✓ |
| `get_alignment_status` | `session_id` \| `release_id`, `include_reviewers`, `include_feedback` | any (detail flags: ADMIN / RELEASE_MANAGER) | ✓ |
| `submit_review` | `session_id`*, `snapshot_id`*, `assessment`* (`OK\|CHANGE\|NOGO`), `comment` | REQ / ADMIN | ✓ |
| `finalize_alignment` | `session_id`*, `action`* (`complete_and_activate\|complete\|cancel`), `notes` | ADMIN / RELEASE_MANAGER | ✓ |

- Note the snake_case argument names — these four tools differ from the camelCase used elsewhere.
- `start_alignment` moves the release `PREPARATION → ALIGNMENT` and enrolls all REQ-role users unless `reviewer_user_ids` narrows the set.
- `get_alignment_status` takes **either** `session_id` or `release_id`. `include_reviewers`/`include_feedback` are rejected for callers without ADMIN or RELEASE_MANAGER.
- `submit_review` reviews a single requirement snapshot, not the whole release.
- `finalize_alignment` — `complete_and_activate` completes and sets the release ACTIVE; `complete` completes but leaves the release as-is; `cancel` aborts the alignment.

### Assets

| Tool | Arguments | Roles | Deleg. |
|---|---|---|---|
| `get_assets` | `page`, `pageSize` (max 500), `name`, `type`, `ip`, `owner`, `group` | any | — |
| `get_all_assets_detail` | `name`, `type`, `ip`, `owner`, `group`, `page`, `pageSize` (default 100, max 1000) | any | — |
| `get_asset_profile` | `assetId`*, `includeVulnerabilities`, `includeScanHistory`, `vulnerabilityLimit` (max 100), `scanHistoryLimit` (max 50) | any | — |
| `get_asset_complete_profile` | `assetId`*, `includeVulnerabilities`, `includeScanResults` | any | — |
| `get_asset_most_vulnerabilities` | `topN` (default 1, max 10) | any | — |
| `create_asset` | `name`*, `type`*, `owner`*, `ip`, `uri`, `description`, `criticality` (`CRITICAL\|HIGH\|MEDIUM\|LOW\|NA`), `adDomain`, `cloudAccountId` | any | ✓ |
| `update_asset` | `assetId`* + any of `name`, `type`, `owner`, `ip`, `uri`, `description`, `criticality`, `adDomain` | any | ✓ |
| `delete_asset` | `assetId`*, `forceTimeout` | ADMIN | ✓ |
| `delete_all_assets` | `confirm`* (boolean `true`) | ADMIN | ✓ |

- All read tools scope results through [unified asset access](../CLAUDE.md#unified-asset-access-any-of).
- `create_asset` rejects duplicate names case-insensitively and records the delegated user as `manualCreator`. `uri` accepts `http`, `https` or `urn` for endpoint-style assets.
- `update_asset` is a partial update with row-level access control — an inaccessible ID returns `NOT_FOUND`, not `FORBIDDEN`. Workgroup membership is changed with `assign_assets_to_workgroup`, not here.
- `delete_asset` cascades to vulnerabilities, scan results and exception requests; it returns the deleted counts and an audit-log id.

### Application register

| Tool | Arguments | Roles | Deleg. |
|---|---|---|---|
| `application_register` | `action`*, `id`, `search`, `assetIds[]`, `application{}` | read: any; write: ADMIN / SECCHAMPION | ✓ |

One tool, six actions selected by `action`:

| `action` | Extra arguments | Roles |
|---|---|---|
| `list` | `search` | any |
| `get` | `id`* | any |
| `create` | `application`* | ADMIN / SECCHAMPION |
| `update` | `id`*, `application`* | ADMIN / SECCHAMPION |
| `delete` | `id`* | ADMIN / SECCHAMPION |
| `replace_assets` | `id`*, `assetIds[]` | ADMIN / SECCHAMPION |

`application` object — required: `name`, `businessOwner`, `applicationManager`. Optional: `carId`, `criticality`, `operationalStatus`, `applicationTechnology`, `applicationArchitecture`, `lastQualityCheck` (ISO date), `informationClassification`, `processingOfPersonalData`, `icsRelevant`, `applicationExportControlRelevant`, `operationModel`, `productionOperatingHours`, `serviceOperatingHours`, `backupRecoveryUrl`, `incidentAssignmentGroup`, `notes`, `cmdbWorkspaceUrl`. A missing required field returns `VALIDATION_ERROR`; an insufficient role on a write action also surfaces as `VALIDATION_ERROR`.

`lastQualityCheck` is what `send_application_register_reminders` ages against.

### Vulnerabilities

| Tool | Arguments | Roles | Deleg. |
|---|---|---|---|
| `get_vulnerabilities` | `page`, `pageSize` (max 500), `cveId`, `severity[]` (`Critical\|High\|Medium\|Low\|Info`), `assetId`, `startDate`, `endDate`, `includeExcepted` | any | — |
| `get_all_vulnerabilities_detail` | `severity` (`CRITICAL\|HIGH\|MEDIUM\|LOW`), `assetId`, `minDaysOpen`, `page`, `pageSize` (default 100, max 1000), `includeExcepted` | any | — |
| `get_all_accessible_vulnerabilities` | `severity[]` (`CRITICAL\|HIGH\|MEDIUM\|LOW`), `includeExcepted`, `limit` (default 5000, max 20000) | any | — |
| `get_overdue_assets` | `page`, `size` (max 100), `minSeverity`, `searchTerm` | ADMIN / VULN | ✓ |
| `add_vulnerability` | `hostname`*, `cve`*, `criticality`* (`CRITICAL\|HIGH\|MEDIUM\|LOW`), `daysOpen`, `owner` | ADMIN / VULN | ✓ |
| `deduplicate_vulnerabilities` | *(none)* | ADMIN | ✓ |
| `get_vulnerability_heatmap` | *(none)* | any | — |
| `refresh_vulnerability_heatmap` | *(none)* | ADMIN | — |
| `get_top_accounts_by_finding_age` | `limit` (1–50, default 10) | ADMIN | ✓ |
| `list_products` | `search` | ADMIN / SECCHAMPION | ✓ |

- `severity` is an **array** on `get_vulnerabilities` and `get_all_accessible_vulnerabilities`, but a plain **string** on `get_all_vulnerabilities_detail`. The casing also differs: `get_vulnerabilities` uses `Critical|High|…`, the others `CRITICAL|HIGH|…`.
- `includeExcepted` defaults to `false` everywhere — excepted findings are hidden unless asked for.
- `get_all_accessible_vulnerabilities` is the single-call, unpaginated view over every asset the caller can reach. The response carries `total`/`returned`/`truncated` so callers can tell when `limit` clipped the result.
- `get_overdue_assets` applies the same unified asset access as the REST endpoint for non-admins.
- `add_vulnerability` auto-creates the asset when `hostname` is unknown (owner defaults to `MCP-IMPORT`). It returns both `id` (DB primary key) and `vulnerabilityId` (the CVE). `daysOpen` backdates the finding and exists for test fixtures.
- `deduplicate_vulnerabilities` keeps the oldest row per `(CVE id, product)` per asset and returns `totalDuplicatesRemoved`, `assetsAffected` and per-asset `details[]`.
- `get_vulnerability_heatmap` returns per-asset severity counts plus a `heatLevel`: `RED` = any CRITICAL or HIGH > 100; `YELLOW` = 1–100 HIGH; `GREEN` = neither. ADMIN/SECCHAMPION see all assets, everyone else is scoped by unified access.
- `get_top_accounts_by_finding_age` ranks AWS accounts by their oldest still-open, non-excepted finding. Returns `accounts[]` (`awsAccountId`, `accountName` — never null, falls back to the bare 12-digit id — `oldestFindingFirstSeenAt`, `oldestFindingDaysOpen`, `oldestFindingCve`, `oldestFindingSeverity`, `oldestFindingAssetName`, `oldestFindingAssetInstanceId`, `openFindingCount`, `affectedAssetCount`) and `count`. Backs `GET /api/admin/account-finding-age/top` and CLI `send-account-finding-age-report`. Non-ADMIN gets `ADMIN_REQUIRED`.

### Exception requests (the approval workflow)

| Tool | Arguments | Roles | Deleg. |
|---|---|---|---|
| `create_exception_request` | `subject`*, `scope`*, `reason`*, `expirationDate`*, `vulnerabilityId`, `kind`, `subjectValue`, `scopeValue`, `assetId`, `validateOnly` | any (ALL_VULNS gated) | ✓ |
| `get_my_exception_requests` | `status` (`PENDING\|APPROVED\|REJECTED\|EXPIRED\|CANCELLED`), `page`, `size` (max 100) | any (own rows) | ✓ |
| `get_my_exception_request_summary` | *(none)* | any (own rows) | ✓ |
| `get_exception_request` | `requestId`* | requester, or ADMIN / SECCHAMPION | ✓ |
| `get_pending_exception_requests` | `page`, `size` (max 100) | ADMIN / SECCHAMPION | ✓ |
| `get_exception_request_statistics` | `dateRange` (`7days\|30days\|90days\|alltime`, default `30days`), `topLimit` (default 10, max 50) | ADMIN / SECCHAMPION | ✓ |
| `approve_exception_request` | `requestId`*, `comment` (≤1024) | ADMIN / SECCHAMPION | ✓ |
| `reject_exception_request` | `requestId`*, `comment`* (10–1024) | ADMIN / SECCHAMPION | ✓ |
| `cancel_exception_request` | `requestId`* | requester only, PENDING only | ✓ |
| `delete_exception_request` | `requestId`* | requester only | ✓ |
| `reconcile_exception_requests` | *(none)* | ADMIN | ✓ |

- **`create_exception_request`** — `reason` is 50–2048 characters, `expirationDate` is a future ISO-8601 date. The request is described on two axes: `subject` (`ALL_VULNS|PRODUCT|CVE`, *what* is excepted) × `scope` (`GLOBAL|IP|ASSET|AWS_ACCOUNT|OS`, *where* it applies).
  - `subjectValue` — product name pattern (`subject=PRODUCT`) or comma-separated CVE list (`subject=CVE`); null for `ALL_VULNS`.
  - `scopeValue` — IP (`scope=IP`), 12-digit AWS account id (`scope=AWS_ACCOUNT`), or an OS-version substring matched case-insensitively against `Asset.osVersion` (`scope=OS`, suppressing the CVEs on every matching asset); null for `GLOBAL`/`ASSET`.
  - `assetId` — required for `scope=ASSET`.
  - `vulnerabilityId` — required unless `kind=NO_EDR`.
  - Third axis `kind` (`VULNERABILITY` default | `NO_EDR`): **`NO_EDR`** records "this asset cannot run an EDR agent". It requires `scope=ASSET` + `assetId`, takes no `vulnerabilityId`/`subjectValue`/`scopeValue`, is stored with the filler `subject=ALL_VULNS`, **suppresses nothing**, and only removes the asset from the EDR-coverage KPI denominator. Unlike a real `ALL_VULNS` request it does *not* need ADMIN/VULN, but the caller must be able to access the named asset.
  - `validateOnly: true` checks the request against the spec invariants and returns success **without persisting** — a dry run.
  - ADMIN/SECCHAMPION requests are auto-approved; the response carries `autoApproved`.
- **`get_exception_request`** returns the full row: ids, CVE, asset name/IP, subject/scope/values, reason, expiration, status, `autoApproved`, reviewer, review date and comment, timestamps.
- **`get_my_exception_request_summary`** returns `totalRequests` plus `approvedCount`, `pendingCount`, `rejectedCount`, `expiredCount`, `cancelledCount`.
- **`get_pending_exception_requests`** is FIFO-sorted.
- **`get_exception_request_statistics`** returns `totalRequests`, `approvalRatePercent`, `averageApprovalTimeHours`, `requestsByStatus`, `topRequesters[]` and `topCVEs[]` over the chosen window.
- **`delete_exception_request`** removes the request row permanently, and for an APPROVED request also deletes the underlying `VulnerabilityException` so the CVE stops being suppressed. Contrast `cancel_exception_request`, which only withdraws a still-PENDING request. Both are restricted to the original requester.
- **`reconcile_exception_requests`** is operator maintenance: it rebuilds missing `VulnerabilityException` rows for APPROVED requests. Returns `scanned`, `alreadyConsistent`, `repaired`, `failed`, `failureReasons`.

### Vulnerability exceptions (what is actually in effect)

| Tool | Arguments | Roles | Deleg. |
|---|---|---|---|
| `list_vulnerability_exceptions` | `activeOnly`, `subject`, `scope`, `kind`, `includeAffectedCount` | ADMIN / SECCHAMPION / VULN | ✓ |
| `delete_all_vulnerability_exceptions` | `confirm`* (string `"DELETE_ALL"`) | ADMIN | ✓ |

- `list_vulnerability_exceptions` lists the `VulnerabilityException` rows themselves, not the request workflow. `activeOnly: true` drops expired rows (default `false`). `kind` filters `VULNERABILITY` vs `NO_EDR`; omit for both. `includeAffectedCount` (default `true`) adds how many current vulnerabilities each exception matches — always `0` for `NO_EDR`, which waives nothing. Each row returns id, kind, subject, scope, subjectValue, scopeValue, assetId/assetName, expirationDate, reason, createdBy, timestamps, `isActive`, `affectedVulnerabilityCount`.
- `delete_all_vulnerability_exceptions` requires the literal string `"DELETE_ALL"` in `confirm` — a template that omits the field cannot wipe data. Returns `deletedCount`.

### Scans and products

| Tool | Arguments | Roles | Deleg. |
|---|---|---|---|
| `get_scans` | `page`, `pageSize` (max 500), `scanType` (`nmap\|masscan`), `uploadedBy`, `startDate`, `endDate` | any | — |
| `get_asset_scan_results` | `portMin`, `portMax` (1–65535), `service`, `state` (`open\|filtered\|closed`), `page`, `pageSize` (default 100, max 1000) | any | — |
| `search_products` | `page`, `pageSize` (max 500), `service`, `stateFilter` (`open\|filtered\|closed\|all`, default `open`) | any | — |

### Workgroups

All ADMIN-only, all require delegation.

| Tool | Arguments |
|---|---|
| `create_workgroup` | `name`* (1–100 chars), `description` (≤512) |
| `delete_workgroup` | `workgroupId`* |
| `assign_assets_to_workgroup` | `workgroupId`*, `assetIds[]`* |
| `assign_users_to_workgroup` | `workgroupId`*, `userIds[]`* |
| `list_workgroup_aws_accounts` | `workgroupId`* |
| `add_workgroup_aws_account` | `workgroupId`*, `awsAccountId`* (exactly 12 digits) |
| `remove_workgroup_aws_account` | `workgroupId`*, `awsAccountId`* |
| `list_workgroup_ad_domains` | `workgroupId`* |
| `add_workgroup_ad_domain` | `workgroupId`*, `adDomain`* |
| `remove_workgroup_ad_domain` | `workgroupId`*, `adDomain`* |

- `delete_workgroup` cascades the user and asset associations.
- AWS-account and AD-domain assignments grant asset access to **direct** workgroup members only (criteria 9 and 10 of unified asset access).

### Users

All ADMIN-only, all require delegation.

| Tool | Arguments |
|---|---|
| `list_users` | *(none)* |
| `add_user` | `username`*, `email`*, `password`*, `roles[]`, `mfaEnabled` |
| `delete_user` | `userId`* |

- `add_user` — `roles` defaults to `["USER","VULN","REQ"]` (not `["USER"]`); valid values are `USER`, `ADMIN`, `VULN`, `RELEASE_MANAGER`, `REQ`, `RISK`, `SECCHAMPION`, `REQADMIN`, `REPORT`. `mfaEnabled` defaults to `false`. Both `username` and `email` must be unique.
- `delete_user` takes the numeric **user id**, not an email.

### User mappings, AWS account risk assessments and onboarding

Delegation required throughout. Most are ADMIN-only; the three onboarding tools also accept SECCHAMPION, mirroring `AccountOnboardingController`.

| Tool | Arguments | Roles | Deleg. |
|---|---|---|---|
| `import_user_mappings` | `mappings[]`*, `dryRun`, `startRiskAssessment`, `riskAssessmentUseCase`, `riskAssessmentDeadlineDays`, `onboardingMode`, `sendWelcomeEmail`, `questionnaireExpiryDays` | ADMIN | yes |
| `list_user_mappings` | `email`, `page`, `size` (max 100) | ADMIN | yes |
| `list_aws_account_risk_assessments` | `awsAccountId`, `ownerEmail`, `status`, `limit` (1–100, default 20) | ADMIN | yes |
| `simulate_account_onboarding` | `awsAccountId`*, `ownerEmail`*, `mode`*, `riskAssessmentUseCase`, `riskAssessmentDeadlineDays` (1–3650), `questionnaireExpiryDays` (1–90), `sendWelcomeEmail`, `dryRun` | ADMIN, SECCHAMPION | yes |
| `list_account_onboarding_rules` | `activeOnly` (default `true`) | ADMIN, SECCHAMPION | yes |
| `preview_account_onboarding_rules` | `answers[]`* (`{questionKey`*, `choiceKeys[]`*`}`) | ADMIN, SECCHAMPION | yes |

- **`import_user_mappings`** — `mappings` holds up to 1000 entries, each with `email`* plus at least one of `awsAccountId` (12 digits) or `domain`. Optionally starts a risk assessment for the owner of every brand-new AWS account the import introduces: `startRiskAssessment` (bool), `riskAssessmentUseCase` (required when it is true), `riskAssessmentDeadlineDays` (1..3650, default `7`). Returns counts `created`, `createdPending` (user does not exist yet), `skipped`, `errors[]`, plus `newAccounts[]` (`awsAccountId`, `emails`) and `riskAssessments[]` (`awsAccountId`, `ownerEmail`, `riskAssessmentId`, `assessor`, `endDate`, `useCase`, `releaseVersion`, `requirementCount`, `skipped`, `skipReason`, `error`). A `riskAssessments[]` entry carries **either** `error` (the assessment failed) **or** `skipped`/`skipReason` (the pair already had an open assessment — an idempotent no-op; do not report it as a failure). Runs the same code path as REST `POST /api/user-mappings/bulk` and CLI `manage-user-mappings import`, so brand-new-account detection is identical. Assessments are pinned to the ACTIVE requirements release; the call is rejected with `VALIDATION_ERROR` when none exists or it carries no requirements for the use case. `onboardingMode` (`WELCOME_ONLY` | `DIRECT` | `GUIDED`) selects what happens for the owner instead; omitting it falls back to `startRiskAssessment`, which on its own means `DIRECT` with no welcome mail — byte-identical to the behaviour before onboarding modes existed. `sendWelcomeEmail` overrides that, and `questionnaireExpiryDays` (1..90, default 14) sets how long a `GUIDED` link lives. The result gains `onboarding[]` with the same three shapes as `riskAssessments[]`. Combining `startRiskAssessment` with a non-`DIRECT` mode is a `VALIDATION_ERROR`. See `docs/AWS_ACCOUNT_RISK_ASSESSMENT.md` and `docs/ACCOUNT_ONBOARDING.md`.
- **`list_user_mappings`** returns the full `UserMappingDto` (id, email, awsAccountId, domain, userId, isFutureMapping, applied/created/updatedAt). `email` is a case-insensitive partial match.
- **`simulate_account_onboarding`** runs the whole onboarding path against an AWS account id and email address you supply — the *same* code path a real import runs, not a mock — so the welcome mail and the guided questionnaire can be exercised without waiting for a real account. `mode` is `WELCOME_ONLY` | `DIRECT` | `GUIDED`; `riskAssessmentUseCase` is required for `DIRECT`. Returns `onboarding[]` and `riskAssessments[]` in the shapes above. **It really sends mail** unless `dryRun` is true, so it is rate limited (20 live runs per actor per hour), every simulated message says it is a test and names the actor, and the invite is stamped `simulated`. The invite **id** is returned; the token never is.
- **`list_account_onboarding_rules`** returns the configured questions and the rules mapping answer combinations to use cases: `questionCount`, `choiceCount`, `activeRuleCount`, `hasDefaultRule`, `reachableUseCases[]`, `reachableRequirementCount`, `releaseVersion`, and `rules[]` (`name`, `description`, `isDefault`, `active`, `combination[]` as `questionKey=choiceKey`, `useCases[]`). `hasDefaultRule` is surfaced at the top because without a fallback, an owner whose answers match nothing is told to wait for a human.
- **`preview_account_onboarding_rules`** resolves a set of answers to the use cases they would scope an assessment to, **writing nothing and consuming no invite**. Returns `matchedRules[]`, `useCases[]`, `requirementCount`, `usedDefault`, `releaseVersion`, `failure` (`NO_RULE_MATCHED` | `EMPTY_QUESTIONNAIRE` | `NO_ACTIVE_RELEASE`, or null). Every matching rule contributes — the result is the union, deduplicated. An unknown question or choice key is a `VALIDATION_ERROR`, never silently ignored: dropping one would resolve a different combination than the caller asked about.
- **`list_aws_account_risk_assessments`** lists only the assessments auto-started by an import — manually created ones never appear. `ownerEmail` matches case-insensitively; `status` is e.g. `STARTED`. Returns `assessments[]` (`riskAssessmentId`, `awsAccountId`, `ownerEmail`, `useCase`, `releaseVersion` and `releaseName` — the requirements version the assessment is measured against, `null` for assessments started before release pinning — `assessor`, `respondent`, `startDate`, `endDate`, `status`, `reminderTwoDaysSentAt`, `reminderOneDaySentAt`, `createdAt`) and `count`.

### AWS account sharing

ADMIN-only, delegation required.

| Tool | Arguments |
|---|---|
| `list_aws_account_sharing` | `page`, `size` (max 100) |
| `create_aws_account_sharing` | `sourceUserId`*, `targetUserId`*, `awsAccountIds[]` |
| `delete_aws_account_sharing` | `id`* |

- Sharing is directional and non-transitive: the target sees the source's accounts, never the reverse.
- `create_aws_account_sharing` validates that the two users are distinct and exist, that the source has at least one AWS mapping, and that no duplicate rule exists. Omitted or empty `awsAccountIds` shares **all** of the source's accounts (legacy default); a non-empty list shares only those accounts and each must match one of the source's actual mappings.
- `list_aws_account_sharing` returns the rules with source/target user info and a shared-account count.

### GitHub repositories

Delegation required. See `docs/GITHUB_REPOS.md`.

| Tool | Arguments | Roles |
|---|---|---|
| `import_github_repos` | *(none)* | ADMIN / VULN |
| `send_github_repo_alerts` | `dryRun`, `thresholdDays` (default 30), `force`, `onlyEmail` | ADMIN |
| `list_github_owner_email_mappings` | *(none)* | ADMIN / VULN / SECCHAMPION |
| `create_github_owner_email_mapping` | `owner`*, `email`* | ADMIN / VULN |
| `delete_github_owner_email_mapping` | `id`* | ADMIN / VULN |
| `discover_github_owner_email_mappings` | `dryRun` | ADMIN / VULN |

- `import_github_repos` imports every repository reachable through the configured GitHub App, including each repo's open high/critical Dependabot alert counts, and writes one finding snapshot per run (the 30-day history). Errors with `NO_GITHUB_CONFIG` when no active GitHub App configuration exists (Admin → GitHub App). Mirrors CLI `import-github-repos`.
- `send_github_repo_alerts` alerts owners whose open high+critical count has not decreased over `thresholdDays` (baseline = newest snapshot at least that old; alert when current `> 0` and `>=` baseline). `force` bypasses the non-decrease comparison and alerts every eligible owner with open high/critical alerts, including repos with no baseline snapshot yet. `onlyEmail` restricts the run to repos owned by that address (case-insensitive). Returns `reposAlerted`, `reposExcepted[]` (active `github_repo_alert_exception`), `unmappedRepos[]` (no `ownerEmail`), `reposSkippedInsufficientHistory[]`, `recipients[]`. Mirrors CLI `alert-github-repo-owners`.
- `create_github_owner_email_mapping` returns 409 if the owner is already mapped, and immediately backfills `ownerEmail` on existing repos under that owner whose value is currently blank — a manually-set or previously auto-filled value is never overwritten.
- `delete_github_owner_email_mapping` does not un-set any `ownerEmail` it previously backfilled.
- `discover_github_owner_email_mappings` is best-effort auto-discovery: for already-imported repos with no `ownerEmail`, it reads the GitHub API's public profile field (`GET /users/{owner}`, works for orgs and users) and creates a mapping (with the usual backfill) for each owner that has one set. It only touches owners without an existing mapping and never calls GitHub's repo-listing API. Returns `discoveredMappings[]` (`owner`, `email`, `repoCount`) and `ownersSkippedNoPublicEmail[]`. Errors with `NO_GITHUB_CONFIG`. Mirrors CLI `manage-github-owner-mappings discover`.

### Notifications and reports

All ADMIN-only, all require delegation, all take `dryRun` (default `false`) to preview recipients without sending.

| Tool | Arguments | Mirrors CLI |
|---|---|---|
| `send_admin_summary` | `dryRun` | `send-admin-summary` |
| `send_patch_notifications` | `emailPrefix`*, `days` (default 30), `dryRun` | `send-patch-notifications` |
| `send_vulnerability_notifications` | `days` (default 30), `notificationUser`, `notAll`, `dryRun` | `send-notification-users` |
| `send_outdated_notifications` | `dryRun` | `send-notifications` |
| `send_exception_expiry_reminders` | `days` (default 7), `dryRun` | `send-exception-expiry-reminders` |
| `send_application_register_reminders` | `days` (default 365), `dryRun` | `send-application-register-reminders` |
| `notify_new_accounts` | `notificationText`*, `hours` (default 24), `dryRun` | `notify-new-accounts` |

- **`send_admin_summary`** emails system statistics to all ADMIN/REPORT users.
- **`send_patch_notifications`** notifies users about missing patches (overdue vulnerabilities), batched by the **first character** of their email — `emailPrefix` is required, e.g. `"a"`.
- **`send_vulnerability_notifications`** notifies every user with affected AWS accounts about vulnerabilities open longer than `days`. `notificationUser` (a login email) restricts the run to one user; an unknown address returns `USER_NOT_FOUND`. `notAll: true` only matters together with `notificationUser` for an ADMIN/SECCHAMPION recipient: it narrows the report to AWS accounts backing assets that user actually owns, belongs to via workgroup, or has via account sharing, instead of the global view those roles get by default. Returns `status`, `notificationScope`, `thresholdDays`, `awsAccountsAffected`, `usersNotified`, `emailsSent`, `emailsFailed`, `recipients[]`, `failedRecipients[]`, `unmappedAccounts[]`. Use `send_patch_notifications` for the batch-by-prefix flow instead.
- **`send_outdated_notifications`** reads the outdated-asset materialized view, resolves owner emails through the AWS account mappings, and sends one consolidated reminder per owner. Assets whose owner maps to no email are skipped. Returns `assetsProcessed`, `emailsSent`, `failures`, `skipped`.
- **`send_exception_expiry_reminders`** emails each exception owner (the exception's `createdBy` username resolved to that user's email) when `expirationDate` falls **exactly** `days` days from today — one consolidated email per owner. A reminder is sent at most once per (exception, expiration date) pair, so it is safe to run daily; extending an expiration date re-arms the reminder once it re-enters the window. Returns `status` (`SUCCESS`/`DRY_RUN`/`PARTIAL_FAILURE`/`FAILURE`), `exceptionsExpiring`, `ownersNotified`, `emailsSent`, `emailsFailed`, `recipients[]`, `failedRecipients[]`, `unmappedOwners[]`, `alreadyNotified`.
- **`send_application_register_reminders`** emails each register entry's business owner and application manager when `lastQualityCheck` is older than `days` or unset. Returns `status`, `entriesOverdue`, `recipientCount`, `emailsSent`, `emailsFailed`, `recipients[]`, `failedRecipients[]`.
- **`notify_new_accounts`** notifies users about AWS account mappings created in the last `hours`. `notificationText` is the email body; the list of new account ids is appended automatically. (The CLI reads this text from a file — the MCP tool takes it inline so callers can vary it per run.) Returns `status`, `accountMappingsFound`, `usersNotified`, `emailsSent`, `emailsFailed`, `recipients`, `failedRecipients`.

### Maintenance and CrowdStrike housekeeping

ADMIN-only unless noted, delegation required.

| Tool | Arguments | Roles |
|---|---|---|
| `get_crowdstrike_last_import` | *(none)* | ADMIN / VULN |
| `delete_asset_not_seen` | `days`* (≥1), `dryRun`, `includeLegacy` | ADMIN |
| `asset_match_clear` | `accountIds[]`*, `resourceIds[]`*, `dryRun`, `maxDeletePercent` (default 25), `strict` | ADMIN |

- **`get_crowdstrike_last_import`** mirrors `/api/crowdstrike/servers/import/latest`. Returns `lastImportAt`, `importedBy`, `serversProcessed`, `serversCreated`, `serversUpdated`, `vulnerabilitiesImported`, `vulnerabilitiesSkipped`, `vulnerabilitiesWithPatchDate`, `errorCount` — or `lastImportAt: null` with a message when no import has ever run.
- **`delete_asset_not_seen`** deletes CrowdStrike-imported assets with no import inside the last `days` days. `includeLegacy` also sweeps legacy CrowdStrike assets that carry no import timestamp at all (defaults to the server-configured value). Returns `days`, `cutoff`, `candidateCount`, `deletedCount`, `skippedCount`, `legacyCandidateCount`, `legacyDeletedCount`, `status`, `runId`, `candidates[]` (with a per-asset `reason`) and `errors[]`. Mirrors CLI `delete-asset-not-seen`.
- **`asset_match_clear`** reconciles AWS assets against an authoritative resource snapshot the caller supplies inline: it deletes every asset whose `cloudAccountId` is in `accountIds` and whose `cloudInstanceId` is **not** in `resourceIds`. `strict: true` treats the snapshot as globally authoritative over all AWS assets rather than only the snapshot-covered accounts. `maxDeletePercent` is a safety brake — the run aborts without deleting anything if the proposed deletions exceed that share of scoped assets; set `0` to disable. An empty snapshot is refused with `EMPTY_SNAPSHOT`. Returns `scopeMode`, `snapshotAccountCount`, `snapshotResourceCount`, `scopedAssetCount`, `uncoveredAccountCount`, `uncoveredAssetCount`, `candidateCount`, `deletedCount`, `skippedCount`, `status`, `safetyBrakeTripped`, `safetyBrakePercent`, `candidates[]`, `errors[]`. Mirrors the delete path of CLI `asset-match-clear` (without the S3 download).

## Keeping this document in sync

Every tool below must appear in both permission maps and in this file. (Registration is automatic — a `@Singleton` `McpTool` bean is picked up by `McpToolRegistry` with no edit needed there.) Run these from the repo root after adding or renaming a tool:

```bash
PERMS=src/backendng/src/main/kotlin/com/secman/mcp/McpToolPermissions.kt
TOOLS=$(grep -h 'override val name' \
    src/backendng/src/main/kotlin/com/secman/mcp/tools/*.kt \
    | sed -E 's/.*"(.*)".*/\1/')
sed -n '/val LISTING/,/val CALLING/p' "$PERMS" > /tmp/mcp-listing.kt
sed -n '/val CALLING/,$p'            "$PERMS" > /tmp/mcp-calling.kt

# 1. Tools missing from this document
for t in $TOOLS; do
  grep -q "\`$t\`" docs/MCP.md || echo "UNDOCUMENTED: $t"
done

# 2. Tools missing from the tools/list map (McpToolPermissions.LISTING)
for t in $TOOLS; do
  grep -q "\"$t\"" /tmp/mcp-listing.kt || echo "NOT LISTABLE: $t"
done

# 3. Tools missing from the tools/call map (McpToolPermissions.CALLING + ToolCategories)
for t in $TOOLS; do
  grep -qE "\"$t\"" /tmp/mcp-calling.kt \
    src/backendng/src/main/kotlin/com/secman/mcp/ToolCategories.kt \
    || echo "NOT CALLABLE: $t"
done
```

Check 3 currently reports a backlog of tools that are documented and listed but rejected with `PERMISSION_DENIED` on `tools/call`, because `CALLING` has drifted behind `LISTING`. Run the command for the live list before relying on a tool in an automation.

## Programmatic example (Python)

```python
import requests, os
r = requests.post("http://localhost:8080/mcp", headers={
    "X-MCP-API-Key": os.environ["SECMAN_MCP_KEY"],
    "X-MCP-User-Email": os.environ["SECMAN_USER_EMAIL"],
    "Content-Type": "application/json",
}, json={"jsonrpc":"2.0","id":"1","method":"tools/call",
         "params":{"name":"get_requirements","arguments":{"limit":10}}})
print(r.json())
```

A standalone Go MCP client lives in `scripts/mcp/`.

## Errors

| Code (JSON-RPC) | Symbol | Cause |
|---|---|---|
| -32007 | `DELEGATION_HEADER_REQUIRED` | `X-MCP-User-Email` missing/empty |
| -32003 | `DELEGATION_NOT_ENABLED` | API key has no delegation |
| -32003 | `DELEGATION_DOMAIN_REJECTED` | email domain not in allowlist |
| -32003 | `DELEGATION_USER_NOT_FOUND` / `_INACTIVE` / `_INVALID_EMAIL` | as named |
| -32003 | `DELEGATION_FAILED` | catch-all |

Configure failure-rate alerts:
```yaml
secman:
  mcp:
    delegation:
      alert:
        threshold: 10        # failures
        window-minutes: 5
```

## Operations

- Rate limits (per API key): 1000 req/h, burst 100, max 10 concurrent sessions. Tunable in `application.yml`:
  ```yaml
  mcp:
    max-concurrent-sessions: 200
    max-sessions-per-key: 10
    session-timeout-minutes: 60
    rate-limiting: { default-requests-per-hour: 1000, burst-limit: 100 }
    caching: { enabled: true, ttl-minutes: 15 }
  ```
- Sessions auto-expire after 60 min idle.
- Audit: every call logged with timestamp, duration, user, key, tool, params, status, IP, UA. Backup tables: `mcp_api_keys`, `mcp_sessions`, `mcp_audit_logs`, `mcp_tool_permissions`.
- Admin UI: **Admin > MCP Monitoring** (sessions, key usage, tool analytics, errors).

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Authentication required` | header missing/expired/invalid; check `expiresAt` |
| `DELEGATION_HEADER_REQUIRED` | add `X-MCP-User-Email`; ensure key has delegation enabled |
| `Permission denied` | API key + delegated user role intersection lacks the required permission |
| `Origin not allowed` | browser request without allowed origin; add to `transport.allowed-origins` |
| Connection refused | backend down; firewall; wrong path (must end in `/mcp`) |

Enable debug headers (logs all `/mcp/**` and `/api/**` headers + decoded JWT claims): `SECMAN_DEBUG=true`. Do not use in production.
