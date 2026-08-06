# Environment Variables

Resolution order (Micronaut): system properties → env vars → `application.yml` → defaults. CLI: same plus `~/.secman/{credentials.conf,crowdstrike.yaml}`.

## Backend

### Database (required)
| Var | Default | Notes |
|---|---|---|
| `DB_CONNECT` | `jdbc:mariadb://localhost:3306/secman` | full JDBC URL |
| `DB_USERNAME` | `secman` | |
| `DB_PASSWORD` | `CHANGEME` | replace in production |

### Auth & crypto (required in production)
| Var | Default | Notes |
|---|---|---|
| `JWT_SECRET` | dev default | ≥256 bits. `openssl rand -base64 32` |
| `SECMAN_ENCRYPTION_PASSWORD` | dev default | for sensitive fields (OAuth secrets, API keys). `openssl rand -hex 32`. **Never rotate**: orphans encrypted data. |
| `SECMAN_ENCRYPTION_SALT` | dev default | exactly 16 hex chars. `openssl rand -hex 8`. Same warning. |
| `SECMAN_AUTH_COOKIE_SECURE` | `true` | set `false` only for local HTTP dev |

### URLs
| Var | Default | Notes |
|---|---|---|
| `SECMAN_BACKEND_URL` | `http://localhost:8080` | used for CORS + email links + OAuth callbacks |
| `FRONTEND_URL` | `http://localhost:4321` | same |

### SMTP
| Var | Default |
|---|---|
| `SMTP_HOST` | `smtp.example.com` |
| `SMTP_PORT` | `587` |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | placeholders |
| `SMTP_FROM_ADDRESS` | `noreply@secman.example.com` |
| `SMTP_FROM_NAME` | `Security Management System` |
| `SMTP_ENABLE_TLS` | `true` |

Gmail requires an [App Password](https://support.google.com/accounts/answer/185833), not the account password.

### OAuth retry (race tolerance for fast Microsoft SSO)
| Var | Default |
|---|---|
| `OAUTH_STATE_RETRY_MAX_ATTEMPTS` | `5` |
| `OAUTH_STATE_RETRY_INITIAL_DELAY` | `100` (ms) |
| `OAUTH_STATE_RETRY_MAX_DELAY` | `500` (ms) |
| `OAUTH_STATE_RETRY_BACKOFF_MULTIPLIER` | `1.5` |
| `OAUTH_TOKEN_EXCHANGE_MAX_RETRIES` | `2` (retries on 5xx + timeouts; never on 4xx) |
| `OAUTH_TOKEN_EXCHANGE_RETRY_DELAY` | `500` (ms) |

State retry default sequence: 100 → 150 → 225 → 337 → 500 ms. Increase max-attempts/delay if "login session not found" appears under DB replication lag.

### Memory optimization (Feature 073)
| Var | Default | Notes |
|---|---|---|
| `MEMORY_LAZY_LOADING` | `true` | LAZY fetch on entity relationships |
| `MEMORY_BATCH_SIZE` | `1000` | range 100–10000 (cleanup + streaming) |
| `MEMORY_STREAMING_EXPORTS` | `true` | streams large exports |

Monitor: `GET /memory` (used/max/free/total MB). Set to `false` to roll back.

### Materialized view refresh debounce
The refresh chain (outdated-asset view + statistics cache + heatmap + AWS KPI) is expensive and
is triggered once per CrowdStrike import sub-batch. These two gates stop it running repeatedly
mid-import. The manual admin "Refresh Now" endpoint bypasses both.

| Var | Default | Effect |
|---|---|---|
| `MATERIALIZED_VIEW_REFRESH_MIN_INTERVAL_SECONDS` | `60` | Minimum gap between one refresh cycle completing and the next starting. |
| `MATERIALIZED_VIEW_REFRESH_QUIET_PERIOD_SECONDS` | `120` | A deferred refresh waits until this long has passed with **no** new request, so a whole import coalesces into one refresh. Raise if imports still overlap a refresh; lower for fresher dashboards sooner after an import. |

### Chat notifications (Slack, Telegram)
Per-user, per-channel subscriptions to reportable events (currently: CrowdStrike import run
completed, AWS account import completed). All credentials — the workspace bot tokens, each
user's Slack webhook URL and each user's Telegram chat ID / personal bot token — live in the
database, encrypted; **none of them are environment variables**. These settings only cover
transport endpoints and timing.

| Var | Default | Effect |
|---|---|---|
| `SECMAN_SLACK_WEBHOOK_URL_PREFIX` | `https://hooks.slack.com/` | Required prefix (and host) for a user-supplied Slack incoming webhook. This is the **SSRF boundary** — the backend performs the outbound request against a value a non-admin user typed, so only point this at a deliberately chosen Slack-compatible relay. |
| `SECMAN_SLACK_API_BASE_URL` | `https://slack.com/api` | Base URL for `chat.postMessage` (workspace-bot delivery). |
| `SECMAN_SLACK_TIMEOUT_SECONDS` | `10` | Connect/read timeout for Slack calls. |
| `SECMAN_TELEGRAM_API_BASE_URL` | `https://api.telegram.org` | Base URL for `sendMessage`. Fixed configuration, never user input. |
| `SECMAN_TELEGRAM_TIMEOUT_SECONDS` | `10` | Connect/read timeout for Telegram calls. |
| `SECMAN_CHAT_CROWDSTRIKE_QUIET_PERIOD_SECONDS` | `180` | A CrowdStrike import run counts as finished after this long with no import activity. A full CLI import arrives as ~94 sub-batch requests across 3 concurrent workers with no well-defined last batch, so exactly one "report completed" message is sent once the run goes quiet. Raise if one run is reported as several; lower for a faster message after an import. |

### Vulnerability dating
| Var | Default | Effect |
|---|---|---|
| `VULN_USE_PATCH_PUBLICATION_DATE` | `false` | `false`: `daysOpen = now − detection`. `true`: `now − patchPublicationDate`. |
| `VULN_REQUIRE_PATCH_PUBLICATION_DATE` | `false` | only import vulns with patch-publication date |

### CrowdStrike stale-asset cleanup
Daily scheduled job (02:30 server TZ) that deletes assets whose `crowdStrikeLastImportedAt` is older than `STALE_DAYS`. Manual runs (admin UI / CLI `delete-asset-not-seen`) ignore the brake but are still recorded in `crowdstrike_cleanup_run`.

| Var | Default | Effect |
|---|---|---|
| `CROWDSTRIKE_CLEANUP_ENABLED` | `false` | opt-in master switch for the scheduled job |
| `CROWDSTRIKE_CLEANUP_STALE_DAYS` | `30` | delete CrowdStrike-tracked assets last imported > N days ago |
| `CROWDSTRIKE_CLEANUP_MAX_DELETE_PERCENT` | `10` | abort the scheduled run if candidates exceed this % of CrowdStrike-tracked assets; set `100` to disable the brake |
| `CROWDSTRIKE_CLEANUP_INCLUDE_LEGACY` | `false` | also delete legacy CrowdStrike-imported assets where `crowdStrikeLastImportedAt IS NULL` (Feature 087, "rule B"). Four-part fence: `owner='CrowdStrike Import'` AND no import timestamp AND no `manualCreator` AND no `scanUploader` AND `COALESCE(lastSeen, updatedAt, createdAt) < cutoff`. Gates BOTH the scheduled job AND the configured default of the manual-run override (which can override per-run via the `includeLegacy` request field). Manually-created and scan-uploaded assets are protected. |

Notifications: ADMIN users receive an email whenever a run deletes ≥1 asset, hits errors, or trips the safety brake. "Boring" runs (0 deletions, 0 errors) are silent.

### AI-assisted risk assessment answers (Feature 088)
Opt-in. Lets ADMIN/SECCHAMPION users trigger an LLM (OpenRouter, web search enabled via the `:online` model suffix) to pre-fill compliance answers as draft `Response` rows. Each answer carries a confidence score and citations; the human reviews and edits before submitting.

| Var | Default | Effect |
|---|---|---|
| `AI_RISK_ASSESSMENT_ENABLED` | `false` | Master feature flag. When `false`, all `/api/risk-assessments/{id}/ai-suggestions/*` endpoints return 403 and the UI button is hidden. |
| `AI_RISK_ASSESSMENT_MODEL` | `anthropic/claude-sonnet-4.6:online` | OpenRouter model id. The `:online` suffix enables built-in web search; citations come back as `message.annotations[].url_citation`. |
| `AI_RISK_ASSESSMENT_MAX_COST` | `5.0` | Per-job hard cap in USD. Pre-flight rejects runs whose projected cost exceeds this; mid-flight aborts when actual usage crosses it. Partial successes are retained. |
| `AI_RISK_ASSESSMENT_MAX_JOBS` | `2` | Global concurrent-job limit. Prevents thundering-herd spend if multiple SECCHAMPIONs trigger whole-assessment runs at once. |
| `OPENROUTER_API_KEY` | unset | OpenRouter API key. Resolve via `pass-cli` per repo convention. Without it the feature stays disabled regardless of the flag. |

What is sent to OpenRouter (audit guarantee, NFR-4 in spec):
- Requirement text + norm/chapter.
- Asset basis: name, type, groups, cloudAccountId, osVersion. Nothing else.
- Demand basis: description only.
- Up to three already-answered sibling requirements as few-shot.

What is NEVER sent: owner emails, IP addresses, internal hostnames, full asset descriptions. Verified by a unit test on the prompt builder.

### Profile pictures
Uploads are validated and **re-encoded** (decode → centre-crop → scale → re-encode), never stored
as sent. That round trip is what removes polyglot payloads and EXIF metadata, so lowering these
limits weakens nothing but raising `max-source-*` raises the memory a single request can demand.

| Var | Default | Effect |
|---|---|---|
| `SECMAN_PROFILE_PICTURE_MAX_UPLOAD_BYTES` | `2097152` (2 MB) | hard cap on the uploaded bytes. Independent of `micronaut.server.multipart.max-file-size`, which stays at 100 MB for the XLSX/vulnerability importers. |
| `SECMAN_PROFILE_PICTURE_MAX_SOURCE_DIMENSION` | `10000` | max pixels per edge, read from the image header **before** any raster is allocated (decompression-bomb guard). |
| `SECMAN_PROFILE_PICTURE_MAX_SOURCE_PIXELS` | `40000000` | max total pixels, same header-only guard. A 2 MB PNG can otherwise decode to a multi-GB `BufferedImage`. |
| `SECMAN_PROFILE_PICTURE_TARGET_EDGE` | `256` | edge length of the stored square thumbnail. Sources smaller than this are not upscaled. |

### Debug & logging
| Var | Default | Effect |
|---|---|---|
| `SECMAN_DEBUG` | `false` | logs all `/mcp/**` and `/api/**` headers + decoded JWT claims (signature never logged). **Production OFF** — header logs may contain secrets. |
| `SECMAN_LOGGING` | unset | `NO` (silent except security audit), `ALL` (TRACE/DEBUG), `ERROR`, or unset (INFO app / WARN frameworks). Security audit log (`logs/security-audit.log`) is always active per NFR-002. |
| `LOG_LEVEL_SECMAN` | `INFO` | level for the `com.secman` logger. `DEBUG` costs real allocation on the CrowdStrike import path (every per-server/per-batch debug line is formatted whether or not anyone reads it) — raise for triage, then put it back. `scripts/startbackenddev.sh` exports `DEBUG`. |
| `LOG_LEVEL_SECURITY` | `INFO` | level for `io.micronaut.security`. `DEBUG` logs authentication internals; leave at `INFO` in production. `scripts/startbackenddev.sh` exports `DEBUG`. |

### JVM heap & out-of-memory behaviour
Not read by the application — passed to the JVM via `JAVA_OPTS` (containers) or
`applicationDefaultJvmArgs` (`gradle run`).

| Context | Setting | Notes |
|---|---|---|
| `docker/aws` (all-in-one) | `-XX:MaxRAMPercentage=45.0` | Three processes share the cgroup limit. See [Memory sizing](DOCKER_AWS.md#memory-sizing). Override the limit with `SECMAN_MEM_LIMIT` under compose. |
| `docker/backend` (backend only) | `-XX:MaxRAMPercentage=75.0` | JVM is the only process in the image. |
| systemd | `-Xmx2g` | See `INSTALL.md` / `docs/DEPLOYMENT.md`. |
| `gradle run` (dev) | `-Xmx1g` | Deliberately bounded so unbounded-query regressions reproduce locally instead of only in production. Override per-run: `./gradlew :backendng:run -PsecmanDevHeap=4g`. |

All four also set `-XX:+HeapDumpOnOutOfMemoryError` and `-XX:+ExitOnOutOfMemoryError`: a JVM
that has thrown `OutOfMemoryError` is in an undefined state, so it dumps and exits and the
supervisor (ECS / compose `restart:` / systemd `Restart=on-failure`) starts a clean one.

Sample debug output:
```
DEBUG c.s.filter.McpDebugHeaderFilter - Debug headers [POST /mcp]:
  Content-Type: application/json
  X-MCP-API-Key: sk-...
  X-MCP-User-Email: user@example.com
DEBUG c.s.filter.McpDebugHeaderFilter - JWT claims [GET /api/assets]:
  {"sub":"admin","roles":["ADMIN"],"iss":"secman-backend-ng","exp":1711756800}
```

## Frontend

| Var | Default | Notes |
|---|---|---|
| `PUBLIC_API_URL` | auto | dev (`localhost`): `http://localhost:8080`; prod: empty (relative URLs through nginx) |

## CLI

CrowdStrike credentials:
| Var | Notes |
|---|---|
| `FALCON_CLIENT_ID` | alias for `CROWDSTRIKE_CLIENT_ID` |
| `FALCON_CLIENT_SECRET` | alias for `CROWDSTRIKE_CLIENT_SECRET` |
| `FALCON_BASE_URL` | per region (see below) |
| `FALCON_CLOUD_REGION` | `us-1`, `us-2`, `eu-1`, `us-gov-1`, `us-gov-2` |

| Region | Base URL |
|---|---|
| US-1 (default) | `https://api.crowdstrike.com` |
| US-2 | `https://api.us-2.crowdstrike.com` |
| EU-1 | `https://api.eu-1.crowdstrike.com` |
| US-GOV-1 / US-GOV-2 | `https://api.laggar.gcw.crowdstrike.com` |

Backend auth (for `--save`):
| Var | Notes |
|---|---|
| `SECMAN_ADMIN_NAME`, `SECMAN_ADMIN_PASS` | required for `--save` and `manage-user-mappings list --send-email` |
| `SECMAN_BACKEND_URL` | default `http://localhost:8080` |
| `SECMAN_INSECURE` | accept self-signed TLS (CLI/JS scanner) |
| `SECMAN_HOST` | shared host URL used by tests; resolved via `pass-cli` |

AWS S3 operations (`asset-match-clear`, `manage-user-mappings import-s3`, `list-bucket`):
| Var | Notes |
|---|---|
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | standard SDK chain (or use `--aws-access-key-id` / `--aws-secret-access-key`) |
| `AWS_SESSION_TOKEN` | required when using ASIA-prefix temporary credentials |
| `AWS_REGION` | region for S3 operations (SDK chain fallback otherwise) |
| `AWS_ASSET_BUCKET_NAME` | S3 bucket name for the `asset-match-clear` snapshot JSON |
| `AWS_ASSET_BUCKET_KEY_NAME` | S3 object key for the `asset-match-clear` snapshot JSON |
| `AWS_ACCOUNT_BUCKET_NAME` | S3 bucket name for the `manage-user-mappings import-s3` mapping file (fallback when `--bucket` is omitted) |
| `AWS_ACCOUNT_BUCKET_KEY_NAME` | S3 object key for the `manage-user-mappings import-s3` mapping file (fallback when `--key` is omitted) |

## adread (Azure AD → workgroup import)

`src/adread/read.py` — see `docs/ADREAD.md` for full usage.

### Azure AD (always required)
| Var | Description |
|---|---|
| `AZURE_TENANT_ID` | Azure AD tenant ID |
| `AZURE_CLIENT_ID` | Service principal client ID |
| `AZURE_CLIENT_SECRET` | Service principal secret |

### secman backend (required with `--import`)
| Var | Default | Description |
|---|---|---|
| `SECMAN_BACKEND_URL` | `http://localhost:8080` | Backend base URL |
| `SECMAN_ADMIN_NAME` | — | secman ADMIN username |
| `SECMAN_ADMIN_PASS` | — | secman ADMIN password |

`LOG_LEVEL` (optional, default `INFO`) controls verbosity for this script as well.

## Templates

`/etc/secman/backend.env`:
```bash
DB_CONNECT=jdbc:mariadb://localhost:3306/secman
DB_USERNAME=secman
DB_PASSWORD=REPLACE
JWT_SECRET=REPLACE                               # openssl rand -base64 32
SECMAN_ENCRYPTION_PASSWORD=REPLACE               # openssl rand -hex 32
SECMAN_ENCRYPTION_SALT=REPLACE                   # openssl rand -hex 8
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=noreply@example.com
SMTP_PASSWORD=REPLACE
SMTP_FROM_ADDRESS=noreply@example.com
SMTP_FROM_NAME=Security Management System
SMTP_ENABLE_TLS=true
SECMAN_BACKEND_URL=https://api.example.com
FRONTEND_URL=https://secman.example.com
SECMAN_AUTH_COOKIE_SECURE=true
# optional
# VULN_USE_PATCH_PUBLICATION_DATE=false
# VULN_REQUIRE_PATCH_PUBLICATION_DATE=false
# SECMAN_DEBUG=false
# SECMAN_LOGGING=
# MEMORY_LAZY_LOADING=true
# MEMORY_BATCH_SIZE=1000
# MEMORY_STREAMING_EXPORTS=true
# OAUTH_STATE_RETRY_MAX_ATTEMPTS=5
# OAUTH_STATE_RETRY_INITIAL_DELAY=100
# OAUTH_STATE_RETRY_MAX_DELAY=500
# OAUTH_STATE_RETRY_BACKOFF_MULTIPLIER=1.5
# OAUTH_TOKEN_EXCHANGE_MAX_RETRIES=2
# OAUTH_TOKEN_EXCHANGE_RETRY_DELAY=500
# CROWDSTRIKE_CLEANUP_ENABLED=false
# CROWDSTRIKE_CLEANUP_STALE_DAYS=30
# CROWDSTRIKE_CLEANUP_MAX_DELETE_PERCENT=10
# CROWDSTRIKE_CLEANUP_INCLUDE_LEGACY=false
```

`/etc/secman/frontend.env`:
```bash
PUBLIC_API_URL=
NODE_ENV=production
HOST=127.0.0.1
PORT=4321
```

`~/.secman/credentials.conf`:
```bash
FALCON_CLIENT_ID=...
FALCON_CLIENT_SECRET=...
FALCON_BASE_URL=https://api.crowdstrike.com
SECMAN_ADMIN_NAME=...
SECMAN_ADMIN_PASS=...
SECMAN_BACKEND_URL=https://api.example.com
```

`~/.secman/crowdstrike.yaml`:
```yaml
clientId: ...
clientSecret: ...
baseUrl: https://api.crowdstrike.com
```

## Hygiene

- Never commit credentials. Use `pass-cli` (Proton Pass) for shared secrets — see `docs/PASS_CLI.md`.
- `chmod 600 /etc/secman/*.env ~/.secman/credentials.conf`.
- Rotate ≤90 days. Keep `SECMAN_ENCRYPTION_*` constant for the lifetime of encrypted data.
- Per-environment credentials (dev/staging/prod).

## Common errors

| Symptom | Likely cause |
|---|---|
| `JWT signature verification failed` | `JWT_SECRET` < 32 bytes or differs across instances |
| `Failed to decrypt sensitive data` | `SECMAN_ENCRYPTION_PASSWORD`/`SALT` changed since data was written — revert |
| `SMTP authentication failed` | wrong `SMTP_HOST`/`SMTP_PORT`; for Gmail use App Password |
| CrowdStrike `401 Unauthorized` | wrong `FALCON_CLIENT_*` or wrong region in `FALCON_BASE_URL`; missing scopes |
| OAuth `Your login session was not found` | state expired/lost — increase `OAUTH_STATE_RETRY_MAX_ATTEMPTS`/`MAX_DELAY`; check `oauth_states` cleanup job |
| OAuth `Could not complete authentication` | token exchange failed (network/IdP) — bump `OAUTH_TOKEN_EXCHANGE_MAX_RETRIES`; check provider logs |
| OAuth `Your login session expired` | user took >10 min at IdP login — UX issue, not a config bug |
