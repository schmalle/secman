# Data Model: MCP Tools for Overdue Vulnerabilities and Exception Handling

**Feature**: 062-mcp-vuln-exceptions
**Date**: 2026-01-11

## Overview

This feature uses **existing entities only**. No new database tables or schema changes are required.

## Existing Entities Used

### VulnerabilityExceptionRequest

**Table**: `vulnerability_exception_request`
**Location**: `src/backendng/src/main/kotlin/com/secman/domain/VulnerabilityExceptionRequest.kt`

Represents a user's request to create an exception for a vulnerability.

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | Primary key |
| `vulnerability` | Vulnerability (FK) | The vulnerability this exception applies to |
| `requestedByUser` | User (FK) | User who created the request |
| `requestedByUsername` | String | Username (preserved for audit) |
| `scope` | ExceptionScope | SINGLE_VULNERABILITY or CVE_PATTERN |
| `reason` | String | Business justification (50-2048 chars) |
| `expirationDate` | LocalDateTime | When exception should expire |
| `status` | ExceptionRequestStatus | PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED |
| `autoApproved` | Boolean | True if auto-approved (ADMIN/SECCHAMPION) |
| `reviewedByUser` | User (FK) | Reviewer who approved/rejected |
| `reviewedByUsername` | String | Reviewer username (preserved for audit) |
| `reviewDate` | LocalDateTime | When reviewed |
| `reviewComment` | String | Reviewer notes (10-1024 chars for rejection) |
| `createdAt` | LocalDateTime | Creation timestamp |
| `updatedAt` | LocalDateTime | Last modification timestamp |
| `version` | Long | Optimistic locking version |

**State Machine**:
```
PENDING → APPROVED, REJECTED, CANCELLED
APPROVED → EXPIRED (via scheduler)
REJECTED → (terminal)
CANCELLED → (terminal)
EXPIRED → (terminal)
```

---

### VulnerabilityException

**Table**: `vulnerability_exception`
**Location**: `src/backendng/src/main/kotlin/com/secman/domain/VulnerabilityException.kt`

The actual exception rule created upon approval.

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | Primary key |
| `exceptionType` | ExceptionType | IP, PRODUCT, or ASSET |
| `targetValue` | String | IP address, product pattern, or asset name |
| `assetId` | Long? | Asset ID for ASSET-type exceptions |
| `expirationDate` | LocalDateTime? | When exception expires |
| `reason` | String | Justification |
| `createdBy` | String | Creator username |
| `createdAt` | LocalDateTime | Creation timestamp |
| `updatedAt` | LocalDateTime | Last modification timestamp |

---

### OutdatedAssetMaterializedView

**Table**: `outdated_asset_materialized_view`
**Location**: `src/backendng/src/main/kotlin/com/secman/domain/OutdatedAssetMaterializedView.kt`

Pre-computed view of assets with overdue vulnerabilities.

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | Primary key (view-specific) |
| `assetId` | Long | Actual asset ID |
| `assetName` | String | Asset name |
| `assetIp` | String? | Asset IP address |
| `assetType` | String | Asset type |
| `workgroupIds` | String? | Comma-separated workgroup IDs |
| `totalVulnerabilities` | Int | Total vulnerability count |
| `criticalCount` | Int | Critical severity count |
| `highCount` | Int | High severity count |
| `mediumCount` | Int | Medium severity count |
| `lowCount` | Int | Low severity count |
| `oldestVulnDays` | Int | Days since oldest overdue vulnerability |
| `maxSeverity` | String | Highest severity level |
| `calculatedAt` | LocalDateTime | When view was refreshed |

---

## Supporting Enums

### ExceptionScope
**Location**: `src/backendng/src/main/kotlin/com/secman/domain/ExceptionScope.kt`

```kotlin
enum class ExceptionScope {
    SINGLE_VULNERABILITY,  // Exception applies to one specific vulnerability
    CVE_PATTERN           // Exception applies to all instances of a CVE
}
```

### ExceptionRequestStatus
**Location**: `src/backendng/src/main/kotlin/com/secman/domain/ExceptionRequestStatus.kt`

```kotlin
enum class ExceptionRequestStatus {
    PENDING,    // Awaiting approval
    APPROVED,   // Approved - exception created
    REJECTED,   // Rejected by reviewer
    EXPIRED,    // Past expiration date
    CANCELLED   // Cancelled by requester
}
```

---

## DTOs for MCP Response

### VulnerabilityExceptionRequestDto
**Location**: `src/backendng/src/main/kotlin/com/secman/dto/VulnerabilityExceptionRequestDto.kt`

Used for all exception request responses via MCP.

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | Request ID |
| `vulnerabilityId` | Long? | Vulnerability ID |
| `vulnerabilityCve` | String? | CVE identifier |
| `assetName` | String? | Asset name |
| `assetIp` | String? | Asset IP |
| `requestedByUsername` | String | Requester |
| `scope` | ExceptionScope | Request scope |
| `reason` | String | Justification |
| `expirationDate` | LocalDateTime | Expiration date |
| `status` | ExceptionRequestStatus | Current status |
| `autoApproved` | Boolean | Auto-approval flag |
| `reviewedByUsername` | String? | Reviewer |
| `reviewDate` | LocalDateTime? | Review timestamp |
| `reviewComment` | String? | Review notes |
| `createdAt` | LocalDateTime | Creation timestamp |
| `updatedAt` | LocalDateTime | Update timestamp |

### OutdatedAssetDto
**Location**: `src/backendng/src/main/kotlin/com/secman/dto/OutdatedAssetDto.kt`

Used for overdue asset responses via MCP.

| Field | Type | Description |
|-------|------|-------------|
| `id` | Long | Materialized view ID |
| `assetId` | Long | Actual asset ID |
| `name` | String | Asset name |
| `ip` | String? | Asset IP |
| `type` | String | Asset type |
| `totalVulnerabilities` | Int | Total count |
| `criticalCount` | Int | Critical count |
| `highCount` | Int | High count |
| `mediumCount` | Int | Medium count |
| `lowCount` | Int | Low count |
| `oldestVulnDays` | Int | Days overdue |
| `maxSeverity` | String | Highest severity |

---

## No Schema Changes Required

This feature operates entirely on existing database tables. The MCP tools are thin wrappers around existing service layer functionality.
