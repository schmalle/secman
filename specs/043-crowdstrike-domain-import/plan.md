# Implementation Plan: CrowdStrike Domain Import Enhancement

**Branch**: `043-crowdstrike-domain-import` | **Date**: 2025-11-08 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/043-crowdstrike-domain-import/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Enhance the CrowdStrike Falcon API import functionality to capture, store, and display Active Directory domain information for assets. The system will extract domain data during imports, store it in the asset table's `ad_domain` field, implement smart field-level update logic to preserve existing data during re-imports, enable manual domain editing in the Asset Management UI, and display domain discovery statistics (count and list) in import summaries.

**Primary Technical Approach**: Extend existing CrowdStrike import service to parse domain information from Falcon API responses, implement field-level comparison logic for smart updates, add domain field to Asset edit UI form with validation, and enhance import result DTOs to include domain statistics tracking.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21 (backend), JavaScript ES2022 (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, MariaDB 11.4 (backend); Astro 5.14, React 19, Bootstrap 5.3, Axios (frontend); FalconPy (existing CrowdStrike integration)
**Storage**: MariaDB 11.4 with existing `asset` table (existing `ad_domain` column already present from Feature 042)
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Linux server (backend), web browser (frontend)
**Project Type**: web - full-stack application with backend API and frontend UI
**Performance Goals**: Import processing for 10,000 assets in <30 seconds; domain statistics calculation <1 second; UI updates without page refresh
**Constraints**: <200ms API response time for asset updates; zero data loss during re-imports; transactional import operations with rollback capability
**Scale/Scope**: 3 API endpoints modified, 1 UI component updated, 1 service extended, domain normalization logic, import result DTO enhancements

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Principle I: Security-First ✅

**Status**: PASS

- **File Upload Validation**: Not applicable (no new file upload endpoints)
- **Input Sanitization**: Domain field requires validation (alphanumeric, dots, hyphens) - covered in FR-005
- **RBAC Enforcement**: Domain editing restricted to ADMIN role (existing Asset Management permissions apply)
- **Sensitive Data**: Domain names are non-sensitive organizational identifiers, logging is acceptable
- **Token Storage**: No new authentication mechanisms required

**Justification**: Extends existing CrowdStrike import which already implements security measures. Domain validation prevents injection attacks.

### Principle II: Test-Driven Development (NON-NEGOTIABLE) ✅

**Status**: PASS (User-Requested per Principle IV)

- **Contract Tests**: Required for modified endpoints (CrowdStrike import service, Asset CRUD)
- **Integration Tests**: Required for smart update logic, domain extraction, statistics calculation
- **Unit Tests**: Required for domain normalization, field comparison, validation logic
- **Test Coverage**: Target ≥80% for modified services and new logic

**Note**: Per Principle IV (User-Requested Testing), test planning will be prepared only if explicitly requested. TDD framework requirements remain enforced.

### Principle III: API-First ✅

**Status**: PASS

- **RESTful Design**: Extends existing endpoints, no new endpoints required (backward compatible)
- **OpenAPI Documentation**: Will be updated to reflect new domain-related fields in DTOs
- **Error Handling**: Consistent error responses for domain validation failures
- **HTTP Status Codes**: 400 for validation errors, 200 for successful updates
- **Backward Compatibility**: New domain fields are optional (nullable), existing API consumers unaffected

**Justification**: Enhancement to existing API maintains backward compatibility. New domain field is additive.

### Principle IV: User-Requested Testing ✅

**Status**: PASS

- Testing frameworks (JUnit, Playwright) remain required per TDD principle
- Specific test case planning deferred until user explicitly requests testing
- Implementation plan proceeds without preemptive test task preparation

### Principle V: Role-Based Access Control (RBAC) ✅

**Status**: PASS

- **@Secured Annotations**: Existing Asset Management endpoints already enforce ADMIN role
- **Roles Involved**: ADMIN (import, manual edit), VULN (read-only via workgroups)
- **UI Role Checks**: Asset edit form already checks ADMIN role before display
- **Workgroup Filtering**: Domain-based access control already implemented in Feature 042
- **Authorization Layer**: Service-level checks already present in AssetService

**Justification**: Leverages existing RBAC infrastructure. No new permission model required.

### Principle VI: Schema Evolution ✅

**Status**: PASS

- **Hibernate Auto-Migration**: No schema changes required (`ad_domain` column already exists from Feature 042)
- **Entity Annotations**: Domain field already defined in Asset entity with validation
- **Indexes**: `ad_domain` index already exists for workgroup filtering performance
- **Data Loss**: Smart update logic explicitly prevents data loss (FR-007, FR-012)

**Justification**: Schema already supports this feature. Zero migration risk.

## Project Structure

### Documentation (this feature)

```text
specs/043-crowdstrike-domain-import/
├── plan.md              # This file (/speckit.plan command output)
├── spec.md              # Feature specification (already created)
├── research.md          # Phase 0 output (to be generated)
├── data-model.md        # Phase 1 output (to be generated)
├── quickstart.md        # Phase 1 output (to be generated)
├── contracts/           # Phase 1 output (to be generated)
│   ├── crowdstrike-import.yaml
│   └── asset-management.yaml
└── checklists/
    └── requirements.md  # Specification quality checklist (already created)
```

### Source Code (repository root)

```text
src/backendng/
├── src/main/kotlin/com/secman/
│   ├── domain/
│   │   └── Asset.kt                    # Existing - domain field already present
│   ├── dto/
│   │   ├── ImportResultDto.kt          # TO MODIFY - add domain statistics
│   │   └── AssetDto.kt                 # Existing - domain field exposed
│   ├── service/
│   │   ├── CrowdStrikeVulnerabilityImportService.kt  # TO MODIFY - extract domain
│   │   └── AssetService.kt             # TO MODIFY - smart update logic
│   ├── repository/
│   │   └── AssetRepository.kt          # Existing - no changes needed
│   └── controller/
│       ├── CrowdStrikeController.kt    # TO MODIFY - return domain stats
│       └── AssetController.kt          # Existing - domain field already editable
└── src/test/kotlin/com/secman/
    ├── integration/
    │   ├── CrowdStrikeImportIntegrationTest.kt  # NEW - domain extraction tests
    │   └── AssetSmartUpdateTest.kt              # NEW - field-level update tests
    └── unit/
        ├── DomainNormalizationTest.kt           # NEW - domain normalization tests
        └── ImportStatisticsTest.kt              # NEW - domain statistics tests

src/frontend/
├── src/
│   ├── components/
│   │   └── AssetManagement.tsx         # TO MODIFY - ensure domain field editable
│   └── services/
│       └── assetService.ts             # Existing - domain field already supported
└── tests/
    └── e2e/
        ├── crowdstrike-import.spec.ts  # TO MODIFY - add domain stats assertions
        └── asset-domain-edit.spec.ts   # NEW - manual domain editing tests

src/helper/
└── src/
    └── services/
        └── falcon_api.py               # RESEARCH NEEDED - check domain field mapping
```

**Structure Decision**: Web application structure (backend + frontend). This feature extends existing CrowdStrike import functionality across both layers. Backend modifications focus on CrowdStrikeVulnerabilityImportService and AssetService. Frontend modifications focus on AssetManagement UI component. Helper tools (FalconPy integration) may require minimal updates to expose domain field from API response.

## Complexity Tracking

**No constitutional violations** - this section is empty as all gates pass.

---

## Phase 0: Research & Design Decisions

**Status**: To be generated in `research.md`

### Research Tasks

1. **CrowdStrike Falcon API Domain Field Mapping**
   - Investigate FalconPy library response structure for device/host queries
   - Identify exact JSON field path for Active Directory domain information
   - Determine if domain is available in `machine_domain`, `ou`, or other field
   - Document field format (NetBIOS vs FQDN) and edge cases

2. **Domain Normalization Strategy**
   - Research domain name normalization best practices (RFC 1035)
   - Decide on storage format: uppercase, lowercase, or original case
   - Determine case-insensitive comparison approach for duplicate detection
   - Handle edge cases: FQDN with trailing dot, internationalized domains

3. **Smart Update Field Comparison**
   - Review existing Asset merge logic in AssetService (Feature 032 pattern)
   - Determine optimal field-by-field comparison strategy (manual vs reflection)
   - Identify performance implications for large imports (10k+ assets)
   - Decide on null vs empty string handling for missing domain data

4. **Import Statistics Collection**
   - Determine efficient approach for tracking unique domains during import loop
   - Decide on data structure: Set<String> for uniqueness, List<String> for ordered display
   - Identify memory usage implications for large domain counts
   - Define statistics display format in import result DTO

5. **Frontend Domain Validation**
   - Research client-side domain validation regex patterns
   - Determine real-time vs submit-time validation UX
   - Identify Bootstrap 5 validation component patterns for consistent UI
   - Decide on error message placement and content

**Output**: `research.md` with decisions, rationale, and alternatives considered

## Phase 1: Design & Contracts

**Status**: To be generated in `data-model.md`, `contracts/`, and `quickstart.md`

### Data Model Updates

**Entity: Asset** (existing, no schema changes)
- Field `adDomain: String?` already exists (Feature 042)
- Validation: `@Pattern(regexp = "^[a-zA-Z0-9.-]+$")`
- Index: Already indexed for domain filtering

**DTO: ImportResultDto** (to be extended)
- Add field: `uniqueDomainCount: Int`
- Add field: `discoveredDomains: List<String>`
- Existing fields: `totalAssets`, `totalVulnerabilities`, `errors`

**DTO: AssetDto** (existing, already includes domain)
- Field `adDomain: String?` already exposed in API

### API Contracts

**1. POST /api/crowdstrike/vulnerabilities/save** (existing endpoint, response modified)

Response DTO enhancement:
```json
{
  "totalAssets": 150,
  "totalVulnerabilities": 2300,
  "uniqueDomainCount": 5,
  "discoveredDomains": ["CONTOSO", "FABRIKAM", "CORP", "FINANCE", "SALES"],
  "errors": []
}
```

**2. PUT /api/assets/{id}** (existing endpoint, domain field already editable)

Request/Response already includes `adDomain` field (no contract changes needed).

**3. GET /api/assets** (existing endpoint, domain field already included)

Response already includes `adDomain` in asset objects (no contract changes needed).

### Service Layer Design

**CrowdStrikeVulnerabilityImportService**:
- Method: `importVulnerabilities()` - enhance to track domain statistics
- New logic: Extract domain from Falcon API response
- New logic: Normalize domain to uppercase
- New logic: Collect unique domains in Set<String>
- New logic: Populate domain statistics in ImportResultDto

**AssetService**:
- Method: `mergeAsset()` - enhance with field-level comparison
- New logic: Compare each field individually (domain, name, IP, owner, etc.)
- New logic: Update only changed fields via repository.update()
- New logic: Skip database write if no fields changed
- Preserve existing: Manual edit tracking, workgroup associations

### UI Design

**AssetManagement Component**:
- Verify `adDomain` text input field is visible in edit form
- Add Bootstrap validation styling for invalid domain format
- Add inline error message below domain field
- Add domain field label: "Active Directory Domain (optional)"

**CrowdStrike Import Results Display**:
- Add section below existing statistics: "Domain Discovery"
- Display: "Unique Domains: {count}"
- Display: "Domains: {comma-separated list}"
- Handle zero domains case: "No domains discovered"

**Output**:
- `data-model.md` with entity details
- `contracts/crowdstrike-import.yaml` with OpenAPI spec
- `contracts/asset-management.yaml` with OpenAPI spec updates
- `quickstart.md` with developer setup instructions

---

## Next Steps

After Phase 1 completion:
1. Run `/speckit.tasks` to generate implementation task list (`tasks.md`)
2. Implement tasks in dependency order
3. Create pull request with constitutional compliance check
4. Deploy to staging for E2E validation

**Agent Context Update**: Will be performed after Phase 1 design artifacts are generated.
