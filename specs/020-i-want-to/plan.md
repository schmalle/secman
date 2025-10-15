# Implementation Plan: IP Address Mapping to Users

**Branch**: `020-i-want-to` | **Date**: 2025-10-15 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/020-i-want-to/spec.md`

## Summary

Extend the existing UserMapping system (Features 013/016) to support IP address and IP range mappings for user access control. Users will be able to see assets based on their mapped IP addresses (individual IPs, CIDR ranges, dash ranges) in addition to AWS account mappings. Administrators will manage IP mappings through UI forms and CSV/Excel bulk uploads with comprehensive validation.

**Technical Approach**: Extend UserMapping entity to include IP-related fields, implement IP range parsing and matching logic, leverage existing CSV/Excel upload infrastructure, integrate with AccountVulnsService for combined access control.

## Technical Context

**Language/Version**: Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, Apache POI 5.3 (Excel), Apache Commons CSV 1.11.0 (CSV), Astro, React 19, Bootstrap 5.3
**Storage**: MariaDB 11.4 via Hibernate JPA with auto-migration
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Linux server (backend), modern web browsers (frontend)
**Project Type**: web - frontend (Astro/React) + backend (Micronaut/Kotlin)
**Performance Goals**:
- IP range matching: <100ms for up to 10,000 mappings
- CSV upload: 1000 mappings validated and imported in <60 seconds
- Asset query with IP filtering: <2 seconds for up to 100,000 assets
**Constraints**:
- IPv4 only in MVP (IPv6 stub for future)
- Overlapping ranges allowed (most permissive access)
- Admin role required for all IP mapping operations
- Must maintain backward compatibility with existing AWS account mappings
**Scale/Scope**:
- Support up to 10,000 IP mappings
- Support CIDR ranges up to /8 (with performance warning)
- Support dash ranges up to 65,536 IPs (Class B)
- UI pagination: 20 mappings per page

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅

**Compliance**:
- ✅ File uploads: Validate CSV/Excel size (10MB limit), format (.csv/.xlsx), content-type before processing (reusing Feature 016 patterns)
- ✅ Input sanitization: All IP addresses, email, domain validated with regex patterns before database insertion
- ✅ RBAC enforcement: @Secured(SecurityRule.IS_AUTHENTICATED) on all endpoints, ADMIN role check in controller and service layer
- ✅ Sensitive data: No sensitive data in IP mappings; error messages sanitized to prevent information disclosure
- ✅ Authentication: Reuse existing JWT sessionStorage pattern from Features 013/016

**Justification**: Extends proven security patterns from Features 013/016. IP validation prevents injection attacks.

### II. Test-Driven Development (NON-NEGOTIABLE) ✅

**Compliance**:
- ✅ Contract tests first: API endpoint tests written before controller implementation (following Feature 016 pattern)
- ✅ Unit tests first: IP parsing, range matching, validation logic tested before implementation
- ✅ Integration tests: CSV/Excel upload flow, access control combination tests
- ✅ Coverage target: ≥80% (backend: JUnit 5 + MockK, frontend: Playwright)
- ✅ Red-Green-Refactor: Documented in tasks.md (Phase 2)

**Justification**: TDD is non-negotiable per constitution. IP range logic is complex and requires comprehensive testing.

### III. API-First ✅

**Compliance**:
- ✅ RESTful API: Extends existing /api/import/* and /api/user-mappings/* endpoints
- ✅ New endpoints:
  - POST /api/import/upload-ip-mappings-csv
  - POST /api/import/upload-ip-mappings-xlsx
  - GET /api/import/ip-mapping-template-csv
  - GET /api/import/ip-mapping-template-xlsx
  - GET /api/user-mappings (enhanced to include IP mappings)
  - PUT /api/user-mappings/{id} (enhanced for IP updates)
  - DELETE /api/user-mappings/{id} (existing)
- ✅ Backward compatibility: Existing UserMapping endpoints remain unchanged; new fields are optional
- ✅ Error formats: Consistent ImportResult DTO with error details (row number, reason)
- ✅ HTTP status codes: 200 (success), 400 (validation), 401 (unauthorized), 403 (forbidden), 500 (server error)

**Justification**: Leverages existing API patterns. No breaking changes to current UserMapping API.

### IV. Docker-First ✅

**Compliance**:
- ✅ Dockerfile: No changes required (existing backend/frontend containers)
- ✅ Multi-arch: Existing AMD64/ARM64 support maintained
- ✅ Environment config: No new environment variables required
- ✅ docker-compose.yml: No changes required
- ✅ Health checks: Existing health checks sufficient
- ✅ Volumes: Database volume already configured for MariaDB

**Justification**: No infrastructure changes needed. Feature contained within existing services.

### V. Role-Based Access Control (RBAC) ✅

**Compliance**:
- ✅ @Secured annotations: All new endpoints require IS_AUTHENTICATED + ADMIN role check
- ✅ Service layer checks: UserMappingService verifies ADMIN role before IP operations
- ✅ Frontend role checks: UI components check hasRole('ADMIN') before rendering IP management features
- ✅ Access control integration: AccountVulnsService combines IP + AWS account filtering with workgroup-based filtering
- ✅ Data visibility: Users see assets from their IP mappings + AWS mappings + workgroups + owned assets

**Justification**: Extends existing RBAC model from Features 013/018. IP mappings follow same ADMIN-only pattern as AWS mappings.

### VI. Schema Evolution ✅

**Compliance**:
- ✅ Hibernate auto-migration: Entity changes auto-migrated via ddl-auto=update
- ✅ Database constraints: Unique constraint on (email, ipAddress, domain), indexes on email, ipAddress, domain
- ✅ Foreign keys: No FK relationships (UserMapping is standalone like Feature 013)
- ✅ Indexes: Added idx_user_mapping_ip, idx_user_mapping_email_ip for query performance
- ✅ No data loss: New fields are nullable; existing UserMapping rows unaffected

**Decision Point**: Extend existing UserMapping entity vs. create separate IpMapping entity
- **CHOSEN**: Extend UserMapping entity with new fields: `ipAddress`, `ipRangeStart`, `ipRangeEnd`, `ipRangeType`
- **Rationale**:
  - Single table maintains consistency with Feature 013 pattern
  - Email can map to AWS accounts, domains, AND IP addresses without duplication
  - Queries are simpler (single join instead of union)
  - Upload logic reuses existing ImportController patterns
  - UI components already handle UserMapping display
- **Trade-off**: Nullable fields (either AWS account OR IP must be present, enforced by validation)

**Justification**: Schema changes follow Hibernate auto-migration pattern. Backward compatible with existing UserMapping data.

**GATE RESULT**: ✅ PASS - All constitutional requirements satisfied. No violations to justify.

## Project Structure

### Documentation (this feature)

```
specs/020-i-want-to/
├── spec.md              # Feature specification
├── plan.md              # This file
├── research.md          # Phase 0 output (IP parsing libraries, range matching algorithms)
├── data-model.md        # Phase 1 output (UserMapping extension, DTOs)
├── quickstart.md        # Phase 1 output (developer setup)
├── contracts/           # Phase 1 output (OpenAPI specs)
│   ├── ip-mapping-upload-csv.yaml
│   ├── ip-mapping-upload-xlsx.yaml
│   └── ip-mapping-crud.yaml
├── checklists/          # Quality validation
│   └── requirements.md  # Specification quality checklist (complete)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```
# Web application structure (existing)
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   └── UserMapping.kt                    # EXTENDED: Add IP fields
│   ├── repository/
│   │   └── UserMappingRepository.kt          # EXTENDED: Add IP query methods
│   ├── service/
│   │   ├── UserMappingService.kt             # EXTENDED: Add IP upload/CRUD
│   │   ├── IpAddressValidator.kt             # NEW: IP validation logic
│   │   ├── IpRangeMatcher.kt                 # NEW: IP range matching logic
│   │   └── AccountVulnsService.kt            # EXTENDED: Add IP-based filtering
│   ├── controller/
│   │   └── ImportController.kt               # EXTENDED: Add IP upload endpoints
│   └── dto/
│       ├── UserMappingDto.kt                 # EXTENDED: Add IP fields
│       └── IpMappingUploadResult.kt          # NEW: Upload result DTO
└── src/test/kotlin/com/secman/
    ├── contract/
    │   ├── IpMappingUploadContractTest.kt    # NEW: API contract tests
    │   └── IpMappingCrudContractTest.kt      # NEW: CRUD contract tests
    ├── service/
    │   ├── IpAddressValidatorTest.kt         # NEW: Validation unit tests
    │   ├── IpRangeMatcherTest.kt             # NEW: Range matching unit tests
    │   └── UserMappingServiceTest.kt         # EXTENDED: IP upload tests
    └── fixtures/
        └── IpMappingTestFixtures.kt          # NEW: Test data fixtures

src/frontend/
├── src/
│   ├── components/
│   │   ├── UserMappingUpload.tsx             # EXTENDED: Add IP upload section
│   │   ├── UserMappingTable.tsx              # EXTENDED: Add IP column
│   │   ├── IpMappingForm.tsx                 # NEW: IP mapping form component
│   │   └── IpRangeDisplay.tsx                # NEW: IP range display component
│   ├── pages/
│   │   └── user-mappings.astro               # EXTENDED: Add IP management UI
│   └── services/
│       └── userMappingService.ts             # EXTENDED: Add IP upload methods
└── tests/e2e/
    └── ip-mapping.spec.ts                    # NEW: E2E tests for IP mapping

```

**Structure Decision**: Extending existing web application structure. No new services or directories required. IP mapping functionality integrated into existing UserMapping components following Feature 013/016 patterns.

## Complexity Tracking

*No constitutional violations identified. This section intentionally left empty.*

## Phase 0: Research & Unknowns

**Status**: Ready to execute (see research.md after generation)

**Research Tasks**:
1. **IP Address Parsing Libraries**: Evaluate Java/Kotlin libraries for IPv4 parsing, CIDR notation, dash range support
   - Options: Apache Commons Net, Google Guava, native Java InetAddress
   - Decision criteria: Validation accuracy, range expansion performance, dependency size
2. **IP Range Matching Algorithms**: Research efficient algorithms for checking if IP is in range
   - CIDR matching: Bitwise AND with subnet mask
   - Dash range matching: Numeric comparison (convert to Long)
   - Overlapping range handling: Multiple range check optimization
3. **Database Index Strategy**: Determine optimal indexes for IP-based queries
   - Index on ipAddress for exact match
   - Index on (ipRangeStart, ipRangeEnd) for range queries
   - Composite index on (email, ipAddress) for user lookup
4. **CSV/Excel Upload Best Practices**: Review Feature 016 implementation for reuse patterns
   - Delimiter detection, encoding handling, scientific notation parsing
   - Error reporting, duplicate detection, batch insertion
5. **Frontend IP Input Validation**: Research client-side IP validation libraries
   - Options: regex patterns, ipaddr.js, validator.js
   - CIDR notation input UX (single field vs. separate IP + mask)

**Unknowns to Resolve**:
- ❓ Performance impact of IP range queries on large asset tables (100k+ assets)
  - Research: Query optimization strategies (index hints, query rewriting)
- ❓ UI/UX for entering IP ranges (single field "192.168.1.0/24" vs. separate fields)
  - Research: Industry best practices, accessibility considerations
- ❓ Handling of /8 and larger ranges (16M+ IPs) - store as range or reject?
  - Research: Performance testing, memory usage, database storage implications

**Output**: research.md with recommendations and rationale for each decision

## Phase 1: Design & Contracts

**Status**: Pending Phase 0 completion

**Deliverables**:

1. **data-model.md**: Entity and DTO specifications
   - UserMapping entity extension (ipAddress, ipRangeStart, ipRangeEnd, ipRangeType enum)
   - IpRangeType enum (SINGLE, CIDR, DASH_RANGE)
   - UserMappingDto extension
   - IpMappingUploadResult DTO
   - Validation rules and constraints

2. **contracts/**: OpenAPI specifications
   - `ip-mapping-upload-csv.yaml`: POST /api/import/upload-ip-mappings-csv
   - `ip-mapping-upload-xlsx.yaml`: POST /api/import/upload-ip-mappings-xlsx
   - `ip-mapping-crud.yaml`: GET/PUT/DELETE /api/user-mappings with IP support
   - Request/response schemas, error formats, examples

3. **quickstart.md**: Developer setup guide
   - How to add IP mappings via UI
   - How to test IP-based access control
   - Sample CSV/Excel files
   - cURL examples for API testing

4. **Agent Context Update**: Run update-agent-context.sh
   - Add IP mapping endpoints to CLAUDE.md
   - Document UserMapping entity extensions
   - Add IP range matching patterns

**Post-Phase 1 Constitution Re-check**: ✅ PASSED

**Verification Results**:

1. **API-First Principle**: ✅ Verified
   - OpenAPI 3.0.3 contracts created for all endpoints (`contracts/*.yaml`)
   - RESTful patterns followed (POST for create, GET for read, PUT for update, DELETE for delete)
   - Consistent error response schemas across all endpoints
   - Request/response examples provided for all operations
   - Backward compatibility maintained (existing UserMapping API unchanged)

2. **Security-First Principle**: ✅ Verified
   - All endpoints require JWT authentication (BearerAuth security scheme)
   - ADMIN role enforced in contract descriptions
   - Input validation patterns specified in schemas (regex for IP, email formats)
   - File upload constraints documented (size limits, content-type validation)
   - Error responses sanitized (no sensitive data leaked)

3. **Data Model Integrity**: ✅ Verified
   - Schema changes backward compatible (nullable fields)
   - Database constraints enforce data integrity (unique, check constraints)
   - Indexes optimize query performance without excessive overhead
   - Migration strategy documented with rollback plan

4. **TDD Readiness**: ✅ Verified
   - Contract tests can be written directly from OpenAPI specs
   - Unit test boundaries clear (IpAddressParser, IpRangeMatcher utilities)
   - Integration test scenarios documented in quickstart.md
   - Test fixtures patterns specified in data-model.md

## Implementation Strategy

**Phased Rollout** (from tasks.md, Phase 2):

**Phase 1 (P1 - MVP)**:
- Extend UserMapping entity with IP fields
- Implement IP validation and range parsing
- Add IP range matching logic
- Create IP mapping upload endpoints (CSV/Excel)
- Extend AccountVulnsService for IP-based filtering
- UI for viewing IP mappings in table

**Phase 2 (P2)**:
- UI for manual IP mapping creation/editing
- Search and filter functionality
- Pagination for large mapping tables
- Delete with confirmation
- Download CSV/Excel templates

**Phase 3 (P3)**:
- Combine IP + AWS account access control
- Warning for large ranges (/16 or bigger)
- Delete impact warning (show affected asset count)
- Performance optimization for range queries

**Dependencies**:
- Feature 013: UserMapping entity and repository (existing)
- Feature 016: CSV upload infrastructure (existing)
- Feature 018: AccountVulnsService (existing)

**Risk Mitigation**:
- IP parsing errors: Comprehensive validation tests before controller layer
- Performance degradation: Benchmark IP range queries on realistic dataset (100k assets, 10k mappings)
- UI complexity: Reuse UserMappingTable component patterns from Feature 013

**Rollback Strategy**:
- New UserMapping fields are nullable; can be dropped if needed
- IP-based access control is additive; disabling has no impact on existing AWS/workgroup access
- Feature flag recommended for initial deployment (can disable IP filtering in AccountVulnsService)
