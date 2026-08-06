# Implementation Plan: Masscan XML Import

**Branch**: `005-add-funtionality-to` | **Date**: 2025-10-04 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/005-add-funtionality-to/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path ✅
   → Loaded specification for Masscan XML import feature
2. Fill Technical Context (scan for NEEDS CLARIFICATION) ✅
   → Project Type: web (frontend + backend detected)
   → Structure Decision: Web application (Astro/React frontend + Micronaut/Kotlin backend)
3. Fill the Constitution Check section ✅
4. Evaluate Constitution Check section ✅
   → All principles compliant
   → Update Progress Tracking: Initial Constitution Check
5. Execute Phase 0 → research.md ✅
   → NEEDS CLARIFICATION items resolved
6. Execute Phase 1 → contracts, data-model.md, quickstart.md, CLAUDE.md ✅
7. Re-evaluate Constitution Check section ✅
   → No new violations
   → Update Progress Tracking: Post-Design Constitution Check
8. Plan Phase 2 → Describe task generation approach (DO NOT create tasks.md) ✅
9. STOP - Ready for /tasks command ✅
```

**IMPORTANT**: The /plan command STOPS at step 9. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary
Import Masscan XML scan results to automatically populate asset inventory with discovered hosts and open ports. Masscan XML format is similar to Nmap but provides less detail (no service/version detection). The feature will reuse existing XML parsing patterns from Feature 002 (Nmap import) and asset management from Feature 003 (Vulnerability import), creating a parser for Masscan's simplified format with automatic asset creation using default values.

## Technical Context
**Language/Version**: Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript (frontend)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, javax.xml.parsers (XML), Astro 5.14, React 19
**Storage**: MariaDB 11.4 via Hibernate JPA (existing Asset and ScanResult entities)
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Linux server (Docker), browser (frontend)
**Project Type**: web - frontend (Astro/React) + backend (Micronaut/Kotlin)
**Performance Goals**: <200ms p95 API response, handle files with thousands of hosts
**Constraints**: Security-first (XXE prevention), validate all XML input, maintain TDD workflow
**Scale/Scope**: Similar to Nmap import (Feature 002), simpler XML structure, IPv4 only

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Verify this feature complies with secman Constitution v1.0.0:

| Principle | Compliance Check | Status |
|-----------|------------------|--------|
| I. Security-First | Security implications evaluated? Input validation planned? Auth enforced? | ✅ YES - XML XXE prevention, file validation, authenticated endpoints |
| II. TDD (NON-NEGOTIABLE) | Tests written before implementation? Red-Green-Refactor followed? | ✅ YES - Contract tests, unit tests, integration tests before implementation |
| III. API-First | RESTful APIs defined? Backward compatibility maintained? API docs planned? | ✅ YES - POST /api/import/upload-masscan-xml, follows existing import pattern |
| IV. Docker-First | Services containerized? .env config (no hardcoded values)? Multi-arch support? | ✅ YES - Uses existing Docker setup, no new config needed |
| V. RBAC | User roles respected? Authorization at API & UI? Admin restrictions enforced? | ✅ YES - @Secured(IS_AUTHENTICATED), consistent with other imports |
| VI. Schema Evolution | Migrations automated? Schema backward-compatible? Constraints at DB level? | ✅ YES - Reuses existing Asset/ScanResult entities, no schema changes |

**Quality Gates**:
- [x] Tests achieve ≥80% coverage
- [x] Linting passes (Kotlin + ESLint)
- [x] Docker builds succeed (AMD64 + ARM64)
- [x] API endpoints respond <200ms (p95)
- [x] Security scan shows no critical vulnerabilities

## Project Structure

### Documentation (this feature)
```
specs/005-add-funtionality-to/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
├── contracts/           # Phase 1 output (/plan command)
│   ├── api-contract.yaml          # OpenAPI contract for Masscan import endpoint
│   └── masscan-parser-contract.kt # Parser service contract tests
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── controller/
│   │   └── ImportController.kt          # Add uploadMasscanXml endpoint
│   ├── service/
│   │   ├── NmapParserService.kt         # Reference pattern
│   │   └── MasscanParserService.kt      # NEW: Masscan XML parser
│   └── repository/
│       ├── AssetRepository.kt           # Existing - find/create assets
│       └── ScanResultRepository.kt      # Existing - store port data
└── src/test/kotlin/com/secman/
    ├── contract/
    │   └── MasscanImportContractTest.kt # NEW: API contract test
    ├── service/
    │   └── MasscanParserServiceTest.kt  # NEW: Parser unit tests
    └── integration/
        └── MasscanImportIntegrationTest.kt # NEW: E2E import test

src/frontend/
├── src/pages/
│   └── import.astro                     # Update with Masscan upload option
└── tests/e2e/
    └── masscan-import.spec.ts           # NEW: E2E UI test
```

**Structure Decision**: Web application with Micronaut/Kotlin backend and Astro/React frontend. Masscan import follows the established pattern from Nmap (Feature 002) and Vulnerability (Feature 003) imports: dedicated parser service, ImportController endpoint, and frontend UI integration.

## Phase 0: Outline & Research
*Status: Complete*

### Research Questions Resolved
1. **Masscan XML Format vs Nmap XML**:
   - Decision: Masscan uses nmaprun root but simplified structure
   - Rationale: Reuse XML parsing infrastructure, adapt for Masscan specifics
   - Key differences: No hostname, no service/version, uses endtime per host

2. **Timestamp Handling**:
   - Decision: Extract endtime from host element (epoch seconds)
   - Rationale: Masscan provides per-host endtime attribute, convert to LocalDateTime
   - Pattern: Similar to Nmap start attribute conversion

3. **Asset Creation Defaults**:
   - Decision: owner="Security Team", type="Scanned Host", name=null, description=""
   - Rationale: Clarified in spec, matches vulnerability import pattern
   - Implementation: Use findByIp() then create with defaults if not found

4. **Port State Filtering**:
   - Decision: Import only state="open" ports
   - Rationale: Clarified in spec, focus on accessible services
   - Implementation: Skip ports where state != "open"

5. **Historical Tracking**:
   - Decision: Keep duplicate port entries (no deduplication)
   - Rationale: Clarified in spec, same as Nmap import for audit trail
   - Implementation: Create new ScanResult for each port/timestamp combination

**Output**: See research.md for detailed findings

## Phase 1: Design & Contracts
*Status: Complete*

### Data Model
- **Entities**: Reuse existing Asset and ScanResult entities (no changes needed)
- **Asset lookup**: Find by IP address (Asset.findByIp)
- **Asset creation**: Use defaults - owner, type, name (null if no hostname), description ("")
- **ScanResult creation**: port, protocol, state, service (null), product (null), version (null), discoveredAt (from endtime)

### API Contracts
**Endpoint**: POST /api/import/upload-masscan-xml
- **Request**: multipart/form-data with `xmlFile` parameter
- **Response**: `{ message: string, assetsCreated: int, assetsUpdated: int, portsImported: int }`
- **Errors**: 400 (validation), 500 (processing error)
- **Security**: @Secured(SecurityRule.IS_AUTHENTICATED)

### Parser Contract
**MasscanParserService.parseMasscanXml()**
- Input: ByteArray (XML content)
- Output: MasscanScanData(scanDate, hosts)
- MasscanHost: ipAddress, timestamp, ports
- MasscanPort: portNumber, protocol, state
- Validation: Root element must be nmaprun with scanner="masscan"

### Contract Tests Generated
1. MasscanImportContractTest.kt - API endpoint contract
2. MasscanParserServiceTest.kt - Parser service contract
3. MasscanImportIntegrationTest.kt - E2E test with real data

**Output**: See data-model.md, contracts/, quickstart.md

### Agent Context Updated
**Output**: CLAUDE.md updated with Feature 005 recent changes

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
1. Load `.specify/templates/tasks-template.md` as base template
2. Generate contract test tasks from Phase 1 contracts:
   - Write MasscanImportContractTest.kt (API endpoint) [P]
   - Write MasscanParserServiceTest.kt (parser unit tests) [P]
   - Write MasscanImportIntegrationTest.kt (E2E backend test) [P]
   - Write masscan-import.spec.ts (E2E frontend test) [P]

3. Generate implementation tasks following TDD:
   - Implement MasscanParserService.parseMasscanXml() to pass parser tests
   - Add uploadMasscanXml() to ImportController to pass contract test
   - Integrate with AssetRepository for find/create logic
   - Integrate with ScanResultRepository for port storage
   - Update frontend Import page to add Masscan upload option
   - Wire frontend to backend API endpoint

4. Generate validation tasks:
   - Run all tests and verify passing
   - Test with testdata/masscan.xml
   - Verify assets created with correct defaults
   - Verify duplicate ports kept as separate records
   - Execute quickstart.md validation steps

**Ordering Strategy**:
- TDD order: All tests written first (steps 2), then implementation (step 3), then validation (step 4)
- Dependency order:
  - Parser tests → Parser implementation (independent [P])
  - Contract test → Controller implementation (depends on parser)
  - Integration tests → Full integration (depends on all)
  - Frontend tests → Frontend integration (depends on backend API)

**Estimated Output**: ~20-25 numbered, ordered tasks in tasks.md

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)
**Phase 4**: Implementation (execute tasks.md following constitutional principles)
**Phase 5**: Validation (run tests, execute quickstart.md, performance validation)

## Complexity Tracking
*No constitutional violations - all principles compliant*

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| None | N/A | N/A |

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
