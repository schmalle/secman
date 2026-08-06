# Implementation Tasks: CLI User Mapping Management

**Feature**: 049-cli-user-mappings
**Branch**: `049-cli-user-mappings`
**Spec**: [spec.md](./spec.md)
**Plan**: [plan.md](./plan.md)

## Overview

This document breaks down the implementation into executable tasks organized by user story priority. Each user story represents an independently testable increment that delivers value.

**MVP Scope**: User Story 1 (Map Domains to Users) - delivers core functionality
**Total Estimated Tasks**: 35 tasks

## Implementation Strategy

**Incremental Delivery**:
1. **Phase 1-2**: Foundation (setup, schema, shared infrastructure)
2. **Phase 3**: User Story 1 (P1) - Domain mapping - **MVP DELIVERABLE**
3. **Phase 4**: User Story 2 (P1) - AWS account mapping - extends MVP
4. **Phase 5**: User Story 3 (P2) - List mappings - adds visibility
5. **Phase 6**: User Story 4 (P2) - Remove mappings - completes lifecycle
6. **Phase 7**: User Story 5 (P3) - Batch import - adds efficiency
7. **Phase 8**: Polish - documentation, final integration

**Parallel Execution**: Tasks marked [P] can be executed in parallel within the same phase.

---

## Phase 1: Setup & Prerequisites

**Goal**: Initialize project structure and verify dependencies

**Duration**: 1-2 hours

### Tasks

- [X] T001 Verify Gradle dependencies include Picocli 4.7, Apache Commons CSV 1.11.0, Jackson in src/cli/build.gradle.kts
- [X] T002 Create directory structure: src/cli/src/main/kotlin/com/secman/cli/model/ and src/cli/src/main/resources/cli-docs/
- [X] T003 Verify existing UserMappingRepository and UserMappingService from Feature 042 are available
- [X] T004 Run `./gradlew build` to confirm baseline compiles successfully

---

## Phase 2: Foundational Infrastructure

**Goal**: Add shared entities, enums, and event listener that all user stories depend on

**Duration**: 4-6 hours

**Why Foundational**: These components are prerequisites for ALL user stories - schema changes, status tracking, and pending mapping activation are core to the feature.

### Tasks

- [X] T005 Create MappingStatus enum in src/backendng/src/main/kotlin/com/secman/domain/MappingStatus.kt with values PENDING, ACTIVE
- [X] T006 Add status field to UserMapping entity in src/backendng/src/main/kotlin/com/secman/domain/UserMapping.kt (VARCHAR(20), default 'ACTIVE')
- [X] T007 Update UserMapping @PrePersist to set status = PENDING if user is null, ACTIVE otherwise
- [X] T008 [P] Create BatchMappingResult data class in src/cli/src/main/kotlin/com/secman/cli/model/BatchMappingResult.kt
- [X] T009 [P] Create MappingOperation enum in src/cli/src/main/kotlin/com/secman/cli/model/MappingOperation.kt
- [X] T010 Create UserMappingApplicationService event listener in src/backendng/src/main/kotlin/com/secman/service/UserMappingApplicationService.kt
- [X] T011 Implement onUserCreated method to auto-apply pending mappings (find by email and status=PENDING, update to ACTIVE)
- [X] T012 Add findByEmailAndStatus repository method to UserMappingRepository for pending mapping queries
- [X] T013 Run `./gradlew build` and verify schema migration generates status column with indexes
- [X] T014 Verify existing user mappings default to ACTIVE status after migration

**Validation**: After this phase, database has status field, pending mappings can be created, and auto-activation works.

---

## Phase 3: User Story 1 - Map Domains to Users (P1) 🎯 MVP

**Goal**: Enable administrators to assign AD domains to users via CLI

**Independent Test**: Run `manage-user-mappings add-domain --emails user@example.com --domains example.com`, then verify user can access assets from that domain via web interface

**Duration**: 6-8 hours

### Tasks

- [X] T015 [US1] Create ManageUserMappingsCommand parent command class in src/cli/src/main/kotlin/com/secman/cli/commands/ManageUserMappingsCommand.kt with @Command annotation
- [X] T016 [US1] Add --admin-user global option and SECMAN_ADMIN_EMAIL environment variable support to ManageUserMappingsCommand
- [X] T017 [US1] Create AddDomainCommand subcommand in src/cli/src/main/kotlin/com/secman/cli/commands/AddDomainCommand.kt
- [X] T018 [US1] Add --emails (comma-separated) and --domains (comma-separated) options to AddDomainCommand with validation
- [X] T019 [US1] Create UserMappingCliService in src/cli/src/main/kotlin/com/secman/cli/service/UserMappingCliService.kt
- [X] T020 [US1] Implement addDomainMappings method with email/domain validation (regex patterns from FR-003, FR-006)
- [X] T021 [US1] Implement duplicate detection logic using existsByEmailAndAwsAccountIdAndDomain repository method
- [X] T022 [US1] Implement user existence check and status determination (ACTIVE vs PENDING)
- [X] T023 [US1] Add audit logging to UserMappingCliService for CREATE_DOMAIN_MAPPING operations
- [X] T024 [US1] Implement AddDomainCommand.run() with n×m cross-product mapping creation and summary output
- [X] T025 [US1] Add error handling for validation failures, database errors, and authorization failures
- [X] T026 [US1] Register AddDomainCommand as subcommand in ManageUserMappingsCommand (also integrated into SecmanCli.kt router with PicocliRunner, added findByEmailIgnoreCase to UserRepository)
- [ ] T027 [US1] Test: Run add-domain with single user/domain and verify mapping created with ACTIVE status
- [ ] T028 [US1] Test: Run add-domain with future user email and verify mapping created with PENDING status
- [ ] T029 [US1] Test: Run add-domain with duplicate mapping and verify it's skipped with warning
- [ ] T030 [US1] Test: Verify non-ADMIN user receives authorization error

**Deliverable**: Working `manage-user-mappings add-domain` command that creates domain mappings

---

## Phase 4: User Story 2 - Map AWS Accounts to Users (P1)

**Goal**: Enable administrators to assign AWS accounts to users via CLI

**Independent Test**: Run `manage-user-mappings add-aws --emails user@example.com --accounts 123456789012`, then verify user can access cloud assets from that account

**Duration**: 4-6 hours

**Why After US1**: Reuses same infrastructure (CLI service, validation patterns, auth) but different validation (12-digit AWS ID)

### Tasks

- [X] T031 [US2] Create AddAwsCommand subcommand in src/cli/src/main/kotlin/com/secman/cli/commands/AddAwsCommand.kt
- [X] T032 [US2] Add --emails and --accounts options with AWS account ID validation (12-digit regex from FR-004)
- [X] T033 [US2] Add addAwsAccountMappings method to UserMappingCliService with AWS account validation (already implemented in Phase 3)
- [X] T034 [US2] Reuse duplicate detection, user existence check, and audit logging from Phase 3 (already implemented via createMapping())
- [X] T035 [US2] Implement AddAwsCommand.run() with cross-product mapping creation and summary output
- [X] T036 [US2] Register AddAwsCommand as subcommand in ManageUserMappingsCommand
- [ ] T037 [US2] Test: Run add-aws with valid 12-digit account ID and verify mapping created
- [ ] T038 [US2] Test: Run add-aws with invalid account ID and verify validation error
- [ ] T039 [US2] Test: Verify pending mapping for future user with AWS account

**Deliverable**: Working `manage-user-mappings add-aws` command that creates AWS account mappings

---

## Phase 5: User Story 3 - List Existing Mappings (P2)

**Goal**: Enable administrators to view existing mappings with filtering and formatting

**Independent Test**: Create mappings via add-domain/add-aws, then run list command and verify output matches

**Duration**: 5-7 hours

**Why After US1-2**: Depends on mappings existing in database from previous stories; adds read-only visibility

### Tasks

- [X] T040 [US3] Create ListCommand subcommand in src/cli/src/main/kotlin/com/secman/cli/commands/ListCommand.kt
- [X] T041 [US3] Add --email, --status (ACTIVE/PENDING/ALL), --format (TABLE/JSON/CSV) options to ListCommand
- [X] T042 [US3] Add listMappings method to UserMappingCliService with filtering by email and status
- [X] T043 [US3] Implement TABLE format output with grouped display (email → domains/AWS accounts)
- [X] T044 [US3] Implement JSON format output matching schema in contracts/commands.md
- [X] T045 [US3] Implement CSV format output (email, type, value, status, created_at)
- [X] T046 [US3] Add summary statistics (total users, total mappings, active/pending counts)
- [X] T047 [US3] Register ListCommand as subcommand in ManageUserMappingsCommand
- [ ] T048 [US3] Test: List all mappings and verify TABLE format output
- [ ] T049 [US3] Test: List with --email filter and verify only that user's mappings shown
- [ ] T050 [US3] Test: List with --status PENDING and verify only pending mappings shown
- [ ] T051 [US3] Test: Export to JSON and verify valid JSON structure

**Deliverable**: Working `manage-user-mappings list` command with multiple output formats

---

## Phase 6: User Story 4 - Remove User Mappings (P2)

**Goal**: Enable administrators to revoke access by removing mappings

**Independent Test**: Create mappings, remove them via CLI, verify user loses access to assets

**Duration**: 4-5 hours

**Why After US3**: Depends on being able to verify removals via list command; completes lifecycle

### Tasks

- [X] T052 [US4] Create RemoveCommand subcommand in src/cli/src/main/kotlin/com/secman/cli/commands/RemoveCommand.kt
- [X] T053 [US4] Add --email (required), --domain, --account, --all options to RemoveCommand
- [X] T054 [US4] Add removeMappings method to UserMappingCliService with conditional deletion logic
- [X] T055 [US4] Implement removal by specific domain (delete where email + domain match)
- [X] T056 [US4] Implement removal by specific AWS account (delete where email + account match)
- [X] T057 [US4] Implement remove all mappings for user (delete where email matches)
- [X] T058 [US4] Add audit logging for DELETE_MAPPING operations
- [X] T059 [US4] Add error handling for "no mappings found" scenario
- [X] T060 [US4] Register RemoveCommand as subcommand in ManageUserMappingsCommand
- [ ] T061 [US4] Test: Remove specific domain mapping and verify deletion
- [ ] T062 [US4] Test: Remove specific AWS account mapping and verify deletion
- [ ] T063 [US4] Test: Remove all mappings with --all flag and verify all deleted
- [ ] T064 [US4] Test: Attempt to remove non-existent mapping and verify error message

**Deliverable**: Working `manage-user-mappings remove` command that deletes mappings

---

## Phase 7: User Story 5 - Batch Operations from File (P3)

**Goal**: Enable bulk import of mappings from CSV/JSON files

**Independent Test**: Create file with 100+ mappings, run import, verify all valid mappings created

**Duration**: 6-8 hours

**Why Last Story**: Depends on all core operations (add, validate, duplicate check) being complete; adds efficiency

### Tasks

- [X] T065 [US5] Create ImportCommand subcommand in src/cli/src/main/kotlin/com/secman/cli/commands/ImportCommand.kt
- [X] T066 [US5] Add --file, --format (CSV/JSON), --dry-run options to ImportCommand
- [X] T067 [US5] Implement CSV parsing using Apache Commons CSV with header validation
- [X] T068 [US5] Implement JSON parsing using Jackson with schema validation
- [X] T069 [US5] Add format auto-detection logic (file extension + content inspection)
- [X] T070 [US5] Add importMappingsFromFile method to UserMappingCliService with partial success mode
- [X] T071 [US5] Implement line-by-line validation and error collection for CSV
- [X] T072 [US5] Implement mapping-level validation and error collection for JSON
- [X] T073 [US5] Add batch insert optimization (using individual createMapping() calls with duplicate detection)
- [X] T074 [US5] Implement dry-run mode (validate without saving)
- [X] T075 [US5] Add detailed summary output (created, skipped, errors with line numbers)
- [X] T076 [US5] Register ImportCommand as subcommand in ManageUserMappingsCommand
- [ ] T077 [US5] Test: Import valid CSV file and verify all mappings created
- [ ] T078 [US5] Test: Import JSON file and verify expansion logic works
- [ ] T079 [US5] Test: Import file with errors and verify partial success with error report
- [ ] T080 [US5] Test: Dry-run mode validates without creating mappings

**Deliverable**: Working `manage-user-mappings import` command that handles CSV/JSON batch imports

---

## Phase 8: Polish & Documentation

**Goal**: Complete CLI documentation and final integration

**Duration**: 3-4 hours

**Cross-Cutting Concerns**: These tasks don't belong to a specific user story but are required for feature completion

### Tasks

- [X] T081 Create comprehensive USER_MAPPING_COMMANDS.md in src/cli/src/main/resources/cli-docs/ based on contracts/commands.md
- [X] T082 Add usage examples, troubleshooting guide, and best practices to USER_MAPPING_COMMANDS.md
- [X] T083 Add --help text to all commands (mixinStandardHelpOptions = true in @Command annotations)
- [X] T084 Verify all commands work with both --admin-user flag and SECMAN_ADMIN_EMAIL env var (implemented in getAdminUserOrThrow())
- [ ] T085 Run end-to-end workflow: add domains → add AWS → list → remove → verify (manual test for user)
- [ ] T086 Verify audit logs capture all operations with correct format (manual test for user)
- [X] T087 Run `./gradlew build` and confirm no compilation errors
- [X] T088 Update CLAUDE.md with CLI commands documentation reference
- [ ] T089 Create commit: "feat(cli): add user mapping management commands" (ready for user to commit)

---

## Dependencies & Execution Order

### Story Dependencies

```
Phase 1 (Setup)
  ↓
Phase 2 (Foundational) ← MUST complete before any user stories
  ↓
Phase 3 (US1: Domain Mapping) ← MVP deliverable, can stop here
  ↓
Phase 4 (US2: AWS Mapping) ← Extends MVP, reuses US1 patterns
  ↓
Phase 5 (US3: List) ← Depends on US1/US2 creating data
  ↓
Phase 6 (US4: Remove) ← Depends on US3 for verification
  ↓
Phase 7 (US5: Import) ← Depends on all core operations
  ↓
Phase 8 (Polish)
```

**Independent Stories**: None - all stories build on Phase 2 foundation

**Parallel Opportunities Within Phases**:
- Phase 2: T008 [P] and T009 [P] can run in parallel
- Phase 3: T027-T030 (tests) can run in parallel after T026 completes
- Phase 4: T037-T039 (tests) can run in parallel after T036 completes
- Phase 5: T048-T051 (tests) can run in parallel after T047 completes
- Phase 6: T061-T064 (tests) can run in parallel after T060 completes
- Phase 7: T077-T080 (tests) can run in parallel after T076 completes

### Task-Level Dependencies

**Critical Path** (minimum tasks for MVP):
```
T001-T004 (Setup)
→ T005-T014 (Foundation)
→ T015-T026 (US1 Core Implementation)
→ T027 (US1 Test)
```

---

## Testing Strategy

**Note**: Per Constitution IV (User-Requested Testing), formal test tasks are NOT included unless user explicitly requests testing.

**Manual Verification** (included in implementation tasks):
- Each user story phase includes manual test steps (T027-T030, T037-T039, etc.)
- These verify acceptance scenarios from spec.md
- Execute via `./gradlew cli:run --args='...'` commands

**If User Requests TDD**:
- Add JUnit/Micronaut Test tasks before implementation tasks
- Contract tests for each command
- Integration tests for UserMappingCliService
- End-to-end workflow tests

---

## Validation Checklist

**Before marking feature complete, verify**:

- [ ] All 5 user stories tested independently
- [ ] MVP (US1) deliverable works end-to-end
- [ ] Schema migration applied (status column exists)
- [ ] Pending mapping auto-activation works (US1 acceptance scenario 6)
- [ ] Duplicate detection works (US1 acceptance scenario 7)
- [ ] All commands accessible via `./gradlew cli:run --args='manage-user-mappings <subcommand>'`
- [ ] Help text available for all commands (`--help` flag)
- [ ] Audit logs capture all operations
- [ ] Performance: Batch import processes 100+ mappings/minute (SC-003)
- [ ] CLI documentation complete in USER_MAPPING_COMMANDS.md
- [ ] Build passes: `./gradlew build`

---

## Summary

**Total Tasks**: 89
**Task Breakdown by Phase**:
- Phase 1 (Setup): 4 tasks
- Phase 2 (Foundation): 10 tasks
- Phase 3 (US1 - Domain Mapping): 16 tasks
- Phase 4 (US2 - AWS Mapping): 9 tasks
- Phase 5 (US3 - List): 12 tasks
- Phase 6 (US4 - Remove): 13 tasks
- Phase 7 (US5 - Import): 16 tasks
- Phase 8 (Polish): 9 tasks

**Parallel Opportunities**: 11 tasks marked [P], plus test tasks within each phase

**MVP Scope**: Phases 1-3 (30 tasks) - delivers User Story 1 (domain mapping)

**Full Feature**: All 8 phases (89 tasks)

**Estimated Timeline**:
- MVP: 2-3 days
- Full Feature: 5-7 days

**Independent Story Testing**: Each user story (US1-US5) has explicit test tasks that verify acceptance scenarios without depending on other stories.
