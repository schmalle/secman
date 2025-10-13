# Feature Specification: CSV-Based User Mapping Upload

**Feature Branch**: `016-i-want-to`
**Created**: 2025-10-13
**Status**: Draft
**Input**: User description: "i want to add an additional upload functionality for json based user mapping files (see /Users/flake/Downloads/accounts.aws.short.csv). The logic must be able to extract accound_id and owner_email and populate the mapping entries already existing. Ensure the upload functioality is added at /admin/user-mappings next to the existing Excel / xlsx upload functionality."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Upload CSV User Mappings (Priority: P1)

As a system administrator, I need to upload user-to-AWS-account mappings from CSV files exported from AWS Organizations so that I can bulk import mapping data in the format already used by our cloud infrastructure team, without needing to manually reformat into Excel.

**Why this priority**: Enables admins to use existing CSV exports from AWS Organizations directly, eliminating manual data transformation and reducing errors. This is the core value proposition of the feature.

**Independent Test**: Can be fully tested by uploading a CSV file with `account_id` and `owner_email` columns, verifying mappings are created in database, and confirming success message shows correct import counts.

**Acceptance Scenarios**:

1. **Given** I am logged in as an administrator, **When** I navigate to Admin → User Mappings and click "Upload CSV", **Then** I see a file selection dialog
2. **Given** I select a valid CSV file with `account_id` and `owner_email` columns, **When** I submit the upload, **Then** the system creates UserMapping records for each valid row
3. **Given** the CSV contains 10 valid rows, **When** the upload completes, **Then** I see "Successfully imported 10 mappings"
4. **Given** the CSV contains mixed valid and invalid rows (8 valid, 2 invalid), **When** the upload completes, **Then** I see "Imported: 8, Skipped: 2" with error details

---

### User Story 2 - Handle CSV Format Variations (Priority: P2)

As a system administrator, I need the CSV upload to work with different column orders and header names so that I can upload files from various sources without manually editing column names or order.

**Why this priority**: Improves usability by accepting real-world CSV variations. Secondary to basic upload functionality.

**Independent Test**: Upload CSV files with columns in different orders (e.g., `owner_email,account_id` vs `account_id,owner_email`) and verify both work correctly.

**Acceptance Scenarios**:

1. **Given** a CSV with columns in order `account_id,owner_email`, **When** I upload it, **Then** mappings are created correctly
2. **Given** a CSV with columns in order `owner_email,account_id`, **When** I upload it, **Then** mappings are created correctly
3. **Given** a CSV with additional columns (e.g., `display_name,account_id,owner_email,status`), **When** I upload it, **Then** only `account_id` and `owner_email` are extracted and used

---

### User Story 3 - Download CSV Template (Priority: P3)

As a system administrator, I need to download a CSV template showing the required format so that I can understand the expected structure and create new mapping files correctly.

**Why this priority**: Nice-to-have for user guidance, but not critical since CSV format is simpler than Excel.

**Independent Test**: Click "Download CSV Template" button, open the downloaded file, verify it has correct headers and one sample row.

**Acceptance Scenarios**:

1. **Given** I am on the User Mappings upload page, **When** I click "Download CSV Template", **Then** I receive a file named `user-mapping-template.csv`
2. **Given** I open the downloaded template, **When** I inspect the contents, **Then** I see headers `account_id,owner_email` and one example row `123456789012,user@example.com` (domain column is optional and defaults to "-NONE-" if omitted)

---

### Edge Cases

- **What happens when CSV has only headers but no data rows?** System displays error "No data rows found in file"
- **What happens when CSV has invalid encoding (non-UTF-8)?** System attempts to read with fallback encoding (ISO-8859-1), or displays error if unable to parse
- **What happens when account_id column contains scientific notation (e.g., 9.98987E+11)?** System parses numeric value and validates it's exactly 12 digits
- **What happens when CSV file is empty (0 bytes)?** System displays error "Empty file uploaded"
- **What happens when CSV has duplicate mappings within the same file?** System processes first occurrence, skips subsequent duplicates with warning
- **What happens when owner_email column is empty for some rows?** System skips those rows and reports "Skipped 2 rows: missing owner_email"
- **What happens when both CSV and Excel upload buttons are visible?** Both work independently; user can choose either format

## Requirements *(mandatory)*

### Functional Requirements

#### Data Extraction (FR-EXT)

- **FR-EXT-001**: System MUST extract AWS account ID from column named `account_id` (case-insensitive header matching)
  - **Rationale**: Match AWS Organizations export format
  - **Acceptance**: CSV with header "account_id", "Account_ID", or "ACCOUNT_ID" all work correctly

- **FR-EXT-002**: System MUST extract email address from column named `owner_email` (case-insensitive header matching)
  - **Rationale**: Match AWS Organizations export format
  - **Acceptance**: CSV with header "owner_email", "Owner_Email", or "OWNER_EMAIL" all work correctly

- **FR-EXT-003**: System MUST handle AWS account IDs in scientific notation format (e.g., 9.98987E+11)
  - **Rationale**: Excel exports large numbers in scientific notation
  - **Acceptance**: "9.98987E+11" is correctly parsed as "998986922434"

- **FR-EXT-004**: System MUST assign domain value "-NONE-" for all CSV uploads that lack a domain column
  - **Rationale**: UserMapping entity requires domain field per Feature 013 schema; CSV files from AWS Organizations do not include domain information
  - **Acceptance**: All mappings created from CSV upload have domain = "-NONE-"; If CSV includes optional `domain` column, use that value instead

- **FR-EXT-005**: System MUST ignore all columns except `account_id`, `owner_email`, and optionally `domain`
  - **Rationale**: CSV may contain extra metadata columns not needed for mapping
  - **Acceptance**: CSV with 11 columns processes successfully using only required columns

#### File Processing (FR-CSV)

- **FR-CSV-001**: System MUST accept CSV files with .csv extension
  - **Rationale**: Standard CSV file format
  - **Acceptance**: File upload accepts .csv files

- **FR-CSV-002**: System MUST reject non-CSV file uploads when using CSV upload button
  - **Rationale**: Security and data integrity
  - **Acceptance**: Attempting to upload .xlsx, .txt, or other formats returns error "Invalid file type: expected CSV"

- **FR-CSV-003**: System MUST reject CSV files larger than 10MB
  - **Rationale**: Consistency with Excel upload limit, resource protection
  - **Acceptance**: Files > 10MB return error "File size exceeds maximum limit of 10MB"

- **FR-CSV-004**: System MUST require headers `account_id` and `owner_email` (flexible order, case-insensitive)
  - **Rationale**: Data consistency
  - **Acceptance**: CSV missing either header returns error listing missing columns

- **FR-CSV-005**: System MUST support comma as primary delimiter, with automatic detection for semicolon or tab
  - **Rationale**: Different systems export CSVs with different delimiters
  - **Acceptance**: CSV with comma, semicolon, or tab delimiters all parse correctly

- **FR-CSV-006**: System MUST handle quoted fields with embedded commas
  - **Rationale**: Standard CSV escaping
  - **Acceptance**: Field value `"Smith, John"` is parsed as single value "Smith, John"

- **FR-CSV-007**: System MUST skip empty rows
  - **Rationale**: CSV files often have trailing empty lines
  - **Acceptance**: Empty rows are ignored without errors

- **FR-CSV-008**: System MUST parse CSV with UTF-8 encoding by default, with fallback to ISO-8859-1
  - **Rationale**: Handle international characters and legacy files
  - **Acceptance**: CSV with unicode characters (ü, é, ñ) parses correctly

- **FR-CSV-009**: System MUST process valid rows and skip invalid rows with detailed reporting
  - **Rationale**: Partial success better than all-or-nothing
  - **Acceptance**: Upload result shows count of imported, skipped, and reasons for skips

#### Data Validation (FR-VAL)

- **FR-VAL-001**: System MUST validate email address format (same rules as Excel upload)
  - **Rationale**: Consistency with existing upload feature
  - **Acceptance**: Same validation logic as Feature 013 Excel upload

- **FR-VAL-002**: System MUST validate AWS account ID format (12-digit numeric string after parsing)
  - **Rationale**: AWS account IDs are always 12 digits
  - **Acceptance**: Non-numeric or wrong-length values are rejected

- **FR-VAL-003**: System MUST normalize email addresses to lowercase
  - **Rationale**: Case-insensitive email matching
  - **Acceptance**: "User@Example.COM" is stored as "user@example.com"

- **FR-VAL-004**: System MUST trim whitespace from all fields
  - **Rationale**: Data quality
  - **Acceptance**: " user@example.com " is stored as "user@example.com"

- **FR-VAL-005**: System MUST reject rows with empty/null values in required fields
  - **Rationale**: Complete data required
  - **Acceptance**: Row with empty account_id or owner_email is skipped

- **FR-VAL-006**: System MUST detect and skip duplicate mappings (same as Excel upload behavior)
  - **Rationale**: Consistency with existing feature
  - **Acceptance**: Duplicate mapping is skipped with "duplicate" reason in report

#### Access Control (FR-AC)

- **FR-AC-001**: CSV upload MUST be restricted to users with ADMIN role
  - **Rationale**: Same access control as Excel upload
  - **Acceptance**: Non-admin users receive 403 Forbidden when accessing CSV upload endpoint

- **FR-AC-002**: CSV upload endpoint MUST require authentication
  - **Rationale**: Security baseline
  - **Acceptance**: Unauthenticated requests receive 401 Unauthorized

#### User Interface (FR-UI)

- **FR-UI-001**: System MUST add "Upload CSV" button next to existing "Upload Excel" button on `/admin/user-mappings` page
  - **Rationale**: Multiple format options for admin convenience
  - **Acceptance**: User Mappings page shows both upload buttons side by side

- **FR-UI-002**: CSV upload button MUST open separate file picker that filters for .csv files
  - **Rationale**: Clear format distinction
  - **Acceptance**: File picker shows only .csv files (or all files with .csv as default)

- **FR-UI-003**: Upload result display MUST use same format for both CSV and Excel uploads
  - **Rationale**: Consistent user experience
  - **Acceptance**: Result message shows "Imported: X, Skipped: Y" with error details in same format

- **FR-UI-004**: System MUST provide "Download CSV Template" link next to "Download Excel Template" link
  - **Rationale**: User guidance for both formats
  - **Acceptance**: Template download provides correctly formatted CSV file

- **FR-UI-005**: CSV upload interface MUST display file requirements (format, max size, required columns)
  - **Rationale**: User guidance
  - **Acceptance**: Help text visible on upload page shows CSV-specific requirements

#### Integration (FR-INT)

- **FR-INT-001**: CSV upload MUST create UserMapping entities identical to Excel upload
  - **Rationale**: Consistent data model regardless of upload format
  - **Acceptance**: UserMapping records from CSV and Excel uploads are indistinguishable

- **FR-INT-002**: CSV upload MUST use same duplicate detection logic as Excel upload
  - **Rationale**: Consistent behavior
  - **Acceptance**: Uploading same mapping via CSV then Excel (or vice versa) correctly detects duplicate

- **FR-INT-003**: CSV upload MUST respect same 10MB file size limit as Excel upload
  - **Rationale**: Fair resource allocation
  - **Acceptance**: Both formats have identical size limits

### Key Entities *(existing entities, no changes)*

### UserMapping (Feature 013 - No Schema Changes)

**Purpose**: Store many-to-many relationships between user emails, AWS account IDs, and domains for role-based access control

**Core Attributes**:
- **email**: String (normalized lowercase, validated format, indexed) - User's email address
- **awsAccountId**: String (12-digit numeric, indexed) - AWS account identifier
- **domain**: String (validated format, indexed) - Organizational domain
- **createdAt**: Timestamp (auto-populated) - Audit trail
- **updatedAt**: Timestamp (auto-updated) - Last modification timestamp

**Note**: CSV upload creates UserMapping records using the same entity structure as Excel upload. No schema changes required. CSV uploads without domain column will use "-NONE-" as the domain value. Optional domain column in CSV allows explicit domain specification if needed.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Administrators can upload 1000-row CSV files in under 10 seconds (same performance as Excel upload)
  - **Verification**: Upload 1000-row CSV, measure time from submit to result display

- **SC-002**: Over 95% of valid CSV rows are successfully imported on first attempt
  - **Verification**: Upload test CSV with 100 valid rows, verify >= 95 imported

- **SC-003**: Administrators can switch between CSV and Excel upload without confusion
  - **Verification**: User testing shows admins understand both buttons serve same purpose with different formats

- **SC-004**: CSV upload handles scientific notation AWS account IDs correctly 100% of the time
  - **Verification**: Upload CSV with account IDs in scientific notation (e.g., 9.98987E+11), verify all parse to correct 12-digit values

- **SC-005**: Error messages for skipped rows are actionable within 30 seconds of reading
  - **Verification**: User testing shows admins can identify and fix errors based on report

- **SC-006**: CSV template download provides working example that uploads successfully without modification
  - **Verification**: Download template, upload it immediately, verify import succeeds

## Assumptions *(documented decisions)*

1. **CSV Format Standard**: Assuming RFC 4180 CSV format (comma-separated, quoted strings for embedded delimiters)
2. **Character Encoding**: Assuming UTF-8 as primary encoding with ISO-8859-1 fallback for legacy files
3. **Header Row**: Assuming first row contains headers (no headerless CSV support)
4. **Delimiter Detection**: Assuming automatic detection of delimiter (comma, semicolon, tab) based on first line analysis
5. **Scientific Notation**: Assuming Excel-style scientific notation (e.g., 9.98987E+11) for large numbers
6. **Duplicate Handling**: Assuming same behavior as Excel upload (skip duplicates, report count)
7. **File Size Limit**: Assuming same 10MB limit as Excel upload for consistency
8. **Column Mapping**: Assuming exact column name matching for `account_id` and `owner_email` (case-insensitive)
9. **Error Reporting**: Assuming same error reporting format as Excel upload for consistency
10. **Domain Field**: Using sentinel value "-NONE-" for domain when CSV lacks domain column; allows optional domain column for future flexibility

## Out of Scope (Phase 1)

1. **JSON Format Support**: Despite user's mention of "JSON," the sample file is CSV; JSON import is not included
2. **Automatic Domain Extraction**: No attempt to infer domain from email address (e.g., extract "covestro.com" from "user@covestro.com")
3. **CSV Export**: Only import is provided; exporting mappings to CSV is future work
4. **Bulk Update via CSV**: Only create/upsert supported; cannot delete mappings via CSV
5. **CSV Validation Before Upload**: No client-side CSV parsing; all validation happens server-side
6. **Progress Bar for Large Files**: No upload progress indicator in Phase 1
7. **CSV Format Auto-Detection**: No automatic format detection between Excel and CSV based on content
8. **Custom Column Mapping UI**: Admin cannot remap CSV columns (e.g., "email" → "owner_email")
9. **Multi-File Upload**: Cannot upload multiple CSV files simultaneously
10. **CSV Preview**: No preview of CSV contents before processing
11. **Incremental Upload**: Cannot resume failed uploads; must re-upload entire file

## Dependencies

- Existing UserMapping entity and repository (Feature 013)
- CSV parsing capability (standard library or parsing utility)
- Existing Excel upload infrastructure for consistency in error handling and reporting
- Existing ADMIN role authentication and authorization
- Database schema from Feature 013 (no changes required)

## Future Enhancements (Post-Phase 1)

1. **JSON Format Support**: Add actual JSON import if needed (user mentioned JSON but provided CSV)
2. **Domain Auto-Extract**: Option to automatically populate domain from email domain part
3. **CSV Export**: Download existing mappings as CSV file
4. **Bulk Delete via CSV**: Mark rows for deletion in CSV file
5. **Custom Column Mapping**: UI to map arbitrary CSV columns to required fields
6. **Upload History**: Log of all CSV/Excel uploads with timestamp, user, file name, results
7. **Multi-File Upload**: Select and process multiple CSV files in one operation
8. **CSV Preview**: Show first 10 rows before committing import
9. **Smart Duplicate Handling**: Options for update-if-exists vs skip-if-exists behavior
10. **Scheduled Imports**: Automated CSV import from network location or cloud storage

---

## Review Checklist

- [x] User scenarios are testable and complete
- [x] Functional requirements are measurable
- [x] Key entities identified (references existing UserMapping entity)
- [x] Out-of-scope items explicitly listed
- [x] All [NEEDS CLARIFICATION] items resolved (domain field uses "-NONE-" sentinel value)
- [x] Success metrics defined and measurable
- [x] Dependencies identified
- [x] Future enhancements scoped separately
- [x] No implementation details leaked (no mention of specific frameworks, languages, or APIs)
- [x] Acceptance criteria are objective

---

## Approval

- [ ] Product Owner: _______________
- [ ] Tech Lead: _______________
- [ ] QA Lead: _______________
- [ ] Date: _______________
