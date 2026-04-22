# Secman Architecture

**Last Updated:** 2026-04-22

This document describes the system architecture, data model, and design patterns used in Secman.

---

## Table of Contents

1. [System Overview](#system-overview)
2. [Technology Stack](#technology-stack)
3. [Component Architecture](#component-architecture)
4. [Data Model](#data-model)
5. [Access Control Model](#access-control-model)
6. [API Design](#api-design)
7. [Design Patterns](#design-patterns)
8. [File Structure](#file-structure)

---

## System Overview

Secman is a security requirement and risk assessment management tool consisting of four main components:

```
                                      Internet
                                         |
                                 [Nginx :80/:443]
                                    (Reverse Proxy)
                                         |
           +-----------------------------+-----------------------------+
           |                             |                             |
      /api/*                        /oauth/*                          /*
           |                             |                             |
           v                             v                             v
   [Backend :8080]              [Backend :8080]              [Frontend :4321]
   Kotlin/Micronaut             OAuth callbacks              Astro/React SSR
           |                                                          |
           +----------------------------------------------------------+
                                         |
                                 [MariaDB :3306]
                                    (Database)

   [CLI Tool]
   CrowdStrike queries
   Email notifications
   Admin summaries
   Data imports
        |
        v
   [Backend :8080]
   REST API calls
```

---

## Technology Stack

| Layer        | Technology    | Version              |
| ------------ | ------------- | -------------------- |
| **Backend**  | Kotlin        | 2.3.20               |
|              | Java          | 21                   |
|              | Micronaut     | 4.10                 |
|              | Hibernate JPA | (via Micronaut Data) |
| **Frontend** | Astro         | 6.1                  |
|              | React         | 19                   |
|              | Bootstrap     | 5.3                  |
|              | Axios         | (HTTP client)        |
| **Database** | MariaDB       | 11.4                 |
| **CLI**      | Picocli       | 4.7.7                |
|              | AWS SDK       | v2                   |
| **Build**    | Gradle        | 9.4.1                |

---

## Component Architecture

### Backend (`src/backendng/`)

The backend follows a layered architecture with 63 controllers:

```
+-----------------------------------------------------------------+
|                       Controller Layer                           |
|   REST endpoints, request validation, response formatting       |
|   @Controller, @Secured, @PathVariable, @Body                   |
+-----------------------------------------------------------------+
|                     Service Layer (97 services)                   |
|   Business logic, transaction management, domain operations     |
|   @Singleton, @Transactional                                    |
+-----------------------------------------------------------------+
|                       Repository Layer                           |
|   Data access, JPA queries, database operations                 |
|   @Repository, CrudRepository, custom queries                   |
+-----------------------------------------------------------------+
|                        Domain Layer                              |
|   Entity definitions, validation, business rules                |
|   @Entity, @Table, @Column, @ManyToOne                          |
+-----------------------------------------------------------------+
```

**Key packages:**

- `com.secman.domain` - JPA entities and enums
- `com.secman.repository` - Data access interfaces
- `com.secman.service` - Business logic
- `com.secman.controller` - REST endpoints
- `com.secman.config` - Configuration classes
- `com.secman.dto` - Data transfer objects
- `com.secman.filter` - HTTP filters
- `com.secman.mcp` - MCP tools and registry

**Controller categories:**

- **Core Domain**: Asset, AssetCompliance, Requirement, Release, ReleaseComparison, Workgroup, Product, Standard, Norm, NormMapping, UseCase
- **Vulnerability**: VulnerabilityManagement, VulnerabilityExceptionRequest, VulnerabilityStatistics, VulnerabilityConfig, VulnerabilityHeatmap, VulnerabilityMaintenance, ExternalHeatmap, AccountVulns, DomainVulns, WorkgroupVulns
- **Authentication**: Auth, OAuth, Passkey, UserProfile
- **Admin**: AppSettings, IdentityProvider, MaintenanceBanner, UserMapping, User, TranslationConfig, NotificationSettings, EmailConfig, EmailProviderConfig, FalconConfig, ConfigBundle, AwsAccountSharing
- **Import/Export**: Import, RequirementFile, PublicRequirementDownload, Scan
- **Email & Notifications**: Notification, NotificationPreference, NotificationLog, TestEmailAccount
- **MCP**: Mcp, McpAdmin, McpStreamableHttp
- **CLI Integration**: Cli
- **Other**: Health, Memory, Alignment, Response, Risk, RiskAssessment, Demand, DemandClassification, CrowdStrike, CveLookup, OutdatedAsset, MaterializedViewRefresh, Report

### Frontend (`src/frontend/`)

Astro with React islands architecture, 69 pages:

```
+-----------------------------------------------------------------+
|                         Astro Pages                              |
|   Static/SSR pages, routing, layouts                            |
|   src/pages/*.astro                                             |
+-----------------------------------------------------------------+
|                      React Components                            |
|   Interactive islands, state management, UI logic               |
|   src/components/*.tsx                                          |
+-----------------------------------------------------------------+
|                        Services Layer                            |
|   API calls, authentication, data fetching                      |
|   src/services/*.ts (Axios)                                     |
+-----------------------------------------------------------------+
```

**Page categories:**

- **Core**: Home, Login, Profile, About
- **Assets**: Assets list, Asset detail, Outdated assets
- **Vulnerabilities**: Current, Exceptions, System, Domain, Account, Workgroup, Statistics, Exception approvals
- **Requirements**: Requirements, Standards, Norms, Releases, Release comparison
- **Risk & Compliance**: Risks, Risk assessments, Demands, Products, Use cases
- **Import/Export**: Import, Export
- **Notifications**: Preferences, Logs
- **Admin (20 pages)**: Add system, App settings, AWS account sharing, Classification rules, Config bundle, EC2 compliance, Email config, Falcon config, Identity providers, Maintenance banners, MCP API keys, Notification settings, Requirements, Releases, Test email accounts, Translation config, User management, User mappings, Vulnerability config

**Authentication flow:**

1. JWT stored in `localStorage` as `authToken`
2. Axios interceptor adds `Authorization: Bearer <token>` header
3. SSE endpoints receive token via query parameter (`?token=<jwt>`)

### CLI (`src/cli/`)

Command-line interface with 25 commands for automated operations:

```
+-----------------------------------------------------------------+
|                       Command Classes                            |
|   Picocli commands, parameter parsing, output formatting        |
|   @Command, @Option, @Parameters                                |
+-----------------------------------------------------------------+
|                        Service Layer                             |
|   API client, CrowdStrike integration, email sending            |
|   HTTP clients, token management                                |
+-----------------------------------------------------------------+
```

**Available commands:**

- `query servers` - Query CrowdStrike for vulnerabilities
- `send-notifications` - Send email notifications (outdated assets, new vulnerabilities)
- `send-admin-summary` - Generate and send admin summary reports
- `manage-user-mappings` - Manage AWS/AD domain mappings; the `list` subcommand additionally supports `--send-email` (Feature 085) to distribute the statistics report to all ADMIN/REPORT users via `POST /api/cli/user-mappings/send-statistics-email`
- `manage-workgroups` - Manage workgroup asset assignments
- `export-requirements` - Export to Excel/Word
- `add-requirement` - Create requirements
- `add-vulnerability` - Add vulnerability records
- `add-aws` - Add AWS account associations
- `add-domain` - Add AD domain associations
- `import` - Import from local files (XLS, Nmap XML, vulnerabilities)
- `import-s3` - Import from S3 buckets
- `config` - Manage CLI configuration
- `monitor` - System monitoring
- `list` - List various entities
- `list-workgroups` - List workgroups
- `remove` - Remove assets/data
- `delete-all-requirements` - Bulk requirement deletion
- `deduplicate-vulnerabilities` - Remove duplicate vulnerability records
- `port-scan` - Scan network ports on assets
- `send-notification-users` - Send notifications to specific users

---

## Data Model

### Core Entities

```
+------------------+     +------------------+     +------------------+
|      User        |     |    Workgroup     |     |      Asset       |
+------------------+     +------------------+     +------------------+
| id               |     | id               |     | id               |
| email            |     | name             |     | name             |
| username         |     | description      |     | type             |
| password         |     | parentWorkgroup  |     | ip               |
| roles[]          |<--->|                  |<--->| owner            |
| enabled          |     |                  |     | description      |
| lastLogin        |     |                  |     | lastSeen         |
| authProvider     |     |                  |     | cloudAccountId   |
| mfaEnabled       |     |                  |     | cloudInstanceId  |
+------------------+     +------------------+     | adDomain         |
                                                   | osVersion        |
                                                   | criticality      |
                                                   +--------+---------+
                                                            |
                    +---------------------------------------+
                    |
                    v
+------------------+     +------------------+     +------------------+
|  Vulnerability   |     |   ScanResult     |     |   Requirement    |
+------------------+     +------------------+     +------------------+
| id               |     | id               |     | id               |
| cveId            |     | port             |     | shortreq         |
| title            |     | protocol         |     | details          |
| severity         |     | state            |     | motivation       |
| description      |     | service          |     | chapter          |
| detectionTime    |     | product          |     | status           |
| patchPublication |     | version          |     | priority         |
| daysOpen         |     | scanTimestamp    |     | tags[]           |
| asset_id (FK)    |     | asset_id (FK)    |     | norms[]          |
+------------------+     +------------------+     +------------------+
```

### Additional Entities

```
+------------------+     +------------------+     +------------------+
| VulnException    |     |   Release        |     |  IdentityProvider|
+------------------+     +------------------+     +------------------+
| id               |     | id               |     | id               |
| cveId            |     | name             |     | name             |
| reason           |     | description      |     | type (OIDC)      |
| status           |     | status           |     | clientId         |
| approvedBy       |     | createdAt        |     | clientSecret     |
| expiresAt        |     | publishedAt      |     | discoveryUrl     |
+------------------+     +------------------+     | enabled          |
                                                   +------------------+

+------------------+     +------------------+     +------------------+
|   McpApiKey      |     |  UserMapping     |     | MaintenanceBanner|
+------------------+     +------------------+     +------------------+
| id               |     | id               |     | id               |
| name             |     | email            |     | title            |
| keyHash          |     | mappingType      |     | message          |
| permissions      |     | mappingValue     |     | severity         |
| userId           |     | status           |     | active           |
| expiresAt        |     |                  |     | startsAt/endsAt  |
+------------------+     +------------------+     +------------------+
```

### Entity Relationships

| Relationship                  | Type         | Description                           |
| ----------------------------- | ------------ | ------------------------------------- |
| User <-> Workgroup            | Many-to-Many | Users belong to workgroups            |
| Asset <-> Workgroup           | Many-to-Many | Assets assigned to workgroups         |
| Asset -> Vulnerability        | One-to-Many  | Assets have vulnerabilities           |
| Asset -> ScanResult           | One-to-Many  | Assets have scan results              |
| User -> Asset (manualCreator) | One-to-Many  | Users create assets manually          |
| User -> Asset (scanUploader)  | One-to-Many  | Users upload scans discovering assets |
| Requirement <-> Norm          | Many-to-Many | Requirements map to standards         |
| Workgroup -> Workgroup        | Self-ref     | Nested workgroup hierarchy            |
| User -> User (AWS sharing)    | Many-to-Many | Directional AWS account visibility    |

### Key Tables

```sql
-- Users and authentication
users, user_roles, user_workgroups, user_mappings

-- Assets and inventory
assets, asset_groups, asset_workgroups

-- Vulnerabilities
vulnerabilities, vulnerability_exceptions, vulnerability_exception_requests

-- Scans
scan_uploads, scan_results

-- Requirements and releases
requirements, norms, requirement_norm, releases, requirement_snapshots

-- Risk and compliance
risks, risk_assessments, demands, demand_classifications, standards, products, use_cases

-- MCP integration
mcp_api_keys, mcp_sessions, mcp_audit_logs, mcp_tool_permissions

-- Email and notifications
email_configs, notification_logs, notification_preferences

-- AWS account sharing
aws_account_sharing

-- System
identity_providers, oauth_states, maintenance_banners, app_settings
```

---

## Access Control Model

### Role-Based Access Control (RBAC)

| Role              | Description           | Key Permissions                       |
| ----------------- | --------------------- | ------------------------------------- |
| `USER`            | Standard user         | View assigned assets, requirements    |
| `ADMIN`           | Full access           | All operations, user management       |
| `VULN`            | Vulnerability manager | Import vulnerabilities, manage scans  |
| `RELEASE_MANAGER` | Release coordinator   | Manage releases, view requirements    |
| `REQ`             | Requirements editor   | Create/edit requirements              |
| `REQADMIN`        | Requirements admin    | Create/delete releases, alignment decisions |
| `REPORT`          | Report viewer         | Report generation and viewing         |
| `RISK`            | Risk assessor         | Risk assessments read/write/execute   |
| `SECCHAMPION`     | Security champion     | Extended read access, product listing |

### Unified Access Control (Asset Visibility)

Users can access an asset if **ANY** of the following is true:

1. **Admin Override**: User has `ADMIN` role (universal access)
2. **Workgroup Membership**: Asset is in a workgroup the user belongs to
3. **Manual Creator**: User manually created the asset
4. **Scan Uploader**: User uploaded a scan that discovered the asset
5. **AWS Mapping**: Asset's `cloudAccountId` matches user's AWS mappings
6. **AD Domain Mapping**: Asset's `adDomain` matches user's domain mappings (case-insensitive)
7. **AWS Account Sharing**: Asset's `cloudAccountId` matches shared AWS accounts via `AwsAccountSharing` (directional, non-transitive)
8. **Owner Match**: Asset's `owner` matches user's username

```kotlin
// Access check in service layer (AssetFilterService)
fun canUserAccessAsset(user: User, asset: Asset): Boolean {
    return user.roles.contains("ADMIN") ||
           assetInUserWorkgroups(asset, user) ||
           asset.manualCreator?.id == user.id ||
           asset.scanUploader?.id == user.id ||
           awsAccountMatches(asset, user) ||
           adDomainMatches(asset, user) ||
           sharedAwsAccountMatches(asset, user)
}
```

### Authentication Methods

| Method         | Storage                  | Use Case                    |
| -------------- | ------------------------ | --------------------------- |
| JWT            | `localStorage`           | Frontend API calls          |
| OAuth2/OIDC    | Session + JWT            | SSO with Azure AD, Google   |
| Passkeys       | WebAuthn credentials     | Passwordless authentication |
| MCP API Key    | Header (`X-MCP-API-Key`) | AI assistant integration    |

---

## API Design

### RESTful Conventions

| HTTP Method | Purpose            | Example                   |
| ----------- | ------------------ | ------------------------- |
| `GET`       | Retrieve resources | `GET /api/assets`         |
| `POST`      | Create resources   | `POST /api/assets`        |
| `PUT`       | Update resources   | `PUT /api/assets/{id}`    |
| `DELETE`    | Delete resources   | `DELETE /api/assets/{id}` |

### Endpoint Categories

**Public (unauthenticated):**

- `POST /api/auth/login`
- `GET /api/identity-providers/enabled`
- `GET /api/maintenance-banners/active`
- `GET /oauth/*`
- `GET /health`
- `POST /mcp` (uses API key auth)

**Protected (authenticated):**

- `GET /api/assets`
- `GET /api/vulnerabilities/*`
- `GET /api/requirements`
- `GET /api/releases`
- `GET /api/outdated-assets`
- `GET/PUT /api/notification-preferences`
- `GET /api/users/profile`

**Admin-only:**

- `POST /api/workgroups`
- `DELETE /api/assets/bulk`
- `GET /api/mcp/admin/api-keys`
- `POST /api/identity-providers`
- `POST /api/maintenance-banners`
- `GET /api/notification-logs`
- `POST /api/materialized-view-refresh/trigger`

### Response Formats

**Paginated response:**

```json
{
  "content": [...],
  "totalElements": 1234,
  "totalPages": 13,
  "page": 0,
  "size": 100
}
```

**Error response:**

```json
{
  "error": "VALIDATION_ERROR",
  "message": "Invalid input",
  "details": ["Field 'name' is required"]
}
```

---

## Design Patterns

### Event-Driven Architecture

Cross-service communication via events:

```kotlin
// Publishing
@Serdeable
data class UserCreatedEvent(val user: User, val source: String)

eventPublisher.publishEvent(UserCreatedEvent(savedUser, "MANUAL"))

// Listening
@EventListener
@Async
open fun onUserCreated(event: UserCreatedEvent) {
    applyFutureUserMapping(event.user)
}
```

**Use cases:**

- User creation -> Apply pending user mappings
- Asset import -> Trigger materialized view refresh
- Vulnerability detection -> Send email notifications

### Transactional Replace Pattern

For idempotent bulk imports (CrowdStrike vulnerabilities):

```kotlin
@Transactional
open fun importVulnerabilitiesForServer(batch: Batch): Result {
    val (asset, isNew) = findOrCreateAsset(batch)

    // DELETE existing vulnerabilities
    vulnerabilityRepository.deleteByAssetId(asset.id!!)

    // INSERT new vulnerabilities
    vulnerabilityRepository.saveAll(vulnerabilities)
}
```

**Guarantees:** Idempotency, no duplicates, atomicity, remediation tracking

**CRITICAL:** `Asset.vulnerabilities` MUST NOT use `cascade = [CascadeType.ALL]` or `orphanRemoval = true`. JPA cascade conflicts with the manual delete-insert pattern, causing data loss. Use explicit `vulnerabilityRepository.deleteByAssetId()` in the service layer instead.

### Entity Merge Pattern

For asset imports with potential duplicates:

```kotlin
fun findOrCreateAsset(dto: AssetDto): Asset {
    val existing = assetRepository.findByName(dto.name)
    return if (existing != null) {
        // Merge: append groups, update IP, preserve owner
        existing.apply {
            ip = dto.ip ?: ip
            groups = (groups + dto.groups).distinct()
        }
    } else {
        Asset(name = dto.name, ...)
    }
}
```

### CSV/Excel Import Pattern

1. Validate file (size <=10MB, extension, content-type)
2. Parse content (Apache POI for Excel, Commons CSV for CSV)
3. Detect encoding (UTF-8 BOM, ISO-8859-1 fallback)
4. Validate headers (case-insensitive matching)
5. Parse rows (skip invalid, handle scientific notation)
6. Check duplicates (database + file)
7. Batch save
8. Return `ImportResult { imported, skipped, errors[] }`

### OAuth Robustness Pattern

Exponential backoff retry for OAuth state lookup to handle fast SSO callbacks:

```kotlin
fun findStateByValueWithRetry(stateToken: String): Optional<OAuthState> {
    val config = oauthConfig.stateRetry
    var currentDelayMs = config.initialDelayMs
    repeat(config.maxAttempts) { attempt ->
        val result = oauthStateRepository.findByStateToken(stateToken)
        if (result.isPresent) return result
        Thread.sleep(currentDelayMs)
        currentDelayMs = minOf((currentDelayMs * config.backoffMultiplier).toLong(), config.maxDelayMs)
    }
    return Optional.empty()
}
```

---

## File Structure

```
secman/
├── src/
│   ├── backendng/                    # Kotlin/Micronaut backend
│   │   └── src/main/kotlin/com/secman/
│   │       ├── domain/               # JPA entities
│   │       ├── repository/           # Data access
│   │       ├── service/              # Business logic
│   │       │   └── mcp/              # MCP-specific services
│   │       ├── controller/           # REST endpoints (63 controllers)
│   │       ├── config/               # Configuration
│   │       ├── dto/                  # DTOs
│   │       │   └── mcp/              # MCP DTOs
│   │       ├── filter/               # HTTP filters
│   │       └── mcp/                  # MCP tools and registry
│   │
│   ├── frontend/                     # Astro/React frontend
│   │   └── src/
│   │       ├── pages/                # Astro pages (69 pages)
│   │       │   └── admin/            # Admin pages (20 pages)
│   │       ├── components/           # React components
│   │       ├── services/             # API services
│   │       └── layouts/              # Layout templates
│   │
│   └── cli/                          # CLI tool
│       └── src/main/kotlin/com/secman/cli/
│           ├── commands/             # Picocli commands (25 commands)
│           └── service/              # CLI services
│
├── docs/                             # Documentation
│   ├── README.md                     # Documentation index
│   ├── ARCHITECTURE.md               # This file
│   ├── DEPLOYMENT.md                 # Production deployment
│   ├── ENVIRONMENT.md                # Environment variables
│   ├── CLI.md                        # CLI reference
│   ├── MCP.md                        # MCP integration
│   ├── TESTING.md                    # Testing guide
│   ├── CROWDSTRIKE_IMPORT.md         # CrowdStrike import
│   ├── TROUBLESHOOTING.md            # Troubleshooting guide
│   ├── E2E_EXCEPTION_WORKFLOW_TEST.md # Exception E2E test
│   ├── S3_USER_MAPPING_IMPORT.md     # S3 import guide
│   ├── SKILLS_AND_AGENTS.md          # Skills & agents reference
│   └── 1PASSWORD.md                  # Secret management with 1Password CLI
│
├── scripts/                          # Utility scripts
├── specs/                            # Feature specifications
└── build.gradle.kts                  # Root build file
```

---

## See Also

- [Deployment Guide](./DEPLOYMENT.md) - Production setup
- [Environment Variables](./ENVIRONMENT.md) - Configuration reference
- [MCP Integration](./MCP.md) - AI assistant integration
- [Testing Guide](./TESTING.md) - Test infrastructure

---

*This document provides a high-level overview. For implementation details, refer to the source code and inline documentation.*
