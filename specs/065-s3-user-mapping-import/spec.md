# Feature Specification: S3 User Mapping Import

**Feature Branch**: `065-s3-user-mapping-import`
**Created**: 2026-01-20
**Status**: Draft
**Input**: User description: "I want to have the possibility to import via the CLI AWS user mappings from an AWS S3 bucket. Please add command line parameters etc. where needed. Goal is to call the CLI once a day to get new user mappings."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Import User Mappings from S3 Bucket (Priority: P1)

An administrator wants to automate the daily import of user-to-AWS-account and user-to-domain mappings from an S3 bucket. They configure a scheduled job (cron) to run the CLI command once daily, pulling the latest mapping file from a centralized S3 location where their identity management team maintains the authoritative user mapping data.

**Why this priority**: This is the core functionality requested - enabling automated, scheduled imports from S3 storage. Without this, the feature provides no value.

**Independent Test**: Can be fully tested by uploading a mapping file to S3, running the import command, and verifying the mappings appear in the system.

**Acceptance Scenarios**:

1. **Given** a valid CSV file exists in the configured S3 bucket, **When** the admin runs the S3 import command with bucket name and object key, **Then** the system downloads the file and imports all valid mappings
2. **Given** the admin runs the command with `--dry-run` flag, **When** the S3 file is fetched, **Then** the system validates the file contents without creating any mappings and reports what would be imported
3. **Given** the S3 file contains the same mappings as a previous import, **When** the import runs, **Then** duplicate mappings are skipped with appropriate warnings

---

### User Story 2 - AWS Authentication Configuration (Priority: P1)

An administrator needs to configure AWS credentials so the CLI can access the S3 bucket securely. They use standard AWS credential mechanisms (environment variables, credential profiles, or IAM roles) that their infrastructure team has already set up.

**Why this priority**: Authentication is required for any S3 access - the import cannot function without proper credential handling.

**Independent Test**: Can be tested by running the command with various AWS credential configurations and verifying bucket access succeeds or fails appropriately.

**Acceptance Scenarios**:

1. **Given** AWS credentials are configured via environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY), **When** the import command runs, **Then** the system authenticates using these credentials
2. **Given** AWS credentials are configured via AWS credentials file with a named profile, **When** the import command runs with `--aws-profile` flag, **Then** the system uses the specified profile
3. **Given** the CLI runs on an EC2 instance with an IAM role attached, **When** the import command runs without explicit credentials, **Then** the system authenticates using the instance role
4. **Given** invalid or expired AWS credentials, **When** the import command runs, **Then** the system reports a clear authentication error and exits with non-zero status

---

### User Story 3 - Scheduled Automation via Cron (Priority: P2)

An operations engineer sets up a cron job to run the S3 import daily. The command exits with appropriate status codes so the cron job can detect success or failure and trigger alerts if needed.

**Why this priority**: While important for the daily automation use case, manual invocation still provides value without scheduling.

**Independent Test**: Can be tested by running the command and verifying exit codes match the documented behavior for success, partial success, and failure scenarios.

**Acceptance Scenarios**:

1. **Given** a successful import with no errors, **When** the command completes, **Then** the exit code is 0
2. **Given** an import with some validation errors but partial success, **When** the command completes, **Then** the exit code is 1 and a summary shows what succeeded and failed
3. **Given** a complete failure (e.g., cannot access S3 or authentication fails), **When** the command completes, **Then** the exit code is non-zero and an error message is logged to stderr

---

### User Story 4 - S3 Region Configuration (Priority: P2)

An administrator needs to specify which AWS region the S3 bucket is located in, since their organization uses region-specific buckets for compliance reasons.

**Why this priority**: Multi-region support is necessary for many organizations but has reasonable defaults for simpler setups.

**Independent Test**: Can be tested by configuring a bucket in a specific region and verifying the command can access it with the correct region parameter.

**Acceptance Scenarios**:

1. **Given** the admin specifies `--aws-region us-west-2`, **When** the import runs, **Then** the system connects to the S3 bucket in that region
2. **Given** no region is specified, **When** the import runs, **Then** the system uses the default region from AWS configuration or falls back to us-east-1

---

### Edge Cases

- What happens when the S3 object does not exist? → Clear error message indicating file not found in bucket
- What happens when the bucket does not exist or is inaccessible? → Clear error message about bucket access failure
- What happens when the file is larger than reasonable? → Enforce file size limit (10MB) to prevent memory issues
- What happens when the file format is invalid or corrupted? → Parsing errors are reported with details
- What happens when the S3 download is interrupted? → Error with retry guidance
- What happens when running concurrent imports? → System handles gracefully with duplicate detection
- What happens when the file encoding is unexpected? → Support UTF-8, detect/handle UTF-8 BOM and ISO-8859-1

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a new CLI subcommand under `manage-user-mappings` to import from S3 (e.g., `manage-user-mappings import-s3`)
- **FR-002**: System MUST accept required parameters: `--bucket` (S3 bucket name) and `--key` (S3 object key/path)
- **FR-003**: System MUST support optional parameter `--aws-region` to specify the AWS region (default: use AWS SDK default resolution)
- **FR-004**: System MUST support optional parameter `--aws-profile` to specify an AWS credential profile name
- **FR-005**: System MUST support AWS credential authentication via standard AWS SDK credential chain (environment variables → credential files → IAM roles)
- **FR-006**: System MUST support the `--dry-run` flag to validate file contents without persisting changes
- **FR-007**: System MUST support the same CSV and JSON file formats already supported by the existing `import` command
- **FR-008**: System MUST validate file size and reject files exceeding 10MB
- **FR-009**: System MUST reuse existing import logic from `UserMappingCliService.importMappingsFromFile` after downloading the S3 file
- **FR-010**: System MUST report clear error messages for S3-specific failures (authentication, bucket access, file not found)
- **FR-011**: System MUST return exit code 0 on success, non-zero on failure
- **FR-012**: System MUST log import operations for audit purposes (matching existing import audit logging)
- **FR-013**: System MUST clean up temporary files after import completes (success or failure)
- **FR-014**: System MUST support optional `--format` parameter (CSV, JSON, AUTO) matching existing import command behavior

### Key Entities *(include if feature involves data)*

- **S3 Import Parameters**: Bucket name, object key, AWS region, AWS profile, format, dry-run flag
- **UserMapping**: Existing entity - email, user reference, domain, AWS account ID, status (ACTIVE/PENDING)
- **MappingResult**: Existing result structure - total processed, created, skipped, errors

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Administrators can successfully import user mappings from an S3 bucket with a single CLI command
- **SC-002**: The import command can be scheduled via cron and runs without manual intervention when credentials are properly configured
- **SC-003**: Import operations complete within reasonable time for files containing up to 10,000 mapping entries
- **SC-004**: Failed imports produce actionable error messages that allow administrators to diagnose and fix issues
- **SC-005**: All imported mappings are correctly persisted and immediately effective for access control decisions

## Assumptions

- AWS SDK for Java is available or can be added as a dependency to the CLI module
- Administrators have appropriate IAM permissions to read from the configured S3 bucket
- The mapping file in S3 follows the same CSV/JSON format as the existing file-based import
- The S3 bucket uses standard S3 (not S3-compatible services like MinIO) unless explicitly tested
- Temporary file storage is available in the system's temp directory for download operations
