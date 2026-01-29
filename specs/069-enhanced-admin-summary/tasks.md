# Tasks: Enhanced Admin Summary Email

**Input**: Design documents from `/specs/069-enhanced-admin-summary/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, quickstart.md

**Tests**: Not requested. No test tasks included per constitution Principle IV.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend service**: `src/backendng/src/main/kotlin/com/secman/service/`
- **Backend config**: `src/backendng/src/main/kotlin/com/secman/config/`
- **Email templates**: `src/backendng/src/main/resources/email-templates/`
- **CLI commands**: `src/cli/src/main/kotlin/com/secman/cli/commands/`
- **CLI service**: `src/cli/src/main/kotlin/com/secman/cli/service/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Extend the data model and inject dependencies needed by all user stories

- [x] T001 Extend `SystemStatistics` data class with `vulnerabilityStatisticsUrl: String`, `topProducts: List<ProductSummary>`, `topServers: List<ServerSummary>` fields and add `ProductSummary` and `ServerSummary` inner data classes in `src/backendng/src/main/kotlin/com/secman/service/AdminSummaryService.kt`
- [x] T002 Inject `AppConfig` and `VulnerabilityStatisticsService` into `AdminSummaryService` constructor in `src/backendng/src/main/kotlin/com/secman/service/AdminSummaryService.kt`
- [x] T003 Add admin-level (no-auth) methods to gather top-10 products and top-10 servers data in `AdminSummaryService`, querying `VulnerabilityStatisticsService` repositories directly without `Authentication` parameter, and update `getSystemStatistics()` to populate all new fields in `src/backendng/src/main/kotlin/com/secman/service/AdminSummaryService.kt`

**Checkpoint**: `SystemStatistics` now contains the vulnerability statistics URL, top 10 products, and top 10 servers. Build should compile: `./gradlew build`

---

## Phase 2: User Story 1 - Clickable Link to Vulnerability Statistics (Priority: P1) 🎯 MVP

**Goal**: Add a clickable link to the vulnerability statistics page in the admin summary email

**Independent Test**: Send email with `./bin/secmanng send-admin-summary --dry-run` and verify the URL `{base-url}/vulnerability-statistics` appears in output

### Implementation for User Story 1

- [x] T004 [P] [US1] Add a call-to-action button/link section with `${vulnerabilityStatisticsUrl}` placeholder to the HTML email template, placed between the existing statistics summary and the end of content, styled as a prominent button (blue background, white text) in `src/backendng/src/main/resources/email-templates/admin-summary.html`
- [x] T005 [P] [US1] Add a vulnerability statistics URL line with `${vulnerabilityStatisticsUrl}` placeholder to the plain-text email template, placed after the existing statistics section in `src/backendng/src/main/resources/email-templates/admin-summary.txt`
- [x] T006 [US1] Update `renderHtmlTemplate()` and `renderTextTemplate()` methods to replace `${vulnerabilityStatisticsUrl}` with the actual URL from `SystemStatistics` in `src/backendng/src/main/kotlin/com/secman/service/AdminSummaryService.kt`
- [x] T007 [US1] Update `SendAdminSummaryCommand.run()` to print the vulnerability statistics URL in dry-run and verbose output in `src/cli/src/main/kotlin/com/secman/cli/commands/SendAdminSummaryCommand.kt`

**Checkpoint**: Email contains clickable link to vulnerability statistics page. Verify with `./gradlew build && ./bin/secmanng send-admin-summary --dry-run`

---

## Phase 3: User Story 2 - Top 10 Most Affected Products (Priority: P2)

**Goal**: Add a top 10 most affected products section to the admin summary email

**Independent Test**: Send email with `./bin/secmanng send-admin-summary --dry-run --verbose` and verify top 10 products list appears

### Implementation for User Story 2

- [x] T008 [P] [US2] Add a "Top 10 Most Affected Products" HTML table section with `${topProductsHtml}` placeholder to the HTML email template, placed after the call-to-action button, with empty-state message "No vulnerability data available" support in `src/backendng/src/main/resources/email-templates/admin-summary.html`
- [x] T009 [P] [US2] Add a "Top 10 Most Affected Products" ASCII-formatted section with `${topProductsText}` placeholder to the plain-text email template in `src/backendng/src/main/resources/email-templates/admin-summary.txt`
- [x] T010 [US2] Add `renderTopProductsHtml(products: List<ProductSummary>): String` and `renderTopProductsText(products: List<ProductSummary>): String` helper methods that generate the pre-rendered HTML table rows / ASCII lines, handling empty list with "No vulnerability data available" message, and wire into `renderHtmlTemplate()` and `renderTextTemplate()` in `src/backendng/src/main/kotlin/com/secman/service/AdminSummaryService.kt`
- [x] T011 [US2] Update `SendAdminSummaryCommand.run()` to print top 10 products (name + count) in dry-run and verbose output in `src/cli/src/main/kotlin/com/secman/cli/commands/SendAdminSummaryCommand.kt`

**Checkpoint**: Email contains top 10 products section. Verify with `./gradlew build && ./bin/secmanng send-admin-summary --dry-run --verbose`

---

## Phase 4: User Story 3 - Top 10 Most Affected Servers (Priority: P3)

**Goal**: Add a top 10 most affected servers section to the admin summary email

**Independent Test**: Send email with `./bin/secmanng send-admin-summary --dry-run --verbose` and verify top 10 servers list appears

### Implementation for User Story 3

- [x] T012 [P] [US3] Add a "Top 10 Most Affected Servers" HTML table section with `${topServersHtml}` placeholder to the HTML email template, placed after the products section, with empty-state message support in `src/backendng/src/main/resources/email-templates/admin-summary.html`
- [x] T013 [P] [US3] Add a "Top 10 Most Affected Servers" ASCII-formatted section with `${topServersText}` placeholder to the plain-text email template in `src/backendng/src/main/resources/email-templates/admin-summary.txt`
- [x] T014 [US3] Add `renderTopServersHtml(servers: List<ServerSummary>): String` and `renderTopServersText(servers: List<ServerSummary>): String` helper methods that generate the pre-rendered HTML table rows / ASCII lines, handling empty list with "No vulnerability data available" message, and wire into `renderHtmlTemplate()` and `renderTextTemplate()` in `src/backendng/src/main/kotlin/com/secman/service/AdminSummaryService.kt`
- [x] T015 [US3] Update `SendAdminSummaryCommand.run()` to print top 10 servers (name + count) in dry-run and verbose output in `src/cli/src/main/kotlin/com/secman/cli/commands/SendAdminSummaryCommand.kt`

**Checkpoint**: Email contains top 10 servers section. Verify with `./gradlew build && ./bin/secmanng send-admin-summary --dry-run --verbose`

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Final verification and documentation

- [x] T016 Run full build verification with `./gradlew build` to ensure no compilation errors or test regressions
- [x] T017 Run end-to-end dry-run verification with `./bin/secmanng send-admin-summary --dry-run --verbose` and confirm all three new sections (link, products, servers) appear correctly

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately. T001 → T002 → T003 (sequential, same file)
- **User Story 1 (Phase 2)**: Depends on Setup (Phase 1) completion
- **User Story 2 (Phase 3)**: Depends on Setup (Phase 1) completion. Can run in parallel with US1 (different template sections)
- **User Story 3 (Phase 4)**: Depends on Setup (Phase 1) completion. Can run in parallel with US1/US2 (different template sections)
- **Polish (Phase 5)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Setup — no dependencies on other stories
- **User Story 2 (P2)**: Can start after Setup — no dependencies on US1 (different template sections and render methods)
- **User Story 3 (P3)**: Can start after Setup — no dependencies on US1/US2 (different template sections and render methods)

### Within Each User Story

- Template changes (HTML + TXT) can run in parallel [P] (different files)
- Service rendering methods depend on template placeholders being defined
- CLI output updates depend on service methods being available

### Parallel Opportunities

- T004 and T005 can run in parallel (HTML vs TXT template, US1)
- T008 and T009 can run in parallel (HTML vs TXT template, US2)
- T012 and T013 can run in parallel (HTML vs TXT template, US3)
- After Phase 1, all three user stories can be implemented in parallel if desired

---

## Parallel Example: User Story 2

```bash
# Launch template tasks in parallel (different files):
Task: "T008 [P] [US2] Add top products HTML table in admin-summary.html"
Task: "T009 [P] [US2] Add top products text section in admin-summary.txt"

# Then sequentially:
Task: "T010 [US2] Add render helpers and wire into template rendering"
Task: "T011 [US2] Update CLI command output"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T003)
2. Complete Phase 2: User Story 1 (T004-T007)
3. **STOP and VALIDATE**: Build + dry-run to confirm link appears
4. Deploy/demo if ready

### Incremental Delivery

1. Complete Setup → Foundation ready
2. Add User Story 1 (link) → Test → Deploy (MVP!)
3. Add User Story 2 (products) → Test → Deploy
4. Add User Story 3 (servers) → Test → Deploy
5. Each story adds value without breaking previous stories

---

## Notes

- All changes are in existing files — no new files created
- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Commit after each phase checkpoint
- The `AdminSummaryService.kt` file is modified across multiple phases — execute phase tasks sequentially within each phase
- Template placeholders must be added before the corresponding render method updates
