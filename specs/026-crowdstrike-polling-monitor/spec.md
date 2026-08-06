# Feature Specification: CrowdStrike Polling Monitor for HIGH/CRITICAL Vulnerabilities

**Feature Branch**: `026-crowdstrike-polling-monitor`  
**Created**: October 18, 2025  
**Status**: Draft  
**Input**: User description: "extend the kotlin based command line client in /src/cli with a functionality to query every n minutes the crowdstrike API to retrieve all HIGH and CRITICAL findings on servers. Additionally storing the retrieved vulnerabilities in secman must be supported."

## Clarifications

### Session 2025-10-18

- **Polling Interval**: The interval "n minutes" should be configurable via CLI arguments and configuration file
- **Severity Filter**: Focus exclusively on HIGH and CRITICAL severity vulnerabilities as specified
- **Storage**: Leverage existing Micronaut backend API for storing vulnerabilities in the secman database
- **Target**: Query all servers/devices managed in CrowdStrike, not just specific hostnames
- **Authentication**: Reuse existing CrowdStrike authentication mechanism with token caching

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Continuous Monitoring Mode (Priority: P1)

Security administrators need to run a continuous monitoring process that polls CrowdStrike API at regular intervals to detect new HIGH and CRITICAL vulnerabilities on all servers in real-time.

**Why this priority**: This is the core capability - automated, periodic polling of the CrowdStrike API for high-severity vulnerabilities enables proactive security monitoring.

**Independent Test**: Can be tested by starting the monitor with a short polling interval (e.g., 2 minutes) and verifying that queries are executed repeatedly at the specified interval.

**Acceptance Scenarios**:

1. **Given** the CLI monitor is started with a polling interval, **When** the specified time elapses, **Then** the CrowdStrike API is queried for HIGH and CRITICAL vulnerabilities
2. **Given** the monitor is running, **When** new HIGH or CRITICAL vulnerabilities are detected, **Then** they are logged and displayed
3. **Given** the monitor is running, **When** a user sends SIGINT (Ctrl+C), **Then** the monitor shuts down gracefully after completing the current poll

---

### User Story 2 - Automatic Vulnerability Storage (Priority: P1)

Security administrators need retrieved HIGH and CRITICAL vulnerabilities to be automatically stored in the secman database for historical tracking and reporting.

**Why this priority**: Storage is essential for maintaining a vulnerability history and enabling analysis. Without this, the monitoring data would be lost after each poll.

**Independent Test**: Can be tested by running a poll cycle and verifying that detected vulnerabilities are successfully saved to the database via the backend API.

**Acceptance Scenarios**:

1. **Given** HIGH or CRITICAL vulnerabilities are detected, **When** the poll completes, **Then** all vulnerabilities are stored in the secman database via the backend API
2. **Given** a vulnerability already exists in the database, **When** it is detected again, **Then** duplicate entries are prevented
3. **Given** the backend API is unavailable, **When** storage is attempted, **Then** an error is logged and the monitor continues operation

---

### User Story 3 - Configurable Polling Parameters (Priority: P2)

Security administrators need to configure polling interval, severity filters, and target devices to customize the monitoring behavior for their environment.

**Why this priority**: Configuration flexibility allows administrators to adapt the tool to their specific needs and rate limit constraints.

**Independent Test**: Can be tested by providing different configuration values and verifying the monitor operates according to the specified parameters.

**Acceptance Scenarios**:

1. **Given** a configuration file exists, **When** the monitor starts, **Then** it loads polling interval and other parameters from the file
2. **Given** CLI arguments are provided, **When** the monitor starts, **Then** CLI arguments override configuration file values
3. **Given** no configuration is provided, **When** the monitor starts, **Then** sensible defaults are used (e.g., 5-minute interval)

---

### User Story 4 - Query All Devices or Specific Groups (Priority: P2)

Security administrators need to query either all devices in CrowdStrike or filter by specific device groups, tags, or hostnames.

**Why this priority**: While monitoring all devices is the default use case, targeting specific groups enables focused monitoring for high-value assets.

**Independent Test**: Can be tested by specifying device filters and verifying that only matching devices are queried.

**Acceptance Scenarios**:

1. **Given** no device filter is specified, **When** polling occurs, **Then** all devices in CrowdStrike are queried for HIGH and CRITICAL vulnerabilities
2. **Given** a hostname list is provided, **When** polling occurs, **Then** only specified hostnames are queried
3. **Given** a device group/tag filter is provided, **When** polling occurs, **Then** only devices in that group are queried

---

### User Story 5 - Monitoring Statistics and Reporting (Priority: P3)

Security administrators need visibility into monitoring statistics such as total polls executed, vulnerabilities detected, and API errors encountered.

**Why this priority**: This is an operational enhancement that helps administrators understand monitor health and effectiveness.

**Independent Test**: Can be tested by running the monitor for several poll cycles and verifying that accurate statistics are displayed.

**Acceptance Scenarios**:

1. **Given** the monitor is running, **When** a poll completes, **Then** statistics are updated and can be viewed
2. **Given** multiple polls have occurred, **When** a user requests statistics, **Then** summary information is displayed (total polls, vulnerabilities found, errors)
3. **Given** the monitor is stopped, **When** shutdown occurs, **Then** final statistics are displayed

---

### Edge Cases

1. **CrowdStrike API Rate Limiting**: Monitor should detect rate limit errors (429) and implement exponential backoff before retrying
2. **Large Result Sets**: Handle pagination if the number of HIGH/CRITICAL vulnerabilities exceeds API response limits
3. **Network Failures**: Implement retry logic with exponential backoff for transient network errors
4. **Token Expiration**: Ensure OAuth2 token is refreshed automatically during long-running monitor sessions
5. **Concurrent Execution**: Prevent multiple monitor instances from running simultaneously on the same system

## Technical Architecture

### Components

1. **PollingMonitor Command** (`src/cli/src/main/kotlin/com/secman/cli/commands/MonitorCommand.kt`)
   - New CLI command for starting the polling monitor
   - Uses scheduled executor for periodic polling
   - Handles graceful shutdown

2. **CrowdStrike Poller Service** (`src/cli/src/main/kotlin/com/secman/cli/service/CrowdStrikePollerService.kt`)
   - Core polling logic
   - Queries CrowdStrike API for all devices with HIGH/CRITICAL vulnerabilities
   - Filters results by severity
   - Handles pagination

3. **Vulnerability Storage Service** (`src/cli/src/main/kotlin/com/secman/cli/service/VulnerabilityStorageService.kt`)
   - HTTP client for backend API
   - Stores vulnerabilities via POST to `/api/crowdstrike/vulnerabilities/save`
   - Handles deduplication and error recovery

4. **Monitor Configuration** (`~/.secman/monitor.conf`)
   - YAML configuration file for monitor settings
   - Polling interval, severity filters, device filters
   - Backend API endpoint configuration

### Data Flow

```
┌─────────────────┐     Periodic      ┌──────────────────────┐
│ PollingMonitor  │───────Timer───────▶│ CrowdStrikePoller   │
│   Command       │                    │    Service          │
└─────────────────┘                    └──────────────────────┘
                                                │
                                                │ Query API
                                                │ (severity:HIGH|CRITICAL)
                                                ▼
                                       ┌──────────────────────┐
                                       │  CrowdStrike API     │
                                       │  (via shared module) │
                                       └──────────────────────┘
                                                │
                                                │ Vulnerabilities
                                                ▼
                                       ┌──────────────────────┐
                                       │ VulnerabilityStorage │
                                       │     Service          │
                                       └──────────────────────┘
                                                │
                                                │ HTTP POST
                                                ▼
                                       ┌──────────────────────┐
                                       │ Secman Backend API   │
                                       │ /api/crowdstrike/    │
                                       │ vulnerabilities/save │
                                       └──────────────────────┘
                                                │
                                                ▼
                                       ┌──────────────────────┐
                                       │  MariaDB Database    │
                                       │  (Asset, Vuln tables)│
                                       └──────────────────────┘
```

### Configuration Schema

```yaml
# ~/.secman/monitor.conf
crowdstrike:
  polling_interval_minutes: 5
  severity_filter:
    - HIGH
    - CRITICAL
  device_filter:
    all: true
    # OR specific hostnames:
    # hostnames:
    #   - server01
    #   - server02
    # OR device groups:
    # groups:
    #   - production
  
backend:
  base_url: "http://localhost:8080"
  timeout_seconds: 30
  retry_attempts: 3

monitor:
  log_level: INFO
  statistics_enabled: true
```

## CLI Interface

### New Command: `monitor`

```bash
# Start monitoring with default settings (5-minute interval)
secman monitor

# Start with custom interval
secman monitor --interval 10

# Monitor with specific hostnames
secman monitor --hostnames server01,server02,server03

# Monitor with backend URL override
secman monitor --backend-url http://secman.company.com:8080

# Dry run (no storage, just query and display)
secman monitor --dry-run --interval 2

# Enable verbose logging
secman monitor --verbose
```

### Command Options

- `--interval <minutes>`: Polling interval in minutes (default: 5)
- `--hostnames <list>`: Comma-separated list of hostnames to monitor
- `--backend-url <url>`: Backend API base URL
- `--dry-run`: Query API but don't store results
- `--verbose`: Enable verbose logging
- `--config <path>`: Path to configuration file (default: ~/.secman/monitor.conf)
- `--no-storage`: Disable automatic storage (display only)

## Implementation Tasks

### Phase 1: Core Polling Infrastructure (P1)

- [ ] **T1**: Create `MonitorCommand` class with Picocli annotations
- [ ] **T2**: Implement scheduled executor for periodic polling
- [ ] **T3**: Add graceful shutdown handler (SIGINT/SIGTERM)
- [ ] **T4**: Create monitor configuration loader

### Phase 2: CrowdStrike Polling Service (P1)

- [ ] **T5**: Create `CrowdStrikePollerService` for querying all devices
- [ ] **T6**: Implement severity filtering (HIGH, CRITICAL)
- [ ] **T7**: Add pagination support for large result sets
- [ ] **T8**: Implement rate limit handling and exponential backoff
- [ ] **T9**: Add OAuth2 token refresh logic for long-running sessions

### Phase 3: Vulnerability Storage (P1)

- [ ] **T10**: Create `VulnerabilityStorageService` with HTTP client
- [ ] **T11**: Implement POST to backend API endpoint
- [ ] **T12**: Add deduplication logic (check existing vulnerabilities)
- [ ] **T13**: Implement error recovery and retry logic

### Phase 4: Configuration and CLI (P2)

- [ ] **T14**: Create YAML configuration schema for monitor settings
- [ ] **T15**: Implement CLI argument parsing for monitor command
- [ ] **T16**: Add configuration validation and defaults
- [ ] **T17**: Implement device filtering (all, hostnames, groups)

### Phase 5: Monitoring and Statistics (P3)

- [ ] **T18**: Add statistics tracking (polls, vulnerabilities, errors)
- [ ] **T19**: Implement periodic statistics logging
- [ ] **T20**: Add final statistics display on shutdown
- [ ] **T21**: Create dashboard output for current monitor status

### Phase 6: Testing (All Phases)

- [ ] **T22**: Unit tests for `CrowdStrikePollerService`
- [ ] **T23**: Unit tests for `VulnerabilityStorageService`
- [ ] **T24**: Integration tests for monitor command
- [ ] **T25**: Contract tests for backend API integration
- [ ] **T26**: End-to-end test with mock CrowdStrike API

## Dependencies

- Existing CrowdStrike API client (shared module)
- Existing backend API endpoint: `/api/crowdstrike/vulnerabilities/save`
- Micronaut framework for dependency injection
- Kotlin coroutines for async operations
- ScheduledExecutorService for periodic polling
- Jackson for YAML configuration parsing

## Non-Functional Requirements

- **Performance**: Support monitoring up to 10,000 devices without performance degradation
- **Reliability**: Handle network failures and API errors gracefully with automatic retry
- **Resource Usage**: Monitor should consume < 100MB memory during operation
- **Scalability**: Support running as system service (systemd, Windows service)
- **Security**: Store API credentials securely, never log sensitive data
- **Observability**: Provide detailed logging for troubleshooting

## Success Criteria

1. Monitor successfully polls CrowdStrike API at configured intervals
2. HIGH and CRITICAL vulnerabilities are correctly filtered and retrieved
3. Vulnerabilities are stored in secman database without duplicates
4. Monitor handles API rate limits and network errors gracefully
5. OAuth2 tokens are refreshed automatically during long sessions
6. Graceful shutdown on SIGINT/SIGTERM without data loss
7. Configuration can be customized via file and CLI arguments
8. Statistics provide visibility into monitor operation and effectiveness
