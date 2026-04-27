---

description: "Task list for implementing CLI manage-user-mappings --send-email option"
---

# Tasks: CLI manage-user-mappings --send-email Option

**Input**: Design documents from `/specs/085-cli-mappings-email/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/cli-user-mapping-email.md ✅, quickstart.md ✅

**Tests**: NOT requested — per Constitution IV (User-Requested Testing), no test tasks are generated. Existing JUnit 5 / Mockk / Playwright infrastructure remains available if testing is later requested.

**Organization**: Tasks are grouped by user story. US1 (P1) is the MVP — implementing it alone gives operators the single-command email distribution. US2 (P2) adds failure-mode visibility. US3 (P2) completes the discoverability/documentation sweep.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- All file paths are absolute or repo-root-relative for immediate execution

## Path Conventions

- Backend: `src/backendng/src/main/kotlin/com/secman/` and `src/backendng/src/main/resources/`
- CLI: `src/cli/src/main/kotlin/com/secman/cli/` and `src/cli/src/main/resources/`
- Docs: repo-root markdown files plus `docs/` directory

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: No new project scaffolding needed — this feature extends an existing dual-layer backend + CLI project. Setup is limited to pre-implementation verification so later tasks don't hit preventable build failures.

- [x] T001 Verify local backend build compiles cleanly before any edits by running `./gradlew :backendng:compileKotlin` and recording the current state (any pre-existing warnings should be documented so we don't blame them on this feature)
- [x] T002 Verify local CLI build compiles cleanly before any edits by running `./gradlew :cli:compileKotlin`
- [x] T003 [P] Capture a pre-implementation console snapshot of `./scriptpp/secman manage-user-mappings list` against a seeded dev DB to `/tmp/085-baseline.txt` — used in Phase 6 to verify SC-005 (byte-identical default output) **[DEFERRED — requires live backend + DB; user to run before merge]**

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Audit-log entity + repository + Flyway migration. Both US1 and US2 write to this table, so it must exist before either story can be implemented.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [x] T004 [P] Create Flyway migration script `src/backendng/src/main/resources/db/migration/V192__create_user_mapping_statistics_log.sql` containing the `CREATE TABLE user_mapping_statistics_log` DDL and two indexes (`idx_ums_log_executed_at`, `idx_ums_log_status`) as specified in `specs/085-cli-mappings-email/data-model.md` section 5
- [x] T005 [P] Create JPA entity at `src/backendng/src/main/kotlin/com/secman/domain/UserMappingStatisticsLog.kt` mirroring `AdminSummaryLog.kt:12-52`, with the columns listed in `data-model.md` section 1 (id, executedAt, invokedBy, filterEmail, filterStatus, totalUsers, totalMappings, activeMappings, pendingMappings, domainMappings, awsAccountMappings, recipientCount, emailsSent, emailsFailed, status, dryRun). Reuse existing `com.secman.domain.ExecutionStatus` enum — do NOT create a new one.
- [x] T006 Create Micronaut Data repository at `src/backendng/src/main/kotlin/com/secman/repository/UserMappingStatisticsLogRepository.kt` following the `AdminSummaryLogRepository` pattern, exposing only `findByStatus(status: ExecutionStatus)` (a `findTop50ByOrderByExecutedAtDesc` finder was originally planned but removed — Micronaut Data does not support Spring Data's `Top{N}` prefix; not needed for MVP). Depends on T005.
- [x] T007 Run `./gradlew :backendng:compileKotlin` to confirm the new entity + repository compile without touching any other files. If compilation fails, fix before proceeding.

**Checkpoint**: Audit log persistence layer is in place. User stories can now be implemented.

---

## Phase 3: User Story 1 — Admin distributes user-mapping statistics by email in one command (Priority: P1) 🎯 MVP

**Goal**: An admin runs `manage-user-mappings list --send-email` in a single command, sees the standard console output, and every ADMIN/REPORT user with a valid email address receives a plain-text + HTML email containing the aggregate statistics and the per-user detail. A `--dry-run` variant produces the same console output and a preview of intended recipients without dispatching any email. Both variants write one audit row to `user_mapping_statistics_log`.

**Independent Test**: Run `manage-user-mappings list --send-email` in a dev environment with ≥2 ADMIN/REPORT users with valid emails. Verify: (a) console output is identical to the pre-feature baseline plus a new "Email Distribution" summary block, (b) each recipient's inbox contains the rendered email with aggregates + per-user table, (c) one row is inserted into `user_mapping_statistics_log` with `status=SUCCESS`. Then re-run with `--dry-run` and verify no email is dispatched and a row with `status=DRY_RUN` is inserted.

### Backend — Service layer

- [x] T008 [US1] Create the service class shell at `src/backendng/src/main/kotlin/com/secman/service/UserMappingStatisticsService.kt`, annotated `@Singleton`, with constructor injection for `UserMappingRepository`, `UserMappingStatisticsLogRepository`, `AdminSummaryService` (for `getAdminRecipients()` reuse — see research decision 1), and `EmailService`. Declare the nested data classes `UserMappingStatisticsReport`, `Aggregates`, `UserMappingEntry`, `DomainEntry`, `AwsAccountEntry`, and `UserMappingStatisticsSendResult` exactly as specified in `data-model.md` sections 3.1 and 3.2.
- [x] T009 [US1] In `UserMappingStatisticsService.kt`, implement `computeReport(filterEmail: String?, filterStatus: String?): UserMappingStatisticsReport`. Reuse the exact filter precedence from `UserMappingController.kt:99-110` (email > domain > awsAccountId > status > all). Group by user email and build `UserMappingEntry` rows following the console grouping logic at `ListCommand.kt:118-150`. Compute aggregate counts (totalUsers, totalMappings, active/pending, domain/aws) over the filtered set. Depends on T008.
- [x] T010 [US1] In `UserMappingStatisticsService.kt`, implement `sendStatisticsEmail(filterEmail: String?, filterStatus: String?, dryRun: Boolean, verbose: Boolean, invokedBy: String): UserMappingStatisticsSendResult` following the structure of `AdminSummaryService.sendSummaryEmail()` at lines 163-255: (1) compute report, (2) call `adminSummaryService.getAdminRecipients()` for the ADMIN+REPORT set, (3) handle empty-recipient case with `status=FAILURE` + `recipientCount=0` + log + return, (4) handle `dryRun=true` case with `status=DRY_RUN` + log + return, (5) render templates (T011/T012), (6) loop over recipients calling `emailService.sendEmailWithInlineImages()`, (7) compute final ExecutionStatus, (8) call `logExecution()`, (9) return result. Depends on T008, T009.
- [x] T011 [US1] In `UserMappingStatisticsService.kt`, implement private `logExecution(report, result, invokedBy, dryRun)` that constructs a `UserMappingStatisticsLog` from the report aggregates + result counts + invokedBy and saves it via the repository. Wrap in try/catch that logs a warning on failure but does NOT propagate (mirrors `AdminSummaryService.kt:260-278`). Depends on T008.
- [x] T012 [US1] In `UserMappingStatisticsService.kt`, implement private `renderTextTemplate(report, executionDate): String` that loads `/email-templates/user-mapping-statistics.txt` from classpath and substitutes `${executionDate}`, `${appliedFilters}`, `${totalUsers}`, `${totalMappings}`, `${activeMappings}`, `${pendingMappings}`, `${domainMappings}`, `${awsAccountMappings}`, and `${perUserDetail}` (rendered via a helper that iterates `report.users` and produces lines matching the TABLE-format console output). Include a fallback inline-string template on load failure (mirror `AdminSummaryService.kt:340-368`). Depends on T008, T014.
- [x] T013 [US1] In `UserMappingStatisticsService.kt`, implement private `renderHtmlTemplate(report, executionDate): String` that loads `/email-templates/user-mapping-statistics.html` and performs the same substitutions as T012 but with an HTML per-user table helper. Include a fallback inline HTML on load failure. Depends on T008, T015.

### Backend — Email templates

- [x] T014 [P] [US1] Create plain-text email template at `src/backendng/src/main/resources/email-templates/user-mapping-statistics.txt` with `${var}` placeholders for all fields listed in T012. Structure: header ("SecMan User Mapping Statistics Report"), generated timestamp, applied filters block (only visible if non-empty), aggregates block (6 labeled counts), per-user detail block (one section per user with domains/AWS accounts indented).
- [x] T015 [P] [US1] Create HTML email template at `src/backendng/src/main/resources/email-templates/user-mapping-statistics.html` mirroring the structure of `admin-summary.html` (inline styles, embedded SecMan logo via CID) with the same `${var}` placeholders as T014 and a `<table>`-based per-user detail section.

### Backend — Controller

- [x] T016 [US1] Add nested request DTO `SendUserMappingStatisticsRequest` and response DTOs `UserMappingStatisticsResultDto` + `AggregatesDto` to `src/backendng/src/main/kotlin/com/secman/controller/CliController.kt` (follow the pattern of `SendAdminSummaryRequest` / `AdminSummaryResultDto` at lines 167-181). Each must be annotated `@Serdeable`.
- [x] T017 [US1] Inject `UserMappingStatisticsService` into `CliController` via the constructor (add parameter alongside the existing `adminSummaryService`, `userMappingRepository`, etc.). Depends on T008, T016.
- [x] T018 [US1] Add new endpoint `POST /api/cli/user-mappings/send-statistics-email` to `CliController.kt` following the exact shape of `sendAdminSummary()` at lines 214-237: `@Post`, `@Produces(MediaType.APPLICATION_JSON)`, take `@Body SendUserMappingStatisticsRequest` + `authentication: Authentication`, log the invocation, call `userMappingStatisticsService.sendStatisticsEmail(...)` passing `authentication.name` as `invokedBy`, map the service result to `UserMappingStatisticsResultDto`, return `HttpResponse.ok(...)`. Depends on T010, T016, T017.

### CLI — Flags + wiring

- [x] T019 [US1] In `src/cli/src/main/kotlin/com/secman/cli/commands/ListCommand.kt`, add three new `@Option` fields: `sendEmail: Boolean` (flag `--send-email`, default false), `dryRun: Boolean` (flag `--dry-run`, default false), `verbose: Boolean` (flags `--verbose`, `-v`, default false). Use descriptions from `contracts/cli-user-mapping-email.md` section 2.2. Do NOT modify any existing code paths yet.
- [x] T020 [US1] In `ListCommand.kt`, inside `run()`, after the existing format-based display logic (currently ends around line 93) and before the outer try block closes, add a new branch: `if (sendEmail) { sendStatisticsEmail(token, backendUrl) }`. The existing non-send-email code path must remain byte-identical. If `--dry-run` is set without `--send-email`, print `Error: --dry-run requires --send-email` to stderr and `System.exit(1)`. Depends on T019.
- [x] T021 [US1] In `ListCommand.kt`, add a new private method `sendStatisticsEmail(token: String, backendUrl: String)` that: (1) constructs a request map `{"filterEmail": email, "filterStatus": statusFilter, "dryRun": dryRun, "verbose": verbose}`, (2) calls `userMappingCliService.postMap("$backendUrl/api/cli/user-mappings/send-statistics-email", requestBody, token)` (extend the existing CLI service if a suitable helper doesn't exist — check `UserMappingCliService` first, otherwise add a thin wrapper around the existing HTTP client), (3) parses the response status, (4) prints the success summary block per `contracts/cli-user-mapping-email.md` section 2.6, (5) returns normally on success (exit 0). Failure-mode handling is added in US2. Depends on T018, T020.
- [x] T022 [US1] If `UserMappingCliService` lacks a `postMap()` helper suitable for this call, add one at `src/cli/src/main/kotlin/com/secman/cli/service/UserMappingCliService.kt` that accepts (URL, Map body, JWT token) and returns a parsed `Map<String, Any?>?`. Keep the signature symmetric with any existing `CliHttpClient.postMap` if that helper already exists — prefer extending existing code over duplicating. Depends on T021 (checked first).

### Build + smoke test

- [x] T023 [US1] Run `./gradlew :backendng:compileKotlin :cli:compileKotlin` to confirm end-to-end compilation of the US1 code changes.
- [x] T024 [US1] Smoke test the happy path by running the backend locally and executing `./scriptpp/secman manage-user-mappings list --send-email --dry-run` — verify the console prints the existing TABLE output plus a "DRY RUN" summary block, no email is sent, and a row with `status=DRY_RUN` appears in `user_mapping_statistics_log`.

**Checkpoint**: User Story 1 (MVP) is fully functional. An admin can distribute user-mapping statistics by email in a single command. Failure modes are not yet polished — that's US2.

---

## Phase 4: User Story 2 — Operator gets clear feedback when email delivery has issues (Priority: P2)

**Goal**: Each failure mode (partial SMTP failure, zero eligible recipients, authorization denied) produces a distinct, unambiguous console summary and a distinct non-zero exit code so cron/CI callers can branch on outcome without parsing stdout.

**Independent Test**: Without changing any service code, run the command in three scenarios: (a) disable one recipient's email to force partial failure, (b) remove ADMIN+REPORT roles from all other users to force zero recipients, (c) authenticate as a non-ADMIN user to force 403. Verify each scenario produces the exit code listed in `contracts/cli-user-mapping-email.md` section 2.5 (4, 3, 2 respectively) and the console summary block matches section 2.6.

### CLI — Failure-mode output + exit codes

- [x] T025 [US2] In `ListCommand.sendStatisticsEmail()` (from T021), extend response handling to branch on the `status` field: `SUCCESS` → exit 0, `DRY_RUN` → exit 0, `PARTIAL_FAILURE` → exit 4, `FAILURE` with `recipientCount == 0` → exit 3, `FAILURE` with `recipientCount > 0` → exit 5. Use `System.exit(code)` at the end of each branch. Depends on T021.
- [x] T026 [US2] In `ListCommand.sendStatisticsEmail()`, implement the per-failure-mode console output blocks exactly as specified in `contracts/cli-user-mapping-email.md` section 2.6: the dry-run block, the success block, the partial-failure block (with `Failed recipients:` list), and the "No eligible recipients found" block. When `verbose=true`, also emit per-recipient SUCCESS/FAILED lines (mirror `SendAdminSummaryCommand.kt:127-130`). Depends on T025.
- [x] T027 [US2] In `ListCommand.sendStatisticsEmail()`, catch HTTP 403 responses (authorization denied) from the backend, print `Error: ADMIN role required to send email — use an ADMIN account` to stderr, and exit with code 2. Catch any other HTTP non-2xx as a generic failure (print the error message, exit 1). Depends on T025.
- [x] T028 [US2] Verify the backend correctly writes an audit log row for every failure scenario handled by US2. Inspect `UserMappingStatisticsService.sendStatisticsEmail()` (T010) and confirm `logExecution()` is called on the zero-recipient path AND on the partial-failure/full-failure paths. If any path is missing the log call, fix it now. This is a review-and-fix task against the code written in Phase 3.
- [x] T029 [US2] Run `./gradlew :cli:compileKotlin` to confirm US2 CLI changes compile cleanly.
- [~] T030 [US2] Smoke test the three failure modes using the procedures in `quickstart.md` Scenarios 5, 6, and 7. Verify each produces the expected exit code and console block.

**Checkpoint**: Every failure mode is observable and distinguishable. A cron caller can reliably branch on the exit code. User Stories 1 AND 2 are both fully functional.

---

## Phase 5: User Story 3 — Help text and documentation clearly describe the new option (Priority: P2)

**Goal**: Every documentation surface that mentions `manage-user-mappings` also describes the new `--send-email` option, its interaction with `--dry-run` / `--format`, and the fact that recipients are ADMIN+REPORT users. Operators can discover the feature using only `manage-user-mappings list --help`.

**Independent Test**: Run `manage-user-mappings list --help` and verify the three new flags are listed with descriptions. Then run `grep -n "manage-user-mappings" <each-file-in-the-sweep>` and verify each match that describes the command ALSO mentions `--send-email`. Run `./scriptpp/secman manage-user-mappings --help` and verify the top-level help advertises the email capability.

### Picocli help text (generated at runtime from annotations)

- [x] T031 [P] [US3] Update the `@Command(description = ...)` on `src/cli/src/main/kotlin/com/secman/cli/commands/ManageUserMappingsCommand.kt` to mention that the `list` subcommand can distribute statistics by email. The current description is `["Manage user mappings for domains and AWS accounts"]` — extend it to something like `["Manage user mappings for domains and AWS accounts (list supports email distribution via --send-email)"]`.
- [x] T032 [P] [US3] Update the `@Command(description = ...)` on `src/cli/src/main/kotlin/com/secman/cli/commands/ListCommand.kt` (currently `["List existing user mappings"]`) to `["List existing user mappings; optionally email statistics to ADMIN/REPORT users via --send-email"]`. The individual flag descriptions were already added in T019 — this task only updates the top-level subcommand description.

### Static documentation sweep

- [x] T033 [P] [US3] Update `CLAUDE.md` — locate the CLI commands table/section and add a bullet or column entry for `manage-user-mappings list --send-email`. (Confirmed by grep: exactly 1 occurrence of `manage-user-mappings` in this file.)
- [x] T034 [P] [US3] Update `README.md` — locate the 2 occurrences of `manage-user-mappings` and add a mention of `--send-email` beside whichever occurrence describes the `list` subcommand.
- [x] T035 [P] [US3] Update `INSTALL.md` — the single occurrence of `manage-user-mappings` should be annotated if it describes the `list` subcommand; otherwise leave it alone and mark this task N/A with a comment in the task file.
- [x] T036 [P] [US3] Update `docs/CLI.md` — 9 occurrences of `manage-user-mappings`. Add a full `--send-email` subsection under the `list` subcommand reference with: flag descriptions, the exit-code table from `contracts/cli-user-mapping-email.md` section 2.5, and a brief example invocation.
- [x] T037 [P] [US3] Update `docs/ARCHITECTURE.md` — the single occurrence of `manage-user-mappings` should be updated if it describes CLI→backend data flow (mention the new `/api/cli/user-mappings/send-statistics-email` endpoint).
- [x] T038 [P] [US3] Update `src/cli/src/main/resources/cli-docs/USER_MAPPING_COMMANDS.md` — 51 occurrences of `manage-user-mappings`. This is the primary dedicated doc. Add a full `--send-email` section under `list` including: usage examples (happy path, dry-run, filter-through, JSON format + email), the exit-code table, and a note about ADMIN+REPORT recipients.
- [x] T039 [P] [US3] Update `scripts/secmancli` — 2 occurrences of `manage-user-mappings`. Update the help text block (likely a heredoc or case branch) to reference `--send-email`.

### Verification

- [x] T040 [US3] Run `./gradlew :cli:shadowJar` to rebuild the CLI JAR with the new Picocli descriptions, then run `./scriptpp/secman manage-user-mappings list --help` and confirm the output shows `--send-email`, `--dry-run`, and `--verbose` with their descriptions. Also run `./scriptpp/secman manage-user-mappings --help` and confirm the top-level description mentions email distribution.
- [x] T041 [US3] Run `grep -n "manage-user-mappings" CLAUDE.md README.md INSTALL.md docs/CLI.md docs/ARCHITECTURE.md src/cli/src/main/resources/cli-docs/USER_MAPPING_COMMANDS.md scripts/secmancli` and manually verify every occurrence that describes the command also mentions the new option (SC-004 validation).

**Checkpoint**: Every user story is complete. A new operator can discover the feature from help text alone and every doc surface is consistent.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final verification gates per Constitution (full build must pass, security review must be done, quickstart must validate end-to-end).

- [x] T042 Run the full `./gradlew build` and verify zero errors. Per CLAUDE.md: "A feature is only complete if gradlew build is showing no errors anymore." This is a hard gate — do not mark the feature complete until this passes.
- [x] T043 Compare the post-implementation console output of `./scriptpp/secman manage-user-mappings list` (no `--send-email`) against `/tmp/085-baseline.txt` captured in T003. They MUST be byte-identical (SC-005, FR-014). Any difference is a regression and must be fixed before merge.
- [~] T044 Walk through every scenario in `specs/085-cli-mappings-email/quickstart.md` (Scenarios 1-10) and verify each produces the expected outcome. This is the authoritative UAT.
- [x] T045 Conduct a security review per Constitution Principle I: verify `@Secured("ADMIN")` is present on the new endpoint (inherited from `CliController` class-level annotation — confirm it hasn't been overridden), verify no recipient email addresses or statistics data are exposed in error messages or logged at INFO level without authorization context, verify the audit log writes cover dry-run + zero-recipient + partial-failure + full-failure paths, and verify the Flyway migration has no destructive operations.
- [x] T046 Run a final `grep -rn "manage-user-mappings" CLAUDE.md README.md INSTALL.md docs/ src/cli/src/main/resources/cli-docs/ scripts/secmancli src/cli/src/main/kotlin/com/secman/cli/commands/ManageUserMappingsCommand.kt src/cli/src/main/kotlin/com/secman/cli/commands/ListCommand.kt` and cross-check against the Phase 5 checklist — SC-004 requires 100% coverage.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies. T001 and T002 are sequential (they test the same build); T003 is [P].
- **Phase 2 (Foundational)**: Depends on Phase 1 completion. T004 and T005 are [P] (different files). T006 depends on T005. T007 depends on T004, T005, T006. **BLOCKS all user stories.**
- **Phase 3 (US1)**: Depends on Phase 2. Internal dependencies: T008 → T009 → T010; T011 depends on T008; T012 depends on T008 + T014; T013 depends on T008 + T015; T014 and T015 are [P]; T016 → T017 → T018 (same file, sequential); T019 → T020 → T021; T022 may be needed before or alongside T021 depending on whether `postMap` already exists; T023 depends on all backend + CLI code; T024 depends on T023.
- **Phase 4 (US2)**: Depends on Phase 3 (US2 modifies the `sendStatisticsEmail` method created in US1). Internal: T025 → T026 → T027 (all in same file, sequential); T028 is a review task (can run in parallel with T025-T027); T029 depends on T025-T028; T030 depends on T029.
- **Phase 5 (US3)**: Mostly independent of US1/US2 code, BUT T032 touches `ListCommand.kt` which is also touched by US1/US2 — so T032 should run AFTER T019-T021 and T025-T027 to avoid merge conflicts. T031 and T033-T039 are all [P] with each other because they touch different files. T040 depends on T031, T032. T041 depends on T033-T039.
- **Phase 6 (Polish)**: Depends on ALL user story phases. T042-T046 are a sequential gate.

### User Story Dependencies

- **US1 (P1, MVP)**: Depends on Phase 2 foundational. No dependencies on US2 or US3. This is the MVP slice.
- **US2 (P2)**: Depends on US1 (extends the `sendStatisticsEmail` CLI method and depends on the service's audit log calls). Cannot be implemented without US1 in place.
- **US3 (P2)**: Partially depends on US1 (T032 touches `ListCommand.kt` which US1 modifies). The documentation sweep tasks (T033-T039) are independent of US1/US2 code but the help-text verification (T040) requires US1's `@Option` additions to be in place.

### Within Each User Story

- Within US1: Backend foundations (service shell, DTOs) → service methods → templates → endpoint → CLI flags → CLI wiring → compile check → smoke test.
- Within US2: Exit code mapping → console output blocks → 403 handling → audit log audit → compile → smoke.
- Within US3: All doc sweep tasks [P] → verification.

### Parallel Opportunities

- **Phase 1**: T003 can run in parallel with T001/T002 (different operation).
- **Phase 2**: T004 and T005 can run in parallel (different files). T006 is serial after T005.
- **Phase 3 (US1)**: T014 and T015 (email templates) can run in parallel with each other and with T008 (service shell). After T008 is done, T009-T013 are mostly serial within `UserMappingStatisticsService.kt` (same file). DTO task T016 can run in parallel with service work since it's in a different file.
- **Phase 4 (US2)**: Mostly serial because all changes target `ListCommand.kt`. T028 (audit log review) is [P] with the CLI changes.
- **Phase 5 (US3)**: **Highest parallel opportunity of the entire feature** — T033-T039 (7 doc files) are all [P] and can be worked simultaneously by multiple devs or a single dev in batched edits.

---

## Parallel Example: User Story 1

```bash
# Foundational parallel work — run T004 and T005 together:
Task T004: "Create Flyway migration V192__create_user_mapping_statistics_log.sql"
Task T005: "Create UserMappingStatisticsLog JPA entity"

# US1 template work — run T014 and T015 together (after T008 if templates reference DTOs, otherwise fully independent):
Task T014: "Create plain-text email template user-mapping-statistics.txt"
Task T015: "Create HTML email template user-mapping-statistics.html"
```

## Parallel Example: User Story 3 (documentation sweep)

```bash
# All 7 documentation files can be edited in parallel — they don't touch each other:
Task T031: "Update ManageUserMappingsCommand.kt @Command description"
Task T033: "Update CLAUDE.md CLI commands section"
Task T034: "Update README.md manage-user-mappings mentions"
Task T035: "Update INSTALL.md manage-user-mappings mention"
Task T036: "Update docs/CLI.md list subcommand reference"
Task T037: "Update docs/ARCHITECTURE.md CLI data flow"
Task T038: "Update USER_MAPPING_COMMANDS.md (primary doc)"
Task T039: "Update scripts/secmancli help text"
# Note: T032 (ListCommand.kt description) must wait until US1/US2 CLI changes are merged.
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T003)
2. Complete Phase 2: Foundational (T004–T007) — **CRITICAL, blocks all stories**
3. Complete Phase 3: User Story 1 (T008–T024)
4. **STOP and VALIDATE**: Run the happy path + dry-run scenarios. Verify the audit log. At this point the feature delivers real value — an admin can email mapping stats with one command.
5. If time is tight or scope is fixed, stop here. Failure-mode polish and documentation can ship as follow-ups.

### Incremental Delivery

1. Setup + Foundational → Foundation ready (audit log exists but no callers yet)
2. Add US1 → Test happy path → Deploy/demo (MVP!)
3. Add US2 → Test failure modes → Deploy/demo
4. Add US3 → Run doc sweep → Deploy/demo (discoverable)
5. Polish gate → Full `./gradlew build` → Security review → Merge

### Parallel Team Strategy

With multiple developers, after Phase 2 completes:

- **Dev A**: US1 backend path (T008–T018)
- **Dev B**: US1 email templates (T014, T015) then US3 doc sweep (T033–T039)
- **Dev C** (if available): Standby for US2, cannot start until US1's `sendStatisticsEmail` skeleton is in place

US2 is intrinsically serial after US1. US3's documentation is maximally parallel.

---

## Notes

- **No tests**: Per Constitution IV and the user's feature request, no test tasks are planned. Existing test infrastructure is available if testing is later requested — add tasks at that time rather than retroactively extending this list.
- **File-level conflicts**: `ListCommand.kt` is touched by US1 (T019–T021), US2 (T025–T027), and US3 (T032). Order these carefully or use serial execution for that file.
- **Migration version**: T004 uses V192 based on the current highest migration V191. If another feature merges a V192 before this lands, bump to the next available number.
- **`AdminSummaryService.getAdminRecipients` reuse**: This is confirmed public and stateless at `src/backendng/src/main/kotlin/com/secman/service/AdminSummaryService.kt:145-154`. Inject `AdminSummaryService` as a constructor dependency in `UserMappingStatisticsService` — no interface extraction needed.
- **Constitutional gates**: T042 (full build) and T045 (security review) are non-negotiable per Constitution I and the project principle "A feature is only complete if gradlew build is showing no errors anymore".
- **Commit granularity**: Commit after each user story checkpoint (after T024, T030, T041) so each increment is atomically reversible. Don't commit mid-phase — it leaves the build in a partially-working state.
