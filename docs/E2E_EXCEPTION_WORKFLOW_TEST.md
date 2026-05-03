# E2E: Vulnerability Exception Workflow

Spec: `specs/063-e2e-vuln-exception/`. Validates the full exception lifecycle via MCP. Idempotent — cleans up before and after.

> **WARNING**: deletes ALL assets in the target environment. Dev/test only.

## Steps (11)

| # | Action | Expectation |
|---|---|---|
| 0 | delete `apple@schmall.io` if present | clean slate |
| 1 | `delete_all_assets` | asset count = 0 |
| 2 | `add_user apple@schmall.io` (USER role) | user created |
| 3 | add 10-day HIGH vuln to `test-asset-e2e-workflow` | asset + vuln created |
| 4 | query as test user | no overdue |
| 5 | add 40-day CRITICAL vuln | overdue (CRITICAL threshold = 30d) |
| 6 | query as test user | 1 overdue |
| 7 | `create_exception_request` | PENDING |
| 8 | `get_my_exception_requests` | sees own pending |
| 9 | admin `approve_exception_request` | APPROVED |
| 10 | re-query as user | sees APPROVED |
| 11 | cleanup user + assets | clean again |

## Prereqs

Backend reachable; `curl http://localhost:8080/health` returns `UP`.

Admin MCP API key with **delegation enabled** and permissions:
`REQUIREMENTS_READ`, `REQUIREMENTS_WRITE`, `ASSETS_READ`, `VULNERABILITIES_READ`, `SCANS_READ`, `USER_ACTIVITY`.

Create via UI (**Settings > MCP Integration > API Keys**) or API:
```bash
TOKEN=$(curl -s -XPOST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"adminuser","password":"…"}' | jq -r .token)

curl -X POST http://localhost:8080/api/mcp/admin/api-keys \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name":"e2e-test-key",
    "permissions":["REQUIREMENTS_READ","REQUIREMENTS_WRITE","ASSETS_READ","VULNERABILITIES_READ","SCANS_READ","USER_ACTIVITY"],
    "delegationEnabled":true,
    "allowedDelegationDomains":"@schmall.io"
  }'
```

Required tools: `curl`, `jq` (`brew install jq` / `apt-get install jq`).

## Run

```bash
SECMAN_MCP_KEY=sk-… ./scriptpp/test/test-e2e-exception-workflowsupport.sh
SECMAN_MCP_KEY=sk-… BASE_URL=http://localhost:8080 ./scriptpp/test/test-e2e-exception-workflowsupport.sh
SECMAN_MCP_KEY=sk-… ./scriptpp/test/test-e2e-exception-workflowsupport.sh --verbose
```

| Env | Required | Default |
|---|---|---|
| `SECMAN_MCP_KEY` | yes | — |
| `BASE_URL` | no | `http://localhost:8080` |
| `VERBOSE` | no | `false` |

## Test data

| | |
|---|---|
| Test user | `apple@schmall.io` / `TestPassword123!` (USER role) |
| Test asset | `test-asset-e2e-workflow` |
| Non-overdue vuln | `CVE-2024-0001` HIGH 10d |
| Overdue vuln | `CVE-2024-0002` CRITICAL 40d (threshold 30d) |
| Reason text | `Testing exception workflow - E2E test suite` |

## MCP tools used

`delete_all_assets` (ADMIN), `add_user` (ADMIN), `add_vulnerability` (ADMIN/VULN), `get_overdue_assets` (USER), `create_exception_request` (USER), `get_my_exception_requests` (USER), `get_pending_exception_requests` (ADMIN), `approve_exception_request` (ADMIN), `delete_user` (ADMIN).

## Troubleshooting

| Error | Fix |
|---|---|
| `SECMAN_MCP_KEY environment variable is required` | export it before invoking |
| `Cannot connect to backend` | `curl http://localhost:8080/health`; set `BASE_URL` if remote |
| `DELEGATION_REQUIRED` / `User Delegation must be enabled` | recreate key with `delegationEnabled: true` |
| `ADMIN_REQUIRED` / `ROLE_REQUIRED` | API key (or delegated user) needs ADMIN |
| Rate limit | `mcp.rate-limiting.default-requests-per-hour: 10000` in `application.yml` |
| `jq: command not found` | install (`brew install jq` / `apt-get install jq`) |
| Hangs/timeouts | rerun with `--verbose`; check backend logs |

## CI

```yaml
- name: Run E2E exception workflow
  env:
    SECMAN_MCP_KEY: ${{ secrets.MCP_ADMIN_API_KEY }}
    BASE_URL: http://localhost:8080
  run: ./scriptpp/test/test-e2e-exception-workflowsupport.sh
```
Backend must be running before this step.
