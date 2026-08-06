# Architecture

## System

```
                              Internet
                                  │
                          [nginx :80/:443]
                                  │
        /api/*  ───────► [Backend :8080]  ◄────── /oauth/*
                                  │
                                  ▼
                          [MariaDB :3306]
                                  ▲
                                  │
        /*       ───────► [Frontend :4321 SSR (Astro/React)]

        [CLI] ─── HTTPS ──► Backend REST API
```

Stack: Kotlin 2.4.10 / Java 25 · Micronaut 5.0 · Hibernate JPA · Astro 7.2 + React 19 · Bootstrap 5.3 · MariaDB 11.4 · Gradle 9.6.1 · Picocli 4.7.7 · AWS SDK v2.

## Backend (`src/backendng/`)

Layered:

```
Controller (80)        @Controller, @Secured, validation
   │
Service (146)          @Singleton, @Transactional, business rules
   │
Repository             CrudRepository / custom JPQL
   │
Domain                 @Entity, value classes, enums
```

Packages: `com.secman.{domain, repository, service, controller, config, dto, filter, mcp}`.

Controller groups:
- **Core**: Asset, AssetCompliance, Requirement, Release, ReleaseComparison, Workgroup, Product, Standard, Norm, NormMapping, UseCase
- **Vulnerability**: VulnerabilityManagement, VulnerabilityExceptionRequest, VulnerabilityStatistics, VulnerabilityConfig, VulnerabilityHeatmap, VulnerabilityMaintenance, ExternalHeatmap, AccountVulns, DomainVulns, WorkgroupVulns
- **Auth**: Auth, OAuth, Passkey, UserProfile
- **Admin**: AppSettings, IdentityProvider, MaintenanceBanner, UserMapping, User, TranslationConfig, NotificationSettings, EmailConfig, EmailProviderConfig, FalconConfig, ConfigBundle, AwsAccountSharing
- **Import/Export**: Import, RequirementFile, PublicRequirementDownload, Scan
- **Email**: Notification, NotificationPreference, NotificationLog, TestEmailAccount
- **MCP**: Mcp, McpAdmin, McpStreamableHttp
- **Other**: Cli, Health, Memory, Alignment, Response, Risk, RiskAssessment, Demand, DemandClassification, CrowdStrike, CveLookup, OutdatedAsset, MaterializedViewRefresh, Report

## Frontend (`src/frontend/`)

Astro pages (77, 23 admin) with React islands; Axios services in `src/services/`.

Auth flow: the JWT lives in the HttpOnly `secman_auth` cookie (`AuthCookieService.AUTH_COOKIE_NAME`), never in JS-readable storage. Axios sends it via `withCredentials: true` (set globally in `utils/csrf.ts`); fetch calls use the `authenticated*` helpers in `utils/auth.ts` / `services/`. `sessionStorage["user"]` holds only the display/role payload — never a token. SSE endpoints take JWT in `?token=` query (EventSource has no header support).

## CLI (`src/cli/`)

Picocli. ~40 command classes under `commands/`, ~37 distinct command names. **`docs/CLI.md` is the reference** — do not maintain a command list here, it drifts.

Dispatch is a mix, which surprises people: most commands are registered as Picocli `subcommands` on `SecmanCli`, but a few (notably `query servers`, the CrowdStrike ingestion path) are matched by hand in `SecmanCli.kt`'s arg-parsing block. Grep `SecmanCli.kt` for a command name before concluding it does not exist.

## Data model

Core entities:

```
User ──┬── Workgroup ──┬── Asset ──┬── Vulnerability
       │               │           ├── ScanResult
       │               │           └── manualCreator/scanUploader (User)
       │
       └── UserMapping (AWS / domain)
       └── AwsAccountSharing (User → User, directional)

Requirement ── Norm (M:N)  ── Release (snapshots)
```

Relationship cheatsheet:

| Relation | Type | Note |
|---|---|---|
| User ↔ Workgroup | M:N | |
| Asset ↔ Workgroup | M:N | |
| Asset → Vulnerability | 1:N | **no JPA cascade** (see CrowdStrike pattern) |
| Asset → ScanResult | 1:N | |
| User → Asset (manualCreator/scanUploader) | 1:N | drives access control |
| Requirement ↔ Norm | M:N | |
| Workgroup → Workgroup | self-ref | nested hierarchies |
| User → User (AWS sharing) | M:N | directional, non-transitive |

Tables (selected):
```
users, user_roles, user_workgroups, user_mappings
assets, asset_groups, asset_workgroups
vulnerabilities, vulnerability_exceptions, vulnerability_exception_requests
scan_uploads, scan_results
requirements, norms, requirement_norm, releases, requirement_snapshots
risks, risk_assessments, demands, demand_classifications, standards, products, use_cases
mcp_api_keys, mcp_sessions, mcp_audit_logs, mcp_tool_permissions
email_configs, notification_logs, notification_preferences
aws_account_sharing, identity_providers, oauth_states, maintenance_banners, app_settings
```

## Access control

Roles: `USER`, `ADMIN`, `VULN`, `RELEASE_MANAGER`, `REQ`, `REQADMIN`, `RISK`, `SECCHAMPION`, `REPORT`.

Asset access (any of):
1. ADMIN **or SECCHAMPION** role (universal access)
2. Asset in user's workgroup
3. `manualCreator == user`
4. `scanUploader == user`
5. `cloudAccountId` ∈ user's AWS UserMapping
6. `adDomain` ∈ user's domain UserMapping (case-insensitive)
7. `owner == username`
8. `cloudAccountId` ∈ AwsAccountSharing rule (directional, non-transitive; per-rule account selection — empty selection = share all of source's accounts)
9. `cloudAccountId` ∈ workgroup's `WorkgroupAwsAccount` (direct membership only)
10. `adDomain` ∈ workgroup's `WorkgroupAdDomain` (direct membership only)

Authoritative implementation: `AssetFilterService.getAccessibleAssets()` — the numbered list above is its contract, kept in sync with the Javadoc on that method. Do not re-derive the predicate elsewhere; SQL pre-filters in materialized views are performance hints, never the auth boundary.

One asymmetry is deliberate and easy to miss: `getAccessibleAssets()` and `getAccessibleAssetIds()` short-circuit for **ADMIN or SECCHAMPION**, but `getScopedAccessibleAssetIds()` short-circuits for **ADMIN only** — a SECCHAMPION falls through to the scoped path there. Check which one you are calling before assuming a role sees everything.

Feature 073: when `memoryConfig.lazyLoadingEnabled` is set, the scoped path runs as one unified query instead of the multi-query fallback. Both must implement the same ten rules.

Authentication methods:
| Method | Carrier | Use |
|---|---|---|
| JWT | HttpOnly `secman_auth` cookie (`Authorization: Bearer …` for CLI/external clients) | frontend API |
| OAuth2/OIDC | session + JWT | SSO (Azure AD, Google) |
| Passkey | WebAuthn credential | passwordless |
| MCP API key | `X-MCP-API-Key` header | AI assistants |

## API conventions

REST. Public endpoints: `POST /api/auth/login`, `GET /api/identity-providers/enabled`, `GET /api/maintenance-banners/active`, `GET /oauth/*`, `GET /health`, `POST /mcp` (key-auth).

Pagination response shape:
```json
{ "content":[…], "totalElements":1234, "totalPages":13, "page":0, "size":100 }
```

Error shape:
```json
{ "error":"VALIDATION_ERROR", "message":"…", "details":["Field 'name' is required"] }
```

## Patterns

### Entity id generation — declare the strategy, never rely on `AUTO`

```kotlin
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)   // column is AUTO_INCREMENT
var id: Long? = null
```

A bare `@GeneratedValue` means `AUTO`, and on MariaDB Hibernate 6 resolves that to a
**native sequence** (`<table>_seq`) — *not* the column's `AUTO_INCREMENT`. Where the
table was created with `AUTO_INCREMENT`, the two number the same column independently:
the sequence starts at 1, knows nothing about rows the database numbered, and inserts
fail with

```
Duplicate entry '<n>' for key 'PRIMARY'
```

once it reaches an occupied id. Each failed attempt advances the sequence, so the
symptom looks intermittent — a retry "fixes" it until the next collision. Bumping the
sequence is not a fix; it drifts again on the next DB-side insert.

**Rule:** an entity whose id column is `AUTO_INCREMENT` must declare
`GenerationType.IDENTITY`. Check before adding an entity:

```sql
SELECT table_name, extra FROM information_schema.columns
WHERE table_schema = DATABASE() AND column_name = 'id';
```

Eight entities are legitimately sequence-backed (`alignment_*`, `demand`,
`demand_classification_*`, `passkey_credentials`, `requirement_review`) — their columns
are plain `BIGINT`, so they keep the bare `@GeneratedValue` and are internally
consistent. Everything else is `IDENTITY`.

### Event-driven
```kotlin
@Serdeable data class UserCreatedEvent(val user: User, val source: String)
eventPublisher.publishEvent(UserCreatedEvent(saved, "MANUAL"))

@EventListener @Async
open fun onUserCreated(e: UserCreatedEvent) { applyFutureUserMapping(e.user) }
```
Used: user create → mapping apply, asset import → view refresh, vuln detect → email.

### Transactional replace (CrowdStrike vuln import)
```kotlin
@Transactional
open fun importVulnerabilitiesForServer(batch: …): Result {
    val (asset, _) = findOrCreateAsset(batch)
    vulnerabilityRepository.deleteByAssetId(asset.id!!)
    vulnerabilityRepository.saveAll(vulnerabilities)
}
```
Idempotent; missing CVEs in next import = remediation. **`Asset.vulnerabilities` MUST NOT use `cascade=ALL` / `orphanRemoval=true`** — JPA cascade fights manual delete-insert and silently drops 99% of rows. Use explicit `deleteByAssetId()`. Full rationale and incident detail: `docs/CROWDSTRIKE_IMPORT.md`.

### Entity merge (asset import)
```kotlin
fun findOrCreateAsset(dto: AssetDto): Asset =
    assetRepository.findByName(dto.name)?.apply {
        ip = dto.ip ?: ip
        groups = (groups + dto.groups).distinct()
    } ?: Asset(name = dto.name, …)
```

### CSV/Excel import
Validate (≤10MB, MIME, ext) → parse (Apache POI / Commons CSV; UTF-8 BOM, ISO-8859-1 fallback) → header check (case-insensitive) → row parse (skip invalid, handle scientific notation) → dedupe (DB + file) → batch save → return `ImportResult{imported, skipped, errors[]}`.

### OAuth state retry
Exponential backoff in `OAuthService.findStateByValueWithRetry` to tolerate Microsoft cached-SSO callbacks landing before the state-save commits (they can arrive in 100–500ms). Configured by `oauthConfig.stateRetry` in `application.yml`, tunable via `OAUTH_STATE_RETRY_*` env vars (`docs/ENVIRONMENT.md`).

## File layout

```
src/
  backendng/src/main/kotlin/com/secman/{domain,repository,service,controller,config,dto,filter,mcp}/
  frontend/src/{pages,components,services,layouts}/
  cli/src/main/kotlin/com/secman/cli/{commands,service}/
docs/
scripts/             all scripts (./scripts deprecated)
specs/                historical implementation plans
```
