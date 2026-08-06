# Tasks: Vulnerability Query Tool

**Input**: Design documents from `/Users/flake/sources/misc/helper/specs/002-implement-a-python/`
**Prerequisites**: plan.md, research.md, data-model.md, contracts/falcon_api.md, contracts/cli_interface.md, quickstart.md

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → SUCCESS: Plan loaded with tech stack and structure
2. Load optional design documents:
   → data-model.md: 6 entities (Vulnerability, Device, FilterCriteria, ExportConfiguration, AuthenticationContext, VulnerabilityRecord)
   → contracts/falcon_api.md: Falcon API contract
   → contracts/cli_interface.md: CLI interface contract
   → research.md: Technical decisions loaded
3. Generate tasks by category:
   → Setup: Project init (3 tasks)
   → Tests: Contract tests (9 tasks [P]), Integration tests (5 tasks [P])
   → Core: Models (6 tasks [P]), Services (4 tasks), Exporters (3 tasks [P]), CLI (3 tasks)
   → Integration: Logging, error handling, retry logic (3 tasks)
   → Polish: Unit tests (4 tasks [P]), docs, performance (3 tasks)
4. Apply task rules:
   → Different files = marked [P] for parallel
   → Tests before implementation (TDD enforced)
   → Models before services before CLI
5. Number tasks sequentially (T001-T040)
6. Generate dependency graph
7. Create parallel execution examples
8. Validate task completeness
9. Return: SUCCESS (tasks ready for execution)
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Include exact file paths in descriptions

## Path Conventions
- **Single project**: `src/`, `tests/` at repository root
- Paths shown below use absolute repository root paths

---

## Phase 3.1: Setup

- [x] **T001** Create project directory structure: `src/{models,services,cli,exporters,lib}/` and `tests/{contract,integration,unit}/`
- [x] **T002** Initialize Python project with `pyproject.toml` and configure Python 3.11+ with build system (setuptools or hatchling)
- [x] **T003** [P] Create `requirements.txt` with pinned dependencies: falconpy>=1.4.0, openpyxl>=3.1.0, pytest>=7.4.0, pytest-mock>=3.12.0, mypy>=1.7.0, ruff>=0.1.0
- [x] **T004** [P] Configure linting and type checking: create `.ruff.toml` (or `ruff.toml`) for PEP 8 compliance and `mypy.ini` for strict type checking

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3

**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation**

### Contract Tests (Falcon API)
- [x] **T005** [P] Contract test: Falcon API authentication success in `tests/contract/test_falcon_auth.py` - verify valid credentials return 200, token obtained
- [x] **T006** [P] Contract test: Falcon API authentication failure in `tests/contract/test_falcon_auth.py` - verify invalid credentials return 401, exit code 1
- [x] **T007** [P] Contract test: Query vulnerabilities returns expected schema in `tests/contract/test_falcon_query.py` - assert response contains resources[], meta.pagination, correct field structure
- [x] **T008** [P] Contract test: Pagination retrieves all records in `tests/contract/test_falcon_pagination.py` - mock multi-page response, verify offset increment logic
- [x] **T009** [P] Contract test: Rate limit triggers retry in `tests/contract/test_falcon_retry.py` - mock 429 response, verify exponential backoff behavior

### Contract Tests (CLI Interface)
- [x] **T010** [P] Contract test: Missing required arguments exit code 3 in `tests/contract/test_cli_args.py` - verify missing --device-type returns usage and exits 3
- [x] **T011** [P] Contract test: Invalid argument values exit code 3 in `tests/contract/test_cli_args.py` - verify invalid --severity value exits 3
- [x] **T012** [P] Contract test: Missing environment variables exit code 1 in `tests/contract/test_cli_env.py` - verify missing FALCON_CLIENT_ID exits 1 with clear error
- [x] **T013** [P] Contract test: Help flag displays usage and exits 0 in `tests/contract/test_cli_help.py` - verify --help prints usage text, exits 0

### Integration Tests (User Scenarios)
- [x] **T014** [P] Integration test: Query critical vulns on servers in AD domain in `tests/integration/test_scenario_1.py` - acceptance scenario 1 (SERVER + CRITICAL + 30 days + CORP.LOCAL)
- [x] **T015** [P] Integration test: Export to XLSX with correct columns in `tests/integration/test_scenario_2.py` - acceptance scenario 2 (verify column order, data types)
- [ ] **T016** [P] Integration test: Export to CSV for specific hostname in `tests/integration/test_scenario_3.py` - acceptance scenario 3 (hostname filter + CSV format)
- [ ] **T017** [P] Integration test: Multiple severity levels OR logic in `tests/integration/test_scenario_4.py` - acceptance scenario 4 (HIGH + CRITICAL returns both)
- [ ] **T018** [P] Integration test: Missing credentials error handling in `tests/integration/test_scenario_5.py` - acceptance scenario 5 (clear error message, exit code 1)

---

## Phase 3.3: Core Implementation (ONLY after tests are failing)

### Enums and Constants
- [x] **T019** [P] Create enums in `src/models/enums.py` - define Severity, DeviceType, ExportFormat, ExitCode enums with validation

### Data Models
- [x] **T020** [P] Vulnerability model in `src/models/vulnerability.py` - dataclass with cve_id, severity, cvss_score, product_versions, days_open, detected_date, description + validation
- [x] **T021** [P] Device model in `src/models/device.py` - dataclass with device_id, hostname, local_ip, host_groups, cloud IDs, os_version, ad_domain, device_type, platform_name + validation
- [x] **T022** [P] FilterCriteria model in `src/models/filter_criteria.py` - dataclass with device_type, severities, min_days_open, ad_domain, hostname + validation + FQL conversion method
- [x] **T023** [P] ExportConfiguration model in `src/models/export_config.py` - dataclass with format, output_path, default_filename_pattern, timestamp_format + validation
- [x] **T024** [P] AuthenticationContext model in `src/models/auth_context.py` - dataclass with client_id, client_secret, cloud_region, base_url + env var loading + validation (never log credentials)
- [x] **T025** [P] VulnerabilityRecord model in `src/models/vulnerability_record.py` - composite dataclass combining Vulnerability + Device for export operations

### Service Layer
- [ ] **T026** FalconClient service in `src/services/falcon_client.py` - initialize falconpy SpotlightVulnerabilities, authenticate, handle auth errors with exit code 1
- [ ] **T027** Query service with pagination in `src/services/falcon_client.py` - implement queryVulnerabilitiesCombined with offset-based pagination loop (no result limit)
- [ ] **T028** Retry logic with exponential backoff in `src/services/falcon_client.py` - retry on 429/502/503/504, exponential backoff (1, 2, 4, 8, 16s), max 5 retries, exit code 4 if exhausted
- [ ] **T029** Progress indication service in `src/services/progress.py` - background thread that prints progress to stderr after 10 seconds ("Fetching page X/est... (Y records)")

### Exporters
- [x] **T030** [P] XLSX exporter in `src/exporters/xlsx_exporter.py` - use openpyxl to create workbook with 11 columns in order, preserve data types, handle empty results (headers only)
- [x] **T031** [P] CSV exporter in `src/exporters/csv_exporter.py` - use stdlib csv module, comma-delimited with proper quoting, same column structure as XLSX
- [x] **T032** [P] TXT exporter in `src/exporters/txt_exporter.py` - tab-delimited format using custom formatter, same column structure as XLSX/CSV

### CLI Interface
- [x] **T033** Argument parser in `src/cli/args.py` - use argparse to define all required and optional arguments per contracts/cli_interface.md, validate choices, generate help text
- [x] **T034** Environment variable validation in `src/cli/env.py` - check FALCON_CLIENT_ID, FALCON_CLIENT_SECRET, FALCON_CLOUD_REGION presence before execution, exit code 1 if missing
- [x] **T035** Main CLI entry point in `src/cli/main.py` - coordinate: parse args → validate env → create models → call FalconClient → export → handle exit codes (0-5)

---

## Phase 3.4: Integration

- [ ] **T036** Logging configuration in `src/lib/logging_config.py` - setup dual modes (INFO vs DEBUG via --verbose), structured format with timestamps, credential sanitization regex filter
- [ ] **T037** Error handling utilities in `src/lib/error_handling.py` - custom exception classes for each exit code (AuthError, NetworkError, InvalidArgsError, APIError, ExportError)
- [ ] **T038** File path utilities in `src/lib/file_utils.py` - generate default filename with timestamp (falcon_vulns_YYYYMMDD_HHMMSS.ext), validate output path writability, exit code 5 if error

---

## Phase 3.5: Polish

- [ ] **T039** [P] Unit tests for models validation in `tests/unit/test_models_validation.py` - test all __post_init__ validations, invalid enum values raise ValueError
- [ ] **T040** [P] Unit tests for FQL conversion in `tests/unit/test_filter_criteria.py` - test FilterCriteria.to_fql() generates correct Falcon Query Language strings
- [ ] **T041** [P] Unit tests for export formatters in `tests/unit/test_exporters.py` - test XLSX/CSV/TXT formatting logic, empty results handling
- [ ] **T042** [P] Unit tests for credential sanitization in `tests/unit/test_logging.py` - verify regex filter removes credential patterns from all log levels
- [x] **T043** Create README.md in repository root - document CLI commands with examples, environment variables, exit codes, installation instructions
- [x] **T044** Create CHANGELOG.md in repository root - initial entry for v0.1.0 with implemented features
- [ ] **T045** Run mypy type checking and fix any type errors - ensure strict mode passes for all src/ files

---

## Dependencies

**Setup** (T001-T004) must complete before any other phase

**Tests Phase** (T005-T018) must complete before **Core Phase** (T019-T035)

**Within Core Phase**:
- T019 (enums) blocks T020-T025 (models use enums)
- T020-T025 (models) block T026-T029 (services use models)
- T026-T029 (services) block T033-T035 (CLI uses services)
- T030-T032 (exporters) can run in parallel, block T035 (main uses exporters)

**Integration Phase** (T036-T038) depends on Core Phase completion

**Polish Phase** (T039-T045) depends on all implementation completion

**Specific Blocking**:
- T019 → T020, T021, T022, T023, T024, T025
- T024 → T026 (auth context needed for client)
- T022 → T027 (filter criteria needed for query)
- T026, T027 → T028 (retry wraps query)
- T027 → T029 (progress tracks pagination)
- T030, T031, T032 → T035 (main needs all exporters)
- T033, T034 → T035 (main needs arg parsing and env validation)
- T036 (logging) → T035 (main configures logging)
- T037 (error handling) → T035 (main uses error classes)

---

## Parallel Execution Examples

### Launch T005-T009 (Falcon API contract tests) together:
```
# All independent test files can run in parallel
pytest tests/contract/test_falcon_auth.py &
pytest tests/contract/test_falcon_query.py &
pytest tests/contract/test_falcon_pagination.py &
pytest tests/contract/test_falcon_retry.py &
wait
```

### Launch T010-T013 (CLI contract tests) together:
```
pytest tests/contract/test_cli_args.py &
pytest tests/contract/test_cli_env.py &
pytest tests/contract/test_cli_help.py &
wait
```

### Launch T014-T018 (integration tests) together:
```
pytest tests/integration/test_scenario_1.py &
pytest tests/integration/test_scenario_2.py &
pytest tests/integration/test_scenario_3.py &
pytest tests/integration/test_scenario_4.py &
pytest tests/integration/test_scenario_5.py &
wait
```

### Launch T020-T025 (model creation) together (after T019 completes):
```
# All model files are independent
python -m src.models.vulnerability &  # Just importing to test
python -m src.models.device &
python -m src.models.filter_criteria &
python -m src.models.export_config &
python -m src.models.auth_context &
python -m src.models.vulnerability_record &
wait
```

### Launch T030-T032 (exporters) together:
```
# All exporter files are independent
python -m src.exporters.xlsx_exporter &
python -m src.exporters.csv_exporter &
python -m src.exporters.txt_exporter &
wait
```

### Launch T039-T042 (unit tests) together:
```
pytest tests/unit/test_models_validation.py &
pytest tests/unit/test_filter_criteria.py &
pytest tests/unit/test_exporters.py &
pytest tests/unit/test_logging.py &
wait
```

---

## Notes

- **[P] tasks** = different files, no dependencies, safe to parallelize
- **TDD CRITICAL**: Verify all tests in Phase 3.2 fail before starting Phase 3.3
- **Exit codes must be tested**: Every test should verify correct exit code (0-5)
- **Commit after each task** for rollback safety
- **Constitutional compliance**: Type hints required, no credentials in logs, test-first approach enforced

---

## Task Generation Rules Applied

1. ✅ **From Contracts**:
   - contracts/falcon_api.md → T005-T009 (API contract tests [P])
   - contracts/cli_interface.md → T010-T013 (CLI contract tests [P])

2. ✅ **From Data Model**:
   - 6 entities → T020-T025 (model creation tasks [P])

3. ✅ **From User Stories** (quickstart.md scenarios):
   - 5 acceptance scenarios → T014-T018 (integration tests [P])

4. ✅ **Ordering**:
   - Setup → Tests → Models → Services → Exporters → CLI → Integration → Polish
   - Dependencies explicitly documented above

---

## Validation Checklist
*GATE: Checked before marking tasks complete*

- [x] All contracts have corresponding tests (T005-T013)
- [x] All entities have model tasks (T020-T025)
- [x] All tests come before implementation (Phase 3.2 before 3.3)
- [x] Parallel tasks truly independent (verified file paths)
- [x] Each task specifies exact file path
- [x] No task modifies same file as another [P] task
- [x] TDD workflow enforced (tests must fail first)
- [x] Exit codes validated in tests
- [x] Constitutional requirements addressed (type hints, logging, test-first)

---

**Total Tasks**: 45
**Parallel Tasks**: 23 marked [P]
**Estimated Completion**: 30-40 hours for full implementation following TDD

Ready for execution with `/implement` or manual task completion.
