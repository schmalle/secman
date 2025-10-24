# Tasks: Servers Query Import

**Feature**: 032-servers-query-import
**Branch**: `032-servers-query-import`
**Spec**: [spec.md](spec.md)
**Plan**: [plan.md](plan.md)

## Overview

This document provides an actionable, dependency-ordered task breakdown for implementing the Servers Query Import feature. Each task follows the format: `- [ ] [TaskID] [P?] [Story] Description with file path`

## Task Summary

**Total Tasks**: 39
**MVP Scope**: Phase 1-4 (Tasks T001-T025) - Core query and import functionality
**Enhancement Scope**: Phase 5-7 (Tasks T026-T039) - Statistics, metadata, error handling

## Dependencies & Execution Order

**Phase Dependency Graph**:
```
Phase 1 (Setup)
    ↓
Phase 2 (Foundational)
    ↓
Phase 3 (US1+US3: Query & Import) ─┐
    ↓                               │
Phase 4 (US2: Replace Pattern)      │ (can run in parallel after Phase 2)
    ↓                               │
Phase 5 (US4: Statistics) ←─────────┘
    ↓
Phase 6 (US5: Metadata)
    ↓
Phase 7 (Polish & Error Handling)
```

**Parallel Opportunities**:
- Within Phase 2: All DTO creation tasks can run in parallel ([P] marked)
- Within Phase 3: CLI and Backend tasks can run in parallel after DTOs complete
- Phase 5 and Phase 6 are independent and can run in parallel

## Implementation Strategy

**MVP First** (Phases 1-4):
- Phase 1-2: Setup and foundational components
- Phase 3: Core query and import (US1+US3)
- Phase 4: Replace pattern (US2)
- Result: Minimum viable product that queries CrowdStrike and imports server vulnerabilities

**Incremental Enhancement** (Phases 5-7):
- Phase 5: Add detailed statistics (US4)
- Phase 6: Add metadata preservation (US5)
- Phase 7: Polish error handling and edge cases

---

## Phase 1: Setup

**Goal**: Verify project structure and dependencies are in place

**Tasks**:

- [X] T001 Verify Gradle multi-module structure exists (cli, backendng, shared modules)
- [X] T002 Verify CrowdStrike shared module dependencies are accessible in src/shared/build.gradle.kts
- [X] T003 Verify existing Asset and Vulnerability entities in src/backendng/src/main/kotlin/com/secman/domain/

---

## Phase 2: Foundational Components

**Goal**: Create shared DTOs and enhance CrowdStrike API client

**User Story**: N/A (foundational prerequisites)

**Independent Test**: Compile successfully and DTOs pass validation tests

**Tasks**:

- [X] T004 [P] Create VulnerabilityDto in src/backendng/src/main/kotlin/com/secman/dto/VulnerabilityDto.kt with fields: cveId, severity, affectedProduct, daysOpen
- [X] T005 [P] Create CrowdStrikeVulnerabilityBatchDto in src/backendng/src/main/kotlin/com/secman/dto/CrowdStrikeVulnerabilityBatchDto.kt with fields: hostname, groups, cloudAccountId, cloudInstanceId, adDomain, osVersion, ip, vulnerabilities
- [X] T006 [P] Create ImportStatisticsDto in src/backendng/src/main/kotlin/com/secman/dto/ImportStatisticsDto.kt with fields: serversProcessed, serversCreated, serversUpdated, vulnerabilitiesImported, vulnerabilitiesSkipped, errors
- [X] T007 Add server filtering support to CrowdStrikeApiClient in src/shared/src/main/kotlin/com/secman/crowdstrike/client/CrowdStrikeApiClient.kt (add deviceType, severity, daysOpen filter parameters)
- [X] T008 Add hostname filtering support to CrowdStrikeApiClient query method (optional hostnames parameter)

---

## Phase 3: Core Query & Import (US1 + US3)

**User Stories**:
- **US1 (P1)**: Query Server Vulnerabilities with Auto-Import
- **US3 (P1)**: Filter by Device Type, Severity, and Days Open

**Goal**: Implement basic CLI query command and backend import endpoint with filtering

**Independent Test**: Run `secman query servers` and verify servers and vulnerabilities are created in database with correct filters applied (device type SERVER, severity HIGH|CRITICAL, days open >=30)

**Tasks**:

### CLI Implementation

- [X] T009 [US1] Create ServersCommand in src/cli/src/main/kotlin/com/secman/cli/commands/ServersCommand.kt with execute() method, --hostnames flag, --dry-run flag, --verbose flag, --backend-url option
- [X] T010 [US1] Register ServersCommand in SecmanCli router in src/cli/src/main/kotlin/com/secman/cli/SecmanCli.kt
- [X] T011 [US1] Enhanced VulnerabilityStorageService in src/cli/src/main/kotlin/com/secman/cli/service/VulnerabilityStorageService.kt with storeServerVulnerabilities() method for batch import via /api/crowdstrike/servers/import
- [X] T012 [US3] Add CrowdStrike API query logic to ServersCommand with filters: deviceType=SERVER, severity=HIGH|CRITICAL, daysOpen>=30
- [X] T013 [US3] Add hostname filtering logic to ServersCommand (use --hostnames parameter if provided, otherwise query all servers)
- [X] T014 [US1] Add pagination support to ServersCommand (loop until all results retrieved from CrowdStrike API)
- [X] T015 [US1] Add dry-run mode to ServersCommand (skip backend API call, display what would be imported)
- [X] T016 [US1] Add verbose logging to ServersCommand (log filters applied, API calls, pagination progress)

### Backend Implementation

- [X] T017 [US1] Enhanced CrowdStrikeController in src/backendng/src/main/kotlin/com/secman/controller/CrowdStrikeController.kt with POST /api/crowdstrike/servers/import endpoint for batch import
- [X] T018 [US1] Add @Secured(SecurityRule.IS_AUTHENTICATED) to batch import endpoint
- [X] T019 [US1] Create CrowdStrikeVulnerabilityImportService in src/backendng/src/main/kotlin/com/secman/service/CrowdStrikeVulnerabilityImportService.kt
- [X] T020 [US1] Implement findOrCreateAsset method in CrowdStrikeVulnerabilityImportService (find by hostname, create if not exists with type=SERVER, owner="CrowdStrike Import")
- [X] T021 [US1] Implement importVulnerabilitiesForServer method in CrowdStrikeVulnerabilityImportService (create Vulnerability records linked to Asset, transactional delete+insert)
- [X] T022 [US1] Implement batch import logic in CrowdStrikeVulnerabilityImportService (iterate servers from request DTO, call findOrCreateAsset and importVulnerabilitiesForServer for each)
- [X] T023 [US1] Wire CrowdStrikeController to call CrowdStrikeVulnerabilityImportService and return ImportStatisticsDto

---

## Phase 4: Replace Pattern (US2)

**User Story**: **US2 (P1)**: Replace Existing Vulnerabilities

**Goal**: Implement transactional delete+import to replace old vulnerability data with current state

**Dependencies**: Requires Phase 3 (US1+US3) complete

**Independent Test**: Import vulnerabilities for a server, re-run import with different data, verify old records deleted and new records added (exactly matching new data, no old data retained)

**Tasks**:

- [X] T024 [US2] Add @Transactional annotation to importVulnerabilitiesForServer method in CrowdStrikeVulnerabilityImportService (ALREADY IMPLEMENTED in T021)
- [X] T025 [US2] Add deleteByAssetId call before creating new vulnerabilities in importVulnerabilitiesForServer (delete all existing vulnerabilities for this asset) (ALREADY IMPLEMENTED in T021)
- [X] T026 [US2] Add try-catch block around delete+import in importVulnerabilitiesForServer to handle rollback on exception (ALREADY IMPLEMENTED in T022)
- [X] T027 [US2] Add error tracking in batch import logic (collect failed servers, continue with remaining servers, include errors in ImportStatisticsDto) (ALREADY IMPLEMENTED in T022)

---

## Phase 5: Import Statistics (US4)

**User Story**: **US4 (P2)**: Display Import Statistics

**Goal**: Track and display detailed import statistics (servers created/updated, vulnerabilities imported/skipped)

**Dependencies**: Requires Phase 3 (US1+US3) complete; can run in parallel with Phase 4

**Independent Test**: Run import and verify output displays counts in format "Imported: X servers (Y new, Z existing), A vulnerabilities, B skipped"

**Tasks**:

- [X] T028 [P] [US4] Add statistics tracking to CrowdStrikeVulnerabilityImportService (track serversCreated, serversUpdated, vulnerabilitiesImported counters) (IMPLEMENTED in importServerVulnerabilities method)
- [X] T029 [P] [US4] Populate ImportStatisticsDto fields from tracked counters in CrowdStrikeVulnerabilityImportService (IMPLEMENTED in importServerVulnerabilities method)
- [X] T030 [US4] Add statistics formatting to ServersCommand CLI output (display formatted counts after import completes) (IMPLEMENTED in ServersCommand.execute())
- [X] T031 [US4] Add empty result handling to ServersCommand (display "Imported: 0 servers, 0 vulnerabilities" when no results) (IMPLEMENTED in ServersCommand.execute())
- [X] T032 [US4] Add error display to ServersCommand output (list error messages from ImportStatisticsDto.errors) (IMPLEMENTED in ServersCommand.execute())

---

## Phase 6: Asset Metadata Preservation (US5)

**User Story**: **US5 (P2)**: Preserve Asset Metadata

**Goal**: Populate Asset metadata fields from CrowdStrike API response and preserve existing metadata when reusing assets

**Dependencies**: Requires Phase 3 (US1+US3) complete; independent of Phase 4-5

**Independent Test**: Import server and verify Asset record includes groups, cloudAccountId, adDomain, osVersion from CrowdStrike response; re-import and verify existing owner/description/workgroups preserved

**Tasks**:

- [X] T033 [P] [US5] Add metadata population logic to findOrCreateAsset in CrowdStrikeVulnerabilityImportService (set groups, cloudAccountId, cloudInstanceId, adDomain, osVersion from DTO when creating new asset) (IMPLEMENTED in findOrCreateAsset method)
- [X] T034 [P] [US5] Add metadata update logic to findOrCreateAsset for existing assets (update groups, cloudAccountId, cloudInstanceId, adDomain, osVersion but preserve owner, description, workgroups, manualCreator, scanUploader) (IMPLEMENTED in findOrCreateAsset method)
- [X] T035 [US5] Add lastSeen and updatedAt timestamp updates to findOrCreateAsset (set to current time on every import) (IMPLEMENTED in findOrCreateAsset method)

---

## Phase 7: Error Handling & Polish

**Goal**: Add robust error handling, CVE validation, rate limiting retry, and authentication checks

**Dependencies**: Requires Phases 3-6 complete

**Independent Test**: Verify graceful handling of edge cases (missing CVE IDs skipped with warnings, rate limiting triggers exponential backoff, invalid credentials fail fast)

**Tasks**:

- [X] T036 [P] Add CVE ID validation to ServersCommand (filter out vulnerabilities with null/blank cveId before sending to backend, track skipped count) (IMPLEMENTED in importVulnerabilitiesForServer - filters on backend)
- [X] T037 [P] Add CVE ID skipped count to CLI statistics output (display "Vulnerabilities skipped (no CVE): X") (IMPLEMENTED in ServersCommand statistics display)
- [ ] T038 Add exponential backoff retry logic to ServersCommand for CrowdStrike API calls (catch HTTP 429, retry with 1s, 2s, 4s, 8s delays, max 3 retries) (TODO: Not yet implemented)
- [ ] T039 Add authentication validation to ServersCommand (call backend health check or validate CrowdStrike credentials before executing query, fail fast with clear error message) (TODO: Not yet implemented)

---

## Parallel Execution Examples

### Phase 2 Parallelization

All DTO creation tasks can run in parallel:
```bash
# Developer 1
git checkout 032-servers-query-import
# Work on T004: Create VulnerabilityDto

# Developer 2
git checkout 032-servers-query-import
# Work on T005: Create CrowdStrikeVulnerabilityBatchDto (different file)

# Developer 3
git checkout 032-servers-query-import
# Work on T006: Create ImportStatisticsDto (different file)
```

### Phase 3 CLI/Backend Parallelization

After DTOs complete (T004-T006), CLI and backend can be developed in parallel:
```bash
# Team A (CLI)
# Work on T009-T016: ServersCommand and CLI logic

# Team B (Backend)
# Work on T017-T023: Controller and Service (different module)
```

### Phase 5-6 Parallelization

Statistics (Phase 5) and Metadata (Phase 6) are independent:
```bash
# Developer A
# Work on T028-T032: Statistics tracking

# Developer B
# Work on T033-T035: Metadata preservation (different methods)
```

---

## Validation Checklist

Before marking feature complete, verify:

- [ ] All 39 tasks completed and marked with ✅
- [ ] `secman query servers` executes successfully
- [ ] Servers created in database with type="SERVER"
- [ ] Vulnerabilities created with HIGH/CRITICAL severity, days open >=30
- [ ] Old vulnerabilities deleted before new import (no duplicates)
- [ ] Statistics display accurate counts
- [ ] Metadata fields populated from CrowdStrike response
- [ ] Rate limiting handled with exponential backoff
- [ ] CVE validation skips missing IDs with warnings
- [ ] Authentication failures fail fast with clear messages
- [ ] Dry-run mode works without database changes
- [ ] Verbose mode displays detailed logging
- [ ] Hostname filtering works correctly
- [ ] Pagination retrieves all results
- [ ] Partial failures tracked separately in statistics
- [ ] Transaction rollback preserves old data on error
- [ ] Backend endpoint returns ImportStatisticsDto
- [ ] All constitutional principles satisfied (Security-First, TDD, API-First, RBAC, Schema Evolution)

---

## Notes

**Tests**: Per Constitution Principle IV (User-Requested Testing), test tasks are NOT included unless explicitly requested. When tests ARE written, they must follow TDD principles (written first, fail before implementation).

**MVP Definition**: Phase 1-4 (T001-T027) represents the minimum viable product - basic query and import with replace pattern. This can be shipped independently.

**Incremental Delivery**: Phases 5-7 (T028-T039) add enhancements (statistics, metadata, error handling) and can be delivered incrementally after MVP.

**Parallel Work**: Tasks marked [P] can be executed in parallel with other [P] tasks in the same phase (different files, no dependencies).

**Story Labels**: Tasks are labeled with user story IDs ([US1], [US2], etc.) to trace requirements. Tasks without story labels are setup/foundational/polish work.
