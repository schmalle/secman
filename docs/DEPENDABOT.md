# GitHub Dependabot Alerts

secman can ingest [GitHub Dependabot](https://docs.github.com/en/code-security/dependabot) alerts and surface them read-only in the Vulnerability Management UI. Alerts are pulled from the GitHub REST API by the CLI and pushed into secman through an ingestion endpoint — secman itself never calls GitHub.

## Architecture

```
GitHub REST API ──(CLI: query dependabot-alerts)──▶ POST /api/dependabot-alerts/import ──▶ dependabot_alert table
                                                                                                │
                                              GET /api/dependabot-alerts ◀── Vulnerability Management → Dependabot alerts (UI)
```

- **CLI** (`query dependabot-alerts`) queries `GET /orgs/{org}/dependabot/alerts` or `GET /repos/{owner}/{name}/dependabot/alerts`, paginates, maps each alert, and POSTs the batch.
- **Backend** upserts on the natural key `(repository, alertNumber)` — re-imports update state (open → fixed/dismissed), patched version, etc. in place. The table is created by Hibernate `hbm2ddl.auto=update`; no Flyway migration.
- **UI** lists alerts with severity ranking, search (package/repo/CVE/GHSA/summary), and a state filter.

## CLI usage

See `docs/CLI.md` → `query dependabot-alerts` for the full option table.

```bash
# Print (dry) — no secman writes:
GITHUB_TOKEN=ghp_xxx ./scripts/secman query dependabot-alerts --org my-org

# Ingest open alerts org-wide:
GITHUB_TOKEN=ghp_xxx ./scripts/secman query dependabot-alerts --org my-org --save

# Single repo, critical, store:
./scripts/secman query dependabot-alerts --repo owner/name --severity critical --save --token ghp_xxx
```

Exactly one of `--org` / `--repo owner/name` is required. The GitHub token comes from `--token` or `GITHUB_TOKEN` and needs the **`security_events`** read scope (a classic PAT needs `repo`). Backend writes (`--save`) authenticate with `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` (ADMIN or VULN).

## REST API

| Endpoint | Method | Roles | Purpose |
|---|---|---|---|
| `/api/dependabot-alerts/import` | POST | ADMIN, VULN | Ingest a batch; upsert by `(repository, alertNumber)` |
| `/api/dependabot-alerts` | GET | ADMIN, VULN, SECCHAMPION | List all alerts for the UI |

`POST /import` body is a JSON array of alert objects (see field mapping). The response summarizes the run:

```json
{ "received": 42, "created": 12, "updated": 30, "skipped": 0, "errors": [] }
```

## Field mapping (GitHub → secman)

| secman field | GitHub source |
|---|---|
| `repository` | `repository.full_name` (org query) / `--repo` (repo query) |
| `alertNumber` | `number` |
| `state` | `state` |
| `packageName` | `dependency.package.name` |
| `ecosystem` | `dependency.package.ecosystem` |
| `manifestPath` | `dependency.manifest_path` |
| `severity` | `security_advisory.severity` (fallback `security_vulnerability.severity`) |
| `ghsaId` | `security_advisory.ghsa_id` |
| `cveId` | `security_advisory.cve_id` |
| `summary` | `security_advisory.summary` |
| `vulnerableVersionRange` | `security_vulnerability.vulnerable_version_range` |
| `firstPatchedVersion` | `security_vulnerability.first_patched_version.identifier` |
| `htmlUrl` | `html_url` |
| `alertCreatedAt` / `alertUpdatedAt` / `dismissedAt` / `fixedAt` | `created_at` / `updated_at` / `dismissed_at` / `fixed_at` (ISO-8601, parsed leniently) |
| `importedAt` | set by the backend on each upsert |

## UI

**Vulnerability Management → Dependabot alerts** (`/dependabot-alerts`), visible to ADMIN, VULN, and SECCHAMPION. Columns: severity, repository, package (+ manifest path), ecosystem, advisory (CVE/GHSA link + summary), vulnerable range, first patched version, state, last updated. The view filters by state (defaults to `open`) and free-text search, and sorts by severity.

## Scheduling

The CLI is cron-safe — run it under the Proton Pass or AWS Secrets Manager wrappers like the other importers (see `docs/CLI.md` → Cron). Re-running is idempotent thanks to the `(repository, alertNumber)` upsert.

## Files

- Entity: `src/backendng/.../domain/DependabotAlert.kt`
- Repository: `src/backendng/.../repository/DependabotAlertRepository.kt`
- Service: `src/backendng/.../service/DependabotAlertService.kt`
- Controller: `src/backendng/.../controller/DependabotAlertController.kt`
- CLI command: `src/cli/.../commands/DependabotAlertsCommand.kt` (registered in `SecmanCli.kt`)
- UI: `src/frontend/src/pages/dependabot-alerts.astro`, `components/DependabotAlerts.tsx`, `services/dependabotAlertsService.ts`, nav in `components/Sidebar.tsx`
