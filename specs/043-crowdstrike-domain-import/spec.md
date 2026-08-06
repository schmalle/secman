# Feature Specification: CrowdStrike Domain Import Enhancement

**Feature Branch**: `043-crowdstrike-domain-import`
**Created**: 2025-11-08
**Status**: Draft
**Input**: User description: "the Crowdstrike API import must ensure, that if i import vulnerabilities and assets, also the domain information is stored in the asset table. Also ad_domain must be editable in asset inventory UI. If a new import scan is run and an asset entry is already existing, only update fields, which have been changed. E.g. the missing active directory domain. At the import end also show how many different domain and which ones have been imported."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Domain Capture During Import (Priority: P1)

An administrator imports vulnerability and asset data from CrowdStrike Falcon API. The system automatically captures Active Directory domain information for each asset and stores it in the asset table. Upon import completion, the administrator sees a summary showing the count of unique domains discovered and a list of domain names that were imported.

**Why this priority**: This is the core functionality that enables domain-based asset organization and access control. Without capturing domain data during import, administrators cannot leverage domain-based filtering or user access mappings.

**Independent Test**: Can be fully tested by running a CrowdStrike import with assets that have AD domain information, then verifying the asset table contains domain data and the import summary displays domain statistics.

**Acceptance Scenarios**:

1. **Given** the CrowdStrike Falcon API returns assets with Active Directory domain information, **When** an administrator runs a vulnerability/asset import, **Then** the system captures and stores the AD domain for each asset in the asset table's `ad_domain` field
2. **Given** a CrowdStrike import has completed successfully, **When** the administrator views the import results, **Then** the system displays a summary showing the count of unique domains discovered (e.g., "3 unique domains") and lists each domain name (e.g., "CONTOSO, FABRIKAM, CORP")
3. **Given** some assets have domain information and some do not, **When** the import completes, **Then** assets without domain data have null/empty `ad_domain` values and the summary only counts domains that were actually present

---

### User Story 2 - Manual Domain Editing (Priority: P2)

An administrator views the Asset Management interface and needs to correct or add Active Directory domain information for an asset. They can edit the `ad_domain` field directly in the asset inventory UI, providing flexibility to manage domain assignments manually when automated imports miss or incorrectly capture domain data.

**Why this priority**: Manual editing is essential for data quality and correction scenarios, but depends on P1 domain capture working first. Administrators need the ability to fix misclassified assets or add domain information for manually created assets.

**Independent Test**: Can be tested by opening an existing asset in the Asset Management UI, editing the AD domain field, saving, and verifying the change persists.

**Acceptance Scenarios**:

1. **Given** an asset exists in the Asset Management interface, **When** an administrator clicks to edit the asset details, **Then** the `ad_domain` field is visible and editable
2. **Given** an administrator is editing an asset's domain field, **When** they enter a valid domain name and save, **Then** the system updates the `ad_domain` value and displays a confirmation
3. **Given** an asset has no domain assigned (null), **When** an administrator manually enters a domain and saves, **Then** the asset is associated with that domain and becomes accessible to users with matching domain mappings
4. **Given** an administrator enters an invalid domain format, **When** they attempt to save, **Then** the system displays a validation error with guidance on acceptable domain formats

---

### User Story 3 - Smart Update for Existing Assets (Priority: P1)

An administrator runs a subsequent CrowdStrike import scan after assets already exist in the system. The system intelligently updates only the fields that have changed, such as adding previously missing Active Directory domain information, while preserving other manually edited or existing data. This prevents data loss and respects manual corrections made by administrators.

**Why this priority**: Essential for maintaining data integrity across multiple imports. Without smart updates, administrators risk losing manual edits or having accurate data overwritten with incomplete information from API responses.

**Independent Test**: Can be tested by importing assets without domain data, manually editing some fields, then re-importing with domain data present and verifying only the domain field was updated while other fields remained unchanged.

**Acceptance Scenarios**:

1. **Given** an asset exists in the database without an AD domain, **When** a new import scan includes domain information for that asset, **Then** the system updates only the `ad_domain` field while preserving all other asset attributes (name, IP, owner, groups, etc.)
2. **Given** an asset was manually edited with a custom domain, **When** a new import provides different domain data, **Then** the system updates the domain to match the latest import data (latest API data takes precedence)
3. **Given** an asset exists with complete data including domain, **When** a new import has the same values, **Then** no database update occurs (avoid unnecessary write operations)
4. **Given** multiple fields have changed in the API data, **When** an import runs, **Then** only the fields with different values are updated in a single database operation

---

### Edge Cases

- What happens when the CrowdStrike API returns domain information in unexpected formats (e.g., FQDN vs NetBIOS name)?
- How does the system handle domain names with special characters or very long domain names exceeding field length limits?
- What happens if an asset has multiple domain associations in the CrowdStrike API (e.g., multi-homed systems)?
- How does the system handle domain case sensitivity (e.g., "CONTOSO" vs "contoso" vs "Contoso")?
- What happens when an import fails mid-process - are partial domain updates rolled back?
- How does the system display domain statistics when zero domains were found in the import?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST extract Active Directory domain information from CrowdStrike Falcon API responses during vulnerability and asset imports
- **FR-002**: System MUST store domain information in the `ad_domain` field of the asset table with support for null values when domain is not available
- **FR-003**: System MUST normalize domain names to a consistent format (case-insensitive comparison, stored in uppercase for consistency)
- **FR-004**: Asset Management UI MUST display the `ad_domain` field as an editable text input field in the asset edit form
- **FR-005**: Asset Management UI MUST validate domain field input to ensure it matches acceptable domain name patterns (alphanumeric characters, dots, hyphens)
- **FR-006**: System MUST implement smart update logic that compares existing asset data with incoming API data field-by-field during imports
- **FR-007**: System MUST update only fields that have changed values during re-import of existing assets, preserving unchanged fields
- **FR-008**: System MUST track domain discovery statistics during import, counting unique domains and collecting domain names
- **FR-009**: Import results MUST display a summary section showing the count of unique domains discovered (e.g., "5 unique domains found")
- **FR-010**: Import results MUST display a list of all unique domain names discovered during the import (e.g., "Domains: CONTOSO, FABRIKAM, CORP, FINANCE, SALES")
- **FR-011**: System MUST handle assets without domain information gracefully by storing null or empty values without failing the import
- **FR-012**: System MUST preserve manually edited asset fields when performing smart updates during subsequent imports

### Key Entities

- **Asset**: Represents a network asset discovered via CrowdStrike or manual entry. Core attributes include name, IP address, type, owner, workgroups, cloud identifiers, and **Active Directory domain** (`ad_domain`). The domain field is optional (nullable) and used for access control via user domain mappings.
- **Import Result**: Represents the outcome of a CrowdStrike vulnerability/asset import operation. Includes statistics such as total assets processed, vulnerabilities imported, errors encountered, and **domain discovery summary** (unique domain count and domain name list).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Administrators can complete a CrowdStrike import and see accurate domain information stored for at least 95% of assets that have domain data available in the Falcon API
- **SC-002**: Import summary displays domain statistics (count and list) within 1 second of import completion for imports containing up to 10,000 assets
- **SC-003**: Administrators can manually edit an asset's domain field in the Asset Management UI and see the change reflected immediately without page refresh
- **SC-004**: Subsequent imports of the same assets complete 30% faster due to smart update logic that skips unchanged fields
- **SC-005**: Zero data loss occurs during re-imports - manually edited fields remain unchanged when not provided in the new import data
- **SC-006**: 100% of import operations display domain statistics in the results summary, even when zero domains are discovered

## Assumptions

- CrowdStrike Falcon API includes Active Directory domain information in the device/asset response payload
- Domain names in the API follow standard naming conventions (alphanumeric, dots, hyphens)
- The existing asset merge logic can be extended to support field-level comparison without performance degradation
- Domain names are case-insensitive and can be normalized to uppercase for consistency
- The Asset Management UI already has an edit form where the domain field can be added
- Import operations are transactional and can be rolled back if errors occur

## Dependencies

- Existing CrowdStrike API integration (Feature 032: servers-query-import)
- Asset entity and asset table schema (existing `ad_domain` column)
- Asset Management UI (existing asset edit capabilities)
- User Mapping feature (Feature 042: domain-based access control depends on accurate domain data)

## Out of Scope

- Automatic domain discovery for assets not managed by CrowdStrike
- Historical tracking of domain changes over time
- Bulk domain editing for multiple assets simultaneously
- Domain validation against Active Directory services
- Automated domain correction or suggestion based on patterns
- Multi-domain support for assets that belong to multiple AD domains
