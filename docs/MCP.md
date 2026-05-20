# MCP Integration

Streamable HTTP / JSON-RPC 2.0 endpoint at `POST /mcp`. 55+ tools spanning requirements, assets, vulnerabilities, scans, releases, workgroups, user mappings, AWS account sharing, and the vulnerability heatmap.

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

| Permission | Tools |
|---|---|
| `REQUIREMENTS_READ` | `get_requirements`, `export_requirements`, `list_releases`, `get_release`, `compare_releases`, `create_release`*, `delete_release`*, `set_release_status`* |
| `REQUIREMENTS_WRITE` | `add_requirement` |
| `REQUIREMENTS_DELETE` | `delete_all_requirements` (ADMIN) |
| `ASSETS_READ` | `get_assets`, `get_all_assets_detail`, `get_asset_profile`, `get_asset_complete_profile`, `create_asset`, `update_asset`, `application_register` |
| `VULNERABILITIES_READ` | `get_vulnerabilities`, `get_all_vulnerabilities_detail`, `get_asset_most_vulnerabilities`, `get_overdue_assets`, `*_exception_request*`, `get_vulnerability_heatmap`, `refresh_vulnerability_heatmap` |
| `SCANS_READ` | `get_scans`, `get_asset_scan_results`, `search_products` |
| `USER_ACTIVITY` | `list_users`, `add_user`, `delete_user`, `import_user_mappings`, `list_user_mappings`, `*_aws_account_sharing` |
| `WORKGROUPS_WRITE` | `create_workgroup`, `delete_workgroup`, `assign_assets_to_workgroup`, `assign_users_to_workgroup`, `add_/remove_workgroup_aws_account` |

\* `create_release`/`delete_release` need ADMIN or REQADMIN; `set_release_status` needs ADMIN or RELEASE_MANAGER.

## Tool reference (parameters)

### Requirements
- **`get_requirements`** — `limit` (default 20, max 100), `offset`, `tags[]`, `status` (`DRAFT|ACTIVE|DEPRECATED|ARCHIVED`), `priority` (`LOW|MEDIUM|HIGH|CRITICAL`).
- **`export_requirements`** — `format` (`xlsx|docx`).
- **`add_requirement`** — `shortreq`* | `details`, `motivation`, `norm`, `chapter`.
- **`delete_all_requirements`** — `confirm: true` (ADMIN).

### Assets (delegation required for writes)
- **`get_assets`** — `page`, `pageSize` (max 500), `name`, `type` (`SERVER|WORKSTATION|NETWORK|OTHER`), `ip`, `owner`.
- **`get_all_assets_detail`**, **`get_asset_profile`** (`assetId`*, `includeVulnerabilities`, `includeScanHistory`), **`get_asset_complete_profile`**.
- **`create_asset`** — `name`*, `type`*, `owner`*, `ip`, `description`, `criticality` (`CRITICAL|HIGH|MEDIUM|LOW|NA`), `adDomain`, `cloudAccountId`. Duplicate names rejected (case-insensitive); delegated user becomes `manualCreator`.
- **`update_asset`** — `assetId`* + any of `name`, `type`, `owner`, `ip`, `description`, `criticality`, `adDomain`. Partial update; row-level access enforced. Workgroup reassignment is via `assign_assets_to_workgroup`.
- **`delete_asset`** — `assetId`*, `forceTimeout` (ADMIN). Cascade-deletes vulnerabilities, scan results, exception requests; returns counts and audit-log id.
- **`delete_all_assets`** — `confirm: true` (ADMIN).

- **`application_register`** — unified Application Register tool via `action`:
  - `list` (`search` optional)
  - `get` (`id`*)
  - `create` (`application`* object; ADMIN/SECCHAMPION, delegation required)
  - `update` (`id`*, `application`*; ADMIN/SECCHAMPION)
  - `delete` (`id`*; ADMIN/SECCHAMPION)
  - `replace_assets` (`id`*, `assetIds[]`; ADMIN/SECCHAMPION)

### Vulnerabilities
- **`get_vulnerabilities`** — `page`, `pageSize`, `cveId`, `severity[]` (`Critical|High|Medium|Low|Info`), `assetId`.
- **`get_all_vulnerabilities_detail`** — `severity` (`CRITICAL|HIGH|MEDIUM|LOW`), `assetId`, `minDaysOpen`.
- **`get_asset_most_vulnerabilities`** — `topN` (default 1, max 10).
- **`get_overdue_assets`** (ADMIN/VULN, delegation) — `page`, `size` (max 100), `minSeverity`, `searchTerm`. Non-admin filtered through unified asset access.
- **`add_vulnerability`** (ADMIN/VULN, delegation) — `hostname`*, `cve`*, `criticality`*, `daysOpen`, `owner`. Auto-creates asset. Returns `id` (DB PK) and `vulnerabilityId` (CVE).

### Exception requests (delegation)
- **`create_exception_request`** — `vulnerabilityId`*, `reason`* (50–2048 chars), `expirationDate`* (ISO-8601 future), `scope` (`SINGLE_VULNERABILITY|CVE_PATTERN`). ADMIN/SECCHAMPION are auto-approved.
- **`get_my_exception_requests`** — `status`, `page`, `size`.
- **`get_pending_exception_requests`** (ADMIN/SECCHAMPION) — FIFO sorted.
- **`approve_exception_request`** (ADMIN/SECCHAMPION) — `requestId`*, `comment` (≤1024).
- **`reject_exception_request`** (ADMIN/SECCHAMPION) — `requestId`*, `comment`* (10–1024).
- **`cancel_exception_request`** — `requestId`*. Only the requester, only PENDING.

### Scans
- **`get_scans`** — `scanType` (`nmap|masscan`), `uploadedBy`, `startDate`.
- **`get_asset_scan_results`** — `portMin`, `portMax` (1–65535), `service`, `state` (`open|filtered|closed`).
- **`search_products`**.

### Workgroups (ADMIN, delegation)
- **`create_workgroup`** — `name`* (1–100), `description` (≤512).
- **`delete_workgroup`** — `workgroupId`*. Cascades user/asset associations.
- **`assign_assets_to_workgroup`** / **`assign_users_to_workgroup`** — `workgroupId`*, `assetIds[]`/`userIds[]`*.
- **`list_/add_/remove_workgroup_aws_account`** — `workgroupId`*, `cloudAccountId`*.

### User mappings (ADMIN, delegation)
- **`import_user_mappings`** — `mappings[]`* (≤1000), each `email`* + at least one of `awsAccountId` (12 digits) or `domain`. `dryRun`. Returns counts: `created`, `createdPending` (user not yet exists), `skipped`, `errors[]`.
- **`list_user_mappings`** — `email`, `page`, `size` (max 100). Returns full `UserMappingDto` (id, email, awsAccountId, domain, userId, isFutureMapping, applied/created/updatedAt).

### AWS account sharing (ADMIN, delegation)
- **`list_aws_account_sharing`** — `page`, `size`. Returns directional sharing rules with source/target user info and shared-account count.
- **`create_aws_account_sharing`** — `sourceUserId`*, `targetUserId`*, `awsAccountIds` (optional array of strings). Validates: distinct users, both exist, source has ≥1 AWS mapping, no duplicate. Empty/omitted `awsAccountIds` → share ALL of source's accounts (legacy default); non-empty → share only the listed accounts (must match source's actual mappings).
- **`delete_aws_account_sharing`** — `id`*.

### Releases (ADMIN/RELEASE_MANAGER for read; create/delete: ADMIN/REQADMIN)
- **`list_releases`** — `status` (`PREPARATION|ALIGNMENT|ACTIVE|ARCHIVED`).
- **`get_release`** — `releaseId`*, `includeRequirements` (default false).
- **`create_release`** — `version`* (`MAJOR.MINOR.PATCH`), `name`*, `description`. Snapshots all current requirements, status PREPARATION.
- **`delete_release`** — `releaseId`*. ACTIVE releases cannot be deleted.
- **`set_release_status`** — `releaseId`*, `status: ACTIVE`. Only PREPARATION/ALIGNMENT can be set ACTIVE; previously-ACTIVE auto-archives. ARCHIVED is terminal. Use `start_alignment` for ALIGNMENT.
- **`compare_releases`** — `fromReleaseId`*, `toReleaseId`*. Returns `summary{added,deleted,modified,unchanged}` plus per-requirement diffs.

### Alignment
- **`start_alignment`** (ADMIN/REQADMIN) — `releaseId`*. PREPARATION → ALIGNMENT.
- **`get_alignment_status`** — `releaseId`*.
- **`submit_review`** — `releaseId`*, `approved`*, `comment`.
- **`finalize_alignment`** (ADMIN/REQADMIN) — `releaseId`*.

### Heatmap (delegation)
- **`get_vulnerability_heatmap`** — no params. Per-asset entries with severity counts and `heatLevel`:
  - `RED`: any CRITICAL or `HIGH > 100`
  - `YELLOW`: 1–100 HIGH
  - `GREEN`: no CRITICAL or HIGH

  ADMIN/SECCHAMPION see all assets; others scoped via unified access.
- **`refresh_vulnerability_heatmap`** (ADMIN) — recalculates.

### Admin / system
- **`list_users`** (ADMIN), **`list_products`** (ADMIN/SECCHAMPION).
- **`add_user`** (ADMIN) — `email`*, `password`*, `roles[]` (default `["USER"]`).
- **`delete_user`** (ADMIN) — `email`*.
- **`send_admin_summary`** (ADMIN) — same payload as CLI `send-admin-summary`.

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
