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

Stack: Kotlin 2.3.21 / Java 21 · Micronaut 4.10 · Hibernate JPA · Astro 6.3 + React 19 · Bootstrap 5.3 · MariaDB 11.4 · Gradle 9.5.0 · Picocli 4.7.7 · AWS SDK v2.

## Backend (`src/backendng/`)

Layered:

```
Controller (62)        @Controller, @Secured, validation
   │
Service (97)           @Singleton, @Transactional, business rules
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

Astro pages (69, 20 admin) with React islands; Axios services in `src/services/`.

Auth flow: JWT in `localStorage["authToken"]` → Axios interceptor adds `Authorization: Bearer …`. SSE endpoints take JWT in `?token=` query (EventSource has no header support).

## CLI (`src/cli/`)

Picocli, 25 commands. Highlights: `query servers`, `send-notifications`, `send-admin-summary`, `manage-user-mappings` (incl. `list --send-email`, `import-s3`, `download-s3`, `print-s3`), `manage-workgroups`, `add-vulnerability`, `add-aws`, `add-domain`, `add-requirement`, `export-requirements`, `import`, `import-s3`, `delete-all-requirements`, `deduplicate-vulnerabilities`, `port-scan`, `send-notification-users`, `config`, `monitor`, `list`, `list-workgroups`, `remove`. Full reference: `docs/CLI.md`.

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
1. ADMIN role
2. Asset in user's workgroup
3. `manualCreator == user`
4. `scanUploader == user`
5. `cloudAccountId` ∈ user's AWS UserMapping
6. `adDomain` ∈ user's domain UserMapping (case-insensitive)
7. `cloudAccountId` ∈ AwsAccountSharing rule (directional; per-rule account selection — empty selection = share all of source's accounts)
8. `owner == username`
9. `cloudAccountId` ∈ workgroup's `WorkgroupAwsAccount` (direct membership only)

```kotlin
fun canUserAccessAsset(user: User, asset: Asset): Boolean =
    user.roles.contains("ADMIN") ||
    assetInUserWorkgroups(asset, user) ||
    asset.manualCreator?.id == user.id ||
    asset.scanUploader?.id == user.id ||
    awsAccountMatches(asset, user) ||
    adDomainMatches(asset, user) ||
    sharedAwsAccountMatches(asset, user)
```

Authoritative implementation: `AssetFilterService.getAccessibleAssets()`.

Authentication methods:
| Method | Carrier | Use |
|---|---|---|
| JWT | `localStorage` + `Authorization` header | frontend API |
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
Exponential backoff in `OAuthService.findStateByValueWithRetry` to tolerate Microsoft cached-SSO callbacks landing before the state-save commits. Tunable via `OAUTH_STATE_RETRY_*` env vars (`docs/ENVIRONMENT.md`).

## File layout

```
src/
  backendng/src/main/kotlin/com/secman/{domain,repository,service,controller,config,dto,filter,mcp}/
  frontend/src/{pages,components,services,layouts}/
  cli/src/main/kotlin/com/secman/cli/{commands,service}/
docs/
scriptpp/             all scripts (./scripts deprecated)
specs/                historical implementation plans
```
