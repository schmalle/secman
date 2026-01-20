# Tasks: S3 User Mapping Import

**Input**: Design documents from `/specs/065-s3-user-mapping-import/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: Not requested in specification. Test tasks omitted per constitution (Principle IV: User-Requested Testing).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

## Path Conventions

- **CLI module**: `src/cli/src/main/kotlin/com/secman/cli/`
- **Commands**: `src/cli/src/main/kotlin/com/secman/cli/commands/`
- **Services**: `src/cli/src/main/kotlin/com/secman/cli/service/`
- **Build config**: `src/cli/build.gradle.kts`
- **Docs**: `src/cli/src/main/resources/cli-docs/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add AWS SDK dependencies and project configuration

- [x] T001 Add AWS SDK BOM and S3 dependency to src/cli/build.gradle.kts
- [x] T002 Verify build compiles with new dependencies by running `./gradlew :cli:compileKotlin`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Create S3 download service that all user stories depend on

**⚠️ CRITICAL**: User story implementation requires this phase to be complete

- [x] T003 Create S3DownloadService class skeleton in src/cli/src/main/kotlin/com/secman/cli/service/S3DownloadService.kt
- [x] T004 Implement S3Client builder with DefaultCredentialsProvider in S3DownloadService
- [x] T005 Implement downloadToTempFile() method with file size validation (10MB limit) in S3DownloadService
- [x] T006 Implement AWS exception translation to user-friendly error messages in S3DownloadService
- [x] T007 Add temp file cleanup in finally block to ensure cleanup on success or failure in S3DownloadService

**Checkpoint**: S3DownloadService ready - user story implementation can now begin

---

## Phase 3: User Story 1 & 2 - Core Import and Authentication (Priority: P1) 🎯 MVP

**Goal**: Administrator can import user mappings from S3 bucket with proper AWS authentication

**Independent Test**: Upload a CSV mapping file to S3, run `./bin/secman manage-user-mappings import-s3 --bucket <bucket> --key <key> --admin-user admin@example.com`, verify mappings appear in database

### Implementation for User Stories 1 & 2

- [x] T008 [US1] Create ImportS3Command class with @Command annotation in src/cli/src/main/kotlin/com/secman/cli/commands/ImportS3Command.kt
- [x] T009 [US1] Add --bucket and --key required parameters with @Option annotations in ImportS3Command.kt
- [x] T010 [US2] Add --aws-profile optional parameter with @Option annotation in ImportS3Command.kt
- [x] T011 [US1] Add --format optional parameter (CSV, JSON, AUTO) with @Option annotation in ImportS3Command.kt
- [x] T012 [US1] Add --dry-run optional boolean parameter with @Option annotation in ImportS3Command.kt
- [x] T013 [US1] Add @ParentCommand reference to ManageUserMappingsCommand for --admin-user inheritance in ImportS3Command.kt
- [x] T014 [US1] Inject S3DownloadService and UserMappingCliService dependencies in ImportS3Command.kt
- [x] T015 [US1] Implement run() method: download from S3 then delegate to importMappingsFromFile() in ImportS3Command.kt
- [x] T016 [US1] Add ImportS3Command to subcommands array in ManageUserMappingsCommand.kt
- [x] T017 [US2] Update S3DownloadService to support ProfileCredentialsProvider when --aws-profile provided
- [x] T018 [US1] Add import summary output matching existing ImportCommand format in ImportS3Command.kt

**Checkpoint**: Core S3 import works with default credentials and profile-based credentials

---

## Phase 4: User Story 3 - Scheduled Automation (Priority: P2)

**Goal**: Command exits with appropriate status codes for cron automation

**Independent Test**: Run command with valid file → exit 0; run with partially invalid file → exit 1; run with S3 error → exit 2+

### Implementation for User Story 3

- [x] T019 [US3] Implement exit code 0 for successful import (no errors) in ImportS3Command.kt
- [x] T020 [US3] Implement exit code 1 for partial success (some validation errors) in ImportS3Command.kt
- [x] T021 [US3] Implement exit code 2 for fatal errors (S3 access, auth failure) in ImportS3Command.kt
- [x] T022 [US3] Ensure error messages write to stderr (not stdout) for cron logging in ImportS3Command.kt

**Checkpoint**: Exit codes match documented behavior for automation

---

## Phase 5: User Story 4 - Region Configuration (Priority: P2)

**Goal**: Administrator can specify AWS region for multi-region bucket access

**Independent Test**: Run with `--aws-region eu-west-1` and verify connection to bucket in that region

### Implementation for User Story 4

- [x] T023 [US4] Add --aws-region optional parameter with @Option annotation in ImportS3Command.kt
- [x] T024 [US4] Update S3DownloadService to accept region parameter and configure S3Client.builder().region()
- [x] T025 [US4] Implement region fallback logic: explicit > AWS_REGION env > SDK default in S3DownloadService

**Checkpoint**: Region configuration works with explicit flag and environment variable

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, security hardening, and validation

- [x] T026 [P] Add S3 import documentation to src/cli/src/main/resources/cli-docs/USER_MAPPING_COMMANDS.md
- [x] T027 [P] Add bucket name validation (3-63 chars, lowercase, no special chars except hyphen) in S3DownloadService
- [x] T028 [P] Add object key validation (no null bytes, reasonable length) in S3DownloadService
- [x] T029 Verify audit logging works for S3 imports (should inherit from existing importMappingsFromFile)
- [x] T030 Run full integration test: build JAR, upload test file to S3, run import, verify mappings
- [x] T031 Validate quickstart.md examples work end-to-end

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories 1&2 (Phase 3)**: Depends on Foundational phase completion
- **User Story 3 (Phase 4)**: Depends on Phase 3 completion (builds on core command)
- **User Story 4 (Phase 5)**: Can start after Phase 2, but integrates better after Phase 3
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Stories 1 & 2 (P1)**: Combined because authentication is integral to S3 access - cannot import without auth
- **User Story 3 (P2)**: Builds on core command - adds exit code behavior
- **User Story 4 (P2)**: Independent of US3 - adds region parameter

### Within Each Phase

- T001 → T002 (add deps then verify)
- T003 → T004 → T005 → T006 → T007 (service build order)
- T008-T013 can run in parallel (different @Option annotations)
- T014-T016 sequential (implementation depends on parameters)
- T019-T022 sequential (exit code logic builds up)

### Parallel Opportunities

- T001, T002 sequential (dependency then build)
- T003-T007 sequential (service implementation order)
- T008-T013 partially parallel (different annotations, same file - suggest sequential for consistency)
- T019-T022 sequential (single file, related logic)
- T026-T028 fully parallel (different files, no dependencies)

---

## Parallel Example: Phase 6

```bash
# Launch all polish tasks together (different files):
Task: "Add S3 import documentation to USER_MAPPING_COMMANDS.md"
Task: "Add bucket name validation in S3DownloadService"
Task: "Add object key validation in S3DownloadService"
```

---

## Implementation Strategy

### MVP First (User Stories 1 & 2)

1. Complete Phase 1: Setup (add AWS SDK)
2. Complete Phase 2: Foundational (S3DownloadService)
3. Complete Phase 3: User Stories 1 & 2 (core import + auth)
4. **STOP and VALIDATE**: Test import with real S3 bucket
5. Deploy/demo if ready - basic S3 import works!

### Incremental Delivery

1. Complete Setup + Foundational → AWS SDK ready
2. Add US1 & US2 → Test with real S3 → MVP complete
3. Add US3 → Test exit codes → Cron-ready
4. Add US4 → Test multi-region → Full feature complete
5. Polish → Documentation and validation → Production-ready

### Single Developer Strategy (Recommended)

1. T001-T002: Setup (~5 min)
2. T003-T007: Foundation (~30 min)
3. T008-T018: Core import + auth (~45 min)
4. **TEST MVP**: Verify basic import works
5. T019-T022: Exit codes (~15 min)
6. T023-T025: Region config (~15 min)
7. T026-T031: Polish (~30 min)

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- User Stories 1 & 2 are combined because authentication is required for any S3 access
- No test tasks included per constitution (Principle IV)
- Verify AWS credentials configured before testing
- Commit after each phase completion for clean history
