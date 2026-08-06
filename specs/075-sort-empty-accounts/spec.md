# Feature Specification: Sort Empty AWS Accounts to Bottom

**Feature Branch**: `075-sort-empty-accounts`
**Created**: 2026-02-05
**Status**: Draft
**Input**: User description: "please change the UI for account vulns, the accounts with no systems / vulnerabilities must be show at the end of the page."

## Clarifications

### Session 2026-02-05

- Q: Should the empty accounts section be collapsible? → A: Collapsible, collapsed by default — section heading with expand/collapse toggle.

## User Scenarios & Testing

### User Story 1 - View Accounts with Assets First (Priority: P1)

As a security manager viewing the Account Vulns page, I want AWS accounts that have assets to appear at the top of the page and accounts with no assets to appear at the bottom, so I can immediately focus on accounts that require attention without scrolling past empty accounts.

**Why this priority**: This is the core request. Users with many AWS accounts (77 in the screenshot) need to quickly find and triage accounts with active vulnerabilities. Empty accounts are noise that pushes actionable content further down the page.

**Independent Test**: Navigate to the Account Vulns page and verify that all AWS accounts containing at least one asset appear before any account with zero assets.

**Acceptance Scenarios**:

1. **Given** a user has 10 AWS account mappings where 6 have assets and 4 have none, **When** the user loads the Account Vulns page, **Then** the 6 accounts with assets are displayed first, followed by the 4 empty accounts at the bottom.
2. **Given** a user has AWS accounts where all accounts have assets, **When** the user loads the Account Vulns page, **Then** the page displays all accounts sorted by account ID ascending with no change in behavior.
3. **Given** a user has AWS accounts where none have assets, **When** the user loads the Account Vulns page, **Then** all accounts are displayed (all empty) with no errors.

---

### User Story 2 - Preserve Sort Order Within Groups (Priority: P1)

As a security manager, I want accounts with assets to remain sorted by account ID among themselves, and empty accounts to also be sorted by account ID among themselves, so the ordering within each group remains predictable and consistent.

**Why this priority**: Consistent ordering within the two groups ensures users can still locate specific accounts by their account ID.

**Independent Test**: Load the Account Vulns page and verify that within the group of accounts with assets, accounts are sorted by account ID ascending, and within the group of empty accounts, accounts are also sorted by account ID ascending.

**Acceptance Scenarios**:

1. **Given** accounts with assets have IDs 003631872748, 111111111111, 222222222222, **When** the page loads, **Then** these three accounts appear in ascending ID order at the top.
2. **Given** empty accounts have IDs 033614065445, 044444444444, 055555555555, **When** the page loads, **Then** these three accounts appear in ascending ID order after all accounts with assets.

---

### User Story 3 - Collapsible Empty Accounts Section (Priority: P2)

As a security manager, I want accounts with no assets to be grouped under a collapsible section that is collapsed by default, so the page stays compact and I can expand the section only when I need to review empty accounts.

**Why this priority**: With potentially dozens of empty accounts, a collapsed section significantly reduces page length and keeps focus on actionable content while still providing access to the full account list.

**Independent Test**: Load the Account Vulns page with a mix of accounts with and without assets, and verify a collapsible section heading exists that is collapsed by default and can be toggled open.

**Acceptance Scenarios**:

1. **Given** the page has both accounts with assets and accounts without, **When** the page renders, **Then** a collapsible section heading (e.g., "Accounts with no assets (N)") appears after accounts with assets, collapsed by default.
2. **Given** the empty accounts section is collapsed, **When** the user clicks the section heading or toggle, **Then** the section expands to reveal all empty account cards.
3. **Given** the empty accounts section is expanded, **When** the user clicks the section heading or toggle again, **Then** the section collapses back.
4. **Given** a user has only accounts with assets (no empty accounts), **When** the page loads, **Then** no collapsible section or heading is displayed.
5. **Given** a user has only empty accounts, **When** the page loads, **Then** all accounts are displayed normally without a collapsible wrapper.

---

### Edge Cases

- What happens when an account has assets but zero vulnerabilities? It is treated as an account "with assets" and appears in the top group.
- What happens when a CrowdStrike import adds assets to a previously empty account? On next page load, the account moves from the bottom group to the top group.
- What happens when all assets are removed from an account? On next page load, the account moves to the bottom group.

## Requirements

### Functional Requirements

- **FR-001**: The Account Vulns page MUST display AWS accounts that contain at least one asset before accounts that contain zero assets.
- **FR-002**: Within the group of accounts that have assets, accounts MUST remain sorted by AWS account ID in ascending order.
- **FR-003**: Within the group of accounts that have no assets, accounts MUST remain sorted by AWS account ID in ascending order.
- **FR-004**: When both groups exist on the page, empty accounts MUST be wrapped in a collapsible section with a heading (e.g., "Accounts with no assets (N)") that is collapsed by default.
- **FR-005**: The collapsible section MUST toggle between expanded and collapsed states when the user clicks the heading or toggle control.
- **FR-006-A**: When only one group exists (all accounts have assets, or all accounts are empty), no collapsible section or extra heading MUST be shown.
- **FR-007**: The sorting MUST be based on the presence of assets in the account, not on vulnerability count. An account with assets but zero vulnerabilities belongs in the "with assets" group.
- **FR-008**: The summary statistics at the top of the page (AWS Accounts count, Total Assets, Total Vulnerabilities, By Severity) MUST remain unchanged and continue to reflect all accounts.

## Success Criteria

### Measurable Outcomes

- **SC-001**: Users can identify the first account requiring attention without scrolling past any empty accounts.
- **SC-002**: Page load behavior and performance remain unchanged; the sorting does not require additional network requests.
- **SC-003**: 100% of accounts with assets appear above 100% of accounts without assets on every page load.
- **SC-004**: The empty accounts section is collapsed on page load, reducing visible page length to only accounts with assets plus one section heading.

## Assumptions

- "Empty account" means an account with zero assets (totalAssets equals 0), regardless of whether it previously had assets.
- The existing per-account severity badges (Critical, High, Medium) and asset count displays remain unchanged.
- No new API endpoints or data fields are required; the existing response structure provides sufficient information (totalAssets field) to determine sort order.
- The sorting can be applied either on the frontend or backend; this decision is deferred to the planning phase.
