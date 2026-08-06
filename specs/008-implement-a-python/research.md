# Research: Vulnerability Query Tool

## Research Summary

This document consolidates technical research decisions for implementing the CrowdStrike Falcon vulnerability query CLI tool.

---

## 1. FalconPy Library - Vulnerability API Patterns

**Decision**: Use falconpy `SpotlightVulnerabilities` service class

**Rationale**:
- Official CrowdStrike Python SDK with active maintenance
- Built-in authentication, pagination, and rate limit handling
- Constitutional requirement (VI) mandates falconpy usage

**Key APIs**:
- `queryVulnerabilitiesCombined()` - Preferred for single-call efficiency with full device data
- `queryVulnerabilities()` - Returns IDs only (requires follow-up call)
- `getVulnerabilities()` - Retrieve details by ID list

**Alternatives Considered**:
- Direct REST API calls → Rejected (violates constitution, requires custom retry/auth logic)

**References**:
- https://www.falconpy.io
- https://falcon spy.io/Service-Collections/Spotlight-Vulnerabilities.html

---

## 2. Pagination Strategy

**Decision**: Offset-based pagination with automatic iteration until all records retrieved

**Implementation**:
```python
offset = 0
limit = 500
while offset < total:
    response = queryVulnerabilitiesCombined(offset=offset, limit=limit)
    total = response['meta']['pagination']['total']
    offset += limit
```

**Rationale**:
- Clarification confirmed no result limit
- Falconpy supports offset/limit parameters natively
- Simple loop handles pagination transparently

**Alternatives Considered**:
- Cursor-based pagination → Not supported by Spotlight Vulnerabilities API
- Result limit → Rejected per clarification (must retrieve all)

---

## 3. Export Library Selection

**Decisions**:
| Format | Library | Rationale |
|--------|---------|-----------|
| XLSX | `openpyxl` | Write-optimized, better type preservation (FR-017) |
| CSV | Python stdlib `csv` | Sufficient for comma-delimited output, no dependency |
| TXT | Custom formatter | Tab-delimited using stdlib, no dependency needed |

**Alternatives Considered**:
- `xlsxwriter` for XLSX → Similar performance, openpyxl chosen for better data type handling
- `pandas` for all formats → Rejected (heavy dependency, YAGNI for simple table export)

---

## 4. CLI Framework

**Decision**: Python stdlib `argparse`

**Rationale**:
- Built-in, sufficient for required argument structure
- No external dependency (aligns with constitution dependency minimalism)
- Supports required vs optional args, choices, help text generation

**Alternatives Considered**:
- Click → More features but external dependency, unnecessary complexity
- Typer → Type-based CLI, rejected (adds dependency, learning curve)

---

## 5. Testing Strategy

**Decisions**:
- **Unit Tests**: Mock falconpy classes using `pytest-mock`
- **Contract Tests**: Manual fixtures with recorded API responses (vcrpy considered but manual chosen for transparency)
- **Integration Tests**: Full workflow with mocked API (constitutional requirement: no live credentials in CI)

**Rationale**:
- Constitutional requirement: "Tests MUST use mocked API responses (avoid live API calls in CI)"
- Contract tests ensure API compatibility after SDK updates
- Integration tests validate end-to-end user scenarios

**Test Coverage Goals**:
- 80%+ for business logic (constitutional SHOULD)
- 100% for error handling paths (constitutional MUST)

---

## 6. Error Handling & Retry Logic

**Decision**: Exponential backoff retry for transient failures

**Configuration**:
- Initial delay: 1 second
- Backoff multiplier: 2x per retry
- Max retries: 5
- Total max wait: ~31 seconds (1+2+4+8+16)
- Retry conditions: HTTP 429, 502, 503, 504, connection timeout

**Rationale**:
- Edge case clarification requires retry for rate limits
- Constitutional requirement for graceful API error handling
- Exponential backoff respects server recovery time

**Alternatives Considered**:
- Fixed interval retry → Rejected (doesn't adapt to server load)
- Infinite retries → Rejected (must exit eventually for automation, use exit code 4)

---

## 7. Logging Configuration

**Decision**: Dual-mode logging via `--verbose` flag

**Configuration**:
| Mode | Flag | Level | Content |
|------|------|-------|---------|
| Normal | (absent) | INFO | Query params, result count, export confirmation |
| Extended | `--verbose` | DEBUG | + API call details, request/response metadata |

**Format**: `%(asctime)s | %(levelname)s | %(name)s | %(message)s`

**Sanitization**: Regex filter to remove credential patterns at all levels

**Rationale**:
- Constitutional requirement IV: "Two logging modes MUST be supported"
- Structured format enables machine parsing
- Sanitization prevents credential leakage (constitutional security requirement)

**Alternatives Considered**:
- JSON structured logging → Deferred to v2 (YAGNI for initial release)

---

## 8. Progress Indication

**Decision**: Background thread with 10-second timer threshold

**Implementation**:
- Start timer thread on query initiation
- If query exceeds 10 seconds, print progress to stderr
- Format: "Fetching page X/est... (Y records retrieved)"
- Estimation based on `total` from first API response

**Rationale**:
- Clarification specifies 10-second threshold
- stderr keeps stdout clean for piping
- Provides user feedback for large datasets

**Alternatives Considered**:
- Spinner animation → Less informative, requires terminal detection
- Progress bar (tqdm) → Adds dependency, complexity for minimal UX gain

---

## 9. Exit Code Strategy

**Decision**: Detailed exit code mapping per clarification

```python
class ExitCode(IntEnum):
    SUCCESS = 0           # Query successful (including 0 results)
    AUTH_ERROR = 1        # Missing/invalid credentials, API auth failure
    NETWORK_ERROR = 2     # Connection timeout, DNS failure
    INVALID_ARGS = 3      # Missing required arg, invalid value
    API_ERROR = 4         # Rate limit exhausted, server error after retries
    EXPORT_ERROR = 5      # File write failure, disk space, permissions
```

**Rationale**:
- Clarification provides explicit exit code specification
- Enables automation/scripting with fine-grained error detection
- Constitutional requirement for automation integration

---

## 10. Device Type Classification

**Decision**: Use Falcon `platform_name` field for device type filtering

**Filter Logic**:
| User Input | Falcon FQL Filter |
|------------|-------------------|
| CLIENT | `platform_name:*'Workstation'` |
| SERVER | `platform_name:*'Server'` |
| BOTH | (no filter applied) |

**Rationale**:
- Falcon API uses `platform_name` metadata for device classification
- Wildcard matching accommodates variations ("Windows Workstation", "Linux Server", etc.)

**Research Needed During Phase 1**:
- Confirm exact field names via contract testing
- Validate filter syntax with test API calls

**Alternatives Considered**:
- User-defined tags → Rejected (requires manual tagging, error-prone, not scalable)
- Machine learning classification → Rejected (overcomplicated, YAGNI)

---

## Additional Technical Decisions

### Timeout Configuration
- **Connect timeout**: 10 seconds
- **Read timeout**: 30 seconds
- **Rationale**: Balance responsiveness with large query tolerance

### TLS Certificate Validation
- **Decision**: MUST validate (never set `verify=False`)
- **Rationale**: Constitutional security requirement
- **Implementation**: Default falconpy/requests behavior

### Default Export Filename
- **Pattern**: `falcon_vulns_YYYYMMDD_HHMMSS.{ext}`
- **Timestamp Format**: `%Y%m%d_%H%M%S`
- **Rationale**: Per clarification specification, ensures unique filenames

---

## Technology Stack Summary

| Component | Technology | Version | Rationale |
|-----------|------------|---------|-----------|
| Language | Python | 3.11+ | Constitutional requirement I |
| API Client | falconpy | Latest stable | Constitutional requirement VI |
| CLI Framework | argparse | stdlib | Minimal dependencies |
| XLSX Export | openpyxl | Latest stable | Type preservation (FR-017) |
| CSV Export | csv module | stdlib | Sufficient for delimiter |
| Testing | pytest | Latest stable | Constitutional standard |
| Mocking | pytest-mock | Latest stable | API test isolation |
| Type Checking | mypy | Latest stable | Constitutional quality gate |
| Linting | ruff or flake8 | Latest stable | Constitutional quality gate |

---

## Dependencies (requirements.txt)

```
falconpy>=1.4.0
openpyxl>=3.1.0
pytest>=7.4.0
pytest-mock>=3.12.0
mypy>=1.7.0
ruff>=0.1.0
```

**Note**: Exact versions to be pinned during Phase 3 setup (constitutional requirement VII)

---

## Risk Assessment

| Risk | Mitigation |
|------|------------|
| Falcon API schema changes | Contract tests fail early, update data models |
| Large datasets (>100k results) | Pagination + progress indication, test with realistic data |
| Rate limiting in production | Exponential backoff + clear messaging, document expected limits |
| Credential leakage in logs | Regex sanitization filters + code review verification |
| Export file size limits (Excel 1M row limit) | Document limitation, consider CSV for very large datasets |

---

## Performance Expectations

- **Small queries (<100 results)**: <5 seconds
- **Medium queries (100-1000 results)**: 5-30 seconds
- **Large queries (1000-10000 results)**: 30-300 seconds (progress indication shown)
- **Very large queries (>10000 results)**: Minutes (pagination essential)

---

## Research Status

✅ All unknowns from Technical Context resolved
✅ No NEEDS CLARIFICATION markers remaining
✅ Ready to proceed to Phase 1 (Design & Contracts)
