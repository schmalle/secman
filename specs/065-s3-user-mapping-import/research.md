# Research: S3 User Mapping Import

**Feature**: 065-s3-user-mapping-import
**Date**: 2026-01-20

## AWS SDK for Java v2 - S3 Integration

### Decision: Use AWS SDK for Java v2 (software.amazon.awssdk)

**Rationale**:
- AWS SDK v2 is the current, actively maintained version
- Supports standard credential chain out of the box
- Provides synchronous S3Client suitable for CLI usage
- Well-documented with Kotlin compatibility

**Alternatives Considered**:
- AWS SDK v1 (com.amazonaws): Legacy, not recommended for new projects
- S3 Transfer Manager: Overkill for single file downloads <10MB
- HTTP client with presigned URLs: More complex, requires additional auth handling

### Credential Chain Resolution

**Decision**: Use `DefaultCredentialsProvider.create()` with optional `ProfileCredentialsProvider`

The AWS SDK credential chain resolves credentials in this order:
1. Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_SESSION_TOKEN)
2. System properties (aws.accessKeyId, aws.secretAccessKey)
3. Web identity token (for EKS/IRSA)
4. Credential profiles file (~/.aws/credentials)
5. EC2 Instance Metadata Service (IMDS) / ECS Container Credentials
6. Process credentials

**Implementation Pattern**:
```kotlin
// Default: Use full credential chain
val credentialsProvider = DefaultCredentialsProvider.create()

// With specific profile (when --aws-profile provided)
val credentialsProvider = ProfileCredentialsProvider.create("profile-name")
```

**Rationale**: Standard credential chain covers all common deployment scenarios (local dev, CI/CD, EC2, ECS, Lambda) without custom code.

### S3 Client Configuration

**Decision**: Use synchronous `S3Client` with configurable region

**Implementation Pattern**:
```kotlin
val s3Client = S3Client.builder()
    .region(Region.of(regionString))  // from --aws-region or default
    .credentialsProvider(credentialsProvider)
    .build()
```

**Region Resolution**:
1. If `--aws-region` provided: use explicitly
2. Otherwise: AWS SDK resolves from AWS_REGION env var, config file, or IMDS

### File Download Pattern

**Decision**: Download to temporary file, then delegate to existing import logic

**Implementation Pattern**:
```kotlin
// 1. Get object metadata to check size
val headResponse = s3Client.headObject(HeadObjectRequest.builder()
    .bucket(bucket)
    .key(key)
    .build())

if (headResponse.contentLength() > MAX_FILE_SIZE) {
    throw IllegalArgumentException("File exceeds 10MB limit")
}

// 2. Download to temp file
val tempFile = Files.createTempFile("s3-import-", getExtension(key))
try {
    s3Client.getObject(
        GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build(),
        tempFile
    )

    // 3. Delegate to existing import logic
    userMappingCliService.importMappingsFromFile(
        filePath = tempFile.toString(),
        format = format,
        dryRun = dryRun,
        adminEmail = adminEmail
    )
} finally {
    // 4. Clean up temp file
    Files.deleteIfExists(tempFile)
}
```

**Rationale**:
- Reuses existing `importMappingsFromFile` logic completely
- Temp file ensures cleanup on success or failure
- HeadObject check prevents downloading oversized files

### Error Handling

**Decision**: Translate AWS exceptions to user-friendly CLI messages

| AWS Exception | User Message |
|---------------|--------------|
| NoSuchBucketException | "Bucket 'X' does not exist or is not accessible" |
| NoSuchKeyException | "File 's3://X/Y' not found" |
| S3Exception (AccessDenied) | "Access denied. Check IAM permissions for bucket 'X'" |
| SdkClientException (credentials) | "AWS credentials not found. Configure via environment, profile, or IAM role" |
| SdkClientException (network) | "Network error connecting to S3. Check connectivity and region" |

### Gradle Dependency

**Decision**: Add AWS SDK BOM for version management

```kotlin
// In build.gradle.kts
dependencies {
    implementation(platform("software.amazon.awssdk:bom:2.29.0"))
    implementation("software.amazon.awssdk:s3")
}
```

**Rationale**: BOM ensures consistent versions across all AWS SDK modules.

## CLI Command Structure

### Decision: New subcommand `import-s3` under `manage-user-mappings`

**Rationale**:
- Follows existing pattern (import for local files, import-s3 for S3)
- Clear distinction between data sources
- Inherits `--admin-user` from parent command

### Command Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `--bucket` | String | Yes | - | S3 bucket name |
| `--key` | String | Yes | - | S3 object key (path) |
| `--aws-region` | String | No | SDK default | AWS region |
| `--aws-profile` | String | No | default | AWS credential profile |
| `--format` | String | No | AUTO | CSV, JSON, or AUTO |
| `--dry-run` | Boolean | No | false | Validate without persisting |

### Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success (all mappings imported or dry-run valid) |
| 1 | Partial success (some mappings failed validation) |
| 2+ | Fatal error (S3 access, auth, or parse failure) |

## Security Considerations

### Credential Handling
- Never log credentials or access keys
- Use SDK credential chain (no hardcoding)
- Support IAM roles for EC2/ECS deployments

### Input Validation
- Validate bucket name format (3-63 chars, lowercase, no special chars except hyphen)
- Validate object key (no null bytes, reasonable length)
- File size check before download (10MB limit)

### Temporary File Security
- Create in system temp directory with restrictive permissions
- Delete immediately after use (in finally block)
- Use unique filename to prevent collisions

## Dependencies Summary

**New Dependencies**:
```kotlin
implementation(platform("software.amazon.awssdk:bom:2.29.0"))
implementation("software.amazon.awssdk:s3")
```

**Existing Dependencies Used**:
- PicoCLI 4.7.5 (CLI framework)
- Apache Commons CSV 1.11.0 (CSV parsing, via existing service)
- Jackson (JSON parsing, via existing service)
