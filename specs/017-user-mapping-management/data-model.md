# Data Model: User Mapping Management

**Feature**: 017-user-mapping-management  
**Phase**: 1 - Design & Contracts  
**Date**: 2025-10-13

## Overview

This document defines the data structures and relationships for managing user mappings within the user edit interface. The feature reuses the existing `UserMapping` entity from Feature 013 without modifications.

## Entities

### UserMapping (Existing - No Changes)

**Description**: Links a user's email address to cloud provider accounts (AWS) and organizational domains for access control purposes.

**Source**: `src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt`

**Fields**:

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | Long | Primary Key, Auto-increment | Unique identifier |
| email | String(255) | NOT NULL, @Email | User's email address (normalized to lowercase) |
| awsAccountId | String(12) | NULLABLE, @Pattern(12 digits) | AWS account identifier |
| domain | String(255) | NULLABLE, @Pattern(lowercase+numbers+dots+hyphens) | Organizational domain |
| createdAt | Instant | NOT NULL, Set on creation | Timestamp when mapping was created |
| updatedAt | Instant | NOT NULL, Updated on modification | Timestamp of last update |

**Constraints**:

- **Unique Constraint**: (email, awsAccountId, domain) - Prevents duplicate mappings
- **Business Rule**: At least one of awsAccountId or domain must be non-null
- **Indexes**: 
  - idx_user_mapping_email on (email)
  - idx_user_mapping_aws_account on (awsAccountId)
  - idx_user_mapping_domain on (domain)
  - idx_user_mapping_email_aws on (email, awsAccountId)

**Relationships**:

- No foreign key to User table (intentional design - mappings are managed independently)
- Email field links logically to User.email but without database constraint

**Lifecycle Hooks**:

- `@PrePersist`: Normalizes email and domain to lowercase, sets timestamps
- `@PreUpdate`: Updates updatedAt timestamp

**Validation**:

- Email format validated via Jakarta `@Email` annotation
- AWS Account ID validated via `@Pattern(regexp = "^\\d{12}$")`
- Domain validated via `@Pattern(regexp = "^[a-z0-9.-]+$")`

## API Data Transfer Objects (DTOs)

### UserMappingResponse

**Purpose**: Safe representation of UserMapping for API responses (excludes internal details)

**Fields**:

```kotlin
@Serdeable
data class UserMappingResponse(
    val id: Long,
    val email: String,
    val awsAccountId: String?,
    val domain: String?,
    val createdAt: String,
    val updatedAt: String
)
```

**Transformation**:

```kotlin
fun UserMapping.toResponse(): UserMappingResponse {
    return UserMappingResponse(
        id = this.id!!,
        email = this.email,
        awsAccountId = this.awsAccountId,
        domain = this.domain,
        createdAt = this.createdAt.toString(),
        updatedAt = this.updatedAt.toString()
    )
}
```

### CreateUserMappingRequest

**Purpose**: Request body for creating new mappings

**Fields**:

```kotlin
@Serdeable
data class CreateUserMappingRequest(
    val awsAccountId: String?,
    val domain: String?
)
```

**Validation**:

- At least one of awsAccountId or domain must be provided (service layer check)
- awsAccountId format: exactly 12 digits if provided
- domain format: lowercase letters, numbers, dots, hyphens if provided

**Notes**:

- Email is derived from the User being edited (userId in path parameter)
- createdAt and updatedAt are set automatically by entity lifecycle hooks

### UpdateUserMappingRequest

**Purpose**: Request body for updating existing mappings

**Fields**:

```kotlin
@Serdeable
data class UpdateUserMappingRequest(
    val awsAccountId: String?,
    val domain: String?
)
```

**Validation**:

- At least one of awsAccountId or domain must be provided
- Same format validation as CreateUserMappingRequest
- Email cannot be changed (derived from User context)

### ErrorResponse

**Purpose**: Consistent error response format (existing pattern)

**Fields**:

```kotlin
@Serdeable
data class ErrorResponse(
    val error: String
)
```

**Usage Examples**:

- "At least one of Domain or AWS Account ID must be provided"
- "This mapping already exists"
- "Invalid AWS Account ID format"
- "Mapping not found"
- "Access denied"

## State Transitions

### Mapping Lifecycle

```
┌─────────────┐
│   Create    │
│  Request    │
└──────┬──────┘
       │
       ▼
┌─────────────────────────────────────┐
│ Validate:                           │
│ - At least one field provided       │
│ - Format validation (AWS ID, domain)│
│ - Check for duplicates              │
└──────┬──────────────────────┬───────┘
       │ Valid                │ Invalid
       ▼                      ▼
┌─────────────┐         ┌──────────┐
│   ACTIVE    │         │  REJECT  │
│  (Persisted)│         │  (400)   │
└──────┬──────┘         └──────────┘
       │
       │ ┌───────────┐
       ├─┤   View    │
       │ └───────────┘
       │
       │ ┌───────────┐
       ├─┤   Update  │ ─────┐
       │ └───────────┘      │ Validate
       │                    │ (same as create)
       │ ┌───────────┐      │
       ├─┤  Delete   │      │
       │ └───────────┘      │
       ▼                    ▼
┌─────────────┐      ┌───────────┐
│   DELETED   │      │  UPDATED  │
│ (Hard delete)│      │ (Modified)│
└─────────────┘      └───────────┘
```

**State Descriptions**:

- **CREATE REQUEST**: Admin submits new mapping via UI
- **VALIDATE**: Service layer performs validation checks
- **ACTIVE**: Mapping exists in database and is queryable
- **REJECT**: Validation failure, returns 400 Bad Request
- **VIEW**: Read-only access to mapping data
- **UPDATE**: Modify awsAccountId or domain fields
- **UPDATED**: Mapping modified with new updatedAt timestamp
- **DELETE**: Remove mapping from database
- **DELETED**: Hard delete (no soft delete requirement)

## Query Patterns

### List Mappings for User

**Query**: Retrieve all mappings associated with a user's email

```kotlin
// Repository method (existing)
fun findByEmail(email: String): List<UserMapping>

// Usage in service
val mappings = userMappingRepository.findByEmail(user.email)
```

**Performance**: Indexed on email column, O(log n) lookup

### Check Duplicate Mapping

**Query**: Verify if mapping already exists before creation

```kotlin
// Repository method (existing)
fun existsByEmailAndAwsAccountIdAndDomain(
    email: String,
    awsAccountId: String?,
    domain: String?
): Boolean

// Usage in service
if (userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
    email, request.awsAccountId, request.domain
)) {
    throw DuplicateMappingException()
}
```

**Performance**: Uses unique constraint index, O(log n) lookup

### Count Mappings for User

**Query**: Get total number of mappings for a user

```kotlin
// Repository method (existing)
fun countByEmail(email: String): Long

// Usage in service
val count = userMappingRepository.countByEmail(user.email)
```

**Performance**: Count operation on indexed column, fast

## Validation Rules

### Field-Level Validation

1. **Email** (applied at entity level):
   - Format: Valid email address per RFC 5322
   - Normalization: Converted to lowercase on save
   - Required: NOT NULL constraint

2. **AWS Account ID** (applied at entity level):
   - Format: Exactly 12 numeric digits (e.g., "123456789012")
   - Regex: `^\\d{12}$`
   - Optional: Can be NULL if domain is provided

3. **Domain** (applied at entity level):
   - Format: Lowercase letters, numbers, dots, hyphens
   - Regex: `^[a-z0-9.-]+$`
   - Normalization: Converted to lowercase on save
   - Optional: Can be NULL if AWS Account ID is provided

### Business Rule Validation

1. **At Least One Field Required** (service layer):
   - Rule: awsAccountId OR domain OR both must be non-null
   - Check: Performed before save operation
   - Error: "At least one of Domain or AWS Account ID must be provided"

2. **No Duplicates** (service + database):
   - Rule: Unique combination of (email, awsAccountId, domain)
   - Check: Service calls existsByEmailAndAwsAccountIdAndDomain()
   - Enforcement: Database unique constraint as fallback
   - Error: "This mapping already exists"

3. **User Email Consistency** (service layer):
   - Rule: Mapping email must match the user being edited
   - Check: Service verifies userId → email consistency
   - Error: "Invalid user for mapping"

## Data Access Patterns

### Repository Layer (Existing)

**Interface**: `UserMappingRepository` extends `JpaRepository<UserMapping, Long>`

**Methods Used**:

- `findByEmail(email: String)`: List all mappings for user
- `findById(id: Long)`: Get single mapping by ID
- `existsByEmailAndAwsAccountIdAndDomain(...)`: Check duplicates
- `save(mapping: UserMapping)`: Create or update mapping
- `delete(mapping: UserMapping)`: Remove mapping

**No Changes Required**: All needed repository methods already exist from Feature 013

### Service Layer (New)

**Class**: `UserMappingService`

**Responsibilities**:

- Business rule validation
- Duplicate detection
- CRUD operations
- DTO transformation

**Methods**:

```kotlin
interface UserMappingService {
    fun getUserMappings(userId: Long): List<UserMappingResponse>
    fun createMapping(userId: Long, request: CreateUserMappingRequest): UserMappingResponse
    fun updateMapping(userId: Long, mappingId: Long, request: UpdateUserMappingRequest): UserMappingResponse
    fun deleteMapping(userId: Long, mappingId: Long): Boolean
}
```

## Data Flow

### Create Mapping Flow

```
Frontend                Controller              Service                Repository
   │                       │                      │                        │
   ├─POST /users/1/mappings─>                     │                        │
   │                       │                      │                        │
   │                       ├─validateAdmin────────>                        │
   │                       │                      │                        │
   │                       ├─createMapping(1, req)>                        │
   │                       │                      │                        │
   │                       │                      ├─getUser(1)────────────>│
   │                       │                      │<─User(email)───────────┤
   │                       │                      │                        │
   │                       │                      ├─validateRequest()      │
   │                       │                      │ (at least one field)   │
   │                       │                      │                        │
   │                       │                      ├─checkDuplicate────────>│
   │                       │                      │<─exists=false──────────┤
   │                       │                      │                        │
   │                       │                      ├─save(mapping)─────────>│
   │                       │                      │<─savedMapping──────────┤
   │                       │                      │                        │
   │                       │<─MappingResponse─────┤                        │
   │<─201 Created──────────┤                      │                        │
```

### Update Mapping Flow

```
Frontend                Controller              Service                Repository
   │                       │                      │                        │
   ├─PUT /users/1/mappings/5─>                    │                        │
   │                       │                      │                        │
   │                       ├─validateAdmin────────>                        │
   │                       │                      │                        │
   │                       ├─updateMapping(1,5,req)>                       │
   │                       │                      │                        │
   │                       │                      ├─findById(5)───────────>│
   │                       │                      │<─mapping───────────────┤
   │                       │                      │                        │
   │                       │                      ├─verifyUser(1,mapping.email)
   │                       │                      │                        │
   │                       │                      ├─validateRequest()      │
   │                       │                      │                        │
   │                       │                      ├─checkDuplicate────────>│
   │                       │                      │ (exclude current)      │
   │                       │                      │<─exists=false──────────┤
   │                       │                      │                        │
   │                       │                      ├─update(mapping)───────>│
   │                       │                      │<─updatedMapping────────┤
   │                       │                      │                        │
   │                       │<─MappingResponse─────┤                        │
   │<─200 OK───────────────┤                      │                        │
```

## Summary

**Entities**:

- ✅ UserMapping (existing, no changes)

**DTOs**:

- ✅ UserMappingResponse (read)
- ✅ CreateUserMappingRequest (write)
- ✅ UpdateUserMappingRequest (write)
- ✅ ErrorResponse (error handling)

**Validation**:

- ✅ Field-level (entity annotations)
- ✅ Business rules (service layer)
- ✅ Database constraints (unique)

**Repository**:

- ✅ All methods exist (Feature 013)

**Service** (new):

- ✅ UserMappingService interface defined

**Ready for**: Contract generation (OpenAPI spec)
