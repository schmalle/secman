# Feature Specification: IP Address Mapping to Users

**Feature Branch**: `020-i-want-to`
**Created**: 2025-10-15
**Status**: Draft
**Input**: User description: "i want to have the possibilities to map IP adresses, individual ones, ranges also to a user. Please extend the UI and the related structures."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Map Individual IP Addresses to User (Priority: P1)

An administrator needs to map specific IP addresses to users for access control and asset visibility. When assets are discovered with these IP addresses, the user should automatically see them in their account view.

**Why this priority**: This is the foundation of IP-based access control. Individual IP mapping is the simplest case and most commonly used for specific servers or workstations assigned to users.

**Independent Test**: Can be fully tested by creating a single IP mapping (e.g., 192.168.1.100 → user@example.com) and verifying that assets with this IP appear in the user's asset view. Delivers immediate value by enabling basic IP-based access control.

**Acceptance Scenarios**:

1. **Given** an administrator is on the user mapping page, **When** they add a mapping with email "user@example.com" and IP address "192.168.1.100", **Then** the mapping is saved and appears in the mapping list.
2. **Given** a user mapping exists for "192.168.1.100" → "user@example.com", **When** an asset with IP "192.168.1.100" is created or imported, **Then** the user with email "user@example.com" can see this asset in their account view.
3. **Given** an administrator is creating an IP mapping, **When** they enter an invalid IP format (e.g., "999.999.999.999"), **Then** validation fails with a clear error message.
4. **Given** a mapping exists for "192.168.1.100" → "user@example.com", **When** an administrator tries to create a duplicate mapping with the same IP and email, **Then** the system prevents the duplicate and shows an error.

---

### User Story 2 - Map IP Address Ranges to User (Priority: P1)

An administrator needs to map entire IP ranges (e.g., 192.168.1.0/24 or 10.0.0.1-10.0.0.255) to users. This allows bulk assignment of network segments to teams or individuals without creating hundreds of individual mappings.

**Why this priority**: Critical for enterprise environments where users are responsible for entire subnets or IP ranges. Without this, administrators would need to create individual mappings for every IP in a range, which is impractical for /24 or larger networks.

**Independent Test**: Can be fully tested by creating a CIDR range mapping (e.g., 192.168.1.0/24 → user@example.com) and verifying that assets with IPs within this range (192.168.1.1, 192.168.1.50, 192.168.1.255) all appear in the user's account view. Delivers standalone value for subnet-based access control.

**Acceptance Scenarios**:

1. **Given** an administrator is creating a mapping, **When** they enter a CIDR notation range "192.168.1.0/24" with email "user@example.com", **Then** the mapping is saved and all IPs in the range (192.168.1.1 through 192.168.1.254) are associated with the user.
2. **Given** an administrator is creating a mapping, **When** they enter a dash-separated range "10.0.0.1-10.0.0.255" with email "user@example.com", **Then** the mapping is saved and all IPs in the range are associated with the user.
3. **Given** a range mapping exists for "192.168.1.0/24" → "user@example.com", **When** an asset with IP "192.168.1.50" is created, **Then** the user can see this asset in their account view.
4. **Given** an administrator enters an invalid CIDR range (e.g., "192.168.1.0/33"), **When** they submit the form, **Then** validation fails with a clear error explaining valid CIDR notation (0-32 for IPv4).
5. **Given** an administrator enters an invalid dash range (e.g., "10.0.0.255-10.0.0.1" where start > end), **When** they submit the form, **Then** validation fails explaining that the start IP must be less than or equal to the end IP.

---

### User Story 3 - Upload IP Mappings via CSV/Excel (Priority: P2)

An administrator needs to bulk upload IP address mappings via CSV or Excel file, similar to the existing AWS account mapping upload (Features 013/016). This allows efficient migration of existing IP assignments or bulk updates.

**Why this priority**: Enables bulk operations for administrators managing hundreds or thousands of IP mappings. Manual entry for large networks is not scalable. This builds on proven upload patterns from Features 013/016.

**Independent Test**: Can be fully tested by uploading a CSV file with columns [email, ip_address, domain] containing both individual IPs and ranges, then verifying all mappings are created and users can see corresponding assets. Delivers standalone value for bulk IP provisioning.

**Acceptance Scenarios**:

1. **Given** an administrator has a CSV file with columns "email, ip_address, domain" containing valid IP mappings, **When** they upload the file, **Then** all valid mappings are imported and a success message shows the count of imported/skipped rows.
2. **Given** a CSV file contains a mix of individual IPs (192.168.1.100) and CIDR ranges (10.0.0.0/24), **When** the file is uploaded, **Then** both formats are correctly parsed and imported.
3. **Given** a CSV file contains invalid entries (bad email format, invalid IP), **When** the file is uploaded, **Then** invalid rows are skipped with error details, and valid rows are still imported.
4. **Given** a CSV file contains duplicate mappings (within file or existing in database), **When** the file is uploaded, **Then** duplicates are skipped and reported in the import summary.
5. **Given** an administrator clicks the "Download CSV Template" button, **When** the file is downloaded, **Then** it contains example rows with individual IPs and ranges in the correct format.

---

### User Story 4 - View and Manage IP Mappings (Priority: P2)

An administrator needs to view all IP mappings in a searchable, filterable table and perform actions like edit and delete. This allows ongoing maintenance of IP assignments as network topology changes.

**Why this priority**: Essential for data governance and maintenance. Without this, administrators cannot verify existing mappings or correct mistakes. Less critical than creation (P1) but necessary for production use.

**Independent Test**: Can be fully tested by creating several IP mappings, then using the UI to search by email, filter by domain, sort by IP address, and delete obsolete mappings. Delivers standalone value for IP mapping administration.

**Acceptance Scenarios**:

1. **Given** multiple IP mappings exist, **When** an administrator navigates to the user mapping page, **Then** all mappings are displayed in a table with columns for email, IP/range, domain, and created date.
2. **Given** an administrator is viewing the mapping table, **When** they enter an email in the search box, **Then** the table filters to show only mappings for that email.
3. **Given** an administrator is viewing the mapping table, **When** they select a domain from the filter dropdown, **Then** the table shows only mappings for that domain.
4. **Given** an administrator clicks the edit button on a mapping, **When** they change the IP address and save, **Then** the mapping is updated and the new IP is reflected in user access.
5. **Given** an administrator clicks the delete button on a mapping, **When** they confirm the deletion, **Then** the mapping is removed and the user no longer sees assets with that IP.
6. **Given** the mapping table has 100+ entries, **When** an administrator views the table, **Then** pagination controls allow navigating through pages (default 20 per page).

---

### User Story 5 - Combine IP and AWS Account Mappings for Access Control (Priority: P3)

A user may have both IP address mappings and AWS account mappings. The system should combine these to show all assets the user can access - both from their mapped AWS accounts AND from their mapped IP addresses.

**Why this priority**: Nice-to-have enhancement that provides a unified view across different mapping types. Not blocking for initial release since IP and AWS mappings can work independently, but improves user experience by avoiding confusion about "missing" assets.

**Independent Test**: Can be fully tested by creating a user with both an AWS account mapping (e.g., 123456789012) and an IP mapping (e.g., 192.168.1.0/24), then verifying the Account Vulns view shows assets from both sources. Delivers standalone value by ensuring consistent access control logic.

**Acceptance Scenarios**:

1. **Given** a user has mapping for AWS account "123456789012" AND IP range "192.168.1.0/24", **When** they view the Account Vulns page, **Then** they see assets from both the AWS account and the IP range in a unified view.
2. **Given** a user has only IP mappings (no AWS mappings), **When** they view the Account Vulns page, **Then** they see assets grouped by IP range instead of showing "no mappings" error.
3. **Given** a user has both mapping types and assets exist in both, **When** an asset has both a matching cloudAccountId and a matching IP, **Then** it appears only once (no duplicates) in the unified view.

---

### Edge Cases

- What happens when an IP address falls within multiple overlapping ranges (e.g., both 192.168.1.0/24 and 192.168.1.0/25 mapped to different users)? The asset will be visible to all users whose ranges include the IP (most permissive approach).
- What happens when an administrator tries to delete an IP mapping that affects currently visible assets for a user? Should there be a warning about impact? (Nice-to-have: show count of affected assets before confirming deletion)
- How should the system handle IPv6 addresses? IPv4 is supported in MVP with validation stub for IPv6 as a future enhancement.
- What happens when a CSV upload contains a very large range (e.g., 0.0.0.0/8 = 16 million IPs)? Should there be a validation limit on range size? (Recommendation: warn on ranges larger than /16 but allow with confirmation)
- How should the system handle CIDR ranges with host bits set (e.g., 192.168.1.50/24 instead of 192.168.1.0/24)? The system will normalize to network address (192.168.1.0/24) for consistency.
- What happens when an asset has no IP address (null or empty)? It will be excluded from IP-based matching and only accessible via AWS account mappings or workgroup access.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST allow administrators to create IP address mappings linking an IP address (individual or range) to a user email and optional domain.
- **FR-002**: System MUST support individual IP addresses in standard IPv4 notation (e.g., 192.168.1.100).
- **FR-003**: System MUST support IP address ranges in CIDR notation (e.g., 192.168.1.0/24).
- **FR-004**: System MUST support IP address ranges in dash notation (e.g., 192.168.1.1-192.168.1.255).
- **FR-005**: System MUST validate IP address format and reject invalid entries (e.g., 999.999.999.999, malformed CIDR).
- **FR-006**: System MUST validate CIDR notation (prefix length 0-32 for IPv4).
- **FR-007**: System MUST validate dash ranges (start IP ≤ end IP, both in same subnet).
- **FR-008**: System MUST validate email format for all IP mappings.
- **FR-009**: System MUST prevent duplicate mappings (same email + same IP/range + same domain).
- **FR-010**: System MUST allow uploading IP mappings via CSV file with columns: email, ip_address, domain.
- **FR-011**: System MUST allow uploading IP mappings via Excel file with columns: email, ip_address, domain.
- **FR-012**: System MUST handle mixed IP formats in upload files (individual IPs and ranges in same file).
- **FR-013**: System MUST skip invalid rows during upload and provide detailed error reports (row number, reason).
- **FR-014**: System MUST provide downloadable CSV and Excel templates with example IP mapping data.
- **FR-015**: System MUST display all IP mappings in a searchable, filterable, paginated table.
- **FR-016**: System MUST allow administrators to search IP mappings by email address.
- **FR-017**: System MUST allow administrators to filter IP mappings by domain.
- **FR-018**: System MUST allow administrators to edit existing IP mappings.
- **FR-019**: System MUST allow administrators to delete IP mappings with confirmation.
- **FR-020**: System MUST apply IP-based access control to asset visibility (users see assets with IPs in their mapped ranges).
- **FR-021**: System MUST check if an asset's IP falls within any mapped ranges for a user (CIDR and dash range matching).
- **FR-022**: System MUST combine IP-based and AWS-account-based access control (user sees assets from BOTH mapping types).
- **FR-023**: System MUST normalize IP addresses for comparison (strip leading zeros, canonical format).
- **FR-024**: System MUST handle assets with missing/null IP addresses (exclude from IP-based matching).
- **FR-025**: System MUST provide clear error messages for validation failures (invalid format, duplicates, range errors).
- **FR-026**: System MUST support IPv4 addresses with validation stub for IPv6 (future enhancement).
- **FR-027**: System MUST allow overlapping IP ranges and grant access to all users whose ranges include the IP address (most permissive approach).
- **FR-028**: System MUST enforce administrator-only access (ADMIN role) for all IP mapping CRUD operations.

### Key Entities

- **IpMapping**: Represents the mapping between an IP address (or range) and a user. Key attributes:
  - User email (required, validated format)
  - IP address or range (required, validated IPv4 format - single IP, CIDR, or dash range)
  - Domain (optional, defaults to "-NONE-", validated format)
  - Creation timestamp
  - Last updated timestamp
  - Created by (administrator username)

- **IP Range**: Logical representation of an IP range for matching. Key attributes:
  - Start IP (first IP in range)
  - End IP (last IP in range)
  - CIDR notation (if applicable)
  - Count of IPs in range

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Administrators can create an individual IP mapping in under 30 seconds via the UI.
- **SC-002**: Administrators can upload 1000 IP mappings via CSV in under 60 seconds with validation and error reporting.
- **SC-003**: Users see assets matching their IP mappings within 2 seconds of page load in the Account Vulns view.
- **SC-004**: IP range matching (CIDR and dash notation) correctly identifies all IPs within the range with 100% accuracy.
- **SC-005**: System prevents 100% of duplicate IP mappings (same email + IP + domain combinations).
- **SC-006**: Search and filter operations on the IP mapping table return results in under 1 second for up to 10,000 mappings.
- **SC-007**: 95% of administrators successfully create their first IP mapping without requiring support documentation.
- **SC-008**: CSV upload with mixed IP formats (individual + CIDR + dash ranges) has zero failures for valid data.
- **SC-009**: Combined IP and AWS account access control shows complete asset inventory (zero missing assets) for users with both mapping types.
- **SC-010**: IP validation rejects 100% of invalid formats (malformed IPs, invalid CIDR, invalid ranges) before database insertion.

## Assumptions

1. **IP Format**: IPv4 addresses are fully supported in MVP. IPv6 validation stub will be included to allow future enhancement without breaking changes. Most enterprise environments currently use IPv4 for internal assets.
2. **Range Overlap Handling**: Overlapping ranges are explicitly allowed. An asset matching multiple ranges grants access to all associated users (most permissive approach). This maximizes flexibility and reduces administrative burden at the cost of potentially broader access.
3. **Domain Field**: Reusing the domain field from existing UserMapping entity (Feature 013) to maintain consistency. Domain is optional and defaults to "-NONE-".
4. **Access Control Logic**: IP-based access control is ADDITIVE to AWS account-based access (Feature 018). Users see assets from BOTH mapping types combined.
5. **Range Size Limits**: Assuming no hard limit on range size initially, but very large ranges (e.g., /8 = 16M IPs) may impact performance and should be validated during implementation.
6. **CIDR Normalization**: Assuming CIDR ranges with host bits set (e.g., 192.168.1.50/24) will be normalized to network address (192.168.1.0/24) for consistency.
7. **Upload Behavior**: Following the pattern from Feature 016 - skip invalid rows with error reporting, continue importing valid rows, support both CSV and Excel.
8. **UI Location**: IP mapping management will be added to the existing User Mapping page/component from Features 013/016, extending the current upload and table UI.
9. **Role Requirement**: Only ADMIN role can create, edit, delete, or view IP mappings (consistent with AWS account mapping from Feature 013).
10. **Asset Matching**: Assets are matched by their `ip` field from the Asset entity. Assets without an IP address (null or empty) are not visible via IP-based access control.

## Dependencies

- **Feature 013**: User Mapping Excel Upload - Provides the foundation for user mapping data model and upload patterns.
- **Feature 016**: CSV-Based User Mapping Upload - Provides CSV upload infrastructure and validation patterns.
- **Feature 018**: Account Vulns View - This view will need to be extended to support IP-based filtering in addition to AWS account filtering.
- **Asset Entity**: The existing Asset entity must have an `ip` field (already exists based on CLAUDE.md context).
- **UserMapping Entity**: May need to be extended or a new IpMapping entity created (depending on data model design in planning phase).
