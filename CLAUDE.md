# Claude Code Agent Context

## Project Overview

**secman** - Security requirement and risk assessment management tool

**Stack**: Kotlin 2.2.21 / Java 21, Micronaut 4.10, Hibernate JPA | Astro 5.15, React 19, Bootstrap 5.3 | MariaDB 12, Gradle 9.2

**Architecture**:
- Backend: `src/backendng/` - Domain (JPA) → Repository → Service → Controller (REST)
- Frontend: `src/frontend/` - Astro + React islands, Axios, sessionStorage JWT
- CLI: `src/cli/` - CrowdStrike API queries, notification emails
- Security: JWT auth, OAuth2/OIDC, RBAC (USER, ADMIN, VULN, RELEASE_MANAGER, SECCHAMPION)

## Key Entities

### Asset
- **Core**: id, name, type, ip, owner, description, lastSeen
- **Metadata**: groups, cloudAccountId, cloudInstanceId, adDomain, osVersion
- **Relations**: vulnerabilities, scanResults, workgroups, manualCreator, scanUploader

### User
- **Fields**: username, email, passwordHash, roles, workgroups
- **Roles**: USER, ADMIN, VULN, RELEASE_MANAGER, SECCHAMPION

### Vulnerability
- **Fields**: asset(FK), vulnerabilityId(CVE), cvssSeverity, vulnerableProductVersions, daysOpen, scanTimestamp
- **Relations**: ManyToOne Asset (cascade delete)

### Workgroup
- **Fields**: name, description, users(ManyToMany), assets(ManyToMany), criticality, parent(FK self-ref)
- **Access**: ADMIN CRUD only
- **Features**: Nested hierarchy, criticality inheritance

### UserMapping (Feature 042 - Future Mappings)
- **Fields**: email, awsAccountId(12 digits), domain, ipAddress, user(FK nullable), appliedAt(nullable)
- **Import**: Excel/CSV with auto-delimiter detection
- **Future Mappings**: user=null, appliedAt=null (applied via @EventListener @Async on user creation)
- **UI**: "Current" (future+active) | "Applied History" tabs
- **Access Control**: Grants asset access by AWS account or AD domain (see below)

### VulnerabilityException
- **Fields**: exceptionType(IP/PRODUCT), targetValue, expirationDate, reason
- **Methods**: isActive(), matches()
- **Access**: ADMIN, VULN

### VulnerabilityExceptionRequest (Feature 031)
- **Fields**: vulnerability, scope(SINGLE/CVE_PATTERN), status(PENDING→APPROVED/REJECTED/CANCELLED), autoApproved, reason(50-2048), expirationDate, requestedByUser, reviewedByUser
- **SSE**: Real-time badge updates
- **API**: 11 endpoints at /api/vulnerability-exception-requests/*

### Release
- **Fields**: version(semantic), name, status(DRAFT/PUBLISHED/ARCHIVED), createdBy
- **Snapshots**: Immutable RequirementSnapshot entities
- **Access**: ADMIN/RELEASE_MANAGER create/delete; all read

### OutdatedAssetMaterializedView (Feature 034)
- **Purpose**: Pre-calculated denormalized view of assets with overdue vulnerabilities
- **Fields**: assetId, assetName, assetType, severityCounts (critical/high/medium/low), oldestVulnDays, workgroupIds, lastCalculatedAt
- **Performance**: <2s query for 10K+ assets
- **Refresh**: Manual (ADMIN button) + automatic (after CLI imports), SSE progress streaming

### MaterializedViewRefreshJob (Feature 034)
- **Purpose**: Track async refresh with progress monitoring
- **Fields**: status(RUNNING/COMPLETED/FAILED), triggeredBy, progress%, timestamps, errorMessage
- **Concurrency**: Single job at a time

### NotificationPreference (Feature 035)
- **Fields**: userId(unique), enableNewVulnNotifications(default false), lastVulnNotificationSentAt
- **Access**: All authenticated users

### AssetReminderState (Feature 035)
- **Purpose**: Track 2-level escalation for outdated assets
- **Fields**: assetId(unique), level(1-2), lastSentAt, outdatedSince
- **Logic**: Level 1 → (7 days) → Level 2; duplicate prevention via lastSentAt.date check

### NotificationLog (Feature 035)
- **Purpose**: Audit trail for emails
- **Fields**: assetId, assetName, ownerEmail, notificationType(OUTDATED_LEVEL1/LEVEL2/NEW_VULNERABILITY), sentAt, status(SENT/FAILED)
- **Access**: ADMIN only, pagination, CSV export

### IdentityProvider (Feature 041)
- **Fields**: name, type(OIDC/SAML), clientId, clientSecret, tenantId, discoveryUrl, authorizationUrl, tokenUrl, userInfoUrl, issuer, jwksUri, scopes, enabled, autoProvision, buttonText, buttonColor, roleMapping, claimMappings, callbackUrl(nullable, max 512 chars)
- **Callback URL**: If null → `${BACKEND_BASE_URL}/oauth/callback`; if set → custom URL (must start with https:// or http://localhost)
- **UI**: Templates for Google, Microsoft, GitHub
- **Auto-provisioning**: Creates new users on first OAuth login

## Unified Access Control

Users access assets if **ANY** is true:
1. User has ADMIN role (universal)
2. Asset in user's workgroup
3. Asset manually created by user
4. Asset discovered via user's scan upload
5. Asset's cloudAccountId matches user's AWS mappings (UserMapping)
6. Asset's adDomain matches user's domain mappings (case-insensitive, UserMapping)

**Implementation**: `AssetFilterService.getAccessibleAssets()` at src/backendng/src/main/kotlin/com/secman/service/AssetFilterService.kt:84-101

## API Endpoints

**Import**: POST /api/import/{upload-xlsx, upload-nmap-xml, upload-vulnerability-xlsx, upload-user-mappings, upload-user-mappings-csv, upload-assets-xlsx}

**Assets**: GET/POST /api/assets, DELETE /api/assets/bulk (ADMIN), GET /api/assets/export

**Vulnerabilities**: GET /api/vulnerabilities/current, GET /api/vulnerability-exceptions, POST /api/vulnerability-exception-requests, GET /api/vulnerability-exception-requests/pending/count, GET /api/exception-badge-updates (SSE)

**Outdated Assets (034)**: GET /api/outdated-assets[/{id}[/vulnerabilities]], GET /api/outdated-assets/{last-refresh,count}, POST /api/materialized-view-refresh/trigger (ADMIN), GET /api/materialized-view-refresh/{progress (SSE), status, history}

**Notifications (035)**: GET/PUT /api/notification-preferences, GET /api/notification-logs, GET /api/notification-logs/export (ADMIN)

**Workgroups**: POST/GET /api/workgroups (ADMIN), POST /api/workgroups/{id}/{users,assets} (ADMIN)

**Releases**: POST /api/releases (ADMIN/RELEASE_MANAGER), GET /api/releases, GET /api/releases/compare

**Auth**: POST /api/auth/login, GET /oauth/{authorize,callback}

**User Mappings (042)**: GET /api/user-mappings/{current,applied-history} (ADMIN), POST/PUT/DELETE /api/user-mappings[/{id}] (ADMIN)

**Identity Providers (041)**: GET /api/identity-providers[/{enabled,{id}}], POST/PUT/DELETE /api/identity-providers[/{id}], POST /api/identity-providers/{id}/test

## Development

**Git**: Commits `type(scope): description`, Branches `###-feature-name`

**Commands**:
- Backend: `./gradlew build`
- Frontend: `npm run dev`
- CLI Notifications: `./gradlew cli:run --args='send-notifications [--dry-run] [--verbose] [--outdated-only]'`

**Principles**:
1. Security-First: File validation, input sanitization, RBAC, security review required
2. API-First: RESTful, backward compatible
3. RBAC: @Secured on endpoints, role checks in UI
4. Schema Evolution: Hibernate auto-migration

## Common Patterns

### CSV/Excel Import
1. Validate file (≤10MB, extension, content-type)
2. Parse (Apache POI/Commons CSV), detect delimiter/encoding (UTF-8 BOM, ISO-8859-1 fallback)
3. Validate headers (case-insensitive), parse rows (skip invalid, handle scientific notation)
4. Check duplicates (DB + file), batch save
5. Return ImportResult: {imported, skipped, errors[]}

### Entity Merge (Asset)
1. Find by name → 2. Merge if exists (append groups, update IP, preserve owner) OR create if new → 3. Save

### Authentication
- Backend: `@Secured(SecurityRule.IS_AUTHENTICATED)`, `authentication.roles.contains("ADMIN"/"VULN")`
- Frontend: JWT in sessionStorage → Axios headers

### Event-Driven Architecture (Feature 042)
**Pattern**: @EventListener @Async for cross-service communication
```kotlin
@Serdeable data class UserCreatedEvent(val user: User, val source: String)
eventPublisher.publishEvent(UserCreatedEvent(savedUser, "MANUAL"))
@EventListener @Async open fun onUserCreated(event: UserCreatedEvent) { applyFutureUserMapping(event.user) }
```
**Use**: User creation → mapping application, Asset import → view refresh, Vuln detection → email
**Performance**: <5ms event delivery, non-blocking

### Materialized View Refresh (Feature 034)
1. Create MaterializedViewRefreshJob (status=RUNNING)
2. @Async: Truncate → Query source data → Batch process (1000 chunks) → Emit SSE progress (Sinks.Many.multicast())
3. Mark COMPLETED/FAILED with metrics
**Triggers**: Manual (ADMIN button) + Auto (CLI imports)
**Performance**: <30s for 10K assets, <2s query time

### Email Notification (Feature 035)
1. CLI: `send-notifications` → NotificationCliService
2. Query: Join OutdatedAssetMaterializedView + Asset + UserMapping
3. Check AssetReminderState (level 1→2 after 7 days), skip if lastSentAt.date == today
4. Aggregate by owner email, render Thymeleaf templates (Level 1: professional, Level 2: urgent)
5. SMTP: EmailSender (3 retries, 1s delay), log to NotificationLog, update AssetReminderState
6. Check NotificationPreference.enableNewVulnNotifications
**Performance**: JavaMail API, 30s timeout

### Last Admin Protection (Feature 037)
**Purpose**: Prevent last ADMIN deletion/demotion
1. UserDeletionValidator: Check admin count via `findAll().count { it.roles.contains(ADMIN) }`
2. Block with 409 Conflict if last admin
3. Return BlockingReference(entityType="SystemConstraint", role="last_admin", details="...")
**Implementation**: UserController.kt:282-323 (delete), :227-258 (update), UserDeletionValidator.kt:32-211

## File Locations
- Backend: `src/backendng/src/main/kotlin/com/secman/{domain,controller,service,repository}/`
- Frontend: `src/frontend/src/{components,pages,services}/`
- CLI: `src/cli/src/main/kotlin/com/secman/cli/{commands,service}/`
- Email Templates: `src/backendng/src/main/resources/email-templates/`
- Config: `src/backendng/src/main/resources/application.yml`

---
*Last updated: 2025-11-09*
