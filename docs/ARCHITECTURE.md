# Secman Architecture

**Last Updated:** 2026-01-11

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
        |
        v
   [Backend :8080]
   REST API calls
```

---

## Technology Stack


| Layer        | Technology    | Version              |
| ------------ | ------------- | -------------------- |
| **Backend**  | Kotlin        | 2.3.0                |
|              | Java          | 21                   |
|              | Micronaut     | 4.10                 |
|              | Hibernate JPA | (via Micronaut Data) |
| **Frontend** | Astro         | 5.15                 |
|              | React         | 19                   |
|              | Bootstrap     | 5.3                  |
|              | Axios         | (HTTP client)        |
| **Database** | MariaDB       | 11.4+                |
| **CLI**      | Picocli       | 4.7                  |
|              | Kotlin        | 2.2.21               |
| **Build**    | Gradle        | 9.2                  |

---

## Component Architecture

### Backend (`src/backendng/`)

The backend follows a layered architecture:

```
┌─────────────────────────────────────────────────────────────────┐
│                       Controller Layer                          │
│   REST endpoints, request validation, response formatting       │
│   @Controller, @Secured, @PathVariable, @Body                  │
├─────────────────────────────────────────────────────────────────┤
│                        Service Layer                            │
│   Business logic, transaction management, domain operations     │
│   @Singleton, @Transactional                                   │
├─────────────────────────────────────────────────────────────────┤
│                       Repository Layer                          │
│   Data access, JPA queries, database operations                │
│   @Repository, CrudRepository, custom queries                  │
├─────────────────────────────────────────────────────────────────┤
│                        Domain Layer                             │
│   Entity definitions, validation, business rules               │
│   @Entity, @Table, @Column, @ManyToOne                         │
└─────────────────────────────────────────────────────────────────┘
```

**Key packages:**

- `com.secman.domain` - JPA entities and enums
- `com.secman.repository` - Data access interfaces
- `com.secman.service` - Business logic
- `com.secman.controller` - REST endpoints
- `com.secman.config` - Configuration classes
- `com.secman.dto` - Data transfer objects
- `com.secman.filter` - HTTP filters

### Frontend (`src/frontend/`)

Astro with React islands architecture:

```
┌─────────────────────────────────────────────────────────────────┐
│                         Astro Pages                             │
│   Static/SSR pages, routing, layouts                           │
│   src/pages/*.astro                                            │
├─────────────────────────────────────────────────────────────────┤
│                      React Components                           │
│   Interactive islands, state management, UI logic              │
│   src/components/*.tsx                                         │
├─────────────────────────────────────────────────────────────────┤
│                        Services Layer                           │
│   API calls, authentication, data fetching                     │
│   src/services/*.ts (Axios)                                    │
└─────────────────────────────────────────────────────────────────┘
```

**Authentication flow:**

1. JWT stored in `localStorage` as `authToken`
2. Axios interceptor adds `Authorization: Bearer <token>` header
3. SSE endpoints receive token via query parameter (`?token=<jwt>`)

### CLI (`src/cli/`)

Command-line interface for automated operations:

```
┌─────────────────────────────────────────────────────────────────┐
│                       Command Classes                           │
│   Picocli commands, parameter parsing, output formatting       │
│   @Command, @Option, @Parameters                               │
├─────────────────────────────────────────────────────────────────┤
│                        Service Layer                            │
│   API client, CrowdStrike integration, email sending           │
│   HTTP clients, token management                               │
└─────────────────────────────────────────────────────────────────┘
```

**Available commands:**

- `query servers` - Query CrowdStrike for vulnerabilities
- `send-notifications` - Send email notifications
- `manage-user-mappings` - Manage AWS/AD domain mappings
- `export-requirements` - Export to Excel/Word
- `add-requirement` - Create requirements
- `add-vulnerability` - Add vulnerability records

---

## Data Model

### Core Entities

```
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│      User        │     │    Workgroup     │     │      Asset       │
├──────────────────┤     ├──────────────────┤     ├──────────────────┤
│ id               │     │ id               │     │ id               │
│ email            │     │ name             │     │ name             │
│ username         │     │ description      │     │ type             │
│ password         │     │                  │     │ ip               │
│ roles[]          │◄───►│                  │◄───►│ owner            │
│ enabled          │     │                  │     │ description      │
│ lastLogin        │     │                  │     │ lastSeen         │
│ authProvider     │     │                  │     │ cloudAccountId   │
└──────────────────┘     └──────────────────┘     │ adDomain         │
                                                   │ osVersion        │
                                                   └────────┬─────────┘
                                                            │
                    ┌───────────────────────────────────────┘
                    │
                    ▼
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│  Vulnerability   │     │   ScanResult     │     │   Requirement    │
├──────────────────┤     ├──────────────────┤     ├──────────────────┤
│ id               │     │ id               │     │ id               │
│ cveId            │     │ port             │     │ shortreq         │
│ title            │     │ protocol         │     │ details          │
│ severity         │     │ state            │     │ motivation       │
│ description      │     │ service          │     │ chapter          │
│ detectionTime    │     │ product          │     │ status           │
│ patchPublication │     │ version          │     │ priority         │
│ daysOpen         │     │ scanTimestamp    │     │ tags[]           │
│ asset_id (FK)    │     │ asset_id (FK)    │     │ norms[]          │
└──────────────────┘     └──────────────────┘     └──────────────────┘
```

### Entity Relationships


| Relationship                  | Type         | Description                           |
| ----------------------------- | ------------ | ------------------------------------- |
| User ↔ Workgroup             | Many-to-Many | Users belong to workgroups            |
| Asset ↔ Workgroup            | Many-to-Many | Assets assigned to workgroups         |
| Asset → Vulnerability        | One-to-Many  | Assets have vulnerabilities           |
| Asset → ScanResult           | One-to-Many  | Assets have scan results              |
| User → Asset (manualCreator) | One-to-Many  | Users create assets manually          |
| User → Asset (scanUploader)  | One-to-Many  | Users upload scans discovering assets |
| Requirement ↔ Norm           | Many-to-Many | Requirements map to standards         |

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

-- Requirements
requirements, norms, requirement_norm

-- MCP integration
mcp_api_keys, mcp_sessions, mcp_audit_logs

-- System
identity_providers, oauth_states, email_config, maintenance_banners
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
| `SECCHAMPION`     | Security champion     | Extended read access, product listing |

### Unified Access Control (Asset Visibility)

Users can access an asset if **ANY** of the following is true:

1. **Admin Override**: User has `ADMIN` role (universal access)
2. **Workgroup Membership**: Asset is in a workgroup the user belongs to
3. **Manual Creator**: User manually created the asset
4. **Scan Uploader**: User uploaded a scan that discovered the asset
5. **AWS Mapping**: Asset's `cloudAccountId` matches user's AWS mappings
6. **AD Domain Mapping**: Asset's `adDomain` matches user's domain mappings (case-insensitive)

```kotlin
// Access check in service layer
fun canUserAccessAsset(user: User, asset: Asset): Boolean {
    return user.roles.contains("ADMIN") ||
           assetInUserWorkgroups(asset, user) ||
           asset.manualCreator?.id == user.id ||
           asset.scanUploader?.id == user.id ||
           awsAccountMatches(asset, user) ||
           adDomainMatches(asset, user)
}
```

### Authentication Methods


| Method      | Storage                  | Use Case                  |
| ----------- | ------------------------ | ------------------------- |
| JWT         | `localStorage`           | Frontend API calls        |
| OAuth2/OIDC | Session + JWT            | SSO with Azure AD, Google |
| MCP API Key | Header (`X-MCP-API-Key`) | AI assistant integration  |

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
- `GET /oauth/*`
- `GET /health`
- `POST /mcp` (uses API key auth)

**Protected (authenticated):**

- `GET /api/assets`
- `GET /api/vulnerabilities/*`
- `GET /api/requirements`

**Admin-only:**

- `POST /api/workgroups`
- `DELETE /api/assets/bulk`
- `GET /api/mcp/admin/api-keys`

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

- User creation → Apply pending user mappings
- Asset import → Trigger materialized view refresh
- Vulnerability detection → Send email notifications

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

1. Validate file (size ≤10MB, extension, content-type)
2. Parse content (Apache POI for Excel, Commons CSV for CSV)
3. Detect encoding (UTF-8 BOM, ISO-8859-1 fallback)
4. Validate headers (case-insensitive matching)
5. Parse rows (skip invalid, handle scientific notation)
6. Check duplicates (database + file)
7. Batch save
8. Return `ImportResult { imported, skipped, errors[] }`

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
│   │       ├── controller/           # REST endpoints
│   │       ├── config/               # Configuration
│   │       ├── dto/                  # DTOs
│   │       └── filter/               # HTTP filters
│   │
│   ├── frontend/                     # Astro/React frontend
│   │   └── src/
│   │       ├── pages/                # Astro pages
│   │       ├── components/           # React components
│   │       ├── services/             # API services
│   │       └── layouts/              # Layout templates
│   │
│   └── cli/                          # CLI tool
│       └── src/main/kotlin/com/secman/cli/
│           ├── commands/             # Picocli commands
│           └── service/              # CLI services
│
├── mcp/                              # MCP Node.js bridge
│   └── mcp-server.js
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
│   └── TROUBLESHOOTING.md            # Troubleshooting guide
│
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
