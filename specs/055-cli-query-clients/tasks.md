# Tasks: CLI Query Clients/Workstations

**Input**: Design documents from `/specs/055-cli-query-clients/`
**Prerequisites**: plan.md (required), spec.md (required), data-model.md, research.md, quickstart.md

**Tests**: Not requested in feature specification (per constitution principle IV)

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

This is a multi-module project:
- **CLI module**: `src/cli/src/main/kotlin/com/secman/cli/`
- **Shared module**: `src/shared/src/main/kotlin/com/secman/crowdstrike/`
- **Backend module**: `src/backendng/` (no changes needed)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Create the new DeviceType enum that all user stories depend on

- [x] T001 [P] Create DeviceType enum in src/shared/src/main/kotlin/com/secman/crowdstrike/dto/DeviceType.kt

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core API client changes that enable all device type queries

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T002 Refactor getServerDeviceIdsFiltered() to accept DeviceType parameter in src/shared/src/main/kotlin/com/secman/crowdstrike/client/CrowdStrikeApiClientImpl.kt
- [x] T003 Add getDeviceIdsFiltered(deviceType: DeviceType) method that generates appropriate FQL filter in src/shared/src/main/kotlin/com/secman/crowdstrike/client/CrowdStrikeApiClientImpl.kt
- [x] T004 Update queryServersWithFilters() to use DeviceType enum instead of String parameter in src/shared/src/main/kotlin/com/secman/crowdstrike/client/CrowdStrikeApiClientImpl.kt

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Query Workstation Vulnerabilities (Priority: P1) 🎯 MVP

**Goal**: Enable querying CrowdStrike for workstation/client device vulnerabilities

**Independent Test**: Run `./gradlew cli:run --args='query servers --device-type WORKSTATION --dry-run --verbose'` and verify workstation devices are queried

### Implementation for User Story 1

- [x] T005 [US1] Add --device-type option validation in ServersCommand.execute() in src/cli/src/main/kotlin/com/secman/cli/commands/ServersCommand.kt
- [x] T006 [US1] Convert deviceType String to DeviceType enum with case-insensitive parsing in src/cli/src/main/kotlin/com/secman/cli/commands/ServersCommand.kt
- [x] T007 [US1] Update console output to reflect actual device type being queried (e.g., "Querying CrowdStrike for workstations...") in src/cli/src/main/kotlin/com/secman/cli/commands/ServersCommand.kt
- [x] T008 [US1] Add error handling for invalid device type values with helpful message in src/cli/src/main/kotlin/com/secman/cli/commands/ServersCommand.kt

**Checkpoint**: User Story 1 complete - can query WORKSTATION devices via CLI

---

## Phase 4: User Story 2 - Default Behavior Preserved (Priority: P1)

**Goal**: Ensure backward compatibility - existing server queries work identically

**Independent Test**: Run existing `./gradlew cli:run --args='query servers --dry-run'` without --device-type and verify SERVER behavior unchanged

### Implementation for User Story 2

- [x] T009 [US2] Verify deviceType property default remains "SERVER" in src/cli/src/main/kotlin/com/secman/cli/commands/ServersCommand.kt
- [x] T010 [US2] Ensure queryServersWithFilters() defaults to SERVER when no deviceType specified in src/shared/src/main/kotlin/com/secman/crowdstrike/client/CrowdStrikeApiClientImpl.kt

**Checkpoint**: User Story 2 complete - backward compatibility verified

---

## Phase 5: User Story 3 - Query All Device Types (Priority: P2)

**Goal**: Enable querying both servers and workstations in a single command

**Independent Test**: Run `./gradlew cli:run --args='query servers --device-type ALL --dry-run --verbose'` and verify both device types are queried

### Implementation for User Story 3

- [x] T011 [US3] Implement ALL device type handling in getDeviceIdsFiltered() - query both SERVER and WORKSTATION, combine results in src/shared/src/main/kotlin/com/secman/crowdstrike/client/CrowdStrikeApiClientImpl.kt
- [x] T012 [US3] Update verbose output to show device type breakdown when querying ALL in src/cli/src/main/kotlin/com/secman/cli/commands/ServersCommand.kt

**Checkpoint**: User Story 3 complete - can query all device types in single command

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final verification and build validation

- [x] T013 Run ./gradlew build to verify compilation succeeds
- [x] T014 Run quickstart.md validation scenarios to verify all device types work correctly
- [x] T015 Update CLI documentation/help text if needed in src/cli/src/main/kotlin/com/secman/cli/SecmanCli.kt

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 completion - BLOCKS all user stories
- **User Stories (Phase 3-5)**: All depend on Foundational phase completion
  - US1 and US2 are both P1 priority and can proceed in parallel
  - US3 depends on foundational work but is independent of US1/US2
- **Polish (Phase 6)**: Depends on all user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Depends on T001-T004 (Foundational) - No dependencies on other stories
- **User Story 2 (P1)**: Depends on T001-T004 (Foundational) - Shares T010 with US1 but tests different behavior
- **User Story 3 (P2)**: Depends on T001-T004 (Foundational) - No dependencies on US1/US2

### Task Dependencies Within Phases

```
Phase 1:  T001 (DeviceType enum)
              ↓
Phase 2:  T002 → T003 → T004 (sequential - same file modifications)
              ↓
Phase 3:  T005 → T006 → T007 → T008 (sequential - same file modifications)
              ↓
Phase 4:  T009, T010 (verification tasks, can run after US1)
              ↓
Phase 5:  T011 → T012 (sequential - T011 API, T012 CLI)
              ↓
Phase 6:  T013 → T014 → T015 (sequential - build first, test, then docs)
```

### Parallel Opportunities

Within this feature, most tasks modify the same files (ServersCommand.kt, CrowdStrikeApiClientImpl.kt) so parallelism is limited. However:

- T001 (DeviceType.kt) is a new file and can be created independently
- User Stories 1, 2, and 3 are conceptually independent (different behaviors)
- T013, T014, T015 in Polish phase are independent activities

---

## Implementation Strategy

### MVP First (User Story 1 + User Story 2)

1. Complete Phase 1: Create DeviceType enum
2. Complete Phase 2: Refactor API client
3. Complete Phase 3: User Story 1 (WORKSTATION queries)
4. Complete Phase 4: User Story 2 (backward compatibility)
5. **STOP and VALIDATE**: Test both WORKSTATION and default SERVER queries
6. Build passes → MVP complete

### Full Implementation

1. Complete MVP (above)
2. Complete Phase 5: User Story 3 (ALL device types)
3. Complete Phase 6: Polish
4. Final build validation

### Incremental Delivery

Each user story delivers independent value:
- **After US1**: Can query workstation vulnerabilities
- **After US2**: Existing server queries still work (confidence)
- **After US3**: Can query comprehensive vulnerability landscape

---

## Notes

- All tasks modify existing files except T001 (new DeviceType.kt)
- No test tasks included (per constitution principle IV - testing only when requested)
- US1 and US2 are both P1 priority because backward compatibility is critical
- Commit after each completed phase
- The existing `deviceType: String = "SERVER"` property in ServersCommand.kt already exists; we're enhancing its validation and usage
