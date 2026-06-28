# CLI Reference

Wrappers (canonical):

- `./scripts/secman <cmd>` — symlink to `secmancli`, resolves secrets via `pass-cli`.
- `./scripts/secmanng <cmd>` — alternative wrapper with explicit env exports (e.g. `SECMAN_INSECURE=1` for self-signed TLS).

Legacy `java -jar secman-cli.jar` invocation is shown in some examples for clarity. Prefer the wrappers in real use. See `docs/PASS_CLI.md` for the secret resolution map.

## Build

```bash
./gradlew :cli:shadowJar
ls src/cli/build/libs/cli-0.1.0-all.jar
# Deploy:
scp src/cli/build/libs/cli-0.1.0-all.jar user@server:/opt/secman/bin/secman-cli.jar
```

## Configuration

Resolution order: system properties → env vars → `~/.secman/*.{conf,yaml}` → defaults.

CrowdStrike API:
```bash
export FALCON_CLIENT_ID=…       # alias of CROWDSTRIKE_CLIENT_ID
export FALCON_CLIENT_SECRET=…
export FALCON_BASE_URL=https://api.crowdstrike.com   # or api.us-2/eu-1/laggar.gcw...
export FALCON_CLOUD_REGION=us-1
```

Backend (for `--save` operations):
```bash
export SECMAN_ADMIN_NAME=…
export SECMAN_ADMIN_PASS=…
export SECMAN_BACKEND_URL=https://api.example.com   # default http://localhost:8080
```

`~/.secman/credentials.conf` mirrors the env vars (no spaces around `=`); `~/.secman/crowdstrike.yaml` carries `clientId/clientSecret/baseUrl`. `chmod 600` both.

### TLS / self-signed certificates

If the backend uses a self-signed or otherwise untrusted certificate, the CLI will fail with `PKIX path building failed: unable to find valid certification path to requested target`. Two ways to bypass certificate verification (use only on trusted networks):

- Add `--insecure` to the command (e.g. `secman delete-asset-not-seen 30 --dry-run --insecure`).
- Export `SECMAN_INSECURE=true` (also accepts `1`, `yes`, `on`) before invoking. The wrapper scripts in `scripts/` already honour this — `deleteoutdated.sh` resolves the value via `pass-cli`.

Both routes set the Micronaut HTTP client property `micronaut.http.client.ssl.insecure-trust-all-certificates=true` for the entire CLI process. The flag is parsed before the application context starts, so it applies to every subcommand (including ones that use the injected `CliHttpClient`).

Full env reference: `docs/ENVIRONMENT.md`.

## Commands

### `query servers` — CrowdStrike vulnerability query

```bash
./scripts/secman query servers --hostname web-01 --severity HIGH,CRITICAL --min-days-open 30 --save
./scripts/secmanng query servers --severity CRITICAL,HIGH --save --device-type SERVER \
  --last-seen-days 1 --min-days-open 1 --insecure --verbose
```

| Option | Default | Notes |
|---|---|---|
| `--hostname` | required (unless filtering by other criteria) | exact host |
| `--severity` | all | `CRITICAL,HIGH,MEDIUM,LOW` |
| `--device-type` | all | `SERVER`, `WORKSTATION`, … |
| `--min-days-open` | 0 | |
| `--last-seen-days` | — | recent-checkin window |
| `--limit` | 100 | |
| `--save` | false | POST to backend |
| `--username` / `--password` | — | required with `--save` |
| `--backend-url` | `http://localhost:8080` | |
| `--output-file` / `--format` | — / `json` | `json|csv` |
| `--verbose` / `--insecure` | false | `--insecure` allows self-signed TLS |

### `installed-products` — CrowdStrike Discover software inventory

Imports installed product/application rows from CrowdStrike Discover into SecMan for assets that already exist in the backend. Use this after `query servers --save` or another asset import has populated the systems table; the command does **not** create missing assets. Unknown hosts are counted as skipped `unknown systems` so operators can identify inventory gaps.

```bash
# Preview Discover coverage without authenticating to SecMan or writing backend data
./scripts/secman installed-products --device-type SERVER --dry-run

# Import software inventory for known servers
./scripts/secman installed-products --device-type SERVER --backend-url https://secman.example.com

# Include servers and workstations, with smaller CrowdStrike pages for constrained environments
./scripts/secman installed-products --device-type ALL --limit 500 --verbose
```

| Option | Default | Notes |
|---|---|---|
| `--device-type` | `SERVER` | CrowdStrike Discover host filter: `SERVER`, `WORKSTATION`, or `ALL`. `ALL` queries servers and workstations separately. |
| `--dry-run` | false | Queries CrowdStrike and prints per-batch/summary counts only; does not authenticate to SecMan and does not write backend data. |
| `--limit` | 1000 | CrowdStrike page size; values are coerced to the API-safe range `1..1000`. This is not a total-row cap. |
| `--backend-url` | `SECMAN_BACKEND_URL`, then `SECMAN_HOST`, then `http://localhost:8080` | Backend API URL for import mode. `SECMAN_HOST` may be a bare hostname; the CLI prefixes `https://`. |
| `--client-id` / `--client-secret` | config/env | CrowdStrike API credentials; both must be provided to override `FALCON_CLIENT_ID` / `FALCON_CLIENT_SECRET` or `~/.secman` config. |
| `--verbose` | false | Prints backend import errors, capped by the backend response. |

Import mode requires `SECMAN_ADMIN_NAME` and `SECMAN_ADMIN_PASS`; the authenticated user must have backend access to `POST /api/installed-products/import` (`ADMIN` or `VULN`). The UI/API listing endpoint `GET /api/installed-products` is available to `ADMIN`, `VULN`, and `SECCHAMPION`, and non-admin listings are filtered to assets the user can access.

Backend import semantics:

- Each CLI batch is posted to `/api/installed-products/import`, which accepts at most 5,000 product rows per request.
- **Clean-state replace:** the import is a per-server snapshot, not a merge. The first time a server appears in a run, its previously imported products are deleted so the result reflects exactly what CrowdStrike currently reports (products uninstalled since the last run are removed). Servers not present in the import are untouched. The summary reports `Products deleted (stale removed)`.
  - The CLI sends a single `importRunId` (UUID) on every batch of one run. The backend only deletes a server's rows that are **not** stamped with the current run id, so a server whose products span multiple batches is replaced once and later batches never wipe rows an earlier batch in the same run inserted.
- Rows are matched to an existing asset by case-insensitive hostname; if the CrowdStrike hostname is fully qualified, the short name before the first dot is tried as a fallback.
- Within a run, products are upserted by `(externalId, asset)` when CrowdStrike provides an external ID, otherwise by logical duplicate `(asset, name, vendor, version)`.
- Product names are required. Blank names, unknown systems, and external IDs already assigned to a different asset are skipped and reflected in the summary.
- Imported fields include CrowdStrike AID, product name, vendor, version, category, installation path, installed timestamp, last-used timestamp, last-updated timestamp, and SecMan import timestamp.

### `delete-asset-not-seen` — CrowdStrike stale asset cleanup

Deletes assets that have not appeared in a CrowdStrike import for more than N days. Always run `--dry-run` first.

```bash
./scripts/secman delete-asset-not-seen 30 --dry-run --verbose
./scripts/secman delete-asset-not-seen 90
```

| Option | Default | Notes |
|---|---|---|
| `<days>` | required | positive integer; assets older than this cutoff are candidates |
| `--dry-run` | false | print candidates without deleting |
| `--backend-url` | `SECMAN_HOST`, `SECMAN_BACKEND_URL`, then `http://localhost:8080` | backend API URL |
| `--username` / `--password` | `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` | ADMIN role required |
| `--insecure` | false | accept self-signed TLS; equivalent to `SECMAN_INSECURE=true` |
| `--verbose` | false | print candidate asset details |

Eligibility is based on `asset.crowdstrike_last_imported_at`, which is updated whenever an asset appears in `query servers --save` / CrowdStrike vulnerability imports. Generic `lastSeen` is not used because it is also touched by other scan/import paths.

Existing assets start with `crowdstrike_last_imported_at = NULL` after the schema migration. Those assets are ignored by this command until a future CrowdStrike import sees them, or an admin performs an explicit one-time backfill. A conservative backfill is to set `crowdstrike_last_imported_at = last_seen` only for assets that are known to be CrowdStrike-managed, for example `owner = 'CrowdStrike Import'` or assets with CrowdStrike-imported vulnerability rows. Do not blanket-fill every asset unless the environment only contains CrowdStrike-managed inventory.

### `send-notifications` — outdated-asset & new-vuln emails

```bash
./scripts/secman send-notifications --dry-run --verbose
./scripts/secman send-notifications --outdated-only
```

| Option | Default | Notes |
|---|---|---|
| `--dry-run` | false | print plan only |
| `--verbose` | false | per-asset detail |
| `--outdated-only` | false | skip new-vuln notifications |

For each outdated asset (an asset with overdue vulnerabilities), the recipients are
the deduplicated, case-insensitive union of — keyed on the asset's AWS account
(`cloud_account_id`):

1. **AWS account owner(s)** — every `UserMapping` row whose `aws_account_id` matches
   the account (plus, for backward compatibility, the legacy `asset.owner` lookup).
2. **Workgroup members** — every member of any workgroup that contains an asset in
   the account (asset → workgroup → users).
3. **Sharing recipients** — every user granted access to the account via the AWS
   Account Sharing feature (directional, honoring per-rule account selection).

This mirrors the `send-notification-users` recipient fan-out. Each recipient receives
one consolidated email covering all outdated assets they can access; an asset is
notified at most once per day regardless of how many recipients it reaches. An asset
with no recipients in any category is skipped (logged with `--verbose`).

### `send-notification-users` — per-AWS-account vulnerability emails

Finds AWS accounts whose EC2 assets have vulnerabilities open longer than
`--days` (excluding active exceptions) and sends each recipient one consolidated
email summarizing their affected accounts.

In the **global** flow (no `--notification-user`, or an ADMIN `--notification-user`)
the recipients for each affected account are the union of:

1. **AWS account owner(s)** — every `UserMapping` row whose `aws_account_id` matches the account.
2. **Workgroup members** — every member of any workgroup that contains an EC2 asset in the account (asset → workgroup → users).
3. **Sharing recipients** — every user granted access to the account via the AWS Account Sharing feature (directional, honoring per-rule account selection).

Emails are deduplicated case-insensitively, so a user who qualifies through more
than one path is notified only once. An account with no recipients in any of the
three categories is reported under "Unmapped accounts". A non-ADMIN
`--notification-user` is scoped to only the accounts that user can access.

```bash
./scripts/secman send-notification-users --dry-run --verbose
./scripts/secman send-notification-users --days 60
./scripts/secman send-notification-users --notification-user user@example.com
```

| Option | Default | Notes |
|---|---|---|
| `--days <n>` | 30 | vulnerability age threshold in days |
| `--dry-run` | false | print planned recipients only |
| `--verbose` | false | per-recipient delivery status |
| `--notification-user <email>` | — | only notify this user (ADMIN ⇒ global, otherwise self-scoped) |
| `--username` / `--password` | env | `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` |
| `--backend-url` | env | `SECMAN_HOST` / `SECMAN_BACKEND_URL` |

### `send-patch-notifications` — missing-patch emails by email first character

Notifies users about missing patches (overdue vulnerabilities) in deterministic
alphabetical batches. The mandatory positional argument is the **first character of
the email address** (e.g. `a` → every user whose login email starts with `a`).
Reuses the user-vulnerability-notification pipeline: finds AWS accounts with
vulnerabilities open longer than `--days`, resolves the recipients for each account
(see `send-notification-users` below), then keeps only recipients matching the prefix
before sending one consolidated email each.
Requires `ADMIN`. Mirrored by MCP tool `send_patch_notifications`.

```bash
./scripts/secman send-patch-notifications a --dry-run
./scripts/secman send-patch-notifications m --days 60 --verbose
```

| Argument / Option | Default | Notes |
|---|---|---|
| `<emailPrefix>` | — | **required**; first character of the email to notify (e.g. `a`) |
| `--days <n>` | 30 | missing-patch (vulnerability) age threshold in days |
| `--dry-run` | false | print planned recipients only |
| `--verbose` | false | per-recipient delivery status |
| `--username` / `--password` | env | `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` |
| `--backend-url` | env | `SECMAN_HOST` / `SECMAN_BACKEND_URL` |

### `notify-new-accounts` — new AWS account mapping notifications

Notifies users who have been mapped to one or more new AWS accounts within a
configurable look-back window (default: **last 24 hours**). A "new account"
is a `UserMapping` row whose `aws_account_id` is set and whose `created_at`
timestamp falls inside the window (i.e. it was created via a recent import).
Users with at least one such mapping receive one consolidated email listing all
their newly-mapped account IDs.

The **notification body text is read from a local file** supplied via `--file`
(`-f`). This lets operators customise the message per-deployment (e.g. with
data-classification instructions, team contacts, or regulatory context) without
redeploying the application. The list of new account IDs is always appended
below the custom text.

Requires `ADMIN`. Backend endpoint: `POST /api/cli/new-account-notifications/send`.

```bash
# Notify users who received new AWS account mappings in the last 24 hours:
./scripts/secman notify-new-accounts --file /etc/secman/welcome-aws.txt

# Preview planned recipients (dry run):
./scripts/secman notify-new-accounts --file welcome-aws.txt --dry-run

# 48-hour window with per-recipient detail:
./scripts/secman notify-new-accounts --file welcome-aws.txt --hours 48 --verbose
```

| Option | Default | Notes |
|---|---|---|
| `-f` / `--file <path>` | — | **required**; path to text file used as the email body |
| `--hours <n>` | 24 | look-back window in hours |
| `--dry-run` | false | print planned recipients only, no emails sent |
| `--verbose` | false | per-recipient delivery status |
| `--username` / `--password` | env | `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` |
| `--backend-url` | env | `SECMAN_HOST` / `SECMAN_BACKEND_URL` |

**Exit codes:** `0` success/dry-run, `1` partial failure or error, `2` invalid arguments.

**Notification file format:** Plain text. Paragraphs are preserved. Example:

```
Dear SecMan user,

You have been granted access to one or more AWS accounts in our security
platform. Please log in to SecMan to review your assets and open
vulnerabilities.

If you have questions, contact your security team.
```

The rendered email appends the account list below this text (both in HTML and
plain-text parts).

### `manage-user-mappings`

Subcommands: `list`, `add-aws`, `add-domain`, `import`, `import-s3`, `download-s3`, `print-s3`, `remove`.

```bash
# list (default table; supports --format json|csv, --output FILE, --type AWS|DOMAIN|ALL)
./scripts/secman manage-user-mappings list --type AWS --format csv --output aws-mappings.csv
./scripts/secman manage-user-mappings list --send-email --dry-run     # preview ADMIN/REPORT recipients
./scripts/secman manage-user-mappings list --send-email --verbose     # dispatch with per-recipient status

# add
./scripts/secman manage-user-mappings add-aws    --email u@x --aws-account-id 123456789012
./scripts/secman manage-user-mappings add-domain --email u@x --domain CORP.X.COM

# import (CSV/JSON, --dry-run validates without persisting)
./scripts/secman manage-user-mappings import --file mappings.csv  --format csv  --dry-run
./scripts/secman manage-user-mappings import --file mappings.json --format json

# remove
./scripts/secman manage-user-mappings remove --id 42
```

#### `list --send-email` (Feature 085)

Recipients: every `ADMIN`+`REPORT` user with a non-empty email (matches `send-admin-summary`). Always writes a row to `user_mapping_statistics_log`. Exit codes:

| Code | Meaning |
|---|---|
| 0 | OK / dry-run / `list` without `--send-email` |
| 1 | generic error, or `--dry-run` used without `--send-email` |
| 2 | not authorized (invoker not ADMIN) |
| 3 | no eligible recipients |
| 4 | partial failure (≥1 sent, ≥1 failed) |
| 5 | full failure (0 sent, ≥1 attempted) |

#### Import file formats

CSV (header required, case-insensitive):
```csv
email,awsAccountId,domain
user1@example.com,123456789012,
user2@example.com,,corp.example.com
```

JSON:
```json
[ {"email":"user1@example.com","awsAccountId":"123456789012"},
  {"email":"user2@example.com","domain":"corp.example.com"} ]
```

Validation: email `user@domain.tld` 3–255 chars; AWS account exactly 12 digits; domain alphanumeric `.`/`-`. At least one of `awsAccountId`/`domain` per entry. Output summarizes `Created (active|pending)`, `Skipped`, `Errors`.

#### S3 subcommands

All three `*-s3` commands share AWS options: `--aws-region`, `--aws-profile`, `--aws-access-key-id`, `--aws-secret-access-key`, `--aws-session-token`, `--endpoint-url` (also `AWS_ENDPOINT_URL`, used for S3Mock/MinIO/LocalStack). 10 MB hard size limit. Default credential chain: env → `~/.aws/credentials` → IAM role → SSO.

- **`import-s3`** — download AND POST to backend. Bucket/key from `--bucket`/`--key` or, when omitted, the `AWS_ACCOUNT_BUCKET_NAME` / `AWS_ACCOUNT_BUCKET_KEY_NAME` env vars (flags take priority). Needs `s3:GetObject` (+ `s3:HeadObject` for pre-download size check). Exit codes: `0` ok / `1` partial / `2` fatal S3/config / `3` unexpected. Detailed flags: `docs/S3_USER_MAPPING_IMPORT.md`.
- **`download-s3`** — download only, no backend contact. `--bucket -b`, `--key -k`, `--output -o` required; `--force -f` to overwrite; `--quiet -q` (success/error stays on stderr). Parent dir must exist; verbatim copy.
- **`print-s3`** — download + parse + print to stdout (temp file deleted). `--type AWS|DOMAIN|ALL` (default `AWS`); `--format TABLE|JSON|CSV`; `--file-format CSV|JSON|AUTO`; `--show-errors` to print parse errors to stderr; `--quiet` suppresses banner+summary (still on stderr). **stdout = mappings only**, safe to pipe through `diff`/`jq`/`awk`.

### `add-vulnerability`

Manual upsert. Auto-creates asset if hostname missing (`type=SERVER`, `owner=CLI-IMPORT`). Same `(asset, cve)` updates instead of duplicating.

```bash
./scripts/secman add-vulnerability --hostname web-01 --cve CVE-2024-1234 \
  --criticality HIGH --days-open 30 --username admin --password ***
```

| Option | Default | Notes |
|---|---|---|
| `--hostname`, `--cve`, `--criticality` (`CRITICAL|HIGH|MEDIUM|LOW`), `--username`, `--password` | required | |
| `--days-open` | 0 | |
| `--backend-url` | `http://localhost:8080` | |
| `--verbose` | false | |

Exit codes: `0` ok, `1` validation/auth error, `2` connection error.

### `manage-workgroups`

Subcommands: `list`, `assign-assets`, `remove-assets`.

```bash
./scripts/secman manage-workgroups list                          # list workgroups
./scripts/secman manage-workgroups list --workgroup Production   # assets in WG
./scripts/secman manage-workgroups list --search-assets "ip-10-*"

./scripts/secman manage-workgroups assign-assets --workgroup Production \
  --pattern "*prod*" --type SERVER --admin-user admin@x

./scripts/secman manage-workgroups assign-assets --workgroup Production --ids 1,2,3 \
  --admin-user admin@x

./scripts/secman manage-workgroups assign-assets --workgroup Production --pattern "*" --dry-run
./scripts/secman manage-workgroups remove-assets --workgroup Test --pattern "*test*" --admin-user admin@x
./scripts/secman manage-workgroups remove-assets --workgroup Test --all              --admin-user admin@x
./scripts/secman manage-workgroups list --format JSON
```

Wildcards: `*` (any), `?` (single char), `*foo*` (contains).

### `send-admin-summary` — system statistics email

Sends a summary email to all users with the `ADMIN` or `REPORT` role. The email includes total user, asset, and vulnerability counts, the top-10 most-affected servers, and the top-10 most-affected products. Statistics are fetched from `GET /api/cli/admin-summary/statistics` and the email is dispatched via `POST /api/cli/admin-summary/send`. Requires `ADMIN`.

```bash
./scripts/secman send-admin-summary --dry-run          # preview recipients, no emails sent
./scripts/secman send-admin-summary --verbose          # show per-recipient delivery status
```

| Option | Default | Notes |
|---|---|---|
| `--dry-run` | false | preview planned recipients and statistics without sending |
| `--verbose` / `-v` | false | show per-recipient SUCCESS / FAILED status |
| `--username` / `--password` | env | `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` |
| `--backend-url` | env | `SECMAN_HOST` / `SECMAN_BACKEND_URL`, then `http://localhost:8080` |

Exit codes: `0` success or dry-run, `1` partial or total failure.

### `crowdstrike-last-import` — last CrowdStrike import metadata

Displays the timestamp and counters of the most recent CrowdStrike vulnerability import. Reads `GET /api/crowdstrike/servers/import/latest`. Useful in cron health-checks and post-import audits.

```bash
./scripts/secman crowdstrike-last-import
./scripts/secman crowdstrike-last-import --format json
./scripts/secman crowdstrike-last-import --verbose
```

| Option | Default | Notes |
|---|---|---|
| `--format` | `text` | `text` (human-readable table) or `json` (raw API response) |
| `--insecure` | false | accept self-signed TLS; equivalent to `SECMAN_INSECURE=true` |
| `--verbose` / `-v` | false | print backend URL and username to stderr before the request |
| `--username` / `--password` | env | `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` |
| `--backend-url` | env | `SECMAN_HOST` / `SECMAN_BACKEND_URL`, then `http://localhost:8080` |

When no import has ever run, outputs `Last CrowdStrike import: never`. Exit code `1` on auth or connection error.

### `add-requirement` — create a security requirement

Creates a single security requirement via `POST /api/requirements`. Requires `ADMIN`.

```bash
./scripts/secman add-requirement --shortreq "All passwords must be ≥ 12 characters"
./scripts/secman add-requirement \
  --shortreq "MFA required for admin access" \
  --chapter "Authentication" \
  --norm "ISO 27001" \
  --details "Use TOTP or hardware tokens" \
  --motivation "Reduces risk of credential compromise" \
  --example "Google Authenticator, YubiKey"
```

| Option | Default | Notes |
|---|---|---|
| `--shortreq` / `-s` | **required** | brief requirement text |
| `--chapter` / `-c` | — | grouping category (e.g. `Authentication`) |
| `--details` / `-d` | — | extended description |
| `--motivation` / `-m` | — | rationale for the requirement |
| `--example` / `-e` | — | sample implementation |
| `--norm` / `-n` | — | regulatory reference (e.g. `ISO 27001`, `GDPR`) |
| `--usecase` | — | use-case description |
| `--verbose` / `-v` | false | print field values before sending |
| `--username` / `--password` | env | `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` |
| `--backend-url` | `http://localhost:8080` | backend API URL |

On success, prints the new requirement's ID. Exit codes: `0` created, `1` validation / auth error.

### `export-requirements` — export requirements to file

Downloads all requirements from the backend and writes them to a local Excel (`.xlsx`) or Word (`.docx`) file. Requires `ADMIN`.

```bash
./scripts/secman export-requirements --format xlsx
./scripts/secman export-requirements --format docx --output /reports/reqs.docx
./scripts/secman export-requirements --format xlsx --output export.xlsx --verbose
```

| Option | Default | Notes |
|---|---|---|
| `--format` / `-f` | **required** | `xlsx` (Excel) or `docx` (Word) |
| `--output` / `-o` | auto | output path; defaults to `requirements_export_YYYYMMDD_HHmmss.{format}` in the current directory |
| `--verbose` / `-v` | false | print format, resolved output path, and backend URL |
| `--username` / `--password` | env | `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` |
| `--backend-url` | `http://localhost:8080` | backend API URL |

Exit codes: `0` written, `1` auth / export error, `1` if the output directory does not exist.

### `delete-all-requirements` — permanently delete all requirements

**Destructive.** Deletes every requirement in the database. Cannot be undone. Always pass `--confirm` explicitly — this is a safety guard against accidental invocation. Requires `ADMIN`.

```bash
./scripts/secman delete-all-requirements --confirm
./scripts/secman delete-all-requirements --confirm --verbose
```

| Option | Default | Notes |
|---|---|---|
| `--confirm` | **required** | explicit confirmation flag; the command refuses to run without it |
| `--verbose` / `-v` | false | print backend URL before the request |
| `--username` / `--password` | env | `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` |
| `--backend-url` | `http://localhost:8080` | backend API URL |

Exit codes: `0` deleted, `1` auth or backend error.

### `deduplicate-vulnerabilities` — remove duplicate vulnerability rows

Scans all assets and removes vulnerability records where the same `(CVE ID, product)` pair appears more than once on the same asset. The oldest row (lowest primary key) is kept. Idempotent; safe to run multiple times. Requires `ADMIN`.

```bash
./scripts/secman deduplicate-vulnerabilities
./scripts/secman deduplicate-vulnerabilities --verbose    # show per-asset CVE details
./scripts/secman deduplicate-vulnerabilities --backend-url https://prod:8080
```

| Option | Default | Notes |
|---|---|---|
| `--verbose` / `-v` | false | print per-asset details: asset ID, name, removed count, affected CVEs |
| `--username` / `--password` | env | `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` |
| `--backend-url` | `http://localhost:8080` | backend API URL |

Exit codes: `0` success (prints `No duplicate vulnerabilities found` when clean), `1` backend / auth error, `2` connection error.

### `port-scan` — nmap scan of internet-facing assets

Fetches assets whose `networkZone` is `EXTERNAL` or `DMZ` and that have an IP address, runs `nmap` against each, then uploads the XML results to `POST /api/scan/upload-nmap`. Requires `nmap` on `PATH` (or `--nmap-path`). Requires `ADMIN` role on the backend.

```bash
./scripts/secman port-scan --dry-run                        # list targets only, no nmap
./scripts/secman port-scan --targets "web-*" --ports 80,443
./scripts/secman port-scan --nmap-args "-sV -T3" --output-dir /var/log/secman/scans
```

| Option | Default | Notes |
|---|---|---|
| `--nmap-path` | `nmap` | path to the nmap binary |
| `--nmap-args` | `-sV -T4` | additional nmap flags passed verbatim |
| `--ports` / `-p` | nmap default top 1000 | port range, e.g. `80,443` or `1-1024` |
| `--targets` | all EXTERNAL/DMZ assets | filter asset names by pattern (supports `*`) |
| `--dry-run` | false | print the nmap commands that would be run without executing them |
| `--output-dir` | temp dir | directory to save XML result files; created if absent |
| `--verbose` / `-v` | false | print nmap stderr and per-upload status |
| `--username` / `--password` | env | `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` |
| `--backend-url` | env | `SECMAN_HOST` / `SECMAN_BACKEND_URL`, then `http://localhost:8080` |

Exit codes: `0` all scans uploaded, `1` ≥1 scan failed or upload failed.

Install nmap if missing:
```bash
sudo apt install nmap      # Debian/Ubuntu
brew install nmap          # macOS
sudo yum install nmap      # RHEL/CentOS
```

### `send-application-register-reminders` — application register overdue reminders

Notifies responsible users about application register entries that have not been reviewed within a configurable threshold (default: 365 days). Calls `POST /api/cli/application-register/reminders/send`. Requires `ADMIN`.

```bash
./scripts/secman send-application-register-reminders --dry-run
./scripts/secman send-application-register-reminders --days 180 --verbose
```

| Option | Default | Notes |
|---|---|---|
| `--days` | 365 | entries not reviewed within this many days are overdue |
| `--dry-run` | false | preview overdue entries and recipients without sending |
| `--verbose` / `-v` | false | detailed per-recipient output |
| `--username` / `--password` | env | `SECMAN_ADMIN_NAME` / `SECMAN_ADMIN_PASS` |
| `--backend-url` | env | `SECMAN_HOST` / `SECMAN_BACKEND_URL`, then `http://localhost:8080` |

### `asset-match-clear` — reconcile AWS assets against S3 snapshot

See `docs/CLI_ASSET_MATCH_CLEAR.md` for the full reference. Quick summary:

Compares `Asset.cloudInstanceId` (case-insensitive) in SecMan against a JSON resource snapshot stored in S3. Deletes SecMan assets whose `cloudInstanceId` is absent from the snapshot, scoped strictly to the `accountId`s present in the snapshot (partial-snapshot safe). A 25% safety brake prevents mass deletion.

```bash
./scripts/secman asset-match-clear --dry-run                        # preview, no deletions
./scripts/secman asset-match-clear --bucket my-bucket --key snap.json
./scripts/secman asset-match-clear --max-delete-percent 0           # disable safety brake
```

Key options: `--bucket`, `--key`, `--dry-run`, `--strict`, `--check`, `--check-fix`, `--max-delete-percent` (default 25), `--save` (dump snapshot to `/tmp/asset.json`), standard AWS credential flags. Full option table: `docs/CLI_ASSET_MATCH_CLEAR.md`.

## Cron

Wrapper template at `/opt/secman/bin/cron-query-servers.sh`:

```bash
#!/usr/bin/env bash
set -Eeuo pipefail
JAR=/opt/secman/bin/secman-cli.jar
LOG=/opt/secman/logs/cronjob.log
LOCK=/var/lock/secman-cli.lock

[ -e "$LOCK" ] && { echo "[$(date -Is)] another run in progress"; exit 0; }
trap "rm -f $LOCK" EXIT
touch "$LOCK"

source /opt/secman/config/credentials.conf
java -jar "$JAR" query servers --severity HIGH,CRITICAL --min-days-open 1 --save \
  --username "$SECMAN_ADMIN_NAME" --password "$SECMAN_ADMIN_PASS" 2>&1 | tee -a "$LOG"
```

`crontab -e` examples:
```cron
PATH=/usr/bin:/bin:/usr/local/bin:/usr/lib/jvm/java-25-amazon-corretto/bin
JAVA_HOME=/usr/lib/jvm/java-25-amazon-corretto

0 2 * * *          /opt/secman/bin/cron-query-servers.sh >> /opt/secman/logs/cronjob.log 2>&1
0 9-17 * * 1-5     /opt/secman/bin/cron-query-servers.sh
0 4 * * 0          /opt/secman/bin/secman delete-asset-not-seen 90 --dry-run >> /opt/secman/logs/stale-assets.log 2>&1
*/10 * * * *       TELEGRAM_BOT_TOKEN=… TELEGRAM_CHAT_ID=… \
                   /opt/secman/src/clinotify/check_crowdstrike_checkin.py \
                   --url https://secman.example.com --max-age-minutes 120
```

Log rotation `/etc/logrotate.d/secman-cli`:
```
/opt/secman/logs/*.log { daily rotate 30 compress delaycompress missingok notifempty create 0644 secman secman }
```

## AWS integration

### Secrets Manager

```bash
SECRETS=$(aws secretsmanager get-secret-value --secret-id secman/crowdstrike --query SecretString --output text)
export FALCON_CLIENT_ID=$(jq -r .client_id     <<<"$SECRETS")
export FALCON_CLIENT_SECRET=$(jq -r .client_secret <<<"$SECRETS")
export SECMAN_ADMIN_NAME=$(jq -r .username        <<<"$SECRETS")
export SECMAN_ADMIN_PASS=$(jq -r .password        <<<"$SECRETS")
java -jar /opt/secman/bin/secman-cli.jar "$@"
```
IAM: `secretsmanager:GetSecretValue` on `arn:aws:secretsmanager:…:secret:secman/*`.

### CloudWatch Logs

`/opt/aws/amazon-cloudwatch-agent/etc/config.json`:
```json
{ "logs": { "logs_collected": { "files": { "collect_list": [
  { "file_path":"/opt/secman/logs/cronjob.log",
    "log_group_name":"/secman/cli/cronjob",
    "log_stream_name":"{instance_id}",
    "retention_in_days":30 }
]}}}}
```
Activate: `amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -s -c file:/opt/aws/amazon-cloudwatch-agent/etc/config.json`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Command not found` in cron | add `PATH`, `JAVA_HOME` to crontab |
| `Credentials not found` | check format (no spaces around `=`); `chmod 600` the file |
| CrowdStrike `401 Unauthorized` | verify client/secret + region (`api.us-2.crowdstrike.com`, `api.eu-1…`, `api.laggar.gcw…`) and required scopes |
| Out of memory | `java -Xmx1g -jar secman-cli.jar …` |

Health-monitor sketch:
```bash
LAST=$(grep "Completed Successfully" /opt/secman/logs/cronjob.log | tail -1 | awk '{print $1, $2}')
EPOCH=$(date -d "$LAST" +%s 2>/dev/null || echo 0)
[ $(( ($(date +%s) - EPOCH) / 3600 )) -gt 24 ] && exit 1 || exit 0
```

CLI command-specific reference docs: `src/cli/src/main/resources/cli-docs/{USER_MAPPING,WORKGROUP,ADD_VULNERABILITY}_COMMANDS.md`.
