# Claude Code Agent Context

## Project Overview

**secman** - Security requirement and risk assessment management tool

- Full-stack: Kotlin/Micronaut backend + Astro/React frontend
- Tech: Micronaut 4.10, Kotlin 2.2, Astro 5.15, React 19, MariaDB 12, Gradle 9.2 build system
- Purpose: Manage security requirements, norms, use cases, assets, and vulnerabilities

## Architecture

**Project Type**: web - frontend (Astro/React) + backend (Micronaut/Kotlin)

### Backend (`src/backendng/`)

- **Layers**: Domain (JPA entities) → Repository (Micronaut Data) → Service → Controller (REST)
- **Security**: JWT auth, OAuth2, RBAC roles: USER, ADMIN, VULN, RELEASE_MANAGER, SECCHAMPION

### Frontend (`src/frontend/`)

- **Framework**: Astro with React islands, Bootstrap 5
- **API**: Axios client, sessionStorage for JWT

### Helper Tools (`src/helper/`)

- **Falcon Tool**: Query CrowdStrike API for vulnerabilities
- **CLI**: `falcon-vulns` with device type/severity filters
- **Auth**: FALCON_CLIENT_ID, FALCON_CLIENT_SECRET, FALCON_CLOUD_REGION

## Command Line Interface (`src/cli/`)

- **Falcon Tool**: Query CrowdStrike API for vulnerabilities

## Key Entities

### VulnerabilityExceptionRequest (Feature 031)

- **Fields**: vulnerability(FK), scope(SINGLE_VULNERABILITY/CVE_PATTERN), status(PENDING/APPROVED/REJECTED/EXPIRED/CANCELLED), autoApproved, reason(50-2048), expirationDate, requestedByUser(FK), reviewedByUser(FK)
- **State**: PENDING → {APPROVED,REJECTED,CANCELLED}; APPROVED → {EXPIRED,CANCELLED}
- **Services**: VulnerabilityExceptionRequestService (auto-approval, optimistic locking), ExceptionRequestAuditService (async), ExceptionRequestStatisticsService (analytics)
- **API**: 11 endpoints (/api/vulnerability-exception-requests/*)
- **Frontend**: ExceptionRequestModal, MyExceptionRequests, ExceptionApprovalDashboard, SSE badge updates

### UserMapping (Feature 013/016/020/042)

- **Fields**: email, awsAccountId(12 digits), domain, ipAddress, user(FK nullable), appliedAt(nullable)
- **Validation**: Email format, 12-digit AWS account, domain format
- **Import**: Excel (.xlsx) + CSV (.csv) with auto-delimiter detection, scientific notation parsing
- **Access**: ADMIN only
- **Feature 042 - Future User Mappings**:
  - Support mappings for users who don't exist yet (user=null, appliedAt=null)
  - Automatic application when users are created (manual or OAuth)
  - Event-driven architecture (@EventListener @Async)
  - Conflict resolution: "pre-existing mapping wins" strategy
  - UI tabs: "Current Mappings" (future + active) | "Applied History" (historical)
  - Visual indicators: Future User (yellow), Active (blue), Applied (green) status badges
- **Access Control Impact**: AWS account mappings and AD domain mappings grant asset access (see Unified Access Control below)

### Workgroup (Feature 008)

- **Fields**: name, description, users(ManyToMany), assets(ManyToMany)
- **Access Control**: See Unified Access Control below
- **CRUD**: ADMIN role only

## Unified Access Control

Users can access assets if **ANY** of the following is true:
1. User has ADMIN role (universal access)
2. Asset belongs to a workgroup the user is a member of
3. Asset was manually created by the user
4. Asset was discovered via a scan uploaded by the user
5. **Asset's cloudAccountId matches any of the user's AWS account mappings (UserMapping table)**
6. **Asset's adDomain matches any of the user's domain mappings (UserMapping table, case-insensitive)**

This unified model ensures consistent access across all views (Asset Management, Asset Detail, Account Vulnerabilities, etc.)

**Implementation Details:**
- Domain matching is case-insensitive (e.g., "CONTOSO", "contoso", "ConTosO" all match)
- Assets with null adDomain are excluded from domain-based filtering
- Domain filtering applies to all roles including VULN (not just ADMIN)
- Access control is implemented in `AssetFilterService.getAccessibleAssets()` at src/backendng/src/main/kotlin/com/secman/service/AssetFilterService.kt:84-101

### Release (Feature 011)

- **Fields**: version(semantic), name, status(DRAFT/PUBLISHED/ARCHIVED), createdBy
- **Snapshots**: RequirementSnapshot entities (immutable point-in-time copies)
- **Access**: ADMIN/RELEASE_MANAGER create/delete; all read

### VulnerabilityException (Feature 004)

- **Fields**: exceptionType(IP/PRODUCT), targetValue, expirationDate, reason
- **Methods**: isActive(), matches(vulnerability, asset)
- **Access**: ADMIN, VULN roles

### Asset (Extended)

- **Core**: id, name, type, ip, owner, description, lastSeen
- **Metadata**: groups, cloudAccountId, cloudInstanceId, adDomain, osVersion
- **Relations**: vulnerabilities(OneToMany), scanResults(OneToMany), workgroups(ManyToMany), manualCreator(FK), scanUploader(FK)

### Vulnerability

- **Fields**: asset(FK), vulnerabilityId(CVE), cvssSeverity, vulnerableProductVersions, daysOpen, scanTimestamp
- **Relations**: ManyToOne Asset (cascade delete)

### User

- **Fields**: username, email, passwordHash, roles(collection), workgroups(ManyToMany)
- **Roles**: USER, ADMIN, VULN, RELEASE_MANAGER, SECCHAMPION

### OutdatedAssetMaterializedView (Feature 034)

- **Purpose**: Fast-loading view of assets with overdue vulnerabilities (>reminder_one_days threshold)
- **Fields**: assetId(FK), assetName, assetType, totalOverdueCount, criticalCount, highCount, mediumCount, lowCount, oldestVulnDays, oldestVulnId, workgroupIds(denormalized), lastCalculatedAt
- **Pattern**: Materialized view - pre-calculated denormalized table refreshed async
- **Performance**: <2s page load for 10,000+ assets via indexed queries
- **Access Control**: ADMIN sees all; VULN sees only workgroup-assigned assets
- **Refresh**: Manual (UI button) + automatic (after CLI imports)

### MaterializedViewRefreshJob (Feature 034)

- **Purpose**: Track async refresh operations with progress monitoring
- **Fields**: status(RUNNING/COMPLETED/FAILED), triggeredBy, assetsProcessed, totalAssets, progressPercentage, startedAt, completedAt, durationMs, errorMessage
- **Pattern**: Background job with @Async execution
- **Progress**: SSE streaming for real-time updates (every 1000 assets)
- **Triggers**: Manual refresh button, CLI vulnerability imports
- **Concurrency**: Single job at a time (prevents concurrent refreshes)

### NotificationPreference (Feature 035)

- **Purpose**: User-configurable email notification settings
- **Fields**: userId(unique), enableNewVulnNotifications(boolean), lastVulnNotificationSentAt, createdAt, updatedAt
- **Access**: All authenticated users can manage their own preferences
- **Default**: New vulnerability notifications disabled by default
- **UI**: Toggle switch in /notification-preferences page

### AssetReminderState (Feature 035)

- **Purpose**: Track reminder escalation levels for outdated assets
- **Fields**: assetId(unique), level(1-2), lastSentAt, outdatedSince, lastCheckedAt
- **Logic**: Level 1 → (7 days if still outdated) → Level 2
- **Duplicate Prevention**: Check lastSentAt.date == today before sending
- **State Reset**: Cleared when asset becomes up-to-date
- **Indexes**: lastSentAt, outdatedSince for query performance

### NotificationLog (Feature 035)

- **Purpose**: Audit trail for all notification emails sent
- **Fields**: assetId(nullable), assetName, ownerEmail, notificationType(OUTDATED_LEVEL1/OUTDATED_LEVEL2/NEW_VULNERABILITY), sentAt, status(SENT/FAILED/PENDING), errorMessage
- **Access**: ADMIN only via /notification-logs page
- **Features**: Pagination, filtering by type/status/email/date, CSV export
- **Indexes**: Multiple indexes for efficient filtering

### IdentityProvider (Feature 041)

- **Purpose**: OAuth2/OIDC single sign-on identity provider configuration
- **Fields**: name, type(OIDC/SAML), clientId, clientSecret, tenantId, discoveryUrl, authorizationUrl, tokenUrl, userInfoUrl, issuer, jwksUri, scopes, enabled, autoProvision, buttonText, buttonColor, roleMapping, claimMappings, **callbackUrl**, createdAt, updatedAt
- **Callback URL**: Custom OAuth callback URL (optional, nullable)
  - If null/blank: Uses default `${BACKEND_BASE_URL}/oauth/callback`
  - If configured: Uses custom URL (e.g., `https://api.yourdomain.com/oauth/callback`)
  - **Validation**: Must start with `https://` (production) or `http://localhost` (development)
  - **Max Length**: 512 characters
  - **Use Case**: Different callback URLs per provider, or when backend URL differs from OAuth endpoint
- **Access**: Authenticated users can view enabled providers; full CRUD requires authentication
- **UI**: IdentityProviderManagement.tsx with templates for Google, Microsoft, GitHub
- **Auto-provisioning**: Create new users automatically on first OAuth login
- **Tenant Validation**: Microsoft providers require valid UUID tenant ID

## API Endpoints (Critical Only)

### Import

- `POST /api/import/upload-xlsx` - Requirements
- `POST /api/import/upload-nmap-xml` - Nmap scans
- `POST /api/import/upload-vulnerability-xlsx` - Vulnerabilities
- `POST /api/import/upload-user-mappings` - Excel user mappings (ADMIN)
- `POST /api/import/upload-user-mappings-csv` - CSV user mappings (ADMIN)
- `POST /api/import/upload-assets-xlsx` - Assets import

### Assets

- `GET /api/assets` - List (workgroup-filtered)
- `POST /api/assets` - Create
- `DELETE /api/assets/bulk` - Bulk delete (ADMIN)
- `GET /api/assets/export` - Excel export

### Vulnerabilities

- `GET /api/vulnerabilities/current` - Current vulns (workgroup-filtered for VULN)
- `GET /api/vulnerability-exceptions` - List exceptions (ADMIN, VULN)
- `POST /api/vulnerability-exception-requests` - Create exception request
- `GET /api/vulnerability-exception-requests/pending/count` - Badge count
- `GET /api/exception-badge-updates` - SSE real-time updates

### Outdated Assets (Feature 034)

- `GET /api/outdated-assets` - List outdated assets (pagination, sorting, filtering by severity/search)
- `GET /api/outdated-assets/{id}` - Single asset details (workgroup access control)
- `GET /api/outdated-assets/{id}/vulnerabilities` - Paginated vulnerabilities for asset
- `GET /api/outdated-assets/last-refresh` - Timestamp of last materialized view refresh
- `GET /api/outdated-assets/count` - Count of outdated assets (workgroup-filtered)
- `POST /api/materialized-view-refresh/trigger` - Trigger async refresh (ADMIN only)
- `GET /api/materialized-view-refresh/progress` - SSE stream of refresh progress
- `GET /api/materialized-view-refresh/status` - Current refresh job status
- `GET /api/materialized-view-refresh/history` - Recent refresh job history (last 10)

### Notifications (Feature 035)

- `GET /api/notification-preferences` - Get current user's preferences (authenticated)
- `PUT /api/notification-preferences` - Update user preferences (authenticated)
- `GET /api/notification-logs` - List notification audit logs with filters (ADMIN only)
- `GET /api/notification-logs/export` - Export logs to CSV (ADMIN only)

### Workgroups

- `POST /api/workgroups` - Create (ADMIN)
- `GET /api/workgroups` - List (ADMIN)
- `POST /api/workgroups/{id}/users` - Assign users (ADMIN)
- `POST /api/workgroups/{id}/assets` - Assign assets (ADMIN)

### Releases

- `POST /api/releases` - Create (ADMIN, RELEASE_MANAGER)
- `GET /api/releases` - List with status filter
- `GET /api/releases/compare` - Compare releases

### Auth

- `POST /api/auth/login` - JWT login
- OAuth2 SSO endpoints

### User Mappings (Feature 042)

- `GET /api/user-mappings/current` - List current mappings (future + active, appliedAt IS NULL) (ADMIN)
- `GET /api/user-mappings/applied-history` - List applied historical mappings (appliedAt IS NOT NULL) (ADMIN)
- `POST /api/user-mappings` - Create new mapping (ADMIN)
- `PUT /api/user-mappings/{id}` - Update mapping (ADMIN)
- `DELETE /api/user-mappings/{id}` - Delete mapping (ADMIN)
- `GET /api/user-mappings/{id}` - Get mapping by ID (ADMIN)

### Identity Providers (Feature 041)

- `GET /api/identity-providers` - List all providers (authenticated)
- `GET /api/identity-providers/enabled` - List enabled providers (public, for login page)
- `GET /api/identity-providers/{id}` - Get single provider (authenticated)
- `POST /api/identity-providers` - Create provider (authenticated, validates callbackUrl)
- `PUT /api/identity-providers/{id}` - Update provider (authenticated, validates callbackUrl)
- `DELETE /api/identity-providers/{id}` - Delete provider (authenticated)
- `POST /api/identity-providers/{id}/test` - Test provider configuration (authenticated)
- `GET /oauth/authorize` - Initiate OAuth flow (uses provider's callbackUrl if configured)
- `GET /oauth/callback` - Handle OAuth callback (validates against stored redirectUri)

## Development Workflow

### Git

- Commits: `type(scope): description`
- Branches: `###-feature-name`

### Commands

```bash
# Backend
./gradlew build

# Frontend
npm run dev

# CLI - Send Notifications (Feature 035)
./gradlew cli:run --args='send-notifications'
./gradlew cli:run --args='send-notifications --dry-run --verbose'
./gradlew cli:run --args='send-notifications --outdated-only'

```

## Constitutional Principles

1. **Security-First**: File validation, input sanitization, RBAC, always do a security review before completing a feature
2. **API-First**: RESTful, backward compatible
3. **RBAC**: @Secured on endpoints, role checks in UI
4. **Schema Evolution**: Hibernate auto-migration

## Common Patterns

### CSV/Excel Import

1. Validate file (size ≤10MB, extension, content-type)
2. Parse (Apache POI for Excel, Commons CSV for CSV)
3. Detect delimiter/encoding for CSV (UTF-8 BOM, ISO-8859-1 fallback)
4. Validate headers (case-insensitive)
5. Parse rows (skip invalid, continue valid, handle scientific notation)
6. Check duplicates (DB + file)
7. Batch save
8. Return ImportResult: imported, skipped, errors[]

### Entity Merge (Asset)

1. Find by name
2. Merge if exists (append groups, update IP, preserve owner)
3. Create if new
4. Save

### Authentication

- Endpoints: `@Secured(SecurityRule.IS_AUTHENTICATED)`
- Admin: `authentication.roles.contains("ADMIN")`
- VULN: Check "VULN" or "ADMIN"
- Frontend: JWT in sessionStorage → Axios headers

### Event-Driven Architecture (Feature 042)

**Pattern**: Async event publishing and listening for cross-service communication

1. **Event Definition**: Create @Serdeable data class with relevant data
2. **Publisher**: Inject ApplicationEventPublisher<EventType> and call publishEvent()
3. **Listener**: Create @EventListener @Async method in service
4. **Non-Blocking**: Event processing happens asynchronously, doesn't block caller
5. **Error Handling**: Catch exceptions in listener to prevent blocking user operations

**Example - Future User Mapping Application:**
```kotlin
// 1. Event definition
@Serdeable
data class UserCreatedEvent(val user: User, val source: String)

// 2. Publisher (UserService)
eventPublisher.publishEvent(UserCreatedEvent(savedUser, "MANUAL"))

// 3. Listener (UserMappingService)
@EventListener
@Async
open fun onUserCreated(event: UserCreatedEvent) {
    applyFutureUserMapping(event.user)  // Runs async
}
```

**Use Cases:**
- User creation triggers mapping application
- Asset import triggers materialized view refresh
- Vulnerability detection triggers notification emails

**Performance**: Event delivery <5ms, async processing doesn't block caller

### Materialized View Refresh (Feature 034)

1. Create MaterializedViewRefreshJob with status=RUNNING
2. Execute @Async refresh method (non-blocking)
3. Delete old materialized view data (truncate table)
4. Query source data with business logic (e.g., overdue threshold)
5. Batch process in chunks (1000 records)
6. Publish progress events via ApplicationEventPublisher
7. Emit SSE events via Sinks.Many.multicast() for real-time UI updates
8. Mark job COMPLETED/FAILED with metrics (duration, processed count)
9. Auto-trigger: After CLI imports (if data changed)
10. Manual trigger: UI refresh button (ADMIN only)

**Performance**: <30s for 10,000 assets, <2s query time via indexed materialized table

### Email Notification Workflow (Feature 035)

1. **CLI Trigger**: `send-notifications` command invokes NotificationCliService
2. **Query Outdated Assets**: Join OutdatedAssetMaterializedView with Asset and UserMapping to get owner emails
3. **Reminder State Check**: For each asset, check AssetReminderState for current level (1 or 2)
4. **Escalation Logic**: If asset at Level 1 for 7+ days and still outdated, escalate to Level 2
5. **Duplicate Prevention**: Skip if lastSentAt.date == today
6. **Email Aggregation**: Group assets by owner email (one combined email per owner)
7. **Template Rendering**: Use Thymeleaf to render HTML + plain-text versions (outdated-reminder-level1/level2 or new-vulnerability-notification)
8. **SMTP Sending**: EmailSender with retry logic (3 attempts, 1s delay between)
9. **Audit Logging**: NotificationLogService logs all attempts (SENT/FAILED status)
10. **State Update**: Update AssetReminderState.lastSentAt for successful sends
11. **User Preferences**: For new vulnerability notifications, check NotificationPreference.enableNewVulnNotifications

**Email Templates**: Thymeleaf with inline CSS, professional tone (Level 1) vs urgent tone (Level 2)
**Retry**: 3 attempts with 1s delay, configurable in application.yml
**Performance**: JavaMail API with connection pooling, 30s delivery timeout

### Last Admin Protection (Feature 037)

**Purpose**: Prevent deletion or role removal of the last ADMIN user to ensure system operability

1. **Service-Layer Validation**: UserDeletionValidator provides pre-operation checks
2. **Admin Count Query**: Use `findAll().count { it.roles.contains(User.Role.ADMIN) }` (O(n) acceptable for <1000 users)
3. **Validation Points**:
   - User deletion: Check if user is ADMIN and last one before delete
   - Role update: Check if removing ADMIN role from last admin
4. **Blocking Reference Pattern**:
   ```kotlin
   BlockingReference(
       entityType = "SystemConstraint",
       count = 1,
       role = "last_admin",
       details = "Cannot delete the last administrator. At least one ADMIN user must remain in the system."
   )
   ```
5. **HTTP Status Codes**:
   - 409 Conflict: SystemConstraint violations (last admin protection)
   - 400 Bad Request: Other blocking references (demands, risk assessments, etc.)
6. **Frontend Error Handling**: Detect 409 status and display actionable guidance
7. **Transaction Safety**: Validation inside @Transactional methods, relies on transaction isolation for concurrent operations

**Validation Methods**:
- `UserDeletionValidator.validateUserDeletion(userId)` - Full deletion validation
- `UserDeletionValidator.validateAdminRoleRemoval(userId, newRoles)` - Role change validation
- `UserService.countAdminUsers()` - Count users with ADMIN role

**Error Message Pattern**: Include clear explanation + actionable next steps (e.g., "Please create another admin user before deleting this one")

**Concurrency Edge Case**: Two simultaneous deletions of last 2 admins theoretically possible; acceptable risk with transaction isolation, documented as low-probability scenario

**Implementation**: UserController.kt:282-323 (delete), UserController.kt:227-258 (update), UserDeletionValidator.kt:32-211

## File Locations

- **Backend**: `src/backendng/src/main/kotlin/com/secman/{domain,controller,service,repository}/`
- **Frontend**: `src/frontend/src/{components,pages,services}/`
- **Helper**: `src/helper/src/{models,services,cli,exporters,lib}/`
- **CLI**: `src/cli/src/main/kotlin/com/secman/cli/{commands,service}/`
- **Email Templates**: `src/backendng/src/main/resources/email-templates/`
- **Config**: `src/backendng/src/main/resources/application.yml`

---

*Optimized for performance. See git history for detailed feature specs. Last updated: 2025-11-07*

## Active Technologies
- Kotlin 2.2.21 / Java 21 (backend), Python 3.11+ (CLI), Astro 5.14 + React 19 (frontend) + Micronaut 4.10, Hibernate JPA, MariaDB 12, Apache POI 5.3, JavaMail API (SMTP), Bootstrap 5.3, Axios (035-notification-system)
- MariaDB 12 (3 new tables: notification_preference, notification_log, asset_reminder_state) (035-notification-system)
- Backend: Kotlin 2.2.21 / Java 21; Frontend: Astro 5.14 + React 19 + Backend: Micronaut 4.10, Hibernate JPA; Frontend: React 19, Chart.js (or Recharts) for visualizations, Axios (036-vuln-stats-lense)
- MariaDB 12 (existing Vulnerability and Asset tables; no new tables required) (036-vuln-stats-lense)
- Kotlin 2.2.21 / Java 21 + Micronaut 4.10, Hibernate JPA, MariaDB 12 (037-last-admin-protection)
- MariaDB 12 (existing `users` and `user_roles` tables, no schema changes required) (037-last-admin-protection)
- Kotlin 2.2.21 / Java 21 (backend), JavaScript ES2022 (frontend - Astro 5.14 + React 19) + Micronaut 4.10, Hibernate JPA, MariaDB 12, Astro 5.14, React 19, Bootstrap 5.3, Axios (039-asset-workgroup-criticality)
- MariaDB 12 (2 new columns: workgroup.criticality, asset.criticality) (039-asset-workgroup-criticality)
- Kotlin 2.2.21 / Java 21 (backend), JavaScript ES2022 (frontend - Astro 5.14 + React 19) + Micronaut 4.10, Hibernate JPA, MariaDB 12 (backend); Astro 5.14, React 19, Bootstrap 5.3, Axios (frontend) (040-nested-workgroups)
- MariaDB 12 with self-referential foreign key on `workgroup` table (`parent_id` column) (040-nested-workgroups)
- Kotlin 2.2.21 / Java 21 (backend), JavaScript ES2022 (frontend - Astro 5.14 + React 19) + Micronaut 4.10, Hibernate JPA, MariaDB 12, Apache POI 5.3, Apache Commons CSV, Astro 5.14, React 19, Bootstrap 5.3, Axios (042-future-user-mappings)
- MariaDB 12 (existing `user_mapping` table - schema extension required to support nullable user_id and add appliedAt timestamp) (042-future-user-mappings)
- Kotlin 2.2.21 / Java 21 (backend), JavaScript ES2022 (frontend - Astro 5.14 + React 19) + Micronaut 4.10, Hibernate JPA, MariaDB 11.4 (backend); Astro 5.14, React 19, Bootstrap 5.3, Axios (frontend); FalconPy (existing CrowdStrike integration) (043-crowdstrike-domain-import)
- MariaDB 11.4 with existing `asset` table (existing `ad_domain` column already present from Feature 042) (043-crowdstrike-domain-import)

## Recent Changes
- 042-future-user-mappings: Added Future User Mapping support with event-driven auto-application, UI tabs for Current/Applied History, visual status indicators (yellow/blue/green badges), conflict resolution strategy, @EventListener @Async pattern
- 035-notification-system: Added Kotlin 2.2.21 / Java 21 (backend), Python 3.11+ (CLI), Astro 5.14 + React 19 (frontend) + Micronaut 4.10, Hibernate JPA, MariaDB 12, Apache POI 5.3, JavaMail API (SMTP), Bootstrap 5.3, Axios
