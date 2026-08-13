# Claude Code Agent Context

Security requirement, vulnerability and risk management platform.

## Stack

- Backend: Kotlin 2.4.10 / Java 25, Micronaut 5.1, Hibernate JPA → `src/backendng/`
- Frontend: Astro 7.2 + React 19 islands, Axios, JWT in the HttpOnly `secman_auth` cookie → `src/frontend/`
- CLI: Kotlin + Picocli 4.7.7, AWS SDK v2 → `src/cli/`
- Mobile relay: Go 1.24, **zero third-party dependencies** (stdlib only, incl. its own RFC 8555 ACME client) → `src/relay/`
- DB: MariaDB 11.4, Flyway + Hibernate auto-migration
- Build: Gradle 9.7.0 (Kotlin DSL)
- MCP: Streamable HTTP / JSON-RPC 2.0. `X-MCP-User-Email` header is **mandatory** on `tools/list` and `tools/call` (only `initialize` and `ping` exempt).

## Roles (RBAC)

`USER`, `ADMIN`, `VULN`, `RELEASE_MANAGER`, `REQ`, `REQADMIN`, `RISK`, `SECCHAMPION`, `REPORT`.

## Tooling Conventions (canonical, do not deviate)

- **Scripts**: `./scripts/` only.
- **Skills — two harness trees, one skill set**: every skill exists twice, once per agent harness. `.claude/skills/` is what **Claude Code** loads; `.agents/skills/` is what **Codex** (and other `AGENTS.md`-driven agents) load. They are two renderings of the same skill, not two skills.
  - **Two-way sync is mandatory.** *Whichever* tree an agent edits — Claude Code editing `.claude/skills/`, Codex editing `.agents/skills/` — the same change must land in the counterpart file **in the same commit**. There is no "port it later"; a commit that touches one tree only is incomplete. This applies to every `*.md` under the trees (`SKILL.md`, `_shared/`, `references/`), not just `SKILL.md`.
  - **Translate, don't copy.** Harness-specific mechanics get their equivalent on the other side rather than a verbatim paste: Bash tool `dangerouslyDisableSandbox: true` ↔ `sandbox_permissions: "require_escalated"`; `AskUserQuestion` ↔ "ask the user directly"; `.claude/skills/…` ↔ `.agents/skills/…` paths. Everything else — steps, commands, thresholds, credentials handling — must be word-for-word identical.
  - **`.claude/skills/` is the tie-breaker, not the only writer.** When the two copies already disagree and neither is obviously newer, `.claude/skills/` wins. That is a conflict rule for resolving existing drift; it does not make a Codex-side edit second-class, and it never licenses leaving the other tree stale. Both copies have been the correct one historically.
  - **New skill → create both.** Adding a skill means adding it to `.claude/skills/` *and* `.agents/skills/` in the same change. Deleting means deleting both. If you *discover* a pre-existing entry that exists in only one tree, report it — do not silently synthesize the missing side, since you cannot know whether the omission was deliberate.
  - **Gate**: `./scripts/check-skill-sync.sh` must exit 0 before any skill change is complete (`--verbose` shows the differing lines). It is report-only and never edits either tree. `/testsuite` runs it as part of the fast tier.
  - Each skill file carries a `> **Sync policy (two-way, mandatory)**` banner naming its counterpart, so the rule is visible to whichever agent opens the file. The sync checker strips the banner before diffing, so the two banners may differ in wording.
  - When asked to update "skills" with no harness named, edit `.claude/skills/` first, then port to `.agents/skills/` — both in that one change.
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
| Export Templates | `GET /api/requirement-export-templates[/latest]` (auth); `GET .../example`, `POST /api/requirement-export-templates`, `POST .../validate`, `GET .../{id}[/download,/usage]`, `POST .../{id}/{activate,deactivate}`, `DELETE .../{id}` (ADMIN/REQADMIN). Company Word template for requirement exports — `docs/REQUIREMENT_EXPORT_TEMPLATES.md` | mixed |
| CrowdStrike | `POST/GET /api/crowdstrike/{servers/import,vulnerabilities/save,vulnerabilities}` (ADMIN/VULN); `GET /api/crowdstrike/last-checkin` (PUBLIC, `text/plain` ISO-8601 or `"never"`) | mixed |
| User Mappings | `GET /api/user-mappings/{current,applied-history}`, `POST/PUT/DELETE /api/user-mappings[/{id}]` | ADMIN |
| AWS Sharing | `GET/POST /api/aws-account-sharing`, `DELETE .../{id}` | ADMIN |
| Heatmap | `GET /api/vulnerability-heatmap`; `POST .../refresh` (ADMIN); `GET /api/external/vulnerability-heatmap` (API-key, CORS) | mixed |
| EOL | `GET /api/eol/{findings,summary,assets/{id},catalog/status}` (auth, asset-scoped); `GET /api/eol/repositories/top` (ADMIN/SECCHAMPION); `POST /api/eol/{catalog/sync,notifications/send}` (ADMIN). Source is operator config (`secman.eol.base-url` + `allowed-hosts`, a pair) | mixed |
| Identity Providers | `GET/POST/PUT/DELETE /api/identity-providers[/{id}[/test]]` | ADMIN |
| Maintenance Banners | `GET /api/maintenance-banners/active` (PUBLIC); `GET/POST/PUT/DELETE /api/maintenance-banners[/{id}]` (ADMIN) | mixed |
| User Profile | `GET /api/users/profile`, `PUT .../change-password` (LOCAL only), `GET/PUT .../mfa-{status,toggle}`; avatar `GET/POST/DELETE /api/users/profile/picture` (own only, no user id in the route) | auth |
| User Dashboard | `GET /api/user-dashboard` (aggregated personal todos, single round-trip) | auth |
| Security KPIs | `GET /api/dashboard/{aws-clean-server-kpi, edr-coverage-kpi}` — precomputed cache reads only, never live queries | ADMIN/SECCHAMPION |
| Notifications | `GET/PUT /api/notification-preferences`; `GET /api/notification-logs`; `.../export` (ADMIN) | mixed |
| Chat Notifications | `GET /api/notification-events` (event catalogue); `GET/PUT /api/slack/settings`, `POST .../test`; `GET/PUT /api/telegram/settings`, `POST .../test` (self-scoped); `GET/PUT /api/slack/config`, `POST .../test`, `GET/PUT /api/telegram/config` (ADMIN) | mixed |
| CLI | `POST /api/vulnerabilities/cli-add` (ADMIN/VULN; auto-creates asset) | ADMIN/VULN |
| Account Onboarding | `GET/POST/PUT/DELETE /api/account-onboarding/questions[/{id}[/choices[/{cid}]]]`, `.../rules[/{id}]`, `GET .../rules/{coverage,matrix}`, `POST .../rules/preview`, `POST .../simulate` (ADMIN/SECCHAMPION); public single-use token `GET/POST /api/public/account-onboarding/{token}` | ADMIN/SECCHAMPION + public |
| Mobile Relay | `GET /api/relay/{status,sections,devices,identities}`, `POST /api/relay/{publish,enrollments,revocations,identities,principals/publish}`, `DELETE /api/relay/identities/{id}` | ADMIN |

MCP tool families mirror these (delegation required): `list_/create_/delete_release`, `set_release_status`, `compare_releases`; `list_workgroup_aws_accounts`, `add_/remove_workgroup_aws_account`, `list_workgroup_ad_domains`, `add_/remove_workgroup_ad_domain`; `list_/create_/delete_aws_account_sharing`; `get_vulnerability_heatmap`, `refresh_vulnerability_heatmap`; `simulate_account_onboarding`, `list_/preview_account_onboarding_rules`; etc. See `docs/MCP.md`.

## Commands

```bash
./gradlew build                                          # backend build + tests
./scripts/startbackenddev.sh                             # canonical dev start — outside any sandbox
./scripts/startfrontenddev.sh                            # canonical dev start, port 4321 — outside any sandbox
./gradlew :cli:shadowJar                                 # build the CLI once
./scripts/secman <command>                               # query servers, send-notifications, manage-user-mappings, eol-sync, ...
./gradlew :backendng:test --tests "*ServiceTest*"        # unit
./gradlew :backendng:test --tests "*IntegrationTest*"    # integration (external MariaDB, see Test Infrastructure)
./gradlew :cli:test
cd src/frontend && npm test                              # frontend unit tier (node:test, no framework dep)
./scripts/test-coverage-report.sh                        # name-reference coverage per area (NOT line coverage)
./scripts/owasp-check.sh                                 # static OWASP gate, diff-scoped (--all audits, --verbose shows lines)
./scripts/test/owasp-check-test.sh                       # self-test for the gate above (fires-when-vulnerable, silent-when-correct)
./tests/e2e/run-e2e.sh                                   # Playwright with pass-cli secrets
```

`/testsuite` runs every tier above plus the frontend build gate, `check-skill-sync.sh`,
the OWASP gate self-test and the coverage report in one pass. It never starts the
stack, so it does not discharge principle 5 or the principle-7 gates.

CrowdStrike monitoring: `src/clinotify/check_crowdstrike_checkin.py` polls `/api/crowdstrike/last-checkin` and Telegrams when stale. Stdlib-only.

## Hard Principles

1. Security-first: file validation, input sanitization, RBAC. **All generated or edited code must satisfy §OWASP Top 10 Compliance below** — that checklist is binding, not advisory. Security review before completion.
2. RBAC enforced at controller (`@Secured`) AND in UI.
3. Schema = Flyway migrations + Hibernate auto-update. **New entities must declare
   `@GeneratedValue(strategy = GenerationType.IDENTITY)`** — a bare `@GeneratedValue`
   resolves to a native `<table>_seq` that fights an `AUTO_INCREMENT` column and yields
   intermittent `Duplicate entry '<n>' for key 'PRIMARY'`. See `docs/ARCHITECTURE.md`
   §Entity id generation.
4. Always write tests. Source of truth for credentials and URLs is `pass-cli`.
5. **A change is complete only when** `./gradlew build` is clean **AND** `./scripts/startbackenddev.sh` starts cleanly. Compile-clean ≠ runtime-clean (Micronaut bean wiring, Flyway, SessionFactory only check at startup). Stop the backend after verifying.
5a-relay. **Relay changes (`src/relay/`) are complete only when** `cd src/relay && go build ./... && go vet ./... && gofmt -l . && go test ./...` are all clean. It is a separate Go module: `./gradlew build` does not compile it and will not catch a break. It has **no third-party dependencies by contract** — adding one needs a stated reason in the PR body, the same bar as `src/clinotify`.
5a. **Frontend changes are complete only when** `cd src/frontend && npm ci && npm run build` exits 0. TypeScript errors, missing imports, and broken Astro/React components are caught here — do not skip this step for any frontend file edit.
5b. **Backend contract changes are complete only when the `extensions/` clients are updated in the same change.** Renaming a path, renaming or retyping a request/response field, tightening `@Secured`, or altering the auth scheme breaks those clients **silently** — nothing in this build compiles or tests against them, so the failure surfaces in production, not in CI. See **Extension Clients** for the surface and the verification rules. `/finalizer` automates the check.
6. Tests route HTTP through `SECMAN_HOST` (from `pass-cli`). No hardcoded localhost URLs.
7. **Mandatory post-change E2E gates** (in addition to build + startup):
   - **`/e2ejs`** must report **0 JS errors** for both admin and normal-user runs against `SECMAN_HOST`. RBAC 403s on role-gated endpoints and documented 404s (e.g., `/api/wg-vulns`, `/api/domain-vulns` for users without mappings) are NOT JS errors. A page that throws or logs `console.error` IS — fix before merge.
   - **`/e2evulnexception`** must run the full vuln + exception lifecycle (MCP + UI, setup + teardown) with **0 failures**.

   Doc-only edits outside `src/`, `tests/`, `scripts/` may skip the gates — state so explicitly. Otherwise both gates are non-negotiable.

## OWASP Top 10 Compliance (mandatory)

Hard Principle 1 in concrete terms. **Do not generate code that violates any rule below.** Categories are pinned to **OWASP Top 10:2021 (A01–A10)**; if the list is revised upstream, revisit this section rather than silently re-mapping names.

Each rule names the control that already exists in this repo. **Reuse it — never write a second one**, and never work around one to make something build, start or pass.

**A01 Broken Access Control**
- Every endpoint carries `@Secured`. A public endpoint is an explicit, justified exception (`GET /api/crowdstrike/last-checkin`, `GET /api/maintenance-banners/active`), never a default and never "for now".
- An id in a request is untrusted input. Resolve assets through `AssetFilterService.getAccessibleAssets()` / `getAccessibleAssetIds()` / `canAccessAsset(assetId, authentication)` — never `findById(userSuppliedId)` and return it. Same for any other owner-scoped entity.
- SQL pre-filters in materialized views and native queries are perf hints, **never the auth boundary** (restated from §Unified Asset Access — it is the single most repeated bug class here).
- A new MCP tool needs entries in **both** `McpToolPermissions.LISTING` and `.CALLING` plus a `McpToolGuards` check. A missing `CALLING` entry fails closed and looks like a bug; a missing guard fails **open** and looks like nothing.
- RBAC at the controller **and** in the UI (Principle 2). The UI check is UX; the controller check is the boundary. Never only the former.

**A02 Cryptographic Failures**
- Passwords and API-key secrets: `BCryptPasswordEncoder` only. Never SHA-256/MD5/hand-rolled hashing for a secret — the SHA-256 API-key path in `McpAuthenticationService` exists solely to migrate legacy keys, do not extend or imitate it.
- The JWT lives in the HttpOnly `secman_auth` cookie. Never write a token to `localStorage`, `sessionStorage`, a non-HttpOnly cookie, or any value that lands in a log. The SSE `?token=` query param is the one documented exception (EventSource has no headers).
- Secrets come from `pass-cli`/env. No literal credential, key, token or internal host in source, tests, scripts or fixtures.
- `JwtSigningValidator`, `DatabaseCredentialValidator` and `DatasourceUrlValidator` fail the boot on weak config **by design** — never relax or bypass one to make the backend start.

**A03 Injection**
- Persistence: derived queries or bound parameters (`:name`) only. **Never** concatenate a value into a query string — this includes the ~30 `nativeQuery = true` methods in `VulnerabilityRepository`, `WorkgroupRepository`, `AwsAccountSharingRepository`. Things that cannot be bound (column name, sort direction, table) map through a closed allowlist/enum; a request value never reaches SQL unbound.
- HTML: no `innerHTML` / `dangerouslySetInnerHTML` without `DOMPurify.sanitize(...)` **at the assignment site** — see `RichContent.tsx` and `HtmlEditor.tsx`. Sanitizing on write is not sufficient: stored rows predate the control.
- Excel/CSV export: every user-controlled cell goes through `ExcelSanitizer.sanitize()` (formula/DDE injection). No E2E gate ever opens an exported file, so a regression here is invisible at runtime and only `ExcelSanitizerTest` will catch it.
- OS: no user-controlled string interpolated into a shell command, from Kotlin or from `./scripts/`. Pass argv arrays; quote every variable in bash.
- Strip or encode CR/LF from user input before it reaches a log line (log forging).

**A04 Insecure Design**
- Deny by default: a new endpoint or MCP tool starts from the narrowest role that works and is widened deliberately — not `IS_AUTHENTICATED` with a TODO.
- Unbounded is a design bug: page at the query (`findByAssetIdIn(ids, pageable)`), never `findAll()` then filter/slice in Kotlin. That exact pattern OOM'd `get_vulnerabilities` on 1.1M rows.
- Business invariants (release status transitions, exception `kind`/subject/scope validity, ownership, workgroup membership) are enforced server-side. A rule that exists only in the UI is not a rule.

**A05 Security Misconfiguration**
- Do not weaken `SecurityHeadersFilter` — CSP, HSTS, `X-Frame-Options: DENY`, COOP/COEP/CORP, permissions policy. If a feature "requires" `unsafe-eval` or a wildcard `connect-src`, change the feature.
- CORS: explicit origin allowlist. Never `*` combined with credentials.
- Error responses carry a generic message; the detail goes to the server log. Never return a stack trace, SQL string, internal path or driver message to a client (`ValidationExceptionHandler` is the pattern).
- No debug endpoint, verbose-logging toggle or seeded default credential enabled outside the `test` profile.

**A06 Vulnerable and Outdated Components**
- Prefer the stdlib and dependencies already on the classpath. A new third-party dependency needs a stated reason and must be called out in the PR body.
- Pin exact versions in the Gradle/npm manifests — no floating ranges, no `latest`. `package-lock.json` must stay in step with `package.json` (`npm ci` is the gate).
- `src/clinotify` is **stdlib-only by contract**; adding a dependency there breaks its deployment.

**A07 Identification and Authentication Failures**
- Authenticate through `AuthenticationProviderUserPassword`, `OAuthService` or `McpAuthenticationService`. Never a bespoke auth path, and never a header that grants access on its own: `X-MCP-User-Email` *identifies* a delegated user, it is not a credential and must always sit behind a verified API key.
- Login, password-reset and lookup errors must not disclose whether an account exists.
- Password change stays LOCAL-account-only; MFA state stays server-enforced.
- Never lengthen a token/session lifetime, or loosen a cookie's `HttpOnly`/`Secure`/`SameSite`, to fix a UX or test problem.

**A08 Software and Data Integrity Failures**
- Every upload validates **size, extension and content type before parsing**, and rejects empty files — `ImportController.validateFile` is the reference; keep `MAX_FILE_SIZE` aligned with `application.yml`.
- XML parsing keeps DTDs and external entities **disabled** (XXE). `NmapParserService`/`MasscanParserService` set `disallow-doctype-decl`, `external-general-entities`, `external-parameter-entities` and `load-external-dtd` — copy that block into any new XML parser; never construct a bare `DocumentBuilderFactory`.
- Never deserialize untrusted input into a polymorphic or arbitrary type — parse into an explicit DTO.
- Never fetch code, config or a template from a remote source at runtime and execute or eval it.
- Archive handling: reject entry paths containing `..` and bound the decompressed size (zip bomb).

**A09 Security Logging and Monitoring Failures**
- Log authentication failures, RBAC denials, admin actions, imports and exports with **actor + target + outcome**.
- Never log a password, token, cookie value, API key, or the body of an auth request. `logger.debug` counts — it runs in dev, where real `pass-cli` secrets are loaded.
- No silent `catch (e: Exception) { }`. A swallowed security-relevant failure is itself a monitoring failure.

**A10 Server-Side Request Forgery (SSRF)**
- Any outbound URL derived from user input or DB-stored config — identity provider endpoints and JWKS, GitHub App, CrowdStrike, S3 endpoints, notification webhooks — is validated before use: `https` scheme allowlist, plus host allowlist or explicit rejection of loopback, link-local and RFC-1918 ranges and cloud metadata (`169.254.169.254`).
- Re-apply the same check to redirect targets; never follow a redirect into a range the original request would have been denied.
- `McpOriginValidationFilter` is the inbound analogue — do not disable it.

### Review gate

Before reporting any code change complete, run `./scripts/owasp-check.sh` (diff-scoped, includes untracked files) and re-read the diff against A01–A10, then state the result in one line, e.g. `OWASP: A01/A03/A09 touched — clean`. A `BLOCK` finding means the change is not complete; a `REVIEW` finding needs a stated decision, not silence. Changes to authentication, authorization, crypto, file upload, export or any outbound HTTP call additionally run `/security-review` on the branch diff (`/finalizer` includes a HIGH/CRITICAL pass). **A finding at HIGH or above blocks the change** — fix it, do not merely note it.

The scanner covers only the mechanical half. Use **`/secure-code`** *before* writing an endpoint, query, MCP tool, upload, export, HTML sink or outbound call: it routes the change to the categories it can actually violate, names the existing control to reuse, and carries the semantic checklist the scanner cannot express.

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

`src/backendng/…/com/secman/{domain,controller,service,repository,config,dto,filter,mcp,relay}/` · `src/frontend/src/{pages,components,services,layouts}/` · `src/cli/…/com/secman/cli/{commands,service}/` · `src/relay/{cmd,internal,deploy}/` (Go). `application.yml` + `email-templates/` under `src/backendng/src/main/resources/`. Env: `docs/ENVIRONMENT.md`. Full tree: `docs/ARCHITECTURE.md` §File layout.

## Extension Clients (`extensions/`)

`secman_ai_github` (GitHub security scanner) and `secman_visual_check` (external attack-surface scanner): independent Python repos with their own remotes, **gitignored here** — root `git status` never shows them and no build or test here covers them.

`secman_app_ios` (iOS/iPadOS status app, Swift) is a **relay client, not a backend client**: it never calls `/api/…` and holds no secman credential. A change to a secman endpoint cannot break it. What *can* is the relay contract — `com.secman.relay.RelayDtos`, the section names and `SECTION_POLICIES` in `RelaySnapshotBuilder`, or `src/relay/internal/api`. Both envelopes carry a `schemaVersion` for that reason; bump it on a breaking change and update `relaySupportedSnapshotSchemaVersion` in the app. Sweep its surface with `grep -rnE '/api/v1/|/ingest/v1/' extensions/secman_app_ios --include='*.swift'`.

Always rediscover the surface; a written list means a newly added call gets checked by nobody:
```bash
grep -rnE '/api/|"/mcp"|X-MCP-User-Email' extensions --include='*.py' --exclude-dir=.venv
```
As of 2026-08-01: `POST /api/auth/login`, `POST /api/vulnerabilities/cli-add`, `GET /api/vulnerabilities/current`, `PUT /api/assets/import`, MCP `/mcp` (`X-MCP-API-Key` + `X-MCP-User-Email`; `get_vulnerabilities`, `add_vulnerability`, `create_asset`).

When you change any of those endpoints, verify all five dimensions against the client: **path, HTTP method, request field names, response fields the client reads, and `@Secured` roles / required headers**. Field names matter most — Jackson drops unknown keys without error, so a rename makes the client "succeed" while sending nothing. Update the client's `tests/` too; a test asserting the old shape is drift.

Fix and commit **inside the extension repo, path-scoped** (`git -C extensions/<repo> commit -m "…" -- <files>`) so pre-existing dirty files are not swallowed. Never `git add -A` / `commit -a`, and **never push** — those repos have their own review. An `@Secured` tightening is a *report*, not a fix: you cannot see which roles the client's service account holds.

## E2E Runner

Triggered by `/e2eexception`, `/admin-asset-e2e`, `/e2ejs`, `/e2evulnexception`, `/e2eeol`, `/account-onboarding`, `/importtest`, `/crowdstrike-vuln-match` skills.

- **Cold start is mandatory**: every skill that touches the running stack assumes backend and frontend must be started by the skill itself. It always begins by killing any running backend/frontend via `./scripts/stopbackenddev.sh` / `./scripts/stopfrontenddev.sh` (unconditional — even if ports look free; the scripts are safe no-ops), then starts both fresh via the canonical start scripts. Never reuse an already-running instance; never assume services are up.
- Backend changes (Kotlin/Java) → restart required.
- Frontend changes → Vite hot-reload (no restart).
- Config (`astro.config.mjs`, `application.yml`) → restart.
- Logs: `.e2e-logs/` (gitignored). Max 5 fix iterations.
- **Liveness check is port-bind**, not HTTP: `lsof -iTCP:8080 -sTCP:LISTEN -n -P` (120s budget) and `:4321` (60s budget).
- **Functional checks** still go through `SECMAN_HOST` from `pass-cli`. Never `curl localhost`.

---

*Last updated: 2026-08-13*

## Recent Changes

Summaries of the three newest only. Every entry is written **verbatim** to `docs/CHANGELOG.md` when it happens — grep there for the full detail.

- **EOL matching for applications and frameworks** — the EOL scan now matches Java's nine vendor distributions (`oracle-jdk`, `amazon-corretto`, `eclipse-temurin`, …; endoflife.date publishes no generic `java`) and the .NET family, plus four false-positive rules: legacy `1.8.0.x` rewrites to `8.0.x` (Java only), labelled cycles are skipped (`isNumericCycle` — `10-1507` was matching Windows Server 2022 and produced 42 % of all findings), and distro packaging revisions (`-6ubuntu14.4`, `~esm1`) or maintainer vendors are rejected because the distro owns the support contract. `ASSET_PRODUCT` findings need `installed_product`, which is fed by `secman installed-products` (CrowdStrike Discover), **not** by the Spotlight vulnerability import. Live estate 304 → 4,492 findings. Tests EVM-015..023.
- **Company Word template for requirement exports** — `GET/POST /api/requirement-export-templates[…]` plus `/admin/requirement-export-templates`; an example template is seeded ACTIVE on startup **only into an empty table**. `${requirements}` is a real insertion point (content is inserted in front of the marker via `XmlCursor`, and the marker is removed by indexing `document.bodyElements`, not `getPosOfParagraph`); release metadata (`${releaseName}`, `${releaseVersion}`, `${releaseDate}`, `${releaseStatus}`) comes from the `Release` entity and resolves to `""` for a live export. `GET /api/requirements/export/{docx,xlsx}` stay `IS_ANONYMOUS` by explicit decision. See `docs/REQUIREMENT_EXPORT_TEMPLATES.md`.
- **AWS account onboarding: welcome mail, direct and guided risk assessments** — three modes (`WELCOME_ONLY`, `DIRECT`, `GUIDED`) on the CLI (`--onboarding-mode`, `--welcome-email`, `--questionnaire-expiry-days`), REST and MCP, all dry-runnable, plus `simulate-onboarding` / `simulate_account_onboarding`. `startRiskAssessment=true` alone still resolves to `DIRECT` with no welcome mail. GUIDED mails a one-time 256-bit `SecureRandom` link (`/onboarding/[token]`, `@Secured(IS_ANONYMOUS)`, 14-day expiry, claimed by a guarded UPDATE **before** the assessment is created, byte-identical 404 for every failure so it is no enumeration oracle); every matching active rule contributes and the assessment is scoped to the **union**, `isDefault` is consulted last, and nothing is ever auto-created. Admin API/UI is ADMIN **or SECCHAMPION**. See `docs/ACCOUNT_ONBOARDING.md`.
