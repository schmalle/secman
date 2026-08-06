# Data Model: CLI and MCP Requirements Management

**Feature**: 057-cli-mcp-requirements
**Date**: 2025-12-29
**Status**: Complete

## Overview

This feature does not introduce new entities. It provides CLI and MCP access to the existing `Requirement` entity and related data.

---

## Existing Entities (No Changes)

### Requirement

The core entity for security requirements. Already exists with full functionality.

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/Requirement.kt`

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | Long | PK, auto-generated | Unique identifier |
| shortreq | String | Required | Short requirement text |
| details | String? | Optional | Detailed description |
| motivation | String? | Optional | Why this requirement exists |
| example | String? | Optional | Implementation example |
| language | String? | Optional | Language code (e.g., "en", "de") |
| norm | String? | Optional | Regulatory norm reference |
| chapter | String? | Optional | Chapter/category grouping |
| usecase | String? | Optional | Use case description |
| createdAt | Instant | Auto | Creation timestamp |
| updatedAt | Instant | Auto | Last update timestamp |

**Relationships**:
- `norms: MutableSet<Norm>` - Many-to-many with Norm entity
- `usecases: MutableSet<UseCase>` - Many-to-many with UseCase entity

### Norm (Referenced)

Regulatory norms linked to requirements.

| Field | Type | Description |
|-------|------|-------------|
| id | Long | Unique identifier |
| name | String? | Norm name (e.g., "ISO 27001") |

### UseCase (Referenced)

Use cases linked to requirements.

| Field | Type | Description |
|-------|------|-------------|
| id | Long | Unique identifier |
| name | String? | Use case name |

---

## DTOs (New for CLI/MCP)

### AddRequirementRequestDto

Input DTO for adding requirements via CLI/MCP.

```kotlin
@Serdeable
data class AddRequirementRequestDto(
    val shortreq: String,           // Required
    val details: String? = null,
    val motivation: String? = null,
    val example: String? = null,
    val norm: String? = null,
    val usecase: String? = null,
    val chapter: String? = null
)
```

### AddRequirementResponseDto

Response DTO for add operation.

```kotlin
@Serdeable
data class AddRequirementResponseDto(
    val success: Boolean,
    val id: Long?,
    val message: String,
    val operation: String  // "CREATED" or "ERROR"
)
```

### DeleteAllResponseDto

Response DTO for delete-all operation.

```kotlin
@Serdeable
data class DeleteAllResponseDto(
    val success: Boolean,
    val deletedCount: Int,
    val message: String
)
```

### ExportMetadataDto

Metadata returned with MCP export.

```kotlin
@Serdeable
data class ExportMetadataDto(
    val filename: String,
    val format: String,      // "xlsx" or "docx"
    val contentType: String,
    val requirementCount: Int,
    val fileSizeBytes: Long
)
```

---

## State Transitions

No state transitions for this feature. Requirements are stateless CRUD entities.

---

## Validation Rules

### Add Requirement

| Field | Rule | Error Message |
|-------|------|---------------|
| shortreq | Required, non-empty | "Short requirement text is required" |
| shortreq | Max 2000 chars | "Short requirement exceeds maximum length" |
| details | Max 10000 chars | "Details exceed maximum length" |
| chapter | Auto-created if new | N/A (valid any value) |

### Delete All Requirements

| Condition | Rule | Error Message |
|-----------|------|---------------|
| Role | Must be ADMIN | "Insufficient permissions. ADMIN role required." |
| Confirmation | --confirm flag required | "Delete operation requires --confirm flag" |

---

## Export Format Mapping

### Excel (XLSX) Columns

| Column | Source Field | Notes |
|--------|--------------|-------|
| Chapter | requirement.chapter | Grouping header |
| Norm | requirement.norms | Comma-separated names |
| Short req | requirement.shortreq | Primary text |
| DetailsEN | requirement.details | English details |
| MotivationEN | requirement.motivation | English motivation |
| ExampleEN | requirement.example | English example |
| UseCase | requirement.usecases | Comma-separated names |

### Word (DOCX) Structure

```
Title Page
- "Security Requirements Export"
- Date
- Count

Table of Contents (placeholder)

For each Chapter:
  Chapter Heading
  For each Requirement in Chapter:
    - Short Requirement (bold)
    - Norm references
    - Details
    - Motivation
    - Example
    - Use Cases
```

---

## Database Impact

**Schema Changes**: None
**Migration Required**: No
**Indexes**: Existing indexes sufficient

The feature only adds read/write access paths to existing data through CLI and MCP interfaces.
