# Tasks: Admin Summary Email

**Input**: Design documents from `/specs/070-admin-summary-email/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md

**Tests**: Not requested - no test tasks included (per Constitution Principle IV).

**Organization**: Tasks grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

Based on plan.md project structure:
- **CLI**: `src/cli/src/main/kotlin/com/secman/cli/`
- **Backend**: `src/backendng/src/main/kotlin/com/secman/`
- **Templates**: `src/backendng/src/main/resources/email-templates/`

---

## Phase 1: Setup

**Purpose**: Create base infrastructure shared by all user stories

- [ ] T001 [P] Create AdminSummaryLog entity in src/backendng/src/main/kotlin/com/secman/domain/AdminSummaryLog.kt
- [ ] T002 [P] Create ExecutionStatus enum in src/backendng/src/main/kotlin/com/secman/domain/AdminSummaryLog.kt (same file as entity)
- [ ] T003 [P] Create AdminSummaryLogRepository in src/backendng/src/main/kotlin/com/secman/repository/AdminSummaryLogRepository.kt
- [ ] T004 [P] Create HTML email template in src/backendng/src/main/resources/email-templates/admin-summary.html
- [ ] T005 [P] Create plain text email template in src/backendng/src/main/resources/email-templates/admin-summary.txt

**Checkpoint**: Database entity and email templates ready. User story implementation can begin.

---

## Phase 2: User Story 1 - Send Admin Summary Email via CLI (Priority: P1/MVP)

**Goal**: Core CLI command that sends summary email to all ADMIN users with system statistics.

**Independent Test**: Run `./bin/secman send-admin-summary` and verify all ADMIN users receive email with user/vuln/asset counts.

**FR Coverage**: FR-001 through FR-006, FR-009, FR-010, FR-011, FR-012, FR-013

### Implementation for User Story 1

- [ ] T006 [US1] Create AdminSummaryService in src/backendng/src/main/kotlin/com/secman/service/AdminSummaryService.kt with methods: getSystemStatistics(), getAdminRecipients(), sendSummaryEmail(), logExecution()
- [ ] T007 [US1] Implement getSystemStatistics() to query UserRepository.count(), VulnerabilityRepository.count(), AssetRepository.count()
- [ ] T008 [US1] Implement getAdminRecipients() using UserRepository.findByRolesContaining(User.Role.ADMIN) filtering users with valid email
- [ ] T009 [US1] Implement sendSummaryEmail() using EmailService.sendEmail() with template rendering
- [ ] T010 [US1] Implement logExecution() to persist AdminSummaryLog entity with execution results
- [ ] T011 [US1] Create AdminSummaryCliService in src/cli/src/main/kotlin/com/secman/cli/service/AdminSummaryCliService.kt bridging CLI to backend
- [ ] T012 [US1] Create SendAdminSummaryCommand in src/cli/src/main/kotlin/com/secman/cli/commands/SendAdminSummaryCommand.kt with basic execution flow
- [ ] T013 [US1] Register SendAdminSummaryCommand in src/cli/src/main/kotlin/com/secman/cli/SecmanCli.kt
- [ ] T014 [US1] Implement summary output showing recipients count, emails sent, failures
- [ ] T015 [US1] Implement exit code logic (0 for success, 1 for any failures)

**Checkpoint**: US1 complete - basic email sending works. Can run `./bin/secman send-admin-summary` successfully.

---

## Phase 3: User Story 2 - Dry Run Mode (Priority: P2)

**Goal**: Preview mode that shows what would be sent without actually sending emails.

**Independent Test**: Run `./bin/secman send-admin-summary --dry-run` and verify output shows recipients and statistics but no emails sent.

**FR Coverage**: FR-007

### Implementation for User Story 2

- [ ] T016 [US2] Add --dry-run flag to SendAdminSummaryCommand in src/cli/src/main/kotlin/com/secman/cli/commands/SendAdminSummaryCommand.kt
- [ ] T017 [US2] Update AdminSummaryCliService to accept dryRun parameter in src/cli/src/main/kotlin/com/secman/cli/service/AdminSummaryCliService.kt
- [ ] T018 [US2] Update AdminSummaryService.sendSummaryEmail() to skip actual sending when dryRun=true
- [ ] T019 [US2] Implement dry-run output formatting showing planned recipients and statistics
- [ ] T020 [US2] Log dry-run executions with status=DRY_RUN in AdminSummaryLog

**Checkpoint**: US2 complete - dry run mode works. Can preview without sending.

---

## Phase 4: User Story 3 - Verbose Output (Priority: P3)

**Goal**: Detailed per-recipient logging for troubleshooting.

**Independent Test**: Run `./bin/secman send-admin-summary --verbose` and verify per-recipient status is displayed.

**FR Coverage**: FR-008

### Implementation for User Story 3

- [ ] T021 [US3] Add --verbose flag to SendAdminSummaryCommand in src/cli/src/main/kotlin/com/secman/cli/commands/SendAdminSummaryCommand.kt
- [ ] T022 [US3] Update AdminSummaryCliService to accept verbose parameter
- [ ] T023 [US3] Implement per-recipient status output (success/failure with email address) when verbose=true
- [ ] T024 [US3] Add verbose logging for statistics gathering phase
- [ ] T025 [US3] Add verbose logging for skipped recipients (no email configured)

**Checkpoint**: US3 complete - verbose mode provides detailed troubleshooting info.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Documentation and final validation

- [ ] T026 Update CLAUDE.md with new CLI command send-admin-summary and AdminSummaryLog entity
- [ ] T027 Update docs/CLI.md with send-admin-summary command documentation
- [ ] T028 Run quickstart.md validation scenarios manually
- [ ] T029 Verify Hibernate auto-creates admin_summary_log table on startup

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies - can start immediately
- **Phase 2 (US1)**: Depends on Phase 1 completion
- **Phase 3 (US2)**: Depends on Phase 2 (US1) completion - extends CLI command
- **Phase 4 (US3)**: Depends on Phase 2 (US1) completion - can run parallel with US2
- **Phase 5 (Polish)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Setup - No dependencies on other stories
- **User Story 2 (P2)**: Depends on US1 (adds flag to existing command)
- **User Story 3 (P3)**: Depends on US1 (adds flag to existing command), independent of US2

### Parallel Opportunities

**Phase 1 (all can run in parallel)**:
```
T001 + T003 + T004 + T005 (different files, no dependencies)
```

**Phase 3 + Phase 4 (after US1 complete)**:
```
US2 (T016-T020) can run in parallel with US3 (T021-T025)
Both add flags to same command but touch different code paths
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T005)
2. Complete Phase 2: User Story 1 (T006-T015)
3. **STOP and VALIDATE**: Test basic email sending works
4. Deploy if ready - MVP complete!

### Incremental Delivery

1. Setup → Basic send works (MVP)
2. Add US2 → Dry run preview works
3. Add US3 → Verbose troubleshooting works
4. Polish → Documentation complete

### Task Counts

| Phase | Tasks | Parallel Opportunities |
|-------|-------|----------------------|
| Setup | 5 | All 5 can run in parallel |
| US1 (MVP) | 10 | T006 blocks T007-T015 |
| US2 | 5 | Sequential (extends US1) |
| US3 | 5 | Sequential (extends US1) |
| Polish | 4 | T026+T027 can run in parallel |
| **Total** | **29** | |

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- No test tasks included (per Constitution Principle IV - user-requested only)
- Entity uses Hibernate auto-migration (no Flyway scripts needed per CLAUDE.md)
- Reuses existing EmailService infrastructure
- Commit after each task or logical group
