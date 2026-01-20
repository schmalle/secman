# Data Model: MCP and CLI User Mapping Upload

**Feature**: 064-mcp-cli-user-mapping
**Date**: 2026-01-19

## Existing Entities (No Changes Required)

### UserMapping

The existing `UserMapping` entity is fully sufficient for this feature. No schema changes required.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | Long | Primary Key, Auto-generated | Unique identifier |
| email | String | Required, max 255 chars | User email address (lowercase) |
| user | User? | Foreign Key (nullable) | Associated user (null for future mappings) |
| awsAccountId | String? | Optional, exactly 12 digits | AWS account ID |
| domain | String? | Optional, alphanumeric with dots/hyphens | AD domain |
| ipAddress | String? | Optional | IP address (not used by this feature) |
| status | MappingStatus | Required, enum | ACTIVE or PENDING |
| appliedAt | Instant? | Nullable | When mapping was applied to user |
| createdAt | Instant | Auto-set | Creation timestamp |
| updatedAt | Instant | Auto-set | Last update timestamp |

**Uniqueness**: Composite unique constraint on `(email, awsAccountId, domain)`

**Validation Rules** (from spec FR-008, FR-009, FR-010):
- Email: Contains `@`, between 3-255 characters
- AWS Account ID: Exactly 12 numeric digits (regex: `^\d{12}$`)
- Domain: Alphanumeric with dots and hyphens, no leading/trailing special chars (regex: `^[a-zA-Z0-9.-]+$`)
- At least one of `awsAccountId` or `domain` must be provided

### MappingStatus (Enum)

| Value | Description |
|-------|-------------|
| ACTIVE | Mapping is applied to an existing user |
| PENDING | Future mapping waiting for user creation |

## MCP Tool DTOs (New)

### ImportUserMappingsRequest

Input schema for `import_user_mappings` MCP tool.

```kotlin
data class UserMappingEntry(
    val email: String,           // Required
    val awsAccountId: String?,   // Optional, 12 digits
    val domain: String?          // Optional, domain format
)

// MCP tool arguments
// {
//   "mappings": [...],
//   "dryRun": false
// }
```

### ImportUserMappingsResult

Output for `import_user_mappings` MCP tool.

```kotlin
data class ImportUserMappingsResult(
    val totalProcessed: Int,
    val created: Int,
    val createdPending: Int,    // Future mappings (user doesn't exist yet)
    val skipped: Int,           // Duplicates
    val errors: List<ImportError>,
    val dryRun: Boolean
)

data class ImportError(
    val index: Int,             // 0-based index in input array
    val email: String,
    val message: String
)
```

### ListUserMappingsResult

Output for `list_user_mappings` MCP tool.

```kotlin
data class ListUserMappingsResult(
    val mappings: List<UserMappingDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)

data class UserMappingDto(
    val id: Long,
    val email: String,
    val awsAccountId: String?,
    val domain: String?,
    val userId: Long?,
    val isFutureMapping: Boolean,  // user == null
    val appliedAt: String?,        // ISO-8601 timestamp or null
    val createdAt: String,         // ISO-8601 timestamp
    val updatedAt: String          // ISO-8601 timestamp
)
```

## State Transitions

```
                    ┌─────────────────┐
                    │   Import Entry  │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │  User exists?   │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              │ Yes          │              │ No
              ▼              │              ▼
    ┌─────────────────┐     │     ┌─────────────────┐
    │   ACTIVE        │     │     │   PENDING       │
    │   (user set,    │     │     │   (user null,   │
    │   appliedAt set)│     │     │   appliedAt null│
    └─────────────────┘     │     └────────┬────────┘
                            │              │
                            │     On User Creation
                            │     (via EventListener)
                            │              │
                            │              ▼
                            │     ┌─────────────────┐
                            │     │   ACTIVE        │
                            └─────│   (auto-applied)│
                                  └─────────────────┘
```

## Repository Queries (Existing)

The following queries are already available in `UserMappingRepository`:

| Method | Purpose |
|--------|---------|
| `existsByEmailAndAwsAccountIdAndDomain()` | Duplicate detection |
| `findByEmail()` | Filter by email |
| `findByEmailAndStatus()` | Filter by email and status |
| `findAll()` | List all mappings |

**New Query Needed**: Pagination support for MCP list tool

```kotlin
// Add to UserMappingRepository
fun findByEmailContaining(email: String, pageable: Pageable): Page<UserMapping>
fun findAll(pageable: Pageable): Page<UserMapping>
```
