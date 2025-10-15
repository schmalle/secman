# Implementation Plan: Account Vulns Severity Breakdown

**Branch**: `019-account-vulns-severity-breakdown` | **Date**: 2025-10-14 | **Spec**: [spec.md](./spec.md)
**Parent Feature**: 018-under-vuln-management (Account Vulns)
**Input**: Feature specification from `/specs/019-account-vulns-severity-breakdown/spec.md`

## Summary

Extend the existing Account Vulns view to display vulnerability severity breakdowns (critical, high, medium) for each asset, each AWS account group, and globally across all accounts. This enhancement enables users to prioritize remediation efforts by identifying which assets have the most critical vulnerabilities rather than just seeing total counts.

**Technical Approach**: Backend modifies the existing vulnerability counting query to group by severity level (CRITICAL, HIGH, MEDIUM) and adds severity count fields to DTOs as optional/nullable properties for backward compatibility. Frontend updates badge displays to show color-coded severity indicators with counts, always displaying all three severity levels even when counts are 0. Includes validation logic to ensure severity counts sum correctly.

## Technical Context

**Language/Version**: Kotlin 2.1.0 / Java 21 (backend), TypeScript/JavaScript (frontend - Astro 5.14 + React 19)
**Primary Dependencies**: Micronaut 4.4, Hibernate JPA, Astro 5.14, React 19, Bootstrap 5.3
**Storage**: MariaDB 11.4 (existing tables: assets, vulnerabilities - no schema changes required)
**Testing**: JUnit 5 + MockK (backend), Playwright (frontend E2E)
**Target Platform**: Web application (Linux server + modern browsers)
**Project Type**: web (backend + frontend)
**Performance Goals**: Page load time increase ≤10% vs current implementation, response size increase ≤30%
**Constraints**: Must maintain backward compatibility with existing API consumers, severity counts must always sum to total
**Scale/Scope**: Designed to handle same scale as parent feature (1-50 AWS accounts, 1-100 assets per account)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅ PASS
- **RBAC Enforcement**: No changes to authentication/authorization (inherits from parent feature)
- **Input Validation**: Severity field validated at DB level (enum/string constraint), normalized to uppercase
- **Data Filtering**: No additional filtering required (uses existing AWS account-based filtering)
- **No Sensitive Data Exposure**: Adds severity counts only (aggregated data), no additional CVE details exposed

### II. Test-Driven Development (NON-NEGOTIABLE) ✅ PASS
- **Contract Tests Required**: Update existing /api/account-vulns contract tests to verify severity fields present and accurate
- **Unit Tests Required**: Service layer severity counting logic, aggregation, validation (sum verification)
- **E2E Tests Required**: Frontend tests for severity badge display, 0-count handling, color-blind accessibility
- **Coverage Target**: ≥80% for new/modified code (service methods, DTO mapping, UI components)

### III. API-First ✅ PASS
- **Extend Existing Endpoint**: GET /api/account-vulns now returns optional severity fields in response DTOs
- **Backward Compatibility**: All severity fields added as nullable/optional (existing consumers unaffected)
- **Consistent Error Format**: No new error cases (uses existing error handling)
- **HTTP Status Codes**: No changes (uses existing 200/401/403/404 responses)

### IV. Docker-First ✅ PASS
- **No Infrastructure Changes**: Existing Dockerfiles/docker-compose.yml sufficient
- **Environment Config**: No new environment variables required

### V. Role-Based Access Control (RBAC) ✅ PASS
- **Endpoint Security**: No changes to existing @Secured annotations
- **Role Checks**: No new role-based logic required
- **UI Role Checks**: No changes to existing role checks

### VI. Schema Evolution ✅ PASS
- **No Schema Changes**: Uses existing vulnerabilities.severity field
- **Existing Indexes**: May benefit from index on vulnerabilities.severity (optional optimization - see research phase)
- **Data Migration**: None required

**Overall Status**: ✅ ALL GATES PASS - Ready for Phase 0 research

## Project Structure

### Documentation (this feature)

```
specs/019-account-vulns-severity-breakdown/
├── spec.md              # Feature specification (completed with clarifications)
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (pending)
├── data-model.md        # Phase 1 output (pending)
├── quickstart.md        # Phase 1 output (pending)
├── contracts/           # Phase 1 output (pending)
│   └── account-vulns-api-v2.yaml
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Backend Code (modifications to existing)

```
src/backendng/src/main/kotlin/com/secman/
├── dto/
│   ├── AccountVulnsSummaryDto.kt    # ADD: globalCritical?, globalHigh?, globalMedium?
│   ├── AccountGroupDto.kt            # ADD: totalCritical?, totalHigh?, totalMedium?
│   └── AssetVulnCountDto.kt          # ADD: criticalCount?, highCount?, mediumCount?
├── service/
│   └── AccountVulnsService.kt        # MODIFY: Add severity grouping logic, validation
└── controller/
    └── AccountVulnsController.kt     # No changes required (DTOs extended transparently)
```

### Frontend Code (modifications to existing)

```
src/frontend/src/
├── components/
│   ├── AccountVulnsView.tsx          # MODIFY: Add severity display to summary cards
│   ├── AssetVulnTable.tsx            # MODIFY: Add severity badges per asset row
│   └── SeverityBadge.tsx             # NEW: Reusable severity badge component
├── services/
│   └── accountVulnsService.ts        # MODIFY: Update TypeScript interfaces
└── pages/
    └── account-vulns.astro           # No changes required (uses updated component)
```

### Test Code

```
src/backendng/src/test/kotlin/com/secman/
├── contract/
│   └── AccountVulnsContractTest.kt   # MODIFY: Add severity field assertions
├── service/
│   └── AccountVulnsServiceTest.kt    # MODIFY: Add severity counting test cases
└── fixtures/
    └── AccountVulnsTestFixtures.kt   # MODIFY: Add severity data to test fixtures

tests/e2e/
└── account-vulns.spec.ts             # MODIFY: Add severity badge visibility tests
```

## Phase 0: Outline & Research

### Research Tasks

1. **Database Query Optimization for Severity Grouping**
   - **Question**: What's the most efficient way to count vulnerabilities grouped by severity in a single query?
   - **Approach**: Research SQL GROUP BY with CASE statements vs multiple queries
   - **Output**: SQL query pattern with performance characteristics

2. **Index Analysis for Severity Field**
   - **Question**: Will adding an index on vulnerabilities.severity improve query performance significantly?
   - **Approach**: Analyze existing query plans, estimate row counts, consider composite indexes
   - **Output**: Index recommendation with justification (add/skip/defer)

3. **Severity Field Normalization Strategy**
   - **Question**: Where should severity normalization (uppercase) happen - DB level, service layer, or both?
   - **Approach**: Research Hibernate/JPA field converters, DB collation settings, performance implications
   - **Output**: Normalization strategy with implementation location

4. **Color-Blind Accessibility Patterns**
   - **Question**: What are best practices for color-blind friendly severity indicators beyond just icons?
   - **Approach**: Research WCAG guidelines, Bootstrap accessibility patterns, icon libraries
   - **Output**: Accessibility implementation checklist with specific icon/pattern choices

5. **Backward Compatibility Testing Strategy**
   - **Question**: How to ensure nullable DTO fields don't break existing consumers (if any)?
   - **Approach**: Research Micronaut Serde serialization behavior with null fields, contract versioning patterns
   - **Output**: Testing strategy for backward compatibility verification

### Unknowns from Technical Context

1. **NEEDS CLARIFICATION**: Current state of vulnerabilities.severity field
   - Is it an ENUM or VARCHAR in MariaDB?
   - What are the actual values present (case-sensitive check)?
   - Are there any NULL or empty values in production data?
   - **Resolution Method**: Query existing database schema and data distribution

2. **NEEDS CLARIFICATION**: Performance baseline for existing query
   - Current query execution time for /api/account-vulns endpoint
   - Current response size for typical user (10 assets across 2 AWS accounts)
   - **Resolution Method**: Performance profiling of existing endpoint

3. **NEEDS CLARIFICATION**: Frontend component architecture
   - Does AssetVulnTable component already have sub-components for columns?
   - What's the current badge/indicator pattern used elsewhere in the UI?
   - **Resolution Method**: Code review of existing frontend components

### Expected Research Outputs

**File**: `research.md`

Structure:
```markdown
# Research: Account Vulns Severity Breakdown

## Database Query Optimization
- Decision: [Single query with CASE/conditional aggregation]
- Rationale: [Performance, maintainability]
- Alternatives: [Multiple queries, subqueries]
- Example SQL: [...]

## Index Strategy
- Decision: [Add/Skip/Defer index on severity]
- Rationale: [Query plan analysis results]
- Migration Required: [Yes/No]

## Severity Normalization
- Decision: [Service layer normalization]
- Rationale: [Flexibility, testability]
- Implementation: [UPPERCASE in service before grouping]

## Accessibility Implementation
- Icons: [⚠️ Critical, ⬆️ High, ➖ Medium]
- Patterns: [Additional visual indicators]
- Testing: [Color-blind simulation tools]

## Backward Compatibility
- Strategy: [Optional fields + contract tests]
- Validation: [Test with old API consumers]
```

## Phase 1: Design & Contracts

### Data Model Extensions

**File**: `data-model.md`

Since no database schema changes are required, this document will focus on DTO extensions:

```markdown
# Data Model: Account Vulns Severity Breakdown

## Existing Entities (No Changes)

### Vulnerability
- **Table**: vulnerabilities
- **Key Field**: severity (VARCHAR or ENUM: CRITICAL, HIGH, MEDIUM, LOW, etc.)
- **Usage**: Queried and grouped by severity for counting

### Asset
- **Table**: assets
- **Relationships**: Has many Vulnerabilities
- **Usage**: Parent entity for vulnerability aggregation

## DTO Extensions (Backend)

### AssetVulnCountDto
**Purpose**: Represents single asset with vulnerability counts per severity

**Fields**:
- `id: Long` (existing)
- `name: String` (existing)
- `type: String` (existing)
- `vulnerabilityCount: Int` (existing - total count)
- `criticalCount: Int?` (NEW - nullable for backward compatibility)
- `highCount: Int?` (NEW - nullable for backward compatibility)
- `mediumCount: Int?` (NEW - nullable for backward compatibility)

**Validation**: criticalCount + highCount + mediumCount + (low/unknown) = vulnerabilityCount

### AccountGroupDto
**Purpose**: Represents AWS account group with aggregated severity totals

**Fields**:
- `awsAccountId: String` (existing)
- `assets: List<AssetVulnCountDto>` (existing - now includes severity data)
- `totalAssets: Int` (existing)
- `totalVulnerabilities: Int` (existing)
- `totalCritical: Int?` (NEW - sum of criticalCount across assets)
- `totalHigh: Int?` (NEW - sum of highCount across assets)
- `totalMedium: Int?` (NEW - sum of mediumCount across assets)

### AccountVulnsSummaryDto
**Purpose**: Top-level response with global severity totals

**Fields**:
- `accountGroups: List<AccountGroupDto>` (existing)
- `totalAssets: Int` (existing)
- `totalVulnerabilities: Int` (existing)
- `globalCritical: Int?` (NEW - sum across all accounts)
- `globalHigh: Int?` (NEW - sum across all accounts)
- `globalMedium: Int?` (NEW - sum across all accounts)

## TypeScript Interfaces (Frontend)

### AssetVulnCount
```typescript
interface AssetVulnCount {
  id: number;
  name: string;
  type: string;
  vulnerabilityCount: number;
  criticalCount?: number;    // NEW
  highCount?: number;         // NEW
  mediumCount?: number;       // NEW
}
```

### AccountGroup
```typescript
interface AccountGroup {
  awsAccountId: string;
  assets: AssetVulnCount[];
  totalAssets: number;
  totalVulnerabilities: number;
  totalCritical?: number;     // NEW
  totalHigh?: number;          // NEW
  totalMedium?: number;        // NEW
}
```

### AccountVulnsSummary
```typescript
interface AccountVulnsSummary {
  accountGroups: AccountGroup[];
  totalAssets: number;
  totalVulnerabilities: number;
  globalCritical?: number;    // NEW
  globalHigh?: number;         // NEW
  globalMedium?: number;       // NEW
}
```
```

### API Contract Updates

**File**: `contracts/account-vulns-api-v2.yaml`

This will be an updated version of the existing contract with severity fields added. See OpenAPI schema in spec.md Implementation Notes section.

Key changes:
- Add nullable severity fields to all DTOs
- Add examples showing severity breakdown
- Document backward compatibility behavior
- Clarify that null severity fields mean feature not implemented (for phased rollout)

### Quickstart Guide

**File**: `quickstart.md`

```markdown
# Quickstart: Account Vulns Severity Breakdown

## Prerequisites
- Feature 018-under-vuln-management deployed and functional
- Database has vulnerabilities with severity field populated
- Development environment set up per main project README

## Backend Changes

### 1. Update DTOs (Kotlin)

Add nullable severity fields to existing DTOs:
- `AccountVulnsSummaryDto.kt`: Add globalCritical?, globalHigh?, globalMedium?
- `AccountGroupDto.kt`: Add totalCritical?, totalHigh?, totalMedium?
- `AssetVulnCountDto.kt`: Add criticalCount?, highCount?, mediumCount?

### 2. Modify Service Layer

Update `AccountVulnsService.kt`:
- Modify vulnerability counting query to use GROUP BY severity
- Add severity aggregation logic at asset and account levels
- Implement validation: log error if counts don't sum to total
- Normalize severity values to uppercase for consistent matching

Example query pattern (SQL):
```sql
SELECT 
    asset_id,
    COUNT(*) as total,
    SUM(CASE WHEN UPPER(severity) = 'CRITICAL' THEN 1 ELSE 0 END) as critical,
    SUM(CASE WHEN UPPER(severity) = 'HIGH' THEN 1 ELSE 0 END) as high,
    SUM(CASE WHEN UPPER(severity) = 'MEDIUM' THEN 1 ELSE 0 END) as medium
FROM vulnerabilities
WHERE asset_id IN (...)
GROUP BY asset_id
```

### 3. Update Tests

Extend existing test files:
- `AccountVulnsServiceTest.kt`: Add test cases for severity counting
- `AccountVulnsContractTest.kt`: Assert severity fields present and correct
- `AccountVulnsTestFixtures.kt`: Add vulnerability data with severity values

## Frontend Changes

### 1. Update TypeScript Interfaces

Update `accountVulnsService.ts` with new optional severity fields.

### 2. Create SeverityBadge Component

New file: `SeverityBadge.tsx`
- Props: severity (CRITICAL|HIGH|MEDIUM), count (number)
- Renders Bootstrap badge with icon and count
- Color coding: red/critical, orange/high, yellow/medium
- Icons: ⚠️/🔴 critical, ⬆️/🟠 high, ➖/🟡 medium

### 3. Update AccountVulnsView Component

Modify summary cards section:
- Add severity breakdown display below or alongside total count
- Use SeverityBadge components for each severity level

### 4. Update AssetVulnTable Component

Add severity badges to each asset row:
- Display all three badges even when count is 0
- Place after asset name or in separate column
- Ensure responsive layout for mobile

### 5. Update E2E Tests

Extend `account-vulns.spec.ts`:
- Test severity badges visible for each asset
- Test account header shows aggregated severity totals
- Test global summary shows correct severity breakdown
- Test 0-count badges are displayed

## Testing Workflow

1. Run backend unit tests: `./gradlew test`
2. Run backend contract tests: `./gradlew test --tests "*Contract*"`
3. Start backend: `./gradlew run`
4. Run frontend E2E tests: `npm run test:e2e`
5. Manual testing: Visit /account-vulns page, verify severity badges

## Validation Checklist

- [ ] All severity badges display for each asset (even 0 counts)
- [ ] Account header shows aggregated severity totals
- [ ] Global summary cards show severity breakdown
- [ ] Severity counts sum to total (check logs for validation errors)
- [ ] Color-blind mode: icons visible without relying on color
- [ ] Mobile responsive: badges readable on 320px width
- [ ] Backward compatibility: existing API consumers not broken
- [ ] Performance: page load increase ≤10%

## Troubleshooting

**Severity counts don't sum to total**:
- Check for NULL or empty severity values in DB
- Review normalization logic (uppercase conversion)
- Check validation logs for specific asset IDs

**Badges not displaying**:
- Verify API returns severity fields (check Network tab)
- Check TypeScript interface matches API response
- Verify SeverityBadge component imported correctly

**Performance regression**:
- Analyze query execution plan
- Consider adding index on vulnerabilities.severity
- Check if GROUP BY is using optimal strategy
```

## Phase 2: Task Decomposition

**Note**: Task breakdown will be generated by `/speckit.tasks` command (not this command).

Expected task categories:
1. **Backend**: DTO updates, service modifications, query optimization, validation logic
2. **Frontend**: Component updates, badge creation, TypeScript interfaces
3. **Testing**: Unit tests, contract tests, E2E tests
4. **Documentation**: Update API docs, code comments
5. **Validation**: Performance testing, accessibility testing

## Dependencies & Risks

### Dependencies
- **Parent Feature**: 018-under-vuln-management must be fully deployed
- **Database State**: vulnerabilities.severity field must exist and be populated
- **Frontend Libraries**: Bootstrap 5.3 for badge styling

### Risks & Mitigation

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| Query performance degrades with GROUP BY | High | Medium | Add index on severity field; test with large datasets before deployment |
| Existing data has inconsistent severity values | High | Low | Add data quality check script; normalize during migration if needed |
| Nullable fields confuse TypeScript consumers | Medium | Low | Clear documentation; optional chaining in frontend code |
| Color-blind users can't distinguish severity | High | Low | Use icons + patterns in addition to colors; test with accessibility tools |
| Backward compatibility breaks old consumers | Medium | Very Low | Contract tests verify optional fields; Micronaut handles nulls gracefully |

## Success Metrics

**Defined in spec.md Success Criteria section:**
- SC-001: Users identify critical vulnerabilities within 5 seconds ✅
- SC-002: 90% users correctly interpret severity without training ✅
- SC-003: 100% accuracy in severity counts ✅
- SC-004: ≤10% page load time increase ✅
- SC-005: ≤30% API response size increase ✅
- SC-006: Zero severity miscategorization ✅
- SC-007: Mobile responsive at 320px width ✅
- SC-008: Color-blind accessible ✅

## Next Steps

1. **Complete Phase 0**: Run research tasks, create `research.md`
2. **Complete Phase 1**: Finalize `data-model.md`, `contracts/`, `quickstart.md`
3. **Run `/speckit.tasks`**: Generate detailed task breakdown for implementation
4. **Implementation**: Follow task sequence with TDD approach
5. **Validation**: Run full test suite + manual testing per quickstart checklist

---

**Plan Status**: ✅ Ready for Phase 0 Research

**Generated**: 2025-10-14 by `/speckit.plan` command
