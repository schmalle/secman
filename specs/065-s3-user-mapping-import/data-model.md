# Data Model: S3 User Mapping Import

**Feature**: 065-s3-user-mapping-import
**Date**: 2026-01-20

## Overview

This feature does not introduce new database entities. It extends the CLI with S3 download capability that feeds into the existing `UserMapping` entity through the established import pipeline.

## Existing Entities (Unchanged)

### UserMapping

The existing `UserMapping` entity (from Feature 042/049) is used without modification:

```
UserMapping
├── id: Long (PK)
├── email: String (user's email address)
├── user: User? (FK, nullable - null for PENDING mappings)
├── domain: String? (AD domain, nullable)
├── awsAccountId: String? (12-digit AWS account ID, nullable)
├── ipAddress: String? (IP address, nullable)
├── status: MappingStatus (ACTIVE | PENDING)
├── createdAt: Instant
└── appliedAt: Instant? (when status changed to ACTIVE)
```

**Constraints**:
- At least one of `domain`, `awsAccountId`, or `ipAddress` must be non-null
- `email` is required and validated against email format
- `awsAccountId` must be exactly 12 digits when present

### MappingResult

Existing result DTO from `UserMappingCliService`:

```
MappingResult
├── totalProcessed: Int
├── created: Int (ACTIVE mappings created)
├── createdPending: Int (PENDING mappings created)
├── skipped: Int (duplicates)
├── errors: List<String>
└── operations: List<MappingOperationResult>
```

## New Service Classes

### S3DownloadService

New service to handle S3 operations:

```
S3DownloadService
├── downloadToTempFile(bucket: String, key: String, region: String?, profile: String?): Path
├── getObjectSize(bucket: String, key: String, region: String?, profile: String?): Long
└── validateBucketAccess(bucket: String, region: String?, profile: String?): Boolean
```

**Responsibilities**:
- Build S3Client with appropriate credentials/region
- Download S3 object to temporary file
- Check object size before download
- Handle AWS exceptions and translate to user-friendly errors

### ImportS3Command

New CLI command class:

```
ImportS3Command
├── bucket: String (required)
├── key: String (required)
├── awsRegion: String? (optional)
├── awsProfile: String? (optional)
├── format: String = "AUTO"
├── dryRun: Boolean = false
└── parent: ManageUserMappingsCommand
```

**Relationships**:
- Subcommand of `ManageUserMappingsCommand`
- Uses `S3DownloadService` for S3 operations
- Delegates to `UserMappingCliService.importMappingsFromFile()` for import logic

## Data Flow

```
┌─────────────────┐
│  S3 Bucket      │
│  (CSV/JSON)     │
└────────┬────────┘
         │ GetObject
         ▼
┌─────────────────┐
│ S3DownloadService│
│  - validate size│
│  - download     │
└────────┬────────┘
         │ temp file path
         ▼
┌─────────────────┐
│UserMappingCli   │
│Service          │
│  - parse CSV/JSON│
│  - validate     │
│  - save         │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  UserMapping    │
│  (database)     │
└─────────────────┘
```

## File Formats (Unchanged)

The import supports the same formats as the existing local file import:

### CSV Format
```csv
email,type,value
user@example.com,DOMAIN,corp.local
user@example.com,AWS_ACCOUNT,123456789012
```

### JSON Format
```json
[
  {
    "email": "user@example.com",
    "domains": ["corp.local", "dev.local"],
    "awsAccounts": ["123456789012"]
  }
]
```

## Validation Rules (Unchanged)

All validation from the existing import command applies:

| Field | Rule |
|-------|------|
| email | Must match `^[^@]+@[^@]+\.[^@]+$` |
| awsAccountId | Must match `^\d{12}$` (exactly 12 digits) |
| domain | Must match `^[a-zA-Z0-9.-]+$` |
| type (CSV) | Must be "DOMAIN" or "AWS_ACCOUNT" |

## State Transitions

```
                     ┌───────────┐
                     │  PENDING  │
                     │  (user    │
     User not found  │  not in   │
          ┌──────────│  system)  │
          │          └─────┬─────┘
          │                │ User created
          │                │ (via OAuth/manual)
          │                ▼
┌─────────┴───┐      ┌───────────┐
│ Import      │      │  ACTIVE   │
│ Mapping     ├─────▶│  (user    │
│ (S3 file)   │ User │  exists)  │
└─────────────┘ found└───────────┘
```
