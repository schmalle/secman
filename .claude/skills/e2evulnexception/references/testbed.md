# Testbed details

Driver script: `scripts/test/test-e2e-vuln-exception-full.sh`.

## Users (USER + VULN + REQ — no ADMIN/SECCHAMPION so requests stay PENDING)
- `e2etestuser1` (`e2etestuser1@e2e.test`)
- `e2etestuser2` (`e2etestuser2@e2e.test`)

## Vulnerability-test assets (owner is a plain string username)
- `testasset1` — owner `e2etestuser1`, ip `10.99.0.1`
- `testasset2` — owner `e2etestuser2`, ip `10.99.0.2`

## Vulnerabilities
- `vuln1` — `CVE-E2E-0001` CRITICAL, `daysOpen=40` on `testasset1` (overdue, threshold = 30d)
- `vuln2` — `CVE-E2E-0002` CRITICAL, `daysOpen=5` on **both** `testasset1` and `testasset2`

## AWS account sharing testbed (Phase 8)
- AWS account `123456789012` (A) — mapped to `e2etestuser1`; later **shared (scoped) → user2**
- AWS account `876543210987` (B) — mapped to `e2etestuser2` (their own; never shared)
- AWS account `555555555555` (C) — mapped to `e2etestuser1` **after** the sharing rule
  is created. Tests prove the rule's per-account scope (`selectedAwsAccountIds=[A]`)
  prevents the new account from leaking to user2.
- Assets `testaws-a` / `testaws-b` / `testaws-c` carry those `cloudAccountId` values and
  use a non-user owner string (`awssharing-owner`) so the only access path is via
  `UserMapping` or `AwsAccountSharing` — not the owner rule.

## Phases inside the driver

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
for Phase 9, passing the captured IDs/credentials through env vars.
