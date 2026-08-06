# Feature Specification: Account Vulns Severity Breakdown

**Feature Branch**: `019-account-vulns-severity-breakdown`
**Created**: 2025-10-14
**Status**: Draft
**Parent Feature**: 018-under-vuln-management (Account Vulns)
**Input**: User request: "i want in the /account-vulns UI not only the total number of vulnerabilities but also the differentiation between medium, high and critical vulnerabilities."

## Overview

This enhancement extends the existing Account Vulns feature (018) to display vulnerability severity breakdowns. Instead of showing only total vulnerability counts, users will see how many vulnerabilities are medium, high, and critical for each asset and each AWS account group.

## Clarifications

### Session 2025-10-14

- Q: Should low severity vulnerabilities also be displayed, or only medium, high, and critical? → A: Display medium, high, and critical only (as specified by user). Low severity can be calculated as: total - (medium + high + critical)
- Q: How should the severity breakdown be displayed in the UI - as separate columns, badges, or a visual chart? → A: Use badge indicators with counts next to each severity level (e.g., "🔴 Critical: 5, 🟠 High: 12, 🟡 Medium: 8")
- Q: Should the total vulnerabilities count remain visible, or be replaced by the breakdown? → A: Keep total count visible, add severity breakdown as additional information
- Q: In the account group summary header, should we show aggregated severity counts across all assets in that account? → A: Yes, show account-level severity totals in the header badge
- Q: Should assets be sortable by severity level (e.g., sort by critical count descending)? → A: Not in MVP - keep existing sort by total count. Can be added later if needed
- Q: What severity field in the Vulnerability entity should be used? → A: Use the existing `severity` field (assumed to be enum or string with values: CRITICAL, HIGH, MEDIUM, LOW)
- Q: Should severity badges with 0 count be hidden or displayed as "Critical: 0"? → A: Show all severity badges with 0 counts explicitly displayed
- Q: How should vulnerabilities with null/empty/non-standard severity be categorized? → A: Treat as UNKNOWN but include in total count, just not in severity breakdown
- Q: Should the global severity summary (top cards) be included in this feature's scope? → A: Yes - implement global severity summary in top cards as part of this feature
- Q: How should backward compatibility be handled for existing API consumers? → A: Add fields as optional (nullable) in DTOs - old consumers ignore them, new ones use them
- Q: Where should severity count validation/verification occur? → A: Backend only - validate during aggregation, log error if mismatch detected

## User Scenarios & Testing *(mandatory)*

### User Story 1 - View Severity Breakdown Per Asset (Priority: P1)

A non-admin user viewing their Account Vulns wants to see not just how many vulnerabilities each asset has, but specifically how many are critical, high, and medium severity to prioritize remediation efforts.

**Why this priority**: Core value proposition - enables risk-based prioritization. Without severity visibility, users can't identify which assets need immediate attention.

**Independent Test**: Can be tested by creating assets with mixed vulnerability severities, loading the Account Vulns page, and verifying each asset row displays severity breakdown badges showing accurate counts for critical, high, and medium vulnerabilities.

**Acceptance Scenarios**:

1. **Given** an asset has 5 critical, 12 high, 8 medium, and 3 low vulnerabilities (28 total), **When** viewing the Account Vulns page, **Then** the asset row displays "Total: 28" and severity badges "🔴 Critical: 5, 🟠 High: 12, 🟡 Medium: 8"
2. **Given** an asset has only critical vulnerabilities (e.g., 3 critical, 0 high, 0 medium), **When** viewing the Account Vulns page, **Then** the asset row displays all severity badges: "🔴 Critical: 3, 🟠 High: 0, 🟡 Medium: 0"
3. **Given** an asset has 0 vulnerabilities, **When** viewing the Account Vulns page, **Then** the asset row displays "Total: 0" with all severity badges showing 0: "🔴 Critical: 0, 🟠 High: 0, 🟡 Medium: 0"

---

### User Story 2 - View Account-Level Severity Aggregation (Priority: P1)

A user with multiple AWS accounts wants to see aggregated severity counts for each account group to quickly identify which AWS account has the most critical security issues.

**Why this priority**: Essential for multi-account users to prioritize which account needs attention first. Complements the per-asset breakdown.

**Independent Test**: Can be tested by creating multiple AWS accounts with assets having different vulnerability severities, and verifying the account group header displays correct aggregated severity counts.

**Acceptance Scenarios**:

1. **Given** an AWS account group has 3 assets with vulnerabilities: Asset1 (2 critical, 5 high, 3 medium), Asset2 (1 critical, 2 high, 0 medium), Asset3 (0 critical, 3 high, 5 medium), **When** viewing Account Vulns, **Then** the account header displays "3 critical, 10 high, 8 medium" (totals: 3 critical, 10 high, 8 medium)
2. **Given** an AWS account group has no vulnerabilities across all assets, **When** viewing Account Vulns, **Then** the account header displays "0 vulnerabilities" or severity counts all showing 0
3. **Given** a user has three AWS accounts, **When** viewing Account Vulns, **Then** each account group header independently shows its own severity breakdown aggregated from its assets

---

### User Story 3 - Global Severity Summary (Priority: P1)

A user wants to see an overall severity breakdown across all their AWS accounts in the top summary cards to get a complete picture of their security posture at a glance.

**Why this priority**: Core feature - provides immediate high-level visibility of security status. Users need to see global critical/high/medium counts before diving into details.

**Independent Test**: Can be tested by creating multiple AWS accounts with various vulnerability severities, and verifying the top summary section displays correct global severity totals across all accounts.

**Acceptance Scenarios**:

1. **Given** a user has two AWS accounts with total vulnerabilities: Account1 (5 critical, 10 high, 15 medium), Account2 (3 critical, 7 high, 5 medium), **When** viewing Account Vulns, **Then** the top summary section displays global totals: "8 critical, 17 high, 20 medium" (or as separate cards/badges)
2. **Given** the global summary cards, **When** viewing them, **Then** they display severity breakdowns in a clear, visually distinct way (e.g., color-coded cards or badges)
3. **Given** the user refreshes the data, **When** vulnerabilities are added or removed, **Then** the global severity summary updates to reflect the new totals

---

### Edge Cases

- What happens when a vulnerability has an unknown/null severity value? (Treat as UNKNOWN and include in total count but exclude from critical/high/medium severity breakdown)
- How are vulnerabilities with severity "INFO" or "WARNING" categorized? (Treat as LOW; not displayed in the medium/high/critical breakdown but included in total)
- What if the severity field uses inconsistent casing (e.g., "Critical" vs "CRITICAL" vs "critical")? (Backend should normalize to uppercase; use case-insensitive comparison)
- How should the UI handle very large severity counts (e.g., 9999 critical vulnerabilities)? (Use number formatting with commas or K notation, e.g., "9,999" or "9.9K")
- What if an asset has vulnerabilities but all are LOW severity? (Display "Total: X" with all severity badges showing 0, or display "Low: X" for completeness)
- Should color-blind friendly design be considered for severity badges? (Yes - use icons/symbols in addition to colors: 🔴/⚠️ for critical, 🟠/⬆️ for high, 🟡/➖ for medium)

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST retrieve vulnerability severity information for each asset when loading Account Vulns data
- **FR-002**: System MUST count vulnerabilities per asset grouped by severity level: CRITICAL, HIGH, MEDIUM
- **FR-003**: System MUST display per-asset severity breakdown in the asset table row, showing counts for critical, high, and medium vulnerabilities
- **FR-004**: System MUST display the total vulnerability count alongside the severity breakdown for each asset
- **FR-005**: System MUST aggregate severity counts across all assets within each AWS account group
- **FR-006**: System MUST display account-level severity aggregation in the account group header/badge
- **FR-007**: System MUST aggregate severity counts across all AWS accounts for the user
- **FR-008**: System MUST display global severity summary in the top summary section as part of this feature (including separate cards or badges for critical, high, and medium counts)
- **FR-009**: System MUST use color-coded visual indicators for severity levels: red/critical, orange/high, yellow/medium
- **FR-009a**: System MUST always display all three severity badges (critical, high, medium) even when counts are 0
- **FR-010**: System MUST handle vulnerabilities with null, empty, or non-standard severity values by categorizing them as UNKNOWN, including them in the total vulnerability count but excluding them from critical/high/medium counts
- **FR-011**: System MUST normalize severity values to uppercase for consistent comparison (e.g., "critical" → "CRITICAL")
- **FR-012**: System MUST maintain the existing sort order (assets sorted by total vulnerability count descending) while adding severity information
- **FR-013**: Backend API MUST extend the existing AccountVulnsSummaryDto to include severity breakdown fields as optional (nullable) properties, maintaining backward compatibility with existing consumers
- **FR-014**: System MUST validate during backend aggregation that severity counts sum correctly (critical + high + medium + low + unknown = total vulnerabilities per asset) and log an error if mismatch is detected

### Non-Functional Requirements

- **NFR-001**: Severity counting and aggregation MUST NOT increase page load time by more than 10% compared to current implementation
- **NFR-002**: Severity badges MUST be accessible and readable for color-blind users (use icons/patterns in addition to colors)
- **NFR-003**: API response size MUST NOT increase by more than 30% when adding severity breakdown data
- **NFR-004**: Severity breakdown display MUST be responsive and readable on mobile devices (minimum 320px width)

### Key Entities

- **Vulnerability**: Existing entity with `severity` field (enum/string: CRITICAL, HIGH, MEDIUM, LOW, etc.). Used to count vulnerabilities by severity.
- **AssetVulnCountDto** (API contract): Extended to include severity breakdown fields (criticalCount, highCount, mediumCount) in addition to existing total vulnerabilityCount.
- **AccountGroupDto** (API contract): Extended to include account-level severity aggregation (totalCritical, totalHigh, totalMedium).
- **AccountVulnsSummaryDto** (API contract): Extended to include global severity totals (P2 feature).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can identify assets with critical vulnerabilities within 5 seconds of page load
- **SC-002**: 90% of users correctly interpret severity breakdowns without additional documentation or training
- **SC-003**: Severity counts match actual vulnerability data with 100% accuracy (no counting errors)
- **SC-004**: Page load time increases by no more than 10% when adding severity breakdown data
- **SC-005**: API response size increases by no more than 30% with severity breakdown fields
- **SC-006**: Zero cases of severity miscategorization (e.g., HIGH counted as CRITICAL)
- **SC-007**: Severity breakdowns remain readable and usable on mobile devices (320px width minimum)
- **SC-008**: Color-blind users can distinguish severity levels using icon/pattern indicators

## Implementation Notes

### Backend Changes Required

1. **Service Layer** (`AccountVulnsService.kt`):
   - Modify vulnerability counting logic to group by severity
   - Add severity aggregation at asset and account levels
   - Update DTO mapping to include severity breakdown fields
   - Implement validation logic to verify severity counts sum to total, log errors for mismatches

2. **DTO Updates**:
   - Extend `AssetVulnCountDto` with: `criticalCount`, `highCount`, `mediumCount`
   - Extend `AccountGroupDto` with: `totalCritical`, `totalHigh`, `totalMedium`
   - Extend `AccountVulnsSummaryDto` with: `globalCritical`, `globalHigh`, `globalMedium`

3. **Database Query**:
   - Modify existing vulnerability count query to include `GROUP BY severity`
   - Use CASE statements or conditional aggregation to count by severity level
   - Example: `SUM(CASE WHEN severity = 'CRITICAL' THEN 1 ELSE 0 END) as criticalCount`

### Frontend Changes Required

1. **Component Update** (`AccountVulnsView.tsx`):
   - Update `AssetVulnTable` component to display severity badges per asset
   - Add severity breakdown to account group header badge
   - Add severity breakdown to top summary cards (separate cards or enhanced display for critical/high/medium)

2. **UI Design**:
   - Use Bootstrap badges with contextual colors (danger/red, warning/orange, info/yellow)
   - Add icons for accessibility: ⚠️ critical, ⬆️ high, ➖ medium
   - Ensure responsive layout for severity indicators

3. **TypeScript Types**:
   - Update `AccountVulnsSummary` interface to include severity fields
   - Update `AccountGroup` and `AssetVulnCount` interfaces

### API Contract Changes

Update `account-vulns-api.yaml`:

```yaml
AssetVulnCountDto:
  properties:
    vulnerabilityCount:
      type: integer
      description: Total vulnerabilities (existing)
    criticalCount:
      type: integer
      nullable: true
      description: Count of CRITICAL severity vulnerabilities (optional for backward compatibility)
    highCount:
      type: integer
      nullable: true
      description: Count of HIGH severity vulnerabilities (optional for backward compatibility)
    mediumCount:
      type: integer
      nullable: true
      description: Count of MEDIUM severity vulnerabilities (optional for backward compatibility)

AccountGroupDto:
  properties:
    totalVulnerabilities:
      type: integer
      description: Total vulnerabilities in account (existing)
    totalCritical:
      type: integer
      nullable: true
      description: Aggregated critical vulnerabilities in account (optional for backward compatibility)
    totalHigh:
      type: integer
      nullable: true
      description: Aggregated high vulnerabilities in account (optional for backward compatibility)
    totalMedium:
      type: integer
      nullable: true
      description: Aggregated medium vulnerabilities in account (optional for backward compatibility)

AccountVulnsSummaryDto:
  properties:
    totalVulnerabilities:
      type: integer
      description: Total vulnerabilities across all accounts (existing)
    globalCritical:
      type: integer
      nullable: true
      description: Total critical vulnerabilities across all accounts (optional for backward compatibility)
    globalHigh:
      type: integer
      nullable: true
      description: Total high vulnerabilities across all accounts (optional for backward compatibility)
    globalMedium:
      type: integer
      nullable: true
      description: Total medium vulnerabilities across all accounts (optional for backward compatibility)
```

## Testing Strategy

### Unit Tests

- Test severity counting logic for single asset with mixed severities
- Test severity aggregation for account group with multiple assets
- Test handling of null/unknown severity values
- Test case-insensitive severity matching

### Integration Tests

- Test API endpoint returns correct severity breakdowns
- Test DTO mapping includes all severity fields
- Test database query performance with severity grouping

### E2E Tests

- Test UI displays severity badges correctly for assets
- Test account header shows aggregated severity totals
- Test severity indicators are accessible (color-blind mode)
- Test mobile responsiveness of severity display

### Contract Tests

- Verify API response includes severity breakdown fields
- Verify backward compatibility (existing fields unchanged)
- Verify severity counts sum to total vulnerability count

## Dependencies

- Depends on: Feature 018-under-vuln-management (Account Vulns) - must be completed and deployed
- Blocked by: None
- Blocks: None

## Risks & Mitigation

- **Risk**: Increased database query complexity may slow down page load
  - *Mitigation*: Use database query optimization; add indexes on `severity` field if needed; measure performance impact
  
- **Risk**: API contract changes may break existing consumers
  - *Mitigation*: Add new fields as optional; maintain backward compatibility; version API if necessary
  
- **Risk**: UI becomes cluttered with severity information
  - *Mitigation*: Use compact badge design; consider collapsible/expandable severity details; conduct UX review

## Open Questions

1. Should LOW severity vulnerabilities be displayed alongside medium/high/critical, or omitted from the UI?
2. Should severity colors/icons follow a specific standard (CVSS, internal guidelines)?
3. Is there a need for filtering/sorting by severity level in future iterations?
4. Should the global severity summary (top cards) be in MVP or deferred to next iteration?

## Related Specifications

- 018-under-vuln-management: Parent feature providing the base Account Vulns functionality
- Future: Sorting/filtering by severity could be a follow-up enhancement (020-account-vulns-severity-filters)
