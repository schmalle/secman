# Feature Specification: CLI Query Clients/Workstations

**Feature Branch**: `055-cli-query-clients`
**Created**: 2025-12-16
**Status**: Draft
**Input**: User description: "in the CLI functionality i can use a 'query servers' command, please add a command line option to also query clients instead of servers, extend the Crowdstrike query logic accordingly"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Query Workstation Vulnerabilities (Priority: P1)

As a security administrator, I want to query CrowdStrike for vulnerabilities on client workstations (laptops, desktops) so that I can assess endpoint security posture across my organization's end-user devices.

**Why this priority**: End-user devices are a significant attack surface and need vulnerability tracking alongside servers. This is the core functionality being requested.

**Independent Test**: Can be fully tested by running the CLI command with the new client/workstation option and verifying vulnerabilities are retrieved for workstation-type devices only.

**Acceptance Scenarios**:

1. **Given** the CLI is configured with valid CrowdStrike credentials, **When** I run `./gradlew cli:run --args='query servers --device-type WORKSTATION ...'`, **Then** the system queries CrowdStrike for devices with product type "Workstation" and returns their vulnerabilities.

2. **Given** the CLI is configured with valid CrowdStrike credentials, **When** I run the query with `--device-type WORKSTATION`, **Then** only workstation/client devices are included (no servers).

3. **Given** I want to query workstations with specific filters, **When** I run the query with `--device-type WORKSTATION --severity CRITICAL,HIGH --min-days-open 30`, **Then** the system applies both the device type filter and the severity/days-open filters correctly.

---

### User Story 2 - Default Behavior Preserved (Priority: P1)

As a security administrator using the existing workflow, I want the default behavior of the `query servers` command to remain unchanged so that my existing scripts and processes continue to work.

**Why this priority**: Backward compatibility is critical to avoid breaking existing automation.

**Independent Test**: Run existing server query command without the new option and verify behavior is identical to before.

**Acceptance Scenarios**:

1. **Given** no `--device-type` option is specified, **When** I run `./gradlew cli:run --args='query servers ...'`, **Then** the system queries only SERVER devices (current default behavior).

2. **Given** I explicitly specify `--device-type SERVER`, **When** I run the query, **Then** the behavior is identical to omitting the option.

---

### User Story 3 - Query All Device Types (Priority: P2)

As a security administrator, I want to query vulnerabilities across all device types (servers and workstations) in a single command so that I can get a complete picture of my organization's vulnerability landscape.

**Why this priority**: Convenient for comprehensive reporting but not essential for the core use case.

**Independent Test**: Run the query with `--device-type ALL` and verify both servers and workstations are included.

**Acceptance Scenarios**:

1. **Given** I specify `--device-type ALL`, **When** I run the query, **Then** the system queries both SERVER and WORKSTATION devices from CrowdStrike.

2. **Given** I query with `--device-type ALL`, **When** results are returned, **Then** I can distinguish servers from workstations in the output.

---

### Edge Cases

- What happens when no workstations are found in CrowdStrike? System should return gracefully with "No vulnerabilities found matching criteria" message.
- What happens when an invalid device type is specified? System should display an error listing valid options (SERVER, WORKSTATION, ALL).
- What happens when workstation count is very large (e.g., 50,000+ devices)? System should use the same pagination and batching optimizations as the server query.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST accept a `--device-type` command-line option with values: SERVER (default), WORKSTATION, ALL
- **FR-002**: System MUST query CrowdStrike with FQL filter `product_type_desc:'Workstation'` when device type is WORKSTATION
- **FR-003**: System MUST query CrowdStrike with FQL filter `product_type_desc:'Server'` when device type is SERVER (existing behavior)
- **FR-004**: System MUST query both device types when device type is ALL
- **FR-005**: System MUST apply the same severity and days-open filters to workstation queries as server queries
- **FR-006**: System MUST apply the same "last seen within 24 hours" optimization to workstation queries
- **FR-007**: System MUST use the same batching and parallelism strategy for workstation queries to handle large device counts
- **FR-008**: System MUST display helpful error message when invalid device type is specified
- **FR-009**: System MUST default to SERVER device type when `--device-type` is not specified (backward compatibility)
- **FR-010**: System MUST support importing workstation vulnerabilities to the backend when `--save` is specified

### Key Entities

- **Device Type**: Enum representing CrowdStrike device classification (SERVER, WORKSTATION, ALL)
- **CrowdStrike Product Type**: The `product_type_desc` field from CrowdStrike API that distinguishes servers from workstations

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can query workstation vulnerabilities using the same CLI workflow as server queries
- **SC-002**: Default behavior remains unchanged - existing server query commands work identically
- **SC-003**: Query performance for workstations is comparable to server queries (same pagination/batching optimizations)
- **SC-004**: Users can query all device types in a single command for comprehensive vulnerability reporting
- **SC-005**: Error messages clearly guide users when invalid options are provided

## Assumptions

- CrowdStrike API uses `product_type_desc:'Workstation'` FQL filter for workstations/clients (industry standard for Falcon API)
- Workstation device counts may be higher than server counts (typical enterprise has more endpoints than servers)
- The existing backend import endpoint (`/api/crowdstrike/servers/import`) can accept workstation data - the endpoint processes Assets generically regardless of device type
