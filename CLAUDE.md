# Claude Code Agent Context

Security requirement, vulnerability and risk management platform.

## Stack

- Backend: Kotlin 2.4.10 / Java 25, Micronaut 5.1, Hibernate JPA → `src/backendng/`
- Frontend: Astro 7.2 + React 19 islands, Axios, JWT in the HttpOnly `secman_auth` cookie → `src/frontend/`
- CLI: Kotlin + Picocli 4.7.7, AWS SDK v2 → `src/cli/`
- DB: MariaDB 11.4, Flyway + Hibernate auto-migration
- Build: Gradle 9.7.0 (Kotlin DSL)
- MCP: Streamable HTTP / JSON-RPC 2.0. `X-MCP-User-Email` header is **mandatory** on `tools/list` and `tools/call` (only `initialize` and `ping` exempt).

## Roles (RBAC)

`USER`, `ADMIN`, `VULN`, `RELEASE_MANAGER`, `REQ`, `REQADMIN`, `RISK`, `SECCHAMPION`, `REPORT`.

## Tooling Conventions (canonical, do not deviate)

- **Scripts**: `./scripts/` only.
- **Skills**: `.claude/skills/` is the canonical, leading skill set for Claude Code sessions in this repo — authoritative over `.agents/skills/` (parallel copies maintained for Codex). On any divergence between the two trees, `.claude/skills/` wins. **Whenever a `.claude/skills/*/SKILL.md` file is edited, the matching `.agents/skills/*/SKILL.md` file must be updated in the same change** so the two never drift — translate Claude-specific mechanics to their Codex equivalent (e.g. Bash tool `dangerouslyDisableSandbox: true` ↔ `sandbox_permissions: "require_escalated"`) rather than copying verbatim. If a `.claude/skills/` entry has no `.agents/skills/` counterpart, flag it instead of silently creating one. When only asked to update "skills" without a harness specified, update `.claude/skills/` first, then port to `.agents/skills/`.
- **Secrets**: `pass-cli` (Proton Pass) only. Never hardcode secrets.
- **Backend dev start**: always `./scripts/startbackenddev.sh` (sources `pass-cli` env, runs Micronaut). Never call `./gradlew run` directly.
- **Frontend dev start**: always `./scripts/startfrontenddev.sh` (sources `pass-cli` env, runs `npm run dev`). Never call `npm run dev` directly.
- Both dev-start scripts require `pass-cli` and must be run **outside any sandbox** (e.g. Bash tool `dangerouslyDisableSandbox: true`, or escalated/unsandboxed permissions in other harnesses) — a sandboxed shell cannot reach `pass-cli` to source secrets, so the process fails to start cleanly.
- **Host URL in tests**: read `SECMAN_HOST` from `pass-cli`. Never hardcode `http://localhost:8080` or `http://localhost:4321`.

## Key Entities

- **Asset**: `id, name, type, ip, owner, description, lastSeen`; metadata `groups, cloudAccountId, cloudInstanceId, adDomain, osVersion`; relations `vulnerabilities, scanResults, workgroups, manualCreator, scanUploader`.
- **AwsAccountSharing**: `id, sourceUser, targetUser, createdBy, createdAt`. Directional, non-transitive. ADMIN-only.

## Unified Asset Access (any of)

1. ADMIN **or SECCHAMPION** role (universal access)
2. Asset in user's workgroup
3. `manualCreator == user`
4. `scanUploader == user`
5. `cloudAccountId` matches user's AWS UserMapping
6. `adDomain` matches user's domain UserMapping (case-insensitive)
7. `cloudAccountId` matches a sharing rule (`AwsAccountSharing`, directional)
8. `owner == username`
9. `cloudAccountId` matches an account assigned to a workgroup the user belongs to (`WorkgroupAwsAccount`, direct membership only)
10. `adDomain` matches a domain assigned to a workgroup the user belongs to (`WorkgroupAdDomain`, direct membership only)

Authoritative filter: `AssetFilterService.getAccessibleAssets()`. SQL pre-filters in materialized views are perf hints only — never the auth boundary. Same enforcement applies to MCP `get_overdue_assets`. Note the deliberate asymmetry: `getAccessibleAssets()`/`getAccessibleAssetIds()` short-circuit for ADMIN **or** SECCHAMPION, but `getScopedAccessibleAssetIds()` short-circuits for ADMIN only.

## API Endpoints (concise)

| Group | Endpoints | Roles |
|---|---|---|
| Auth | `POST /api/auth/login`, `GET /oauth/{authorize,callback}` | public |
| Import | `POST /api/import/{upload-xlsx, upload-nmap-xml, upload-vulnerability-xlsx, upload-user-mappings[-csv], upload-assets-xlsx}` | ADMIN |
| Assets | `GET/POST /api/assets`, `DELETE /api/assets/bulk` (ADMIN), `GET /api/assets/export` | mixed |
| Vulns | `GET /api/vulnerabilities/current`; export job: `POST /api/vulnerabilities/export` → `GET .../{jobId}/{status,download}`, `DELETE .../{jobId}`; history `GET .../export/history` (ADMIN/VULN/SECCHAMPION); `GET /api/vulnerability-exceptions`; `POST /api/vulnerability-exception-requests`; `GET .../pending/count`; SSE `GET /api/exception-badge-updates` | mixed |
| Outdated | `GET /api/outdated-assets[/{id}[/vulnerabilities]]`, `.../{last-refresh,count}`; `POST /api/materialized-view-refresh/trigger` (ADMIN); SSE `GET .../progress`; `GET .../status,history` | mixed |
| Workgroups | `GET/POST /api/workgroups` (ADMIN), `POST /api/workgroups/{id}/{users,assets}` (ADMIN), `GET/POST/DELETE /api/workgroups/{id}/aws-accounts`, `GET/POST/DELETE /api/workgroups/{id}/ad-domains` | ADMIN/member-scoped |
| Releases | `GET/POST/DELETE /api/releases[/{id}]` (ADMIN/REQADMIN to write), `GET /api/releases/compare`. Statuses: PREPARATION→ALIGNMENT→ACTIVE→ARCHIVED | mixed |
| CrowdStrike | `POST/GET /api/crowdstrike/{servers/import,vulnerabilities/save,vulnerabilities}` (ADMIN/VULN); `GET /api/crowdstrike/last-checkin` (PUBLIC, `text/plain` ISO-8601 or `"never"`) | mixed |
| User Mappings | `GET /api/user-mappings/{current,applied-history}`, `POST/PUT/DELETE /api/user-mappings[/{id}]` | ADMIN |
| AWS Sharing | `GET/POST /api/aws-account-sharing`, `DELETE .../{id}` | ADMIN |
| Heatmap | `GET /api/vulnerability-heatmap`; `POST .../refresh` (ADMIN); `GET /api/external/vulnerability-heatmap` (API-key, CORS) | mixed |
| Identity Providers | `GET/POST/PUT/DELETE /api/identity-providers[/{id}[/test]]` | ADMIN |
| Maintenance Banners | `GET /api/maintenance-banners/active` (PUBLIC); `GET/POST/PUT/DELETE /api/maintenance-banners[/{id}]` (ADMIN) | mixed |
| User Profile | `GET /api/users/profile`, `PUT .../change-password` (LOCAL only), `GET/PUT .../mfa-{status,toggle}` | auth |
| User Dashboard | `GET /api/user-dashboard` (aggregated personal todos, single round-trip) | auth |
| Security KPIs | `GET /api/dashboard/{aws-clean-server-kpi, edr-coverage-kpi}` — precomputed cache reads only, never live queries | ADMIN/SECCHAMPION |
| Notifications | `GET/PUT /api/notification-preferences`; `GET /api/notification-logs`; `.../export` (ADMIN) | mixed |
| Chat Notifications | `GET /api/notification-events` (event catalogue); `GET/PUT /api/slack/settings`, `POST .../test`; `GET/PUT /api/telegram/settings`, `POST .../test` (self-scoped); `GET/PUT /api/slack/config`, `POST .../test`, `GET/PUT /api/telegram/config` (ADMIN) | mixed |
| CLI | `POST /api/vulnerabilities/cli-add` (ADMIN/VULN; auto-creates asset) | ADMIN/VULN |

MCP tool families mirror these (delegation required): `list_/create_/delete_release`, `set_release_status`, `compare_releases`; `list_workgroup_aws_accounts`, `add_/remove_workgroup_aws_account`, `list_workgroup_ad_domains`, `add_/remove_workgroup_ad_domain`; `list_/create_/delete_aws_account_sharing`; `get_vulnerability_heatmap`, `refresh_vulnerability_heatmap`; etc. See `docs/MCP.md`.

## Commands

```bash
./gradlew build                                          # backend build + tests
./scripts/startbackenddev.sh                             # canonical dev start — outside any sandbox
./scripts/startfrontenddev.sh                            # canonical dev start, port 4321 — outside any sandbox
./gradlew :cli:shadowJar                                 # build the CLI once
./scripts/secman <command>                               # query servers, send-notifications, manage-user-mappings, ...
./gradlew :backendng:test --tests "*ServiceTest*"        # unit
./gradlew :backendng:test --tests "*IntegrationTest*"    # integration (external MariaDB, see Test Infrastructure)
./gradlew :cli:test
cd src/frontend && npm test                              # frontend unit tier (node:test, no framework dep)
./scripts/test-coverage-report.sh                        # name-reference coverage per area (NOT line coverage)
./tests/e2e/run-e2e.sh                                   # Playwright with pass-cli secrets
```

`/testsuite` runs every tier above plus the frontend build gate, `check-skill-sync.sh`
and the coverage report in one pass. It never starts the stack, so it does not
discharge principle 5 or the principle-7 gates.

CrowdStrike monitoring: `src/clinotify/check_crowdstrike_checkin.py` polls `/api/crowdstrike/last-checkin` and Telegrams when stale. Stdlib-only.

## Hard Principles

1. Security-first: file validation, input sanitization, RBAC. Security review before completion.
2. RBAC enforced at controller (`@Secured`) AND in UI.
3. Schema = Flyway migrations + Hibernate auto-update. **New entities must declare
   `@GeneratedValue(strategy = GenerationType.IDENTITY)`** — a bare `@GeneratedValue`
   resolves to a native `<table>_seq` that fights an `AUTO_INCREMENT` column and yields
   intermittent `Duplicate entry '<n>' for key 'PRIMARY'`. See `docs/ARCHITECTURE.md`
   §Entity id generation.
4. Always write tests. Source of truth for credentials and URLs is `pass-cli`.
5. **A change is complete only when** `./gradlew build` is clean **AND** `./scripts/startbackenddev.sh` starts cleanly. Compile-clean ≠ runtime-clean (Micronaut bean wiring, Flyway, SessionFactory only check at startup). Stop the backend after verifying.
5a. **Frontend changes are complete only when** `cd src/frontend && npm ci && npm run build` exits 0. TypeScript errors, missing imports, and broken Astro/React components are caught here — do not skip this step for any frontend file edit.
5b. **Backend contract changes are complete only when the `extensions/` clients are updated in the same change.** Renaming a path, renaming or retyping a request/response field, tightening `@Secured`, or altering the auth scheme breaks those clients **silently** — nothing in this build compiles or tests against them, so the failure surfaces in production, not in CI. See **Extension Clients** for the surface and the verification rules. `/finalizer` automates the check.
6. Tests route HTTP through `SECMAN_HOST` (from `pass-cli`). No hardcoded localhost URLs.
7. **Mandatory post-change E2E gates** (in addition to build + startup):
   - **`/e2ejs`** must report **0 JS errors** for both admin and normal-user runs against `SECMAN_HOST`. RBAC 403s on role-gated endpoints and documented 404s (e.g., `/api/wg-vulns`, `/api/domain-vulns` for users without mappings) are NOT JS errors. A page that throws or logs `console.error` IS — fix before merge.
   - **`/e2evulnexception`** must run the full vuln + exception lifecycle (MCP + UI, setup + teardown) with **0 failures**.

   Doc-only edits outside `src/`, `tests/`, `scripts/` may skip the gates — state so explicitly. Otherwise both gates are non-negotiable.

## Patterns (worth knowing)

Code samples in `docs/ARCHITECTURE.md` §Patterns — CSV/Excel import (validate → parse → header check → row parse → dedupe → batch save → `ImportResult`), asset merge (`findByName` → append groups, update IP, preserve owner; else create), event-driven (`@EventListener @Async`), OAuth state retry (`OAuthService.findStateByValueWithRetry`, `OAUTH_STATE_RETRY_*`). Memory optimization: `MemoryOptimizationConfig` reads `secman.memory.*`, monitor `GET /memory`, any toggle `false` rolls back. Env names: `docs/ENVIRONMENT.md`.

### Auth
- Backend: `@Secured(SecurityRule.IS_AUTHENTICATED)` + `authentication.roles.contains("…")`.
- Frontend: the JWT lives in the **HttpOnly `secman_auth` cookie** (`AuthCookieService.AUTH_COOKIE_NAME`), not in JS-readable storage. Axios sends it via `withCredentials: true` (set globally in `utils/csrf.ts`); fetch calls use the `authenticated*` helpers in `utils/auth.ts` / `services/`. `sessionStorage["user"]` holds only the display/role payload — never a token.
- External/CLI clients: `POST /api/auth/login` returns the JWT **only** in `Set-Cookie: secman_auth=…`; they re-send it as `Authorization: Bearer …` (the bearer reader stays active alongside cookie auth).
- SSE: JWT in `?token=…` query param (EventSource has no header support).

### Chat notifications (Slack / Telegram)
Publishers stay transport-agnostic: publish a `ChatNotificationEvent` where the work completes;
`ChatNotificationEventListener` (`@EventListener @Async`) → `ChatNotificationService` does
subscriber lookup, destination resolution and rendering. **A new reportable event = one
`NotificationEventType` constant + one publish call** (both settings APIs, both settings UIs and
the dispatcher derive from the enum). Subscriptions are `(user_id, channel, event_type)` rows, so
a new event or channel needs no migration and per-channel saves never disturb each other.
Dispatch must stay non-`@Transactional` — it does per-recipient HTTP with a multi-second timeout.
**Security**: the per-user Slack webhook URL is the only user-supplied URL the backend fetches —
host-allowlisted in `SlackClient.validateWebhookUrl`, redirects never followed. The Telegram bot
token goes in the request URL *path*, so `TelegramClient.validateBotToken` is a security control,
not input hygiene. All credentials use `EncryptedStringConverter`, are never returned (only
`…Configured` booleans), and accept `***HIDDEN***` back to mean "keep". Telegram sends with no
`parse_mode` on purpose. CrowdStrike completion is detected by a quiet-period debounce in
`ImportCompletionNotifier` (no last batch exists), AWS account imports publish inline post-commit.

### Transactional replace (CrowdStrike vuln import)
Per server: `findOrCreateAsset` → `vulnerabilityRepository.deleteByAssetId()` → `saveAll`, all in one `@Transactional`. Idempotent; missing CVEs in the next import = remediation.

**CRITICAL**: `Asset.vulnerabilities` MUST NOT use `cascade=ALL` or `orphanRemoval=true`. JPA cascade fights the manual delete-insert and silently drops 99% of rows (real incident: 166,812 → 1,819). Use explicit `vulnerabilityRepository.deleteByAssetId()` in the service. See `docs/CROWDSTRIKE_IMPORT.md`.

## Test Infrastructure

JUnit 6, Mockk, AssertJ, `@MicronautTest`. Integration tests run against an **external MariaDB** (no Docker/Testcontainers). **`junit-jupiter-params` is not on the classpath** — `@ParameterizedTest` will not compile; loop inside a plain `@Test`. Helpers in `src/backendng/src/test/kotlin/com/secman/testutil/`:
- `BaseIntegrationTest` — base for DB-backed tests; datasource comes from `application-test.yml`.
- `TestDataFactory` — admin/vuln/regular user, asset, vulnerability builders.
- `TestAuthHelper` — JWT login → bearer token.

Datasource env (set via `pass-cli`; defaults to a local `secman_test`): `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`. Schema is Hibernate `create-drop` (Flyway off in `test`), so **point `TEST_DB_*` only at a disposable test DB — never `DB_CONNECT`** (it would drop tables). Integration tests now run **unconditionally** — they fail (not skip) if no test DB is reachable.

```kotlin
class MyIntegrationTest : BaseIntegrationTest() { @Inject lateinit var repo: Repository }
```

**Frontend unit tier**: `cd src/frontend && npm test` — Node's own runner with native
TypeScript stripping, no test-framework dependency. Tests live beside their module as
`<module>.test.ts`. Imports resolve `.ts` only: Node cannot parse JSX, so pure logic
must be extracted out of `.tsx` into a sibling `.ts` module to be unit-testable (the
resolver hook in `src/frontend/test/` handles extensionless specifiers and raises a
pointed error on `.tsx`). Details in `docs/TESTING.md` §Frontend.

One-time local setup (admin DB user):
```sql
CREATE DATABASE IF NOT EXISTS secman_test;
CREATE USER IF NOT EXISTS 'secman_test'@'localhost' IDENTIFIED BY 'secman_test';
GRANT ALL PRIVILEGES ON secman_test.* TO 'secman_test'@'localhost';
```

## File Layout

`src/backendng/…/com/secman/{domain,controller,service,repository,config,dto,filter,mcp}/` · `src/frontend/src/{pages,components,services,layouts}/` · `src/cli/…/com/secman/cli/{commands,service}/`. `application.yml` + `email-templates/` under `src/backendng/src/main/resources/`. Env: `docs/ENVIRONMENT.md`. Full tree: `docs/ARCHITECTURE.md` §File layout.

## Extension Clients (`extensions/`)

`secman_ai_github` (GitHub security scanner) and `secman_visual_check` (external attack-surface scanner): independent Python repos with their own remotes, **gitignored here** — root `git status` never shows them and no build or test here covers them.

Always rediscover the surface; a written list means a newly added call gets checked by nobody:
```bash
grep -rnE '/api/|"/mcp"|X-MCP-User-Email' extensions --include='*.py' --exclude-dir=.venv
```
As of 2026-08-01: `POST /api/auth/login`, `POST /api/vulnerabilities/cli-add`, `GET /api/vulnerabilities/current`, `PUT /api/assets/import`, MCP `/mcp` (`X-MCP-API-Key` + `X-MCP-User-Email`; `get_vulnerabilities`, `add_vulnerability`, `create_asset`).

When you change any of those endpoints, verify all five dimensions against the client: **path, HTTP method, request field names, response fields the client reads, and `@Secured` roles / required headers**. Field names matter most — Jackson drops unknown keys without error, so a rename makes the client "succeed" while sending nothing. Update the client's `tests/` too; a test asserting the old shape is drift.

Fix and commit **inside the extension repo, path-scoped** (`git -C extensions/<repo> commit -m "…" -- <files>`) so pre-existing dirty files are not swallowed. Never `git add -A` / `commit -a`, and **never push** — those repos have their own review. An `@Secured` tightening is a *report*, not a fix: you cannot see which roles the client's service account holds.

## E2E Runner

Triggered by `/e2eexception`, `/admin-asset-e2e`, `/e2ejs`, `/e2evulnexception`, `/importtest`, `/crowdstrike-vuln-match` skills.

- **Cold start is mandatory**: every skill that touches the running stack assumes backend and frontend must be started by the skill itself. It always begins by killing any running backend/frontend via `./scripts/stopbackenddev.sh` / `./scripts/stopfrontenddev.sh` (unconditional — even if ports look free; the scripts are safe no-ops), then starts both fresh via the canonical start scripts. Never reuse an already-running instance; never assume services are up.
- Backend changes (Kotlin/Java) → restart required.
- Frontend changes → Vite hot-reload (no restart).
- Config (`astro.config.mjs`, `application.yml`) → restart.
- Logs: `.e2e-logs/` (gitignored). Max 5 fix iterations.
- **Liveness check is port-bind**, not HTTP: `lsof -iTCP:8080 -sTCP:LISTEN -n -P` (120s budget) and `:4321` (60s budget).
- **Functional checks** still go through `SECMAN_HOST` from `pass-cli`. Never `curl localhost`.

---

*Last updated: 2026-08-06*

## Recent Changes

Summaries of the three newest only. Every entry is written **verbatim** to `docs/CHANGELOG.md` when it happens — grep there for the full detail.

- **Generic chat notifications (Slack + Telegram), per user** — users pick their own destination and, independently per channel, which events they want reported. Two events: "New CrowdStrike report completed" and "New AWS account import completed". Publishers stay transport-agnostic (`ChatNotificationEvent` → `@Async` listener → `ChatNotificationService`), subscriptions are `(user, channel, event_type)` rows so a new event or channel needs no migration, and destinations fall back personal→workspace on both channels. CrowdStrike has no last batch, so `ImportCompletionNotifier` debounces ~94 sub-batches into one event after a quiet period. Security-critical: the per-user Slack webhook URL is host-allowlisted (the only user-supplied URL the backend fetches) and the Telegram bot token's shape is validated because it goes in the request URL path; all credentials encrypted, never returned, mask-preserved. New: `/chat-notifications`, `/admin/chat-config`, `V251__chat_notifications.sql`. **Backend is compile-unverified** — egress policy blocked Gradle/Maven Central in that session. Full detail below.
- **Test-coverage evaluation, repaired frontend test tier, `/testsuite` skill** — the frontend gate `npm ci && npm run build` was unrunnable (lockfile drifted from `package.json`), and 8 `*.test.ts` files existed with no npm script, no docs and 2 hard failures. Now `npm test` on Node's own runner (no framework dep) with a resolver hook in `src/frontend/test/`: **59 passing**. Found a real bug — `getPermissionErrorMessage('constructor')` returned an `Object.prototype` member instead of the fallback. New tests: `permissions` (UI half of Hard Principle #2), `cacheUtils`, `severityColors`, `ExcelSanitizerTest` (the export formula-injection control had zero), `AwsInstanceIdRecognitionTest` (two duplicated regexes must agree). New `./scripts/test-coverage-report.sh` (name-reference, **not** line coverage; controller/mcp-tools understated) and `/testsuite` skill for the whole fast tier. Fixed a permanent false positive in `check-skill-sync.sh`. Full detail below.
- **Documentation correctness sweep (living docs)** — repo-wide review of root docs, `docs/`, `.claude`/`.agents` skills+commands, and `src/`/`scripts/`/`tests/`/`testdata/` READMEs (`specs/` excluded as frozen history). Fixed stale version banners (build files are ground truth, not the docs), Java 21→25 leftovers, docs telling readers to bypass the canonical dev-start scripts, two CLI docs whose examples used a deprecated/non-functional auth flag, a fabricated CLI config schema, and several `.claude`↔`.agents` skill-mirror drifts. Full detail below.
