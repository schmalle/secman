
# Implementation Plan: Vulnerability Query Tool

**Branch**: `002-implement-a-python` | **Date**: 2025-10-05 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/Users/flake/sources/misc/helper/specs/002-implement-a-python/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path
   → SUCCESS: Spec loaded
2. Fill Technical Context (scan for NEEDS CLARIFICATION)
   → SUCCESS: All clarifications resolved in spec
   → Project Type: Single (CLI tool)
3. Fill the Constitution Check section
   → COMPLETE: All constitutional requirements addressed
4. Evaluate Constitution Check section
   → PASS: No violations detected
   → Update Progress Tracking: Initial Constitution Check ✓
5. Execute Phase 0 → research.md
   → COMPLETE: research.md created with all technical decisions
6. Execute Phase 1 → contracts, data-model.md, quickstart.md, CLAUDE.md
   → COMPLETE: All artifacts generated
7. Re-evaluate Constitution Check section
   → COMPLETE: Post-design check PASS (no new violations)
8. Plan Phase 2 → Describe task generation approach
   → COMPLETE: Task planning approach documented
9. STOP - Ready for /tasks command
   → READY: /tasks can now generate tasks.md
```

**IMPORTANT**: The /plan command STOPS at step 8. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary
Security analysts need to query CrowdStrike Falcon API for vulnerability data with flexible filtering (device type, severity, age, AD domain, hostname) and export results in multiple formats (XLSX, CSV, TXT). The tool must handle complete result sets via pagination, implement retry logic for rate limiting, provide progress feedback for long queries, and use specific exit codes for automation integration. All credentials sourced from environment variables, with comprehensive error handling and logging capabilities.

## Technical Context
**Language/Version**: Python 3.11+
**Primary Dependencies**: falconpy (CrowdStrike Falcon API), openpyxl/xlsxwriter (XLSX export), argparse (CLI), pytest (testing)
**Storage**: N/A (stateless CLI tool, no persistent storage)
**Testing**: pytest with mocked API responses, contract tests for API integration, integration tests for user scenarios
**Target Platform**: Cross-platform CLI (Linux, macOS, Windows) - Python 3.11+ runtime required
**Project Type**: single (standalone CLI tool)
**Performance Goals**: Progress indication after 10 seconds, handle unlimited result sets via pagination, retry with exponential backoff for rate limits
**Constraints**: Environment-based auth only (no credential files), TLS certificate validation required, sanitized logging (no credentials in any log level)
**Scale/Scope**: Support enterprise device fleets (thousands of devices), handle large vulnerability datasets efficiently, export files with timestamped naming

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Python-First Development ✅
- **Compliance**: Using Python 3.11+ with type hints, following PEP 8
- **Evidence**: Technical Context specifies modern Python standards

### II. CLI Interface ✅
- **Compliance**: All functionality exposed via CLI, accepts parameters, outputs to stdout/stderr
- **Evidence**: FR-024, FR-025, FR-026 require comprehensive CLI interface with help text

### III. Configuration via Environment ✅
- **Compliance**: FALCON_CLIENT_ID, FALCON_CLIENT_SECRET, FALCON_CLOUD_REGION from environment
- **Evidence**: FR-020, FR-021, FR-022 mandate environment variable configuration

### IV. Dual Logging Modes ✅
- **Compliance**: Normal and extended logging modes via CLI flag
- **Evidence**: Constitution requirement directly applicable, will implement --verbose flag

### V. Data Export Capabilities ✅
- **Compliance**: XLSX export with proper data type preservation
- **Evidence**: FR-013, FR-016, FR-017 specify Excel export with column structure

### VI. API Integration Standards ✅
- **Compliance**: Using falconpy library for all Falcon API interactions
- **Evidence**: Primary dependency, FR-034 requires retry logic, error handling per constitution

### VII. Dependency Management ✅
- **Compliance**: Latest stable versions, pinned in requirements.txt
- **Evidence**: Technical Context specifies contemporary libraries

### Security & Compliance ✅
- **Credential Handling**: FR-023 mandates no credential logging, environment-only auth
- **API Security**: TLS validation required per constraints, timeout controls needed
- **Audit Trail**: FR-027 progress indication, structured logging for API operations

### Development Workflow ✅
- **Test-First**: Contract tests before implementation, mocked API responses in CI
- **Code Quality**: Type hints required, pytest framework selected
- **Documentation**: README will document CLI commands and environment variables

**GATE RESULT**: ✅ PASS - All constitutional requirements satisfied

## Project Structure

### Documentation (this feature)
```
specs/002-implement-a-python/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
├── contracts/           # Phase 1 output (/plan command)
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
src/
├── models/              # Data models (Vulnerability, Device, FilterCriteria, etc.)
├── services/            # API service layer (FalconClient, pagination, retry logic)
├── cli/                 # CLI command interface (argument parsing, main entry point)
├── exporters/           # Export formatters (XLSXExporter, CSVExporter, TXTExporter)
└── lib/                 # Shared utilities (logging config, validation, error handling)

tests/
├── contract/            # API contract tests (Falcon API integration points)
├── integration/         # End-to-end scenario tests (user story validation)
└── unit/                # Unit tests (business logic, transformations, formatters)
```

**Structure Decision**: Single project structure selected. This is a standalone CLI tool with no web/mobile components. All source code in `src/` organized by layer (models, services, CLI, exporters, utilities). Tests mirror source structure with contract/integration/unit separation per constitutional TDD requirements.

## Phase 0: Outline & Research

**Status**: All technical unknowns resolved via feature specification and clarifications

### Research Topics

#### 1. FalconPy Library - Vulnerability API Patterns
**Decision**: Use falconpy `SpotlightVulnerabilities` service class
**Rationale**: Official CrowdStrike Python SDK, active maintenance, handles authentication/pagination/rate limiting
**Alternatives considered**: Direct REST API calls rejected (violates constitution VI, no retry/rate limit handling)
**Key APIs**:
- `queryVulnerabilities()` - Search with filters
- `getVulnerabilities()` - Retrieve details by IDs
- `queryVulnerabilitiesCombined()` - Single call with full data (preferred for pagination efficiency)

#### 2. Pagination Strategy for Unlimited Result Sets
**Decision**: Offset-based pagination with automatic iteration
**Rationale**: Clarification confirmed no result limit, falconpy supports offset/limit parameters
**Implementation**: Loop with offset increment until `total < offset + limit`
**Alternatives considered**: Cursor-based pagination (not supported by Spotlight Vulnerabilities API)

#### 3. Export Library Selection
**Decision**:
- XLSX: `openpyxl` (write-optimized, better for large datasets)
- CSV: Python stdlib `csv` module
- TXT: Custom formatter using tab delimiters

**Rationale**:
- openpyxl balances features and performance for XLSX
- CSV stdlib sufficient for simple comma-delimited output
- Tab-delimited TXT requires no external dependency

**Alternatives considered**: xlsxwriter (similar performance, openpyxl has better type preservation per FR-017)

#### 4. CLI Framework
**Decision**: Python stdlib `argparse`
**Rationale**: Built-in, sufficient for required parameter structure, no external dependency
**Alternatives considered**: Click/Typer rejected (constitution prefers minimal dependencies for simple cases)

#### 5. Testing Strategy for API Integration
**Decision**:
- Unit tests: Mock falconpy service classes using `pytest-mock`
- Contract tests: Record real API responses, replay with `vcrpy` or manual fixtures
- Integration tests: Full workflow with mocked API (no live credentials in CI)

**Rationale**: Constitutional requirement for mocked API in CI, contract tests ensure API compatibility
**Alternatives considered**: Live API tests rejected (requires credentials, slow, flaky)

#### 6. Error Handling & Retry Logic
**Decision**:
- Exponential backoff: Start 1s, double each retry, max 5 retries
- Retry conditions: 429 (rate limit), 502/503/504 (server errors)
- Max total wait: ~30 seconds before exit code 4

**Rationale**: Edge case clarification requires retry for rate limits, constitutional requirement for graceful handling
**Alternatives considered**: Fixed retry intervals rejected (doesn't respect server recovery time)

#### 7. Logging Configuration
**Decision**:
- Normal mode (`--verbose` absent): INFO level, minimal output
- Extended mode (`--verbose`): DEBUG level, includes API call details
- Format: `%(asctime)s | %(levelname)s | %(name)s | %(message)s`
- Sanitization: Regex filter for credential patterns in all log levels

**Rationale**: Constitutional dual logging mode requirement, structured format for machine parsing
**Alternatives considered**: JSON structured logging (deferred - YAGNI for v1, can add later)

#### 8. Progress Indication Implementation
**Decision**:
- Timer-based: Start background thread if query exceeds 10 seconds
- Output: "Fetching page X/est... (Y records retrieved)" to stderr
- Estimation: Use `total` from first API response

**Rationale**: Clarification specifies 10-second threshold, stderr keeps stdout clean for piping
**Alternatives considered**: Spinner (less informative), progress bar (requires terminal detection complexity)

#### 9. Exit Code Strategy
**Decision**: Map error types to codes per clarification:
```python
class ExitCode(IntEnum):
    SUCCESS = 0
    AUTH_ERROR = 1
    NETWORK_ERROR = 2
    INVALID_ARGS = 3
    API_ERROR = 4
    EXPORT_ERROR = 5
```

**Rationale**: Clarification provides detailed exit code spec for automation integration
**Alternatives considered**: N/A (spec is explicit)

#### 10. Device Type Classification
**Decision**:
- CLIENT: Filter `platform_name` contains "Workstation" or tags include client indicators
- SERVER: Filter `platform_name` contains "Server" or tags include server indicators
- BOTH: No filter applied

**Rationale**: Falcon API uses platform metadata and tags for device classification
**Research needed**: Confirm exact field names during Phase 1 API contract review
**Alternatives considered**: Manual user tagging (rejected - not scalable, error-prone)

**Output**: research.md (consolidated above)

## Phase 1: Design & Contracts
*Prerequisites: research.md complete*

### Artifacts to Generate:
1. `data-model.md` - Entity definitions with validation rules
2. `contracts/falcon_api.md` - Falcon API contract specifications
3. `contracts/cli_interface.md` - CLI argument contract
4. `tests/contract/test_falcon_api.py` - Failing contract tests for API
5. `tests/integration/test_user_scenarios.py` - Failing integration tests for acceptance scenarios
6. `quickstart.md` - Manual validation steps matching acceptance scenarios
7. `CLAUDE.md` - Agent context file for Claude Code

### Execution:

#### Step 1: Extract Entities → data-model.md

**Entities** (from spec Key Entities section):
- Vulnerability (CVE ID, severity, product versions, days open)
- Device/Host (hostname, IP, groups, cloud IDs, OS, AD domain, type)
- FilterCriteria (device type, severities, min days, optional AD domain, optional hostname)
- ExportConfiguration (format, file path, column ordering)
- AuthenticationContext (client ID, secret, region)

**Validation Rules** (from functional requirements):
- Severity must be in {MEDIUM, HIGH, CRITICAL}
- Device type must be in {CLIENT, SERVER, BOTH}
- Min days open must be non-negative integer
- Export format must be in {XLSX, CSV, TXT}
- Required env vars: FALCON_CLIENT_ID, FALCON_CLIENT_SECRET, FALCON_CLOUD_REGION

**State Transitions**: N/A (stateless operations)

#### Step 2: Generate API Contracts → contracts/

**Falcon API Contract** (`contracts/falcon_api.md`):
- **Endpoint**: `POST /spotlight/combined/vulnerabilities/v1`
- **Authentication**: OAuth2 client credentials flow
- **Request**: Filter object with device/severity/domain parameters
- **Response**: Paginated vulnerability list with device metadata
- **Error Responses**: 401 (auth), 429 (rate limit), 500 (server error)

**CLI Interface Contract** (`contracts/cli_interface.md`):
- **Required**: `--device-type {CLIENT,SERVER,BOTH}`, `--severity MEDIUM HIGH CRITICAL`, `--min-days-open N`
- **Optional**: `--ad-domain DOMAIN`, `--hostname NAME`, `--output PATH`, `--format {XLSX,CSV,TXT}`, `--verbose`
- **Output**: Records to stdout OR file (based on --output), errors to stderr
- **Exit Codes**: 0-5 per clarification mapping

#### Step 3: Generate Contract Tests

**`tests/contract/test_falcon_api.py`** (failing):
```python
def test_query_vulnerabilities_returns_expected_schema():
    # Mock falconpy client
    # Call queryVulnerabilitiesCombined with filters
    # Assert response contains: resources[], meta.pagination
    # Assert resource schema matches: host.hostname, host.local_ip, cve.id, cve.severity, etc.
    assert False  # No implementation yet

def test_authentication_with_invalid_credentials_returns_401():
    assert False  # No implementation yet
```

**`tests/integration/test_user_scenarios.py`** (failing):
```python
def test_query_critical_vulns_30_days_servers_corp_domain():
    # Acceptance scenario 1
    # Mock API response
    # Run CLI: --device-type SERVER --severity CRITICAL --min-days-open 30 --ad-domain CORP.LOCAL
    # Assert output contains expected vulnerability records
    assert False  # No implementation yet

def test_export_to_xlsx_matches_required_columns():
    # Acceptance scenario 2
    # Run query, export to XLSX
    # Assert file exists with correct columns in order
    assert False  # No implementation yet
```

#### Step 4: Extract Test Scenarios → quickstart.md

**Quickstart Validation Steps**:
1. Configure environment variables
2. Run query with filters
3. Verify result count displayed
4. Export to XLSX and inspect columns
5. Verify exit codes for error scenarios

#### Step 5: Update Agent Context File

Will execute: `.specify/scripts/bash/update-agent-context.sh claude`

This will create/update `CLAUDE.md` with:
- Project overview: CrowdStrike Falcon vulnerability query CLI
- Tech stack: Python 3.11+, falconpy, openpyxl, pytest
- Recent changes: Initial implementation plan for vulnerability query tool
- Constitutional constraints: Test-first, type hints, environment config, dual logging

**Output**: data-model.md, contracts/, failing tests, quickstart.md, CLAUDE.md

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
- Load `.specify/templates/tasks-template.md` as base
- Generate tasks from Phase 1 artifacts:
  - contracts/falcon_api.md → contract test tasks [P]
  - contracts/cli_interface.md → CLI contract test tasks [P]
  - data-model.md entities → model creation tasks [P]
  - User scenarios → integration test tasks [P]
  - Implementation tasks to make tests pass

**Ordering Strategy**:
- Setup phase: Project initialization, dependencies, linting config
- Tests phase (TDD): Contract tests, integration tests (all failing)
- Models phase: Create data classes for entities [P]
- Services phase: FalconClient, pagination, retry logic
- Exporters phase: XLSX, CSV, TXT formatters [P]
- CLI phase: Argument parsing, main entry point, error handling
- Polish phase: Unit tests, logging, documentation

**Parallel Execution**:
- Different model files can be created in parallel [P]
- Different exporter files can be created in parallel [P]
- Contract tests for different APIs can be created in parallel [P]

**Estimated Output**: 30-35 numbered, ordered tasks in tasks.md

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)
**Phase 4**: Implementation (execute tasks.md following constitutional principles)
**Phase 5**: Validation (run tests, execute quickstart.md, performance validation)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

No constitutional violations detected. All requirements align with established principles.

## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command)
- [x] Phase 1: Design complete (/plan command)
- [x] Phase 2: Task planning complete (/plan command - describe approach only)
- [ ] Phase 3: Tasks generated (/tasks command)
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS
- [x] Post-Design Constitution Check: PASS
- [x] All NEEDS CLARIFICATION resolved
- [x] Complexity deviations documented (none)

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
