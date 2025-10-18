# Tasks: CrowdStrike CLI - Vulnerability Query Tool

**Input**: Design documents from `/specs/023-create-in-the/`  
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: TDD approach mandated by constitution - contract tests, unit tests, and integration tests included

**Organization**: Tasks are grouped by phase to enable multi-project Gradle setup first, then user story implementation

## Format: `[ID] [P?] [Story] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions
- **Multi-project Gradle**: Root `settings.gradle.kts`, `src/shared/`, `src/cli/`, `src/backendng/`
- Shared module: `src/shared/src/main/kotlin/com/secman/crowdstrike/`
- CLI module: `src/cli/src/main/kotlin/com/secman/cli/`
- Tests: `src/shared/src/test/`, `src/cli/src/test/`

---

## Phase 0: Multi-Project Gradle Setup

**Purpose**: Establish multi-project Gradle build with shared CrowdStrike module for code reuse

**⚠️ CRITICAL**: This must be complete before any shared module extraction or CLI development

- [X] T001 Create root `settings.gradle.kts` with `include("shared", "cli", "backendng")` at repository root
- [X] T002 Create root `build.gradle.kts` with common plugin management and dependency versions
- [X] T003 [P] Create `src/shared/build.gradle.kts` with Micronaut HTTP client, Jackson, retry dependencies (no web/database)
- [X] T004 [P] Create `src/shared/settings.gradle.kts` for shared module configuration
- [X] T005 [P] Create `src/shared/gradle.properties` with Kotlin 2.1.0, Micronaut 4.4, JVM target 21
- [X] T006 Update `src/backendng/build.gradle.kts` to add `implementation(project(":shared"))` dependency
- [X] T007 [P] Create `src/cli/build.gradle.kts` with Picocli, Commons CSV, and `implementation(project(":shared"))` dependency
- [X] T008 [P] Create `src/cli/settings.gradle.kts` for CLI module configuration
- [X] T009 [P] Create `src/cli/gradle.properties` with Kotlin 2.1.0, Micronaut 4.4, JVM target 21
- [X] T010 Create shared module directory structure: `src/shared/src/main/kotlin/com/secman/crowdstrike/{client,auth,dto,model,exception}/`
- [X] T011 [P] Create shared module test directory: `src/shared/src/test/kotlin/com/secman/crowdstrike/{contract,unit}/`
- [X] T012 Create CLI directory structure: `src/cli/src/main/kotlin/com/secman/cli/{commands,config,export,util}/`
- [X] T013 [P] Create CLI test directory: `src/cli/src/test/kotlin/com/secman/cli/{contract,integration,unit}/`
- [X] T014 Verify multi-project build: `./gradlew :shared:build :cli:build :backendng:build` runs successfully

**Checkpoint**: ✅ Multi-project Gradle structure ready - can now extract shared code and build CLI

---

## Phase 1: Extract Shared CrowdStrike Module from Backendng

**Purpose**: Extract CrowdStrike API client code from backendng into shared module for reuse

**⚠️ CRITICAL**: This phase enables code reuse between backendng and CLI per user requirement

### Shared Module DTOs (from backendng)

- [X] T015 [P] Extract `CrowdStrikeVulnerabilityDto` from backendng to `src/shared/src/main/kotlin/com/secman/crowdstrike/dto/CrowdStrikeVulnerabilityDto.kt`
- [X] T016 [P] Extract `CrowdStrikeQueryResponse` from backendng to `src/shared/src/main/kotlin/com/secman/crowdstrike/dto/CrowdStrikeQueryResponse.kt`
- [X] T017 [P] Create `FalconConfigDto` in `src/shared/src/main/kotlin/com/secman/crowdstrike/dto/FalconConfigDto.kt` (client ID, secret, base URL)

### Shared Module Domain Models

- [X] T018 [P] Create `Severity` enum in `src/shared/src/main/kotlin/com/secman/crowdstrike/model/Severity.kt` (CRITICAL, HIGH, MEDIUM, LOW)
- [X] T019 [P] Create `Vulnerability` data class in `src/shared/src/main/kotlin/com/secman/crowdstrike/model/Vulnerability.kt` with CVE validation
- [X] T020 [P] Create `Host` data class in `src/shared/src/main/kotlin/com/secman/crowdstrike/model/Host.kt` with hostname validation
- [X] T021 [P] Create `AuthToken` data class in `src/shared/src/main/kotlin/com/secman/crowdstrike/model/AuthToken.kt` with expiration logic

### Shared Module Exceptions

- [X] T022 [P] Create `CrowdStrikeException` base exception in `src/shared/src/main/kotlin/com/secman/crowdstrike/exception/CrowdStrikeException.kt`
- [X] T023 [P] Create `AuthenticationException` in `src/shared/src/main/kotlin/com/secman/crowdstrike/exception/AuthenticationException.kt`
- [X] T024 [P] Create `RateLimitException` in `src/shared/src/main/kotlin/com/secman/crowdstrike/exception/RateLimitException.kt`
- [X] T025 [P] Create `NotFoundException` in `src/shared/src/main/kotlin/com/secman/crowdstrike/exception/NotFoundException.kt`

### Shared Module Authentication (extracted from backendng)

- [X] T026 Extract OAuth2 authentication logic from backendng `CrowdStrikeVulnerabilityService.authenticateWithCrowdStrike()` to `src/shared/src/main/kotlin/com/secman/crowdstrike/auth/CrowdStrikeAuthService.kt`
- [X] T027 Implement token caching logic in shared `CrowdStrikeAuthService` (30-minute expiration, proactive refresh)
- [X] T028 Add token validation methods: `isExpired()`, `isExpiringSoon(bufferSeconds = 60)` in `AuthToken` model

### Shared Module API Client (extracted from backendng)

- [X] T029 Extract CrowdStrike API client interface from backendng to `src/shared/src/main/kotlin/com/secman/crowdstrike/client/CrowdStrikeApiClient.kt`
- [X] T030 Extract vulnerability query logic from backendng `CrowdStrikeVulnerabilityService.queryCrowdStrikeApi()` to shared `CrowdStrikeApiClient.queryVulnerabilities()`
- [X] T031 Add retry logic with exponential backoff (base: 1s, max: 60s, jitter: 0-1s) in `CrowdStrikeApiClient` for 429/500/502/503/504 errors
- [X] T032 Add pagination support in `CrowdStrikeApiClient.queryAllVulnerabilities()` (limit: 100, auto-paginate until total reached)
- [X] T033 Implement response mapping from CrowdStrike API JSON to shared DTOs in `CrowdStrikeApiClient`

### Shared Module Contract Tests (TDD - write FIRST)

- [X] T034 [P] Write contract test for OAuth2 authentication success in `src/shared/src/test/kotlin/com/secman/crowdstrike/contract/CrowdStrikeAuthServiceContractTest.kt` using MockWebServer
- [X] T035 [P] Write contract test for OAuth2 authentication 401 failure in `CrowdStrikeAuthServiceContractTest.kt`
- [X] T036 [P] Write contract test for OAuth2 rate limit 429 retry in `CrowdStrikeAuthServiceContractTest.kt`
- [X] T037 [P] Write contract test for vulnerability query success in `src/shared/src/test/kotlin/com/secman/crowdstrike/contract/CrowdStrikeApiClientContractTest.kt`
- [X] T038 [P] Write contract test for vulnerability query 404 host not found in `CrowdStrikeApiClientContractTest.kt`
- [X] T039 [P] Write contract test for vulnerability query pagination in `CrowdStrikeApiClientContractTest.kt`
- [X] T040 [P] Write contract test for vulnerability query rate limit retry in `CrowdStrikeApiClientContractTest.kt`

### Shared Module Unit Tests

- [X] T041 [P] Write unit test for `AuthToken.isExpired()` edge cases in `src/shared/src/test/kotlin/com/secman/crowdstrike/unit/AuthTokenTest.kt`
- [X] T042 [P] Write unit test for `Vulnerability` CVE ID validation in `src/shared/src/test/kotlin/com/secman/crowdstrike/unit/VulnerabilityTest.kt`
- [X] T043 [P] Write unit test for `Host` hostname validation and severity grouping in `src/shared/src/test/kotlin/com/secman/crowdstrike/unit/HostTest.kt`
- [X] T044 [P] Write unit test for `Severity` enum parsing in `src/shared/src/test/kotlin/com/secman/crowdstrike/unit/SeverityTest.kt`
- [ ] T043 [P] Write unit test for `Host` hostname validation in `src/shared/src/test/kotlin/com/secman/crowdstrike/unit/HostTest.kt`

### Migrate Backendng to Use Shared Module

- [ ] T044 Update backendng `CrowdStrikeVulnerabilityService` to depend on shared `CrowdStrikeAuthService` and `CrowdStrikeApiClient`
- [ ] T045 Remove duplicated authentication logic from backendng (replaced by shared module)
- [ ] T046 Remove duplicated API client logic from backendng (replaced by shared module)
- [ ] T047 Update backendng imports to use `com.secman.crowdstrike.*` from shared module
- [ ] T048 Run backendng tests to verify shared module integration: `./gradlew :backendng:test`
- [ ] T049 Verify backendng contract test `CrowdStrikeQueryContractTest` still passes with shared module

**Checkpoint**: Shared module complete and tested - backendng migrated - CLI can now use shared code

---

## Phase 2: CLI Foundational (Blocking Prerequisites)

**Purpose**: Core CLI infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### CLI Application Bootstrap

- [ ] T050 Create `CrowdStrikeCliApplication.kt` main entry point in `src/cli/src/main/kotlin/com/secman/cli/CrowdStrikeCliApplication.kt` with Micronaut + Picocli integration
- [ ] T051 Create `application.yml` in `src/cli/src/main/resources/application.yml` with HTTP client timeout (30s), logging config
- [ ] T052 Create `logback.xml` in `src/cli/src/main/resources/logback.xml` with console appender, INFO level, NO sensitive data logging
- [ ] T053 [P] Create `test-application.yml` in `src/cli/src/test/resources/test-application.yml` for test configuration

### CLI Configuration Management

- [ ] T054 Create `CliConfig` data class in `src/cli/src/main/kotlin/com/secman/cli/config/CliConfig.kt` (clientId, clientSecret, baseUrl, timeout)
- [ ] T055 Implement `ConfigLoader` in `src/cli/src/main/kotlin/com/secman/cli/config/ConfigLoader.kt` to read `~/.secman/crowdstrike.conf` using Lightbend Config (HOCON)
- [ ] T056 Add file permission validation in `ConfigLoader` (MUST be 600 or 400, refuse to load if too open)
- [ ] T057 Add security constraint: NEVER log credentials (client ID, secret, tokens) in `ConfigLoader`

### CLI Utilities

- [ ] T058 [P] Create `InputValidator` in `src/cli/src/main/kotlin/com/secman/cli/util/InputValidator.kt` with hostname regex validation (`^[a-zA-Z0-9.-]+$`)
- [ ] T059 [P] Create `RetryHandler` in `src/cli/src/main/kotlin/com/secman/cli/util/RetryHandler.kt` with exponential backoff calculation (base: 1s, max: 60s, jitter)
- [ ] T060 [P] Create `FileHelper` in `src/cli/src/main/kotlin/com/secman/cli/util/FileHelper.kt` with overwrite prompt logic, directory creation

### CLI Domain Models (CLI-specific, different from shared)

- [ ] T061 [P] Create `QueryResult` data class in `src/cli/src/main/kotlin/com/secman/cli/model/QueryResult.kt` (timestamp, params, results, summary, errors)
- [ ] T062 [P] Create `QueryParameters` data class in `src/cli/src/main/kotlin/com/secman/cli/model/QueryParameters.kt` (hosts, severityFilter, includeRemediation)
- [ ] T063 [P] Create `QuerySummary` data class in `src/cli/src/main/kotlin/com/secman/cli/model/QuerySummary.kt` with `compute(hosts)` factory method
- [ ] T064 [P] Create `QueryError` data class in `src/cli/src/main/kotlin/com/secman/cli/model/QueryError.kt` (hostname, errorType, message, retryable)

### CLI Unit Tests for Foundation

- [ ] T065 [P] Write unit test for `ConfigLoader` HOCON parsing in `src/cli/src/test/kotlin/com/secman/cli/unit/ConfigLoaderTest.kt`
- [ ] T066 [P] Write unit test for `ConfigLoader` permission validation (600/400 pass, 644 fail) in `ConfigLoaderTest.kt`
- [ ] T067 [P] Write unit test for `InputValidator.isValidHostname()` edge cases in `src/cli/src/test/kotlin/com/secman/cli/unit/InputValidatorTest.kt`
- [ ] T068 [P] Write unit test for `RetryHandler` backoff calculation in `src/cli/src/test/kotlin/com/secman/cli/unit/RetryHandlerTest.kt`
- [ ] T069 [P] Write unit test for `QuerySummary.compute()` aggregation logic in `src/cli/src/test/kotlin/com/secman/cli/unit/QuerySummaryTest.kt`

**Checkpoint**: CLI foundation ready - user story implementation can now begin

---

## Phase 3: User Story 2 - Authenticate with CrowdStrike API (Priority: P1) 🎯 MVP

**Goal**: Security administrators can securely authenticate with CrowdStrike API using configuration file

**Why First**: Authentication is prerequisite for all vulnerability queries - must work before US1

**Independent Test**: Configure credentials in `~/.secman/crowdstrike.conf`, run CLI, verify successful authentication without errors

### CLI Authentication Service (uses shared module)

- [ ] T070 [US2] Create `CliAuthService` in `src/cli/src/main/kotlin/com/secman/cli/service/CliAuthService.kt` that wraps shared `CrowdStrikeAuthService`
- [ ] T071 [US2] Implement token caching in `CliAuthService` (reuse shared `AuthToken` expiration logic)
- [ ] T072 [US2] Add automatic token refresh in `CliAuthService` when token expires within 60 seconds
- [ ] T073 [US2] Implement error handling for authentication failures (401 → clear message, exit code 1)

### CLI Authentication Integration Tests

- [ ] T074 [P] [US2] Write integration test for successful authentication with valid config in `src/cli/src/test/kotlin/com/secman/cli/integration/CliAuthServiceIntegrationTest.kt`
- [ ] T075 [P] [US2] Write integration test for authentication failure with invalid credentials in `CliAuthServiceIntegrationTest.kt`
- [ ] T076 [P] [US2] Write integration test for token caching (no re-auth on second call) in `CliAuthServiceIntegrationTest.kt`
- [ ] T077 [P] [US2] Write integration test for token refresh on expiration in `CliAuthServiceIntegrationTest.kt`

**Checkpoint**: Authentication works independently - credentials can be validated

---

## Phase 4: User Story 1 - Query Vulnerabilities by Host (Priority: P1) 🎯 MVP

**Goal**: Security administrators can query all vulnerabilities for a specific host from command line

**Independent Test**: Run `./gradlew :cli:run --args="query --hostname=web-server-01"` and verify vulnerability data is displayed

### CLI Vulnerability Service (uses shared module)

- [ ] T078 [US1] Create `CliVulnerabilityService` in `src/cli/src/main/kotlin/com/secman/cli/service/CliVulnerabilityService.kt` that wraps shared `CrowdStrikeApiClient`
- [ ] T079 [US1] Implement `queryByHostname(hostname)` in `CliVulnerabilityService` using shared API client
- [ ] T080 [US1] Add error handling for 404 host not found → return empty `Host` with message
- [ ] T081 [US1] Add error handling for network errors → retry with `RetryHandler`, max 3 attempts
- [ ] T082 [US1] Add error handling for rate limits → retry with exponential backoff, max 5 attempts

### CLI Query Command (Picocli)

- [ ] T083 [US1] Create `QueryCommand` in `src/cli/src/main/kotlin/com/secman/cli/commands/QueryCommand.kt` with `@Command` annotation
- [ ] T084 [US1] Add `@Parameters(index = "0")` hostname parameter to `QueryCommand`
- [ ] T085 [US1] Implement `call()` method in `QueryCommand`: load config → authenticate → query → display results
- [ ] T086 [US1] Add console output formatting: display CVE ID, severity, affected software, description (truncated to 100 chars)
- [ ] T087 [US1] Add summary output: total vulnerabilities, count by severity (CRITICAL: X, HIGH: Y, etc.)
- [ ] T088 [US1] Return exit code 0 on success, 1 on error

### CLI Query Command Tests

- [ ] T089 [P] [US1] Write integration test for query success in `src/cli/src/test/kotlin/com/secman/cli/integration/QueryCommandTest.kt` with MockWebServer
- [ ] T090 [P] [US1] Write integration test for query with no vulnerabilities in `QueryCommandTest.kt`
- [ ] T091 [P] [US1] Write integration test for query with invalid hostname in `QueryCommandTest.kt` (validation error)
- [ ] T092 [P] [US1] Write integration test for query with host not found (404) in `QueryCommandTest.kt`
- [ ] T093 [P] [US1] Write integration test for query with rate limit retry in `QueryCommandTest.kt`

**Checkpoint**: US1 + US2 = MVP (authentication + single host query) - fully functional

---

## Phase 5: User Story 3 - Filter Vulnerabilities by Severity (Priority: P2)

**Goal**: Security administrators can filter vulnerability results by severity level to prioritize remediation

**Independent Test**: Run `./gradlew :cli:run --args="query --hostname=web-server-01 --severity=CRITICAL"` and verify only critical vulnerabilities displayed

### CLI Filter Implementation

- [ ] T094 [US3] Add `@Option(names = ["--severity"])` to `QueryCommand` in `src/cli/src/main/kotlin/com/secman/cli/commands/QueryCommand.kt`
- [ ] T095 [US3] Add severity enum validation (CRITICAL, HIGH, MEDIUM, LOW) in `QueryCommand`
- [ ] T096 [US3] Implement client-side filtering in `CliVulnerabilityService.queryByHostname()` if severity filter provided
- [ ] T097 [US3] Update console output to show filtered count: "Showing X CRITICAL vulnerabilities (Y total)"

### CLI Filter Tests

- [ ] T098 [P] [US3] Write integration test for severity filter CRITICAL in `src/cli/src/test/kotlin/com/secman/cli/integration/FilterCommandTest.kt`
- [ ] T099 [P] [US3] Write integration test for severity filter with no matches in `FilterCommandTest.kt`
- [ ] T100 [P] [US3] Write integration test for invalid severity value in `FilterCommandTest.kt`

**Checkpoint**: US1 + US2 + US3 work independently - filtering enabled

---

## Phase 6: User Story 4 - Export Results to File (Priority: P2)

**Goal**: Security administrators can export vulnerability query results to JSON or CSV for reporting

**Independent Test**: Run `./gradlew :cli:run --args="query --hostname=web-server-01 --export=/tmp/results.json"` and verify JSON file created

### CLI Export Services

- [ ] T101 [P] [US4] Create `JsonExporter` in `src/cli/src/main/kotlin/com/secman/cli/export/JsonExporter.kt` using Jackson pretty-print
- [ ] T102 [P] [US4] Create `CsvExporter` in `src/cli/src/main/kotlin/com/secman/cli/export/CsvExporter.kt` using Apache Commons CSV
- [ ] T103 [US4] Implement JSON export structure in `JsonExporter`: timestamp, query params, results (hosts + vulnerabilities), summary
- [ ] T104 [US4] Implement CSV export with header row: Host, CVE ID, Severity, Affected Software, Description, CVSS Score, Published Date
- [ ] T105 [US4] Add streaming to disk (don't load entire result set into memory) in both exporters

### CLI Export Command Options

- [ ] T106 [US4] Add `@Option(names = ["--export", "-o"])` to `QueryCommand` with file path parameter
- [ ] T107 [US4] Add `@Option(names = ["--format"])` to `QueryCommand` with enum (JSON, CSV), default JSON
- [ ] T108 [US4] Implement file overwrite prompt in `QueryCommand`: "File exists. Overwrite? (y/n)"
- [ ] T109 [US4] Add directory creation if parent doesn't exist using `FileHelper.ensureDirectoryExists()`
- [ ] T110 [US4] Add write permission validation before export attempt

### CLI Export Tests

- [ ] T111 [P] [US4] Write unit test for JSON export structure in `src/cli/src/test/kotlin/com/secman/cli/unit/JsonExporterTest.kt`
- [ ] T112 [P] [US4] Write unit test for CSV export formatting in `src/cli/src/test/kotlin/com/secman/cli/unit/CsvExporterTest.kt`
- [ ] T113 [P] [US4] Write integration test for JSON export to file in `src/cli/src/test/kotlin/com/secman/cli/integration/ExportCommandTest.kt`
- [ ] T114 [P] [US4] Write integration test for CSV export to file in `ExportCommandTest.kt`
- [ ] T115 [P] [US4] Write integration test for export file overwrite prompt in `ExportCommandTest.kt`
- [ ] T116 [P] [US4] Write integration test for export to non-writable directory in `ExportCommandTest.kt`

**Checkpoint**: US1 + US2 + US3 + US4 work independently - export enabled

---

## Phase 7: User Story 5 - Query Multiple Hosts (Priority: P3)

**Goal**: Security administrators can query vulnerabilities for multiple hosts in a single command

**Independent Test**: Run `./gradlew :cli:run --args="query --hostname=server1,server2,server3"` and verify results for all hosts

### CLI Bulk Query Implementation

- [ ] T117 [US5] Update `QueryCommand` to accept comma-separated hostnames in `@Parameters` hostname parameter
- [ ] T118 [US5] Implement `CliVulnerabilityService.queryMultipleHosts(hostnames)` with parallel queries using Kotlin coroutines (max 10 concurrent)
- [ ] T119 [US5] Add error collection: continue querying remaining hosts if one fails, collect errors in `QueryError` list
- [ ] T120 [US5] Update console output to clearly separate results by host: "=== Results for web-server-01 ===" headers
- [ ] T121 [US5] Add global summary: "Queried X hosts: Y succeeded, Z failed. Total vulnerabilities: N"

### CLI Bulk Query from File

- [ ] T122 [US5] Add `@Option(names = ["--hosts-file"])` to `QueryCommand` for file path containing hostnames (one per line)
- [ ] T123 [US5] Implement file reading in `QueryCommand`: read lines, trim whitespace, skip empty/comment lines (starting with #)
- [ ] T124 [US5] Add file validation: file must exist, be readable, contain at least one valid hostname

### CLI Bulk Query Tests

- [ ] T125 [P] [US5] Write integration test for bulk query with multiple hostnames in `src/cli/src/test/kotlin/com/secman/cli/integration/BulkQueryCommandTest.kt`
- [ ] T126 [P] [US5] Write integration test for bulk query with partial failures in `BulkQueryCommandTest.kt`
- [ ] T127 [P] [US5] Write integration test for bulk query from file in `BulkQueryCommandTest.kt`
- [ ] T128 [P] [US5] Write integration test for bulk query with invalid file path in `BulkQueryCommandTest.kt`

**Checkpoint**: All user stories (US1-US5) work independently - full feature set complete

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories or overall quality

### CLI Help & Documentation

- [ ] T129 [P] Add `@Command` description and usage examples to `QueryCommand` for auto-generated help
- [ ] T130 [P] Create CLI README.md in `src/cli/README.md` with installation, configuration, usage examples
- [ ] T131 [P] Add `--version` option to display CLI version and build info

### CLI Error Messages & UX

- [ ] T132 Standardize error messages across all commands (consistent format, actionable guidance)
- [ ] T133 Add colored output for severity levels (red=CRITICAL, orange=HIGH, yellow=MEDIUM, green=LOW) using ANSI codes
- [ ] T134 Add progress indicator for long-running bulk queries (e.g., "Querying 50 hosts... [25/50]")

### CLI Performance & Optimization

- [ ] T135 Add connection pooling for parallel queries in `CliVulnerabilityService`
- [ ] T136 Optimize memory usage for large exports (streaming, chunked writes)
- [ ] T137 Add request timeout handling (fail fast after 30s per host)

### CLI Security Hardening

- [ ] T138 Add config file permission warning on startup if permissions too open
- [ ] T139 Verify HTTPS-only communication (reject HTTP base URLs)
- [ ] T140 Add credential sanitization in all log statements and error messages

### CLI Testing & Quality

- [ ] T141 [P] Run all shared module tests: `./gradlew :shared:test` - verify ≥80% coverage
- [ ] T142 [P] Run all CLI tests: `./gradlew :cli:test` - verify ≥80% coverage
- [ ] T143 [P] Run backendng tests after shared module migration: `./gradlew :backendng:test`
- [ ] T144 Generate test coverage reports for shared, CLI, backendng modules
- [ ] T145 Fix any lint/format violations: `./gradlew ktlintFormat`

### CLI Build & Distribution

- [ ] T146 [P] Create executable JAR: `./gradlew :cli:shadowJar`
- [ ] T147 [P] Create distribution ZIP with docs: `./gradlew :cli:distZip`
- [ ] T148 [P] Add shell script wrapper `bin/crowdstrike-cli` for easy execution
- [ ] T149 Test CLI installation from distribution package on clean system

### Documentation Updates

- [ ] T150 [P] Update root README.md to document multi-project Gradle structure
- [ ] T151 [P] Update `.github/copilot-instructions.md` with final architecture (shared + cli + backendng)
- [ ] T152 [P] Create CHANGELOG.md documenting Feature 023 completion

**Checkpoint**: All polish complete - ready for production deployment

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 0 (Multi-Project Setup)**: No dependencies - can start immediately
- **Phase 1 (Extract Shared Module)**: Depends on Phase 0 completion - BLOCKS all CLI development
- **Phase 2 (CLI Foundational)**: Depends on Phase 1 completion - BLOCKS all user stories
- **Phase 3 (US2 - Auth)**: Depends on Phase 2 completion - BLOCKS US1
- **Phase 4 (US1 - Query)**: Depends on Phase 3 completion - MVP achievable at this point
- **Phase 5 (US3 - Filter)**: Depends on Phase 4 completion - Can start after MVP
- **Phase 6 (US4 - Export)**: Depends on Phase 4 completion - Independent of US3/US5
- **Phase 7 (US5 - Bulk)**: Depends on Phase 4 completion - Independent of US3/US4
- **Phase 8 (Polish)**: Depends on desired user stories being complete

### User Story Dependencies

- **US2 (Auth)**: No dependencies on other stories - but required by all
- **US1 (Query)**: Depends on US2 (Auth) - cannot query without authentication
- **US3 (Filter)**: Depends on US1 (Query) - filters query results
- **US4 (Export)**: Depends on US1 (Query) - exports query results
- **US5 (Bulk)**: Depends on US1 (Query) - bulk queries reuse single query logic

### Within Each User Story

- Tests MUST be written and FAIL before implementation (TDD requirement)
- Models before services
- Services before commands
- Core implementation before integration
- Story complete before moving to next priority

### Parallel Opportunities

**Phase 0 (Multi-Project Setup)**:
- T003 (shared build), T007 (CLI build) can run in parallel
- T010 (shared structure), T012 (CLI structure) can run in parallel
- T011 (shared tests), T013 (CLI tests) can run in parallel

**Phase 1 (Extract Shared Module)**:
- All DTO extraction (T015-T017) can run in parallel
- All model creation (T018-T021) can run in parallel
- All exception creation (T022-T025) can run in parallel
- All contract tests (T034-T040) can run in parallel
- All unit tests (T041-T043) can run in parallel

**Phase 2 (CLI Foundational)**:
- T053 (test config), T058-T060 (utilities) can run in parallel
- T061-T064 (CLI models) can run in parallel
- T065-T069 (unit tests) can run in parallel

**User Story Tests**: All tests within a story can run in parallel:
- US2: T074-T077 (auth tests)
- US1: T089-T093 (query tests)
- US3: T098-T100 (filter tests)
- US4: T111-T116 (export tests)
- US5: T125-T128 (bulk tests)

**Phase 8 (Polish)**: Most tasks marked [P] can run in parallel

---

## Parallel Example: Phase 1 (Extract Shared Module)

```bash
# All DTOs can be extracted in parallel:
Parallel:
  - T015: CrowdStrikeVulnerabilityDto
  - T016: CrowdStrikeQueryResponse
  - T017: FalconConfigDto

# All models can be created in parallel:
Parallel:
  - T018: Severity enum
  - T019: Vulnerability data class
  - T020: Host data class
  - T021: AuthToken data class

# All contract tests can be written in parallel:
Parallel:
  - T034: Auth success test
  - T035: Auth 401 test
  - T036: Auth 429 retry test
  - T037: Query success test
  - T038: Query 404 test
  - T039: Query pagination test
  - T040: Query rate limit test
```

---

## Implementation Strategy

### MVP First (US2 + US1 Only)

1. Complete Phase 0: Multi-Project Setup (T001-T014)
2. Complete Phase 1: Extract Shared Module (T015-T049) - **CRITICAL CODE REUSE**
3. Complete Phase 2: CLI Foundational (T050-T069) - **BLOCKS ALL STORIES**
4. Complete Phase 3: US2 - Authentication (T070-T077)
5. Complete Phase 4: US1 - Query Single Host (T078-T093)
6. **STOP and VALIDATE**: Test authentication + single host query independently
7. Deploy/demo if ready - **MVP ACHIEVED**

### Incremental Delivery

1. **Foundation** (Phases 0-2): Multi-project build + shared module + CLI foundation
2. **MVP** (Phases 3-4): Authentication + single host query → Deploy/Demo
3. **Enhanced** (Phase 5): Add severity filtering → Deploy/Demo
4. **Reporting** (Phase 6): Add export to JSON/CSV → Deploy/Demo
5. **Bulk** (Phase 7): Add multi-host queries → Deploy/Demo
6. **Production-Ready** (Phase 8): Polish, optimize, document → Final Release

### Parallel Team Strategy

With multiple developers:

**Phase 0-1 (Foundation)**: Team works together on multi-project setup and shared module extraction

**Phase 2 (CLI Foundation)**: Team works together on CLI bootstrap

**Phases 3-7 (User Stories)**: After foundation complete:
- Developer A: US2 (Auth) → US1 (Query) → US3 (Filter)
- Developer B: US4 (Export) - can start after US1 complete
- Developer C: US5 (Bulk) - can start after US1 complete

**Phase 8 (Polish)**: Team works together on cross-cutting improvements

---

## Task Count Summary

- **Phase 0 (Multi-Project Setup)**: 14 tasks
- **Phase 1 (Extract Shared Module)**: 35 tasks (T015-T049)
- **Phase 2 (CLI Foundational)**: 20 tasks (T050-T069)
- **Phase 3 (US2 - Auth)**: 8 tasks (T070-T077)
- **Phase 4 (US1 - Query)**: 16 tasks (T078-T093)
- **Phase 5 (US3 - Filter)**: 7 tasks (T094-T100)
- **Phase 6 (US4 - Export)**: 16 tasks (T101-T116)
- **Phase 7 (US5 - Bulk)**: 12 tasks (T117-T128)
- **Phase 8 (Polish)**: 24 tasks (T129-T152)

**Total Tasks**: 152

**Test Tasks**: 48 tasks (32% of total - exceeds 30% constitution requirement)

**Parallelizable Tasks**: 67 tasks (44% of total)

---

## MVP Scope

**Minimum Viable Product** = Phases 0-4 (US2 + US1):
- Multi-project Gradle build with shared CrowdStrike module
- Shared module extracted from backendng (code reuse achieved)
- CLI authentication with CrowdStrike API
- CLI query single host for vulnerabilities
- Console output with essential vulnerability info

**MVP Task Count**: 73 tasks (48% of total)

**Estimated MVP Effort**: 2-3 weeks with 1 developer following TDD approach

---

## Notes

- **[P] tasks** = different files, no dependencies - can run in parallel
- **[Story] label** = maps task to specific user story for traceability
- **TDD mandatory**: All contract/unit/integration tests MUST be written FIRST and FAIL before implementation
- **Code reuse achieved**: Shared module (Phase 1) extracts CrowdStrike API code from backendng per user requirement
- **Multi-project build**: Root Gradle project with 3 modules (shared, cli, backendng)
- **Each user story independently testable**: Can validate US2, US1, US3, US4, US5 separately
- **Verify tests fail before implementing**: Red → Green → Refactor cycle
- **Commit after each task or logical group**
- **Stop at any checkpoint to validate story independently**
- **Constitution compliance**: ≥80% test coverage required, security-first approach enforced
