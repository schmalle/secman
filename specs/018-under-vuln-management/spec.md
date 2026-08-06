# Feature Specification: Account Vulns - AWS Account-Based Vulnerability Overview

**Feature Branch**: `018-under-vuln-management`
**Created**: 2025-10-14
**Status**: Draft
**Input**: User description: "under Vuln Management i want to have a new sub item called Account vulns, where a user gets an overview of all systems in his mapped AWS accounts and how many vulnerability per system exist. If the user has no mapped AWS accounts (see user_mapping table) show an error. If a user has more than one mapped AWS account, ensure a visible grouping. User with admin roles will get an error message saying "Please use System Vulns view"."

## Clarifications

### Session 2025-10-14

- Q: FR-011 mentions that workgroup-based access control applies "in addition to" AWS account filtering. How should these two filters interact? → A: AWS overrides workgroup: If asset is in user's AWS account, show it regardless of workgroup membership (AWS mapping is sufficient)
- Q: For users with many accounts and assets (e.g., 20 AWS accounts with 50 assets each = 1000 total assets), how should pagination work? → A: Per-account pagination: Each AWS account group has its own "Load more" or pagination controls (e.g., show first 20 assets per account, expandable)
- Q: FR-012 specifies how assets are sorted within each AWS account group (by vulnerability count descending). But when a user has multiple AWS accounts, in what order should the account groups themselves be displayed? → A: By AWS account ID: Numerical/alphabetical order of the 12-digit account IDs
- Q: This is a security-focused feature showing vulnerability data. Should the system log/audit when users access the Account Vulns view? → A: No special logging: Use standard application logging, no specific audit trail for this view
- Q: Since admin users should use the System Vulns view instead, should the "Account Vulns" menu item be visible to them at all? → A: Show with indicator: Menu item visible with a visual indicator (e.g., disabled/grayed out) for admins

## User Scenarios & Testing *(mandatory)*

### User Story 1 - View Vulnerabilities for Single AWS Account (Priority: P1)

A non-admin user with exactly one mapped AWS account accesses the "Account Vulns" view to see all systems in their account and their vulnerability counts at a glance.

**Why this priority**: This is the core MVP - enabling users to see their AWS account's security posture without admin privileges. Most users will have one AWS account mapped.

**Independent Test**: Can be fully tested by creating a test user with one AWS account mapping, adding assets with that cloudAccountId, importing vulnerabilities, and verifying the table displays asset names with vulnerability counts.

**Acceptance Scenarios**:

1. **Given** a logged-in non-admin user with one AWS account mapping (e.g., email@example.com → 123456789012), **When** they navigate to "Vuln Management → Account Vulns", **Then** they see a single table listing all assets where cloudAccountId matches 123456789012, showing asset name, type, and total vulnerability count per asset
2. **Given** the user's AWS account has 5 assets with varying vulnerability counts (0, 3, 10, 25, 100), **When** viewing the Account Vulns page, **Then** each asset row displays its exact vulnerability count
3. **Given** an asset in the user's AWS account has no vulnerabilities, **When** viewing the Account Vulns page, **Then** that asset appears in the list with a vulnerability count of 0

---

### User Story 2 - View Vulnerabilities for Multiple AWS Accounts with Grouping (Priority: P2)

A non-admin user with multiple mapped AWS accounts accesses the "Account Vulns" view and sees their systems clearly grouped by AWS account for easy navigation.

**Why this priority**: Extends the MVP to support power users managing multiple AWS accounts. Essential for enterprises with multi-account setups.

**Independent Test**: Can be tested by creating a test user with 2+ AWS account mappings, adding assets for each account, and verifying the UI groups assets by account with clear visual separation (e.g., collapsible sections, distinct headers, or separate tables).

**Acceptance Scenarios**:

1. **Given** a logged-in non-admin user with three AWS account mappings (123456789012, 987654321098, 555555555555), **When** they navigate to "Account Vulns", **Then** they see three distinct groups, each labeled with the AWS account ID
2. **Given** multiple AWS accounts, **When** viewing the Account Vulns page, **Then** each account group displays a summary (e.g., "AWS Account: 123456789012 - 12 assets, 47 total vulnerabilities") before the asset list
3. **Given** one AWS account has 20 assets and another has 3 assets, **When** viewing the Account Vulns page, **Then** each group shows only the assets belonging to that specific AWS account

---

### User Story 3 - Error Handling for Missing Account Mappings (Priority: P1)

A non-admin user with no AWS account mappings in the user_mapping table attempts to access "Account Vulns" and receives a clear, actionable error message.

**Why this priority**: Critical for preventing confusion - users need to understand why they can't see data and what to do next. Part of MVP to ensure graceful failure.

**Independent Test**: Can be tested by creating a test user with no user_mapping records, accessing the Account Vulns page, and verifying an informative error message appears (e.g., "No AWS accounts are mapped to your user. Please contact your administrator to configure account access.").

**Acceptance Scenarios**:

1. **Given** a logged-in non-admin user with no records in user_mapping table, **When** they navigate to "Account Vulns", **Then** they see an error message stating "No AWS accounts are mapped to your user account. Please contact your administrator."
2. **Given** a user has user_mapping records but with null/empty awsAccountId values, **When** they access "Account Vulns", **Then** they see the same error message as if no mappings exist
3. **Given** a user receives the no-mapping error, **When** viewing the error message, **Then** they see guidance text (e.g., "AWS account mappings are required to view vulnerability data for your infrastructure")

---

### User Story 4 - Admin Role Redirect (Priority: P1)

An administrator attempts to access "Account Vulns" and receives a clear message directing them to use the existing "System Vulns" view instead, which provides full system-wide visibility.

**Why this priority**: Part of MVP - prevents admins from using a limited view when they have access to a more comprehensive one. Maintains clear separation of concerns.

**Independent Test**: Can be tested by logging in as an admin user, navigating to "Account Vulns", and verifying an error/info message appears: "Please use System Vulns view" with a link/button to the System Vulns page.

**Acceptance Scenarios**:

1. **Given** a logged-in user with ADMIN role, **When** they navigate to "Vuln Management → Account Vulns", **Then** they see a message "Please use System Vulns view" and cannot access the Account Vulns data
2. **Given** an admin views the redirect message, **When** they see the message, **Then** they also see a clickable link or button to navigate to the System Vulns view
3. **Given** a user has both ADMIN and USER roles, **When** they access "Account Vulns", **Then** the ADMIN role takes precedence and they see the redirect message

---

### User Story 5 - Asset Navigation and Detail View (Priority: P3)

A user viewing their AWS account vulnerabilities clicks on an asset name to view detailed vulnerability information for that specific system.

**Why this priority**: Enhances usability after the core listing is implemented. Allows users to drill down from overview to details.

**Independent Test**: Can be tested by clicking an asset row in the Account Vulns view and verifying it navigates to the existing asset detail page showing all vulnerabilities for that asset.

**Acceptance Scenarios**:

1. **Given** a user is viewing the Account Vulns page, **When** they click on an asset name, **Then** they navigate to the asset detail page showing full vulnerability information
2. **Given** an asset has 15 vulnerabilities, **When** a user clicks the asset from Account Vulns, **Then** the detail page displays all 15 vulnerabilities with CVE IDs, severity, and other relevant data
3. **Given** a user navigates to an asset detail from Account Vulns, **When** they click "Back" or a breadcrumb, **Then** they return to the Account Vulns view (not the System Vulns view)

---

### Edge Cases

- What happens when a user has an AWS account mapping but no assets exist with that cloudAccountId? (Display "No assets found for AWS account [ID]" message)
- What happens when an asset has a null/empty cloudAccountId but belongs to a user's account? (Assets without cloudAccountId are not displayed in Account Vulns view, only in System Vulns)
- How does the system handle a user with 50+ AWS account mappings? (All account groups displayed; each account group uses per-account pagination if it has more than 20 assets)
- What happens if user_mapping contains duplicate AWS account IDs for the same user email? (Handle gracefully by de-duplicating during query - unique constraint should prevent this at DB level)
- How does the system handle assets that exist in multiple AWS accounts? (Assets are uniquely identified; if cloudAccountId changes, it reflects the current account assignment)
- What happens when vulnerability counts change while the user is viewing the page? (Counts are calculated at page load; user must refresh to see updated counts)

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a new navigation item "Account Vulns" under the "Vuln Management" menu, visible to all authenticated users; for users with ADMIN role, the menu item MUST display a visual indicator (e.g., grayed out, disabled styling, or tooltip) signaling it redirects to System Vulns
- **FR-002**: System MUST query user_mapping table by logged-in user's email to retrieve all associated AWS account IDs (awsAccountId field)
- **FR-003**: System MUST display an error message "No AWS accounts are mapped to your user account. Please contact your administrator." when a non-admin user has no AWS account mappings with non-null awsAccountId values
- **FR-004**: System MUST display an error message "Please use System Vulns view" with a link to the System Vulns page when a user with ADMIN role attempts to access Account Vulns
- **FR-005**: System MUST retrieve all assets where cloudAccountId matches any of the user's mapped AWS account IDs
- **FR-006**: System MUST count the number of vulnerabilities per asset for all retrieved assets
- **FR-007**: System MUST display assets in a table/list format showing: asset name, asset type, and total vulnerability count
- **FR-008**: System MUST visually group assets by AWS account ID when a user has multiple AWS account mappings (e.g., accordion/collapsible sections, separate tables, or distinct headers)
- **FR-008a**: System MUST sort AWS account groups by account ID in numerical/ascending order (e.g., 123456789012 before 987654321098)
- **FR-008b**: System MUST implement per-account pagination controls (when an AWS account has more than 20 assets, display the first 20 with "Load more" or expandable pagination within that account group)
- **FR-009**: System MUST display a group summary for each AWS account showing the account ID, total number of assets, and total vulnerabilities across all assets in that account
- **FR-010**: System MUST allow users to click on an asset name to navigate to the detailed asset view showing all vulnerabilities for that asset
- **FR-011**: System MUST use AWS account mapping as the primary access control for Account Vulns view (if an asset's cloudAccountId matches any of the user's mapped AWS accounts, the user can view it regardless of workgroup membership; workgroup restrictions do not apply to this view)
- **FR-012**: System MUST sort assets within each AWS account group by vulnerability count (highest to lowest) by default
- **FR-013**: System MUST display "No assets found for AWS account [ID]" when an AWS account has no matching assets in the database
- **FR-014**: System MUST exclude assets with null or empty cloudAccountId values from the Account Vulns view (these are only visible in System Vulns)

### Key Entities

- **UserMapping**: Existing entity linking user email to AWS account IDs. Key attributes: email, awsAccountId (12 digits), domain. Used to determine which AWS accounts the current user can view.
- **Asset**: Existing entity representing systems/infrastructure. Key attributes: name, type, ip, owner, cloudAccountId, vulnerabilities (relationship). Filtered by cloudAccountId matching user's AWS account mappings.
- **Vulnerability**: Existing entity representing security vulnerabilities. Relationship: Many vulnerabilities belong to one asset. Used for counting vulnerabilities per asset.
- **User**: Existing entity representing authenticated users. Key attributes: email, roles. Email used to query user_mapping table; roles determine if ADMIN redirect applies.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Non-admin users with AWS account mappings can view their account-specific vulnerability overview in under 3 seconds on page load (for up to 100 assets)
- **SC-002**: Users can identify which AWS account has the most vulnerable systems within 10 seconds of viewing the page
- **SC-003**: 95% of users with multiple AWS accounts can correctly identify which account a specific asset belongs to without confusion
- **SC-004**: Zero cases of admin users being able to bypass the "Please use System Vulns view" restriction
- **SC-005**: Users with no AWS account mappings receive a clear error message 100% of the time, with zero cases of cryptic errors or blank pages
- **SC-006**: Asset vulnerability counts match the actual vulnerability records in the database with 100% accuracy (no counting errors)
- **SC-007**: Page remains responsive and usable for users with up to 50 AWS account mappings and 500 total assets
- **SC-008**: Users can navigate from Account Vulns to asset detail view and back without losing context 100% of the time
