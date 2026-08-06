# Implementation Plan: AWS Instance ID Lookup for CrowdStrike Vulnerability Queries

**Branch**: `041-falcon-instance-lookup` | **Date**: 2025-11-03 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/041-falcon-instance-lookup/spec.md`

## Summary

Enable users to query CrowdStrike Falcon for vulnerability data using AWS EC2 Instance IDs (format: `i-0048f94221fe110cf`) in addition to hostnames. The system will automatically detect input type (hostname vs AWS instance ID), query CrowdStrike's API by searching systems with matching instance ID metadata, cache results for 15 minutes, and optionally persist results to the database with asset auto-creation/enrichment.

**Technical Approach**: Extend existing CrowdStrike query service to support AWS instance ID pattern detection (`i-[0-9a-fA-F]{8,17}`), add metadata field querying to the CrowdStrike API client, update frontend input validation and UI labels, implement cache freshness indicators, and maintain backward compatibility with hostname queries.

## Technical Context

**Language/Version**:
- Backend: Kotlin 2.2.21 / Java 21
- Frontend: JavaScript ES2022 (Astro 5.14 + React 19)
- Shared Module: Kotlin 2.2.21

**Primary Dependencies**:
- Backend: Micronaut 4.10, Hibernate JPA, MariaDB 11.4
- Frontend: Astro 5.14, React 19, Bootstrap 5.3, Axios
- Shared: CrowdStrike Falcon API client (existing in buildSrc/crowdstrike-client)

**Storage**:
- MariaDB 11.4 (existing Asset entity with cloudInstanceId field)
- 15-minute in-memory cache (Micronaut @Cacheable)

**Testing**:
- Backend: JUnit 5 + MockK
- Frontend: Playwright (E2E)
- Contract tests for new/modified API endpoints

**Target Platform**: Web application (browser + server)

**Project Type**: web - frontend (Astro/React) + backend (Micronaut/Kotlin) + shared module

**Performance Goals**:
- Query response <3 seconds for cached results
- Query response <5 seconds for fresh API calls to CrowdStrike
- Auto-detection accuracy 100% for valid formats

**Constraints**:
- Must maintain backward compatibility with existing hostname queries
- Must not query database for vulnerability data (API-first with cache)
- 15-minute cache TTL (non-negotiable for API rate limiting)
- Must preserve existing RBAC (ADMIN, VULN roles)

**Scale/Scope**:
- Existing /vulnerabilities/system page (single page modification)
- 1 frontend component (CrowdStrikeVulnerabilityLookup.tsx)
- 2-3 backend service methods (CrowdStrikeQueryService)
- 1 shared module enhancement (CrowdStrikeApiClient)
- ~5-7 new contract tests

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅ PASS

**Analysis**:
- Input validation: AWS instance ID format validated with regex (`i-[0-9a-fA-F]{8,17}`)
- Sanitization: Existing hostname sanitization patterns apply; instance ID format is inherently safe (alphanumeric only)
- RBAC: Preserves existing @Secured("ADMIN", "VULN") on endpoints
- No sensitive data exposure: Instance IDs are non-sensitive identifiers
- Authentication: Uses existing JWT sessionStorage pattern

**Actions**:
- Add instance ID format validation in frontend and backend
- Reuse existing sanitization patterns from CrowdStrikeController
- No new authentication/authorization changes needed

### II. Test-Driven Development (NON-NEGOTIABLE) ✅ PASS

**Analysis**: Tests will be written per TDD principle (Red-Green-Refactor)

**Planned Test Coverage**:
- Contract tests for modified /api/vulnerabilities endpoint with instance ID parameter
- Unit tests for instance ID detection logic
- Unit tests for CrowdStrike API client metadata query
- Integration tests for cache behavior with instance IDs
- E2E tests for UI auto-detection and query flow

**Note**: Per Constitution Principle IV (User-Requested Testing), specific test case planning deferred until implementation phase. Test frameworks (JUnit, Playwright) remain required per TDD.

### III. API-First ✅ PASS

**Analysis**:
- Extends existing REST endpoint: `GET /api/vulnerabilities?hostname={input}`
- Backward compatible: hostname queries continue to work unchanged
- Response format unchanged: CrowdStrikeQueryResponse DTO
- Error handling: Reuses existing error formats (400, 404, 429, 500)
- HTTP status codes: No changes to existing patterns

**Actions**:
- Document new query pattern in OpenAPI (instance ID via hostname parameter)
- Maintain existing response structure for compatibility

### IV. User-Requested Testing ✅ PASS

**Analysis**: Test planning deferred per constitution. Test infrastructure (JUnit, Playwright, MockK) already exists. Specific test cases will be prepared when explicitly requested during implementation.

### V. Role-Based Access Control (RBAC) ✅ PASS

**Analysis**:
- Existing @Secured("ADMIN", "VULN") annotation preserved on CrowdStrikeController
- No new endpoints or role changes
- Frontend role checks: No changes (same page, same roles)
- Workgroup filtering: Not applicable (online API queries, not database)

**Actions**: None - existing RBAC maintained

### VI. Schema Evolution ✅ PASS

**Analysis**:
- No schema changes required
- Existing Asset.cloudInstanceId field already supports AWS instance IDs
- Existing Vulnerability entity unchanged
- Migration: N/A (no schema changes)

**Actions**: None - existing schema sufficient

### Constitutional Compliance Summary

**Status**: ✅ ALL GATES PASSED

All six constitutional principles are satisfied:
1. Security-First: Input validation and existing RBAC maintained
2. TDD: Test frameworks in place, TDD workflow will be followed
3. API-First: Backward-compatible REST endpoint extension
4. User-Requested Testing: Planning deferred appropriately
5. RBAC: Existing controls preserved
6. Schema Evolution: No changes needed

**Pre-Research Gate**: CLEARED ✅
**Post-Design Re-Check**: Pending Phase 1 completion

## Project Structure

### Documentation (this feature)

```text
specs/041-falcon-instance-lookup/
├── spec.md              # Feature specification (completed)
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (next)
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (API contracts)
│   └── vulnerabilities-api.yaml
├── checklists/          # Quality validation
│   └── requirements.md  # Spec quality checklist (completed)
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
# Web application structure (existing)
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── controller/
│   │   └── CrowdStrikeController.kt         # MODIFY: Add instance ID detection
│   ├── service/
│   │   ├── CrowdStrikeQueryService.kt       # MODIFY: Support instance ID queries
│   │   └── CrowdStrikeVulnerabilityService.kt  # MODIFY: Asset enrichment
│   ├── dto/
│   │   └── CrowdStrikeQueryResponse.kt      # NO CHANGE (existing DTO)
│   └── domain/
│       └── Asset.kt                         # NO CHANGE (cloudInstanceId exists)
└── src/test/kotlin/com/secman/
    ├── controller/
    │   └── CrowdStrikeControllerTest.kt     # ADD: Instance ID contract tests
    └── service/
        └── CrowdStrikeQueryServiceTest.kt   # ADD: Instance ID unit tests

src/frontend/
├── src/
│   ├── components/
│   │   └── CrowdStrikeVulnerabilityLookup.tsx  # MODIFY: Auto-detection, cache badge
│   ├── services/
│   │   └── crowdstrikeService.ts            # NO CHANGE (uses same endpoint)
│   └── pages/
│       └── vulnerabilities/
│           └── system.astro                 # NO CHANGE
└── tests/e2e/
    └── crowdstrike-instance-id.spec.ts      # ADD: E2E tests

buildSrc/crowdstrike-client/
└── src/main/kotlin/com/secman/crowdstrike/
    └── client/
        └── CrowdStrikeApiClient.kt          # MODIFY: Add metadata field querying
```

**Structure Decision**: Web application (Option 2) - existing structure with frontend + backend. This feature modifies 3 existing files (CrowdStrikeController, CrowdStrikeQueryService, CrowdStrikeVulnerabilityLookup.tsx), adds metadata query capability to shared CrowdStrike API client, and requires contract/unit/E2E test additions per TDD.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No constitutional violations - table not applicable.

---

## Phase 0: Research & Discovery

*Status: IN PROGRESS*

### Research Tasks

1. **CrowdStrike Falcon API - AWS Instance ID Metadata Query**
   - **Question**: What is the exact API endpoint and query syntax for searching CrowdStrike systems by AWS instance ID metadata?
   - **Why**: Need to know how to construct metadata field queries
   - **Deliverable**: API endpoint, filter syntax, example request/response

2. **CrowdStrike Metadata Field Names**
   - **Question**: What is the exact field name for AWS EC2 Instance ID in CrowdStrike system metadata?
   - **Why**: Need correct field name for filter queries
   - **Deliverable**: Confirmed field name (e.g., "aws_instance_id", "instance_id", "ec2_instance_id")

3. **Cache Key Strategy for Instance IDs**
   - **Question**: How should cache keys differentiate between hostname and instance ID queries?
   - **Why**: Prevent cache collisions between "i-abc" hostname vs "i-abc" instance ID
   - **Deliverable**: Cache key format decision

4. **Multiple Systems with Same Instance ID**
   - **Question**: What is the expected behavior when CrowdStrike returns multiple systems with the same AWS instance ID?
   - **Why**: Edge case handling for API response processing
   - **Deliverable**: Aggregation strategy (combine all vulnerabilities or show per-system)

5. **Legacy vs Current AWS Instance ID Format**
   - **Question**: Are there validation differences needed for legacy (8-char) vs current (17-char) formats?
   - **Why**: Ensure both formats work correctly
   - **Deliverable**: Validation regex pattern supporting both

### Unknowns from Technical Context

All technical context fields have been resolved:
- ✅ Language/Version: Kotlin 2.2.21, JavaScript ES2022
- ✅ Dependencies: Micronaut 4.10, React 19, CrowdStrike API client
- ✅ Storage: MariaDB (existing schema)
- ✅ Testing: JUnit 5, Playwright
- ✅ Performance: <3s cached, <5s fresh
- ✅ Constraints: Backward compatibility, 15-min cache
- ✅ Scale: Single page, 3 files, ~7 tests

### Next Steps

1. Execute research tasks (dispatch to research agents)
2. Consolidate findings in research.md
3. Proceed to Phase 1 (Design & Contracts)

---

## Phase 1: Design & Contracts

*Status: PENDING (after Phase 0)*

### Planned Artifacts

1. **data-model.md**:
   - No new entities
   - Document Asset.cloudInstanceId field usage
   - Document cache key structure

2. **contracts/vulnerabilities-api.yaml**:
   - OpenAPI spec for modified GET /api/vulnerabilities
   - Document instance ID query pattern
   - Request/response examples

3. **quickstart.md**:
   - Integration scenarios (hostname query, instance ID query, cache behavior)
   - Example requests with curl
   - Error handling examples

---

## Phase 2: Task Breakdown

*Status: NOT STARTED (requires /speckit.tasks command)*

Task breakdown deferred to `/speckit.tasks` command execution.

---

## Notes

- **AWS Instance ID Clarification**: Critical post-session clarification confirmed instance IDs are AWS EC2 identifiers (i-XXXXXXXX...), NOT CrowdStrike Agent IDs
- **Query Method**: Must query CrowdStrike by AWS instance ID metadata field, not by agent/device ID
- **Backward Compatibility**: Hostname queries must continue to work unchanged
- **Cache Strategy**: 15-minute TTL applies to both query types consistently
- **UI Enhancement**: Added freshness indicators (⚡ Live data / 📋 Cached X min ago) per clarification session
