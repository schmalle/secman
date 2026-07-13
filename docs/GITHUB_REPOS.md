# GitHub Repository Vulnerability Management

secman can inventory all GitHub repositories accessible via a **GitHub App**, track each repo's open **high/critical Dependabot alerts** over time, and **alert repo owners whose counts have not decreased in 30 days** — with per-repo exceptions.

The GitHub calls happen **server-side** using GitHub App credentials stored (encrypted) in secman — no personal access token management. The CLI and MCP tools only trigger backend operations.

## Architecture

```
Admin → GitHub App (UI)  ──▶ github_app_config (private key encrypted at rest)
                                    │
CLI import-github-repos ─┐          ▼
MCP import_github_repos ─┼─▶ POST /api/github/import ──▶ GitHub API (App JWT → installation token)
UI  "Import now"        ─┘          │                       ├─ GET /installation/repositories
                                    │                       └─ GET /repos/{o}/{r}/dependabot/alerts?state=open
                                    ▼
                    github_repository (counts, lastImportAt, lastHighCriticalFindingAt)
                    github_repo_finding_snapshot (one row per repo per import run)
                                    │
CLI alert-github-repo-owners ─┬─▶ POST /api/cli/github-repo-alerts/send
MCP send_github_repo_alerts ──┘     │  (pure DB — compares current counts vs the snapshot ≥30 days old,
                                    │   skips repos with an active github_repo_alert_exception)
                                    ▼
                    one consolidated email per repo ownerEmail
```

## GitHub App setup

1. Create a GitHub App (org **Settings → Developer settings → GitHub Apps**) with **Repository permissions**:
   - **Metadata: Read-only** (list repositories)
   - **Dependabot alerts: Read-only** (count open alerts)
2. Install the App on your organization (all repos or a selection — secman sees exactly what the App sees).
3. Note the **App ID** and generate/download a **private key** (`.pem`, PKCS#1 `BEGIN RSA PRIVATE KEY`; PKCS#8 also accepted).
4. In secman: **Admin → GitHub App** → *Add Configuration* → enter App ID + private key. `Installation ID` and `Organization` are optional — with a single installation both may stay empty; with multiple installations set one of them. Use **Test connection** to validate.

Only one configuration can be active at a time. The private key is encrypted at rest (`EncryptedStringConverter`) and every API response masks it as `***HIDDEN***`.

**GitHub Enterprise Server (corporate tenants)**: set `API Base URL` to your GHES REST API root, e.g. `https://ghe.corp.example.com/api/v3` (note the `/api/v3` suffix — GHES doesn't serve the API at the bare hostname the way github.com does). Leave it empty for public github.com. Every GitHub API call the App client makes — import, alerts, and owner-email discovery — uses this URL.

**Client ID / Client secret are not needed.** The App's settings page also shows a Client ID and a "Client secrets" section — those are a separate credential pair for the *user-to-server* OAuth flow (signing users in through the App; see [GitHub's docs](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/identifying-and-authorizing-users-for-github-apps)) and are unrelated to this integration, which only [authenticates as the App itself](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-as-a-github-app) (App ID + private key → signed JWT → installation access token). The client secret is a plain opaque bearer string, not a cryptographic key — do not paste it into the private key field.

## Data model (V238, V239, V241)

| Table | Purpose | Key fields |
|---|---|---|
| `github_app_config` | App credentials | `app_id`, `private_key_pem` (encrypted), `installation_id?`, `organization?`, `api_base_url?` (V241 — GHES REST root, e.g. `https://ghe.corp.example.com/api/v3`; blank/null = public github.com), `is_active` |
| `github_repository` | Repo inventory | `github_repo_id` (unique, rename-safe upsert key), `full_name` (unique), `owner`, `owner_email?`, `critical_count`, `high_count`, `last_import_at`, `last_high_critical_finding_at`, `archived` |
| `github_repo_finding_snapshot` | Per-import count history | FK → repo (cascade), `snapshot_at`, `critical_count`, `high_count` |
| `github_repo_alert_exception` | Alerting exceptions | FK → repo (cascade), `reason`, `expiration_date?` (null = permanent), `created_by`, `created_at` |
| `github_repo_dependabot_alert` (V239) | Current open per-alert detail | FK → repo (cascade), `alert_number`, `package_name`, `ecosystem`, `manifest_path?`, `severity`, `ghsa_id?`, `cve_id?`, `summary?`, `vulnerable_version_range?`, `first_patched_version?`, `html_url?`, `alert_created_at?`, `alert_updated_at?` |
| `github_owner_email_mapping` (V240) | Default owner → email | `owner` (unique), `email`, `created_by`, `created_at`, `updated_at` |

- `last_import_at` — stamped on every import.
- `last_high_critical_finding_at` — stamped on an import **iff** the repo had ≥1 open high/critical alert at that moment.
- One snapshot row per repo per import run is the history that makes the 30-day comparison possible.
- `github_repo_dependabot_alert` is **current-state only**: on every import, a repo's rows are deleted and reinserted from the freshly fetched `state=open` alert list (same delete-by-asset + reinsert pattern as the CrowdStrike vulnerability import — no history table, no retention job). V239 also drops the standalone `dependabot_alert` table it supersedes.

## The 30-day non-decrease rule

For each repository, the alert run:

1. Skips the repo when it has an **active exception** (reported in `reposExcepted`; expired exceptions are ignored but kept for audit).
2. Skips silently when the current `criticalCount + highCount` is **0**.
3. Picks the **baseline**: the newest snapshot with `snapshot_at ≤ now − thresholdDays` (default 30). No such snapshot → the repo is **skipped and reported** in `reposSkippedInsufficientHistory` (prevents false alerts during the first month — keep importing daily and it will age in).
4. **Alerts** when `current ≥ baseline` (not decreased), grouped into **one email per `ownerEmail`** listing all of that owner's non-decreasing repos with current-vs-baseline counts.
5. Repos that qualify but have **no `ownerEmail`** are reported in `unmappedRepos` — set the email in the UI (or via `PUT /api/github/repositories/{id}/owner-email`), or map the owner once (see below) so every repo under it gets covered.

The alert run reads only the secman DB — no GitHub round-trip — so it is fast and works even when GitHub is unreachable. Run the **import regularly** (e.g. daily cron) to build accurate history.

## Owner email mappings (bulk `ownerEmail` coverage)

`github_repository.owner` (the GitHub org/user login, auto-populated on import) is unrelated to `ownerEmail` (the alert recipient, purely manual) — mapping one to the other by hand, repo by repo, doesn't scale when many repos share the same owner. `github_owner_email_mapping` closes that gap: map an **owner login → default email once**, and every repo under that owner picks it up.

- **Auto-fill on import**: `GithubRepoImportService.persistRepo()` fills a repo's `ownerEmail` from this table **only when it is currently blank** — a manually-set (via the per-repo field) or previously auto-filled value is never overwritten. GitHub itself is never queried for emails — its API doesn't reliably expose them (orgs have none; user emails are usually private) — this is a secman-only mapping.
- **Immediate backfill**: creating or updating a mapping also backfills every *existing* repo under that owner whose `ownerEmail` is currently blank, in the same request — you don't have to wait for the next import.
- Deleting a mapping does **not** un-set any `ownerEmail` it already filled.
- Managed in the UI under **Vulnerability Management → GitHub → Owner email mappings** tab (ADMIN/VULN create/edit/delete; ADMIN/VULN/SECCHAMPION view), or in bulk via CSV upload (`owner,email` columns) — same tab, or `POST /api/github/owner-email-mappings/upload-csv`.

### Auto-discovery (best-effort, opt-in)

The mapping table above is otherwise entirely manual. `GithubOwnerEmailDiscoveryService` adds an **explicitly-triggered** complement: for every already-imported repo with **no `ownerEmail` set**, it calls `GET /users/{owner}` — the same endpoint for both org and user accounts — and reads the account's **public email**, if any. Most GitHub accounts don't expose one (orgs have none by default; user emails are usually private), so this is genuinely best-effort, not a replacement for the manual/CSV workflow.

- **Scope**: only repos already imported into secman (never calls GitHub's repo-listing API), grouped by owner, and only owners that don't already have a mapping.
- **On a hit**: creates a `github_owner_email_mapping` row exactly as the manual/CSV path does — same validation, same immediate backfill of matching repos.
- **On a miss** (404, or an account with no public email): the owner is reported, not retried automatically — set it manually or via CSV if you have another source for that address.
- **Dry-run**: preview discovered owner → email pairs without writing anything.
- **Import is unaffected**: `import-github-repos` still never calls GitHub for emails — discovery is a separate, explicit action, run via CLI (`manage-github-owner-mappings discover`) or MCP (`discover_github_owner_email_mappings`).
- Works against GitHub Enterprise Server tenants via the App config's `API Base URL` (see GitHub App setup, above).

## UI

- **Vulnerability Management → GitHub** (`/github-repos`), visible to ADMIN, VULN, SECCHAMPION, with two tabs:
  - **Repositories**: repository (link), owner, **critical**, **high**, last import, last high/critical finding, owner email (inline edit — ADMIN/VULN), exception badge. Toolbar: **Import now** (ADMIN/VULN). Per-row: create/remove an alert exception (reason + optional expiry) — ADMIN/VULN; expand (chevron) to show that repo's open Dependabot alerts — severity, package/ecosystem, CVE/GHSA advisory link, vulnerable range, patched version, last updated.
  - **Owner email mappings**: owner, default email (inline edit — ADMIN/VULN), repo count, created by, updated. Add-row form + CSV upload (ADMIN/VULN).
- **Admin → GitHub App** (`/admin/github-config`), ADMIN only: credential CRUD, activate, test connection.

## REST API

| Endpoint | Method | Roles | Purpose |
|---|---|---|---|
| `/api/github-config[/{id}]`, `/active`, `/{id}/activate`, `/{id}/test` | CRUD/POST | ADMIN | GitHub App credentials (key always masked) |
| `/api/github/repositories` | GET | ADMIN, VULN, SECCHAMPION | Repo list incl. counts + active exception |
| `/api/github/repositories/{id}/alerts` | GET | ADMIN, VULN, SECCHAMPION | A repository's current open Dependabot alerts |
| `/api/github/repositories/{id}/owner-email` | PUT | ADMIN, VULN | Set/clear the alert recipient (`{"ownerEmail": "x@y.z"}`, blank/null clears) |
| `/api/github/import` | POST | ADMIN, VULN | Run the GitHub App import |
| `/api/github/repo-alert-exceptions[/{id}]` | GET / POST / DELETE | read: +SECCHAMPION; write: ADMIN, VULN | Alerting exceptions |
| `/api/github/owner-email-mappings[/{id}]` | GET / POST / PUT / DELETE | read: +SECCHAMPION; write: ADMIN, VULN | Owner → default email mappings; create/update backfills blank repo `ownerEmail`s for that owner |
| `/api/github/owner-email-mappings/upload-csv` | POST (multipart) | ADMIN, VULN | Bulk-create mappings from a CSV of `owner,email` rows |
| `/api/github/owner-email-mappings/discover` | POST | ADMIN, VULN | Auto-discover owner emails via the GitHub API (`{"dryRun": bool}`) |
| `/api/cli/github-repo-alerts/send` | POST | ADMIN | Run the 30-day alert (`{"dryRun": bool, "thresholdDays": int, "force": bool, "onlyEmail": string?}`) |

Import response: `{reposDiscovered, reposNew, reposUpdated, totalCritical, totalHigh, reposWithAlertsDisabled[], errors[], importedAt}`. Repos with Dependabot alerts disabled/inaccessible are recorded with 0/0 and listed — they do not fail the run.

Alert response: `{status: SUCCESS|DRY_RUN|PARTIAL_FAILURE|FAILURE, thresholdDays, reposEvaluated, reposAlerted, reposExcepted[], reposSkippedInsufficientHistory[], unmappedRepos[], emailsSent, emailsFailed, recipients[], failedRecipients[], alertedRepos[]}`.

Discovery response: `{status: SUCCESS|DRY_RUN|PARTIAL_FAILURE|FAILURE, ownersEvaluated, ownersDiscovered, discoveredMappings: [{owner, email, repoCount}], ownersSkippedNoPublicEmail[], errors[]}`.

## CLI

See `docs/CLI.md` for the full option tables.

```bash
# Import repos + alert counts (backend does the GitHub calls):
./scripts/secman import-github-repos

# Preview who would be alerted (nothing sent):
./scripts/secman alert-github-repo-owners --dry-run

# Send the 30-day alerts:
./scripts/secman alert-github-repo-owners

# Custom window:
./scripts/secman alert-github-repo-owners --days 60

# Force-alert every eligible owner, regardless of trend (also covers repos with no baseline yet):
./scripts/secman alert-github-repo-owners --force

# Only notify one owner (e.g. to test the email without spamming everyone):
./scripts/secman alert-github-repo-owners --only-email owner@example.com

# Manage owner -> default email mappings:
./scripts/secman manage-github-owner-mappings add --owner acme-corp --email security@acme-corp.example.com
./scripts/secman manage-github-owner-mappings list
./scripts/secman manage-github-owner-mappings remove --owner acme-corp
./scripts/secman manage-github-owner-mappings import --file mappings.csv

# Auto-discover owner emails via the GitHub API (preview, then run):
./scripts/secman manage-github-owner-mappings discover --dry-run
./scripts/secman manage-github-owner-mappings discover
```

Both authenticate with `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` against `SECMAN_HOST` (ADMIN required; import also accepts VULN via the REST endpoint). Exit codes: 0 success, 1 failure/partial failure, 2 usage error.

## MCP tools

See `docs/MCP.md`. All require **User Delegation** (`X-MCP-User-Email`).

- `import_github_repos` — no arguments. Delegated user must be ADMIN or VULN; API key needs `VULNERABILITIES_READ`. Errors: `NO_GITHUB_CONFIG` when no active configuration exists.
- `send_github_repo_alerts` — `{dryRun?: boolean, thresholdDays?: number, force?: boolean, onlyEmail?: string}` (defaults false / 30 / false / all owners). Delegated user must be ADMIN; API key needs `NOTIFICATIONS_SEND`.
- `list_github_owner_email_mappings` — no arguments. Delegated user must be ADMIN, VULN, or SECCHAMPION; API key needs `VULNERABILITIES_READ`.
- `create_github_owner_email_mapping` — `{owner: string, email: string}`. Delegated user must be ADMIN or VULN; API key needs `VULNERABILITIES_READ`. Backfills blank repo `ownerEmail`s for that owner.
- `delete_github_owner_email_mapping` — `{id: number}`. Delegated user must be ADMIN or VULN; API key needs `VULNERABILITIES_READ`.
- `discover_github_owner_email_mappings` — `{dryRun?: boolean}` (default false). Delegated user must be ADMIN or VULN; API key needs `VULNERABILITIES_READ`. Errors: `NO_GITHUB_CONFIG` when no active configuration exists.

## Email

Template: `src/backendng/src/main/resources/email-templates/github-repo-alert.{html,txt}` — one email per owner, a table of their non-decreasing repos with current vs baseline counts and the baseline date.

## Scheduling

Run the import daily (cron-safe, idempotent upsert) and the alert e.g. weekly:

```cron
15 6 * * *  pass-cli run --env-file secman.env -- /path/to/scripts/secman import-github-repos
30 7 * * 1  pass-cli run --env-file secman.env -- /path/to/scripts/secman alert-github-repo-owners
```

Future work: an in-backend scheduler (following `scheduler/AwsAccountRiskAssessmentReminderScheduler.kt`) could replace the cron entries.

## Files

- Entities: `src/backendng/.../domain/GithubAppConfig.kt` (`apiBaseUrl`, `effectiveApiBaseUrl()`), `GithubRepository.kt`, `GithubRepoFindingSnapshot.kt`, `GithubRepoAlertException.kt`, `GithubRepoDependabotAlert.kt`, `GithubOwnerEmailMapping.kt` (+ Flyway `V238__github_repos.sql`, `V239__github_repo_dependabot_alerts.sql`, `V240__github_owner_email_mapping.sql`, `V241__github_app_config_api_base_url.sql`)
- Services: `GithubAppClientService.kt` (App JWT via `util/PemUtils.kt`, installation token, repo/alert queries, `fetchPublicEmail`, per-config `apiBaseUrl`), `GithubRepoImportService.kt`, `GithubRepoAlertService.kt`, `GithubOwnerEmailMappingService.kt`, `GithubOwnerEmailDiscoveryService.kt`, `CSVGithubOwnerEmailMappingParser.kt`
- Controllers: `GithubConfigController.kt`, `GithubRepositoryController.kt` (also owns `/owner-email-mappings`, incl. `/discover`), `CliController.kt` (`/github-repo-alerts/send`)
- MCP: `mcp/tools/ImportGithubReposTool.kt`, `SendGithubRepoAlertsTool.kt`, `ListGithubOwnerEmailMappingsTool.kt`, `CreateGithubOwnerEmailMappingTool.kt`, `DeleteGithubOwnerEmailMappingTool.kt`, `DiscoverGithubOwnerEmailMappingsTool.kt` (registered in `McpToolRegistry.kt`)
- CLI: `src/cli/.../commands/ImportGithubReposCommand.kt`, `AlertGithubRepoOwnersCommand.kt`, `ManageGithubOwnerMappingsCommand.kt` + `AddGithubOwnerMappingCommand.kt`, `ListGithubOwnerMappingsCommand.kt`, `RemoveGithubOwnerMappingCommand.kt`, `ImportGithubOwnerMappingsCommand.kt`, `DiscoverGithubOwnerMappingsCommand.kt`
- UI: `pages/github-repos.astro` + `components/GithubRepoTabs.tsx` (tab shell), `GithubRepoManagement.tsx`, `GithubOwnerEmailMappings.tsx`, `pages/admin/github-config.astro` + `components/GithubConfigManagement.tsx`, `services/githubReposService.ts`
- E2E: Phase 8d in `scripts/test/test-e2e-vuln-exception-full.sh`
