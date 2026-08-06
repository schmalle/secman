# Feature Specification: Products Overview

**Feature Branch**: `054-products-overview`
**Created**: 2025-12-15
**Status**: Draft
**Input**: User description: "Add a 'Products' sidebar item for users with VULN, SECCHAMPION, or ADMIN roles. When selected, display a dropdown to choose a product and show an overview of systems (including IP and domain) that have that product running. Products are extracted from CrowdStrike vulnerability information."

## Clarifications

### Session 2025-12-15

- Q: What file format should be used for exporting the systems list? → A: Excel (.xlsx) - matches existing SECMAN export patterns
- Q: How should large result sets be handled in the systems table? → A: Server-side pagination (e.g., 50 items per page with page controls)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - View Systems Running a Specific Product (Priority: P1)

A security champion or vulnerability manager wants to quickly identify all systems in their environment that have a specific software product installed. They navigate to the Products section in the sidebar, select a product from the dropdown (populated from CrowdStrike vulnerability data), and see a list of all systems running that product with their IP addresses and domains.

**Why this priority**: This is the core value proposition of the feature - enabling users to answer "Where is product X running?" which is essential for vulnerability triage, patch planning, and software lifecycle management. Leverages existing CrowdStrike data without requiring manual product entry.

**Independent Test**: Can be fully tested by selecting a product from the dropdown and verifying the displayed system list matches expected assets with vulnerabilities for that product.

**Acceptance Scenarios**:

1. **Given** a user with VULN, SECCHAMPION, or ADMIN role is logged in, **When** they expand the Vulnerability Management section in the sidebar, **Then** they see a "Products" menu item.

2. **Given** a user clicks on "Products" in the sidebar, **When** the Products page loads, **Then** they see a dropdown containing all unique product names extracted from vulnerability data, sorted alphabetically.

3. **Given** a user selects a product from the dropdown, **When** the selection is made, **Then** the page displays a table showing all systems with that product, including columns for: System Name, IP Address, Domain.

4. **Given** a user with VULN role (non-admin), **When** they view products, **Then** they only see systems they have access to (based on workgroup membership, cloud account mappings, or AD domain mappings).

5. **Given** assets exist with vulnerabilities containing "OpenSSL 1.0.2k" in the vulnerableProductVersions field, **When** a user selects "OpenSSL 1.0.2k" from the dropdown, **Then** all assets with that product are displayed.

---

### User Story 2 - Search and Filter Products (Priority: P2)

A user needs to find a specific product among potentially hundreds of products. They can type in a search field to filter the product dropdown to quickly locate the product they're looking for.

**Why this priority**: With many products imported from CrowdStrike, users need efficient filtering to find specific products without scrolling through a long list.

**Independent Test**: Can be tested by typing a partial product name and verifying the dropdown filters to matching products only.

**Acceptance Scenarios**:

1. **Given** a user is on the Products page with many products available, **When** they type "Apache" in the product search field, **Then** only products containing "Apache" are shown in the dropdown.

2. **Given** a user has filtered products by search, **When** they clear the search field, **Then** all products are shown again.

---

### User Story 3 - Export Product Systems List (Priority: P3)

A security champion needs to share the list of systems running a specific product with a team that doesn't have SECMAN access. They can export the filtered systems list to a spreadsheet format.

**Why this priority**: Enables collaboration with external teams and offline analysis, but the primary value is the in-application view.

**Independent Test**: Can be tested by selecting a product, clicking export, and verifying the downloaded file contains the displayed system data.

**Acceptance Scenarios**:

1. **Given** a user has selected a product and systems are displayed, **When** they click "Export", **Then** a file is downloaded containing the system list with Name, IP, and Domain columns.

---

### Edge Cases

- What happens when a user selects a product that has no associated systems in their access scope? Display an empty state message: "No systems found running this product."
- What happens when no vulnerability data has been imported yet? Display a message: "No products available. Products are discovered automatically from CrowdStrike vulnerability imports."
- How does the system handle different product/version formats from CrowdStrike? Display the exact `vulnerableProductVersions` value as the product name without modification.
- What happens when the same asset has multiple vulnerabilities with the same product? The asset appears once in the results (deduplicated).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST display "Products" menu item in the Vulnerability Management sidebar section for users with VULN, SECCHAMPION, or ADMIN roles.
- **FR-002**: System MUST extract unique product names from the `vulnerableProductVersions` field in vulnerability data.
- **FR-003**: System MUST provide a searchable dropdown selector listing all unique products alphabetically.
- **FR-004**: System MUST display a table of systems running the selected product with columns: System Name, IP Address, Domain.
- **FR-005**: System MUST respect existing access control rules when displaying systems (workgroup membership, cloud account mappings, AD domain mappings per unified access control).
- **FR-006**: System MUST deduplicate assets when the same asset has multiple vulnerabilities with the same product.
- **FR-007**: System MUST display informative empty state when no systems match the selected product.
- **FR-008**: System MUST display informative message when no products are available (no vulnerability data imported).
- **FR-009**: System MUST allow users to export the displayed systems list to Excel (.xlsx) format.
- **FR-010**: Product search/filter MUST support case-insensitive partial matching.
- **FR-011**: System MUST use server-side pagination for the systems table with page controls (default 50 items per page).

### Key Entities *(include if feature involves data)*

- **No new entities required**: This feature aggregates existing data from the `Vulnerability.vulnerableProductVersions` field and the `Asset` entity. Products are derived dynamically, not stored as separate entities.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can find all systems running a specific product within 3 clicks from the dashboard (expand Vulnerability Management, click Products, select product).
- **SC-002**: Products page loads with product list within 2 seconds for environments with up to 10,000 assets.
- **SC-003**: 95% of users can successfully locate a product and view its systems on first attempt.
- **SC-004**: Product search filters results in under 500ms for up to 5,000 unique products.
- **SC-005**: Export generates a downloadable file within 5 seconds for up to 10,000 systems.

## Assumptions

- Products are extracted from the existing `vulnerableProductVersions` field in CrowdStrike vulnerability imports - no new data collection is needed.
- The `vulnerableProductVersions` field contains product/version strings that are meaningful to users (e.g., "OpenSSL 1.0.2k", "Apache HTTP Server 2.4.6").
- The feature follows existing SECMAN design patterns including Bootstrap styling, table layouts, and dropdown components.
- Access control follows the existing unified access control pattern (admin universal access, workgroup membership, manual creator, scan uploader, cloud account mappings, AD domain mappings).
- The "Products" menu item placement under "Vulnerability Management" follows the user's request and the existing sidebar structure pattern.
