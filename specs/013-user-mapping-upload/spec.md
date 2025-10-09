# Feature Specification: User Mapping with AWS Account & Domain Upload

**Feature Branch**: `013-user-mapping-upload`
**Created**: 2025-10-07
**Status**: Draft
**Input**: User description: "i need to be able to upload usermappings, meaning a user defined by an email address can be mapped to various aws account ids and to various domains. This information can later be used for role based access. in a first step i need a domain class for it and an upload functions for an excel file, which has line by line the structure emailadress, aws account id, domain. The upload functions must only be made available in the Admin/General area."

## Execution Flow (main)
```
1. Parse user description from Input
   → Identified: UserMapping entity, Excel upload, email+AWS account+domain mapping, Admin-only access
2. Extract key concepts from description
   → Identified: Multi-tenant mapping, future RBAC integration, bulk import via Excel
3. For each unclear aspect:
   → Mark with [NEEDS CLARIFICATION: specific question]
4. Fill User Scenarios & Testing section
   → User flows identified for upload and viewing mappings
5. Generate Functional Requirements
   → Each requirement must be testable
   → Mark ambiguous requirements
6. Identify Key Entities (if data involved)
7. Run Review Checklist
   → If any [NEEDS CLARIFICATION]: WARN "Spec has uncertainties"
   → If implementation details found: ERROR "Remove tech details"
8. Return: SUCCESS (spec ready for planning)
```

---

## ⚡ Quick Guidelines
- ✅ Focus on WHAT users need and WHY
- ❌ Avoid HOW to implement (no tech stack, APIs, code structure)
- 👥 Written for business stakeholders, not developers

---

## Clarifications

### Session 2025-10-07
- Q: Can a single email address have multiple AWS account IDs? → **A: YES** - One email can map to multiple AWS accounts (many-to-many relationship implied)
- Q: Can a single email address have multiple domains? → **A: YES** - One email can map to multiple domains (many-to-many relationship implied)
- Q: What is the expected Excel file format? → **A: Three columns per row: Email Address, AWS Account ID, Domain** (one mapping per row, repeating email for multiple mappings)
- Q: Should we validate email format? → **A: YES** - Basic email format validation
- Q: Should we validate AWS account ID format? → **A: YES** - AWS account IDs are 12-digit numeric strings
- Q: Should we validate domain format? → **A: YES** - Basic domain format validation (alphanumeric, dots, hyphens)
- Q: What happens with duplicate mappings in the file? → **A: Skip duplicates** - If same email+AWS account+domain already exists, skip it
- Q: Should we link UserMapping.email to existing User.email? → **A: OPTIONAL** - UserMapping is independent; may reference non-existent users (for future onboarding)
- Q: What happens when uploading a new file? → **A: ADDITIVE** - New mappings are added; existing mappings remain unless explicitly present in the file (upsert behavior)
- Q: Should there be a UI to view/manage individual mappings? → **DEFERRED** - Phase 1 focuses on upload only; CRUD UI is future work
- Q: What access control applies to viewing mappings? → **A: ADMIN-only** - Only administrators can upload and view mappings

---

## User Scenarios & Testing

### Primary User Story
As a system administrator, I need to upload user-to-AWS-account-to-domain mappings in bulk via Excel so that I can configure multi-tenant access control for future role-based access features, allowing users to be associated with specific AWS accounts and domains.

### Acceptance Scenarios

#### Scenario 1: Upload Valid Mapping File
1. **Given** I am logged in as an administrator
2. **When** I navigate to Admin → User Mappings
3. **And** I upload an Excel file with columns: Email Address, AWS Account ID, Domain
4. **And** all rows contain valid data
5. **Then** the system creates or updates all mappings
6. **And** displays a success message with count of imported mappings

#### Scenario 2: Upload with Invalid Email
1. **Given** I am logged in as an administrator
2. **When** I upload an Excel file containing a row with invalid email format (e.g., "notanemail")
3. **Then** the system skips that row
4. **And** continues processing other valid rows
5. **And** displays summary: "5 imported, 1 skipped (invalid email)"

#### Scenario 3: Upload with Invalid AWS Account ID
1. **Given** I am logged in as an administrator
2. **When** I upload an Excel file containing a row with invalid AWS account ID (e.g., "ABC123" or "123" - not 12 digits)
3. **Then** the system skips that row
4. **And** continues processing other valid rows
5. **And** displays summary: "4 imported, 1 skipped (invalid AWS account ID)"

#### Scenario 4: Upload with Missing Columns
1. **Given** I am logged in as an administrator
2. **When** I upload an Excel file missing required columns (e.g., only "Email Address" and "AWS Account ID")
3. **Then** the system rejects the file
4. **And** displays error: "Missing required column: Domain"

#### Scenario 5: Upload with Duplicate Mappings
1. **Given** the database already contains mapping: john@example.com → 123456789012 → example.com
2. **When** I upload an Excel file containing the same mapping
3. **Then** the system skips the duplicate
4. **And** displays summary: "3 imported, 1 skipped (duplicate)"

#### Scenario 6: Upload with Empty File
1. **Given** I am logged in as an administrator
2. **When** I upload an Excel file with only header row (no data)
3. **Then** the system displays error: "No data rows found in file"

#### Scenario 7: Upload with Oversized File
1. **Given** I am logged in as an administrator
2. **When** I attempt to upload an Excel file larger than 10MB
3. **Then** the system rejects the file
4. **And** displays error: "File size exceeds maximum limit of 10MB"

#### Scenario 8: Non-Admin Access Denied
1. **Given** I am logged in as a non-admin user (USER role)
2. **When** I attempt to access the User Mappings upload page
3. **Then** the system displays "Access Denied: Admin privileges required"

#### Scenario 9: Multiple Mappings for Same Email
1. **Given** I am logged in as an administrator
2. **When** I upload an Excel file with multiple rows for the same email (different AWS accounts or domains)
3. **Then** the system creates separate mapping records for each row
4. **And** displays success with total count

#### Scenario 10: Non-existent User Email
1. **Given** the system has no User entity with email "newuser@example.com"
2. **When** I upload a mapping for "newuser@example.com → 987654321098 → newdomain.com"
3. **Then** the system creates the mapping successfully
4. **And** the mapping exists independently (no foreign key constraint to User table)

---

## Functional Requirements

### Data Management (FR-DM)

**FR-DM-001**: System MUST store user mappings with three fields: email address, AWS account ID, domain name
- **Rationale**: Core data structure for multi-tenant access control
- **Acceptance**: Database can persist and retrieve mappings with all three fields

**FR-DM-002**: System MUST allow one email address to map to multiple AWS account IDs
- **Rationale**: Users may have access to multiple AWS accounts
- **Acceptance**: Same email can exist in multiple mapping records with different AWS account IDs

**FR-DM-003**: System MUST allow one email address to map to multiple domains
- **Rationale**: Users may have access to multiple organizational domains
- **Acceptance**: Same email can exist in multiple mapping records with different domains

**FR-DM-004**: System MUST treat each unique combination of (email, AWS account ID, domain) as a distinct mapping
- **Rationale**: Prevent duplicate mappings
- **Acceptance**: Attempting to create duplicate mapping is idempotent (no error, no duplicate)

**FR-DM-005**: System MUST NOT enforce foreign key relationship between UserMapping.email and User.email
- **Rationale**: Mappings may be created for users who don't exist yet (pre-onboarding)
- **Acceptance**: UserMapping can be created for non-existent user email

**FR-DM-006**: System MUST track creation timestamp for each mapping
- **Rationale**: Audit and troubleshooting
- **Acceptance**: Each mapping has createdAt field populated on creation

### File Upload (FR-UL)

**FR-UL-001**: System MUST accept Excel (.xlsx) files for user mapping upload
- **Rationale**: Standard format for bulk data entry
- **Acceptance**: .xlsx files are processed successfully

**FR-UL-002**: System MUST reject non-Excel file uploads
- **Rationale**: Security and data integrity
- **Acceptance**: Attempting to upload .csv, .txt, or other formats returns error

**FR-UL-003**: System MUST reject Excel files larger than 10MB
- **Rationale**: Resource protection
- **Acceptance**: Files > 10MB return error: "File size exceeds maximum limit"

**FR-UL-004**: System MUST require exactly three columns: "Email Address", "AWS Account ID", "Domain" (case-insensitive header matching)
- **Rationale**: Data consistency and validation
- **Acceptance**: Files with missing columns return error listing missing headers

**FR-UL-005**: System MUST allow flexible column ordering
- **Rationale**: User convenience
- **Acceptance**: Columns can appear in any order as long as headers match

**FR-UL-006**: System MUST skip empty rows
- **Rationale**: Excel files often have trailing empty rows
- **Acceptance**: Empty rows are ignored without errors

**FR-UL-007**: System MUST process valid rows and skip invalid rows with detailed reporting
- **Rationale**: Partial success better than all-or-nothing
- **Acceptance**: Upload result shows count of imported, skipped, and reasons for skips

### Data Validation (FR-VAL)

**FR-VAL-001**: System MUST validate email address format
- **Rationale**: Data quality
- **Acceptance**: Invalid emails (missing @, invalid domain) are rejected with specific error

**FR-VAL-002**: System MUST validate AWS account ID format (12-digit numeric string)
- **Rationale**: AWS account IDs are always 12 digits
- **Acceptance**: Non-numeric or wrong-length values are rejected

**FR-VAL-003**: System MUST validate domain name format
- **Rationale**: Data quality
- **Acceptance**: Invalid domains (spaces, special chars except dot/hyphen) are rejected

**FR-VAL-004**: System MUST normalize email addresses to lowercase
- **Rationale**: Case-insensitive email matching
- **Acceptance**: "User@Example.COM" is stored as "user@example.com"

**FR-VAL-005**: System MUST trim whitespace from all fields
- **Rationale**: Data quality
- **Acceptance**: " user@example.com " is stored as "user@example.com"

**FR-VAL-006**: System MUST reject rows with empty/null values in any required field
- **Rationale**: Complete data required
- **Acceptance**: Row with missing AWS account ID is skipped

### Access Control (FR-AC)

**FR-AC-001**: User mapping upload MUST be restricted to users with ADMIN role
- **Rationale**: Sensitive configuration data
- **Acceptance**: Non-admin users receive 403 Forbidden when accessing upload endpoint

**FR-AC-002**: User mapping viewing MUST be restricted to users with ADMIN role
- **Rationale**: Sensitive configuration data
- **Acceptance**: Non-admin users cannot see mapping data

**FR-AC-003**: Upload endpoint MUST require authentication
- **Rationale**: Security baseline
- **Acceptance**: Unauthenticated requests receive 401 Unauthorized

### User Interface (FR-UI)

**FR-UI-001**: System MUST provide user mapping upload interface in Admin section
- **Rationale**: Centralized admin functionality
- **Acceptance**: Admin navigation includes "User Mappings" link

**FR-UI-002**: Upload interface MUST display file requirements (format, max size, column structure)
- **Rationale**: User guidance
- **Acceptance**: Help text visible on upload page

**FR-UI-003**: Upload interface MUST display detailed results after processing
- **Rationale**: User feedback and troubleshooting
- **Acceptance**: Results show counts: imported, skipped, errors with details

**FR-UI-004**: Upload interface MUST provide sample Excel file download
- **Rationale**: User convenience and error prevention
- **Acceptance**: "Download Sample" button provides correctly formatted template

---

## Key Entities

### UserMapping
**Purpose**: Store many-to-many relationships between user emails, AWS account IDs, and domains for future role-based access control

**Core Attributes**:
- **email**: String (normalized lowercase, validated format, indexed) - User's email address
- **awsAccountId**: String (12-digit numeric, indexed) - AWS account identifier
- **domain**: String (validated format, indexed) - Organizational domain
- **createdAt**: Timestamp (auto-populated) - Audit trail

**Constraints**:
- Unique constraint on (email, awsAccountId, domain) combination
- Email format validation: RFC 5322 basic pattern
- AWS account ID validation: exactly 12 numeric digits
- Domain validation: alphanumeric, dots, hyphens only

**Relationships**:
- No foreign key to User entity (independent mapping, may reference future users)

**Indexes**:
- Composite unique index: (email, awsAccountId, domain)
- Individual index: email (for lookup by user)
- Individual index: awsAccountId (for lookup by AWS account)
- Individual index: domain (for lookup by domain)

**Business Rules**:
- Email addresses are case-insensitive (stored lowercase)
- AWS account IDs must be exactly 12 digits
- Domain names must be valid DNS-like format
- Duplicate mappings are silently skipped (idempotent)
- Mappings can exist without corresponding User entity

---

## Out of Scope (Phase 1)

The following are explicitly **not included** in this initial phase:

1. **CRUD UI for individual mappings** - Only bulk upload is provided; manual add/edit/delete UI is future work
2. **User mapping deletion** - No deletion functionality in Phase 1; workaround is manual database operation if needed
3. **Export mappings to Excel** - No export functionality in Phase 1
4. **Mapping search/filter UI** - No browsing interface in Phase 1
5. **Integration with actual RBAC** - Mappings are stored but not yet consumed by access control logic
6. **Validation against existing User table** - UserMapping.email is not validated to exist in User table
7. **Automatic user provisioning** - Creating UserMapping does not create User entity
8. **AWS account validation** - System does not verify AWS account IDs exist in AWS
9. **Domain validation against DNS** - System does not verify domains resolve
10. **Mapping approval workflow** - All uploaded mappings are immediately active
11. **Mapping expiration/lifecycle** - Mappings are permanent until manually removed
12. **Bulk update/delete via Excel** - Only create/upsert is supported

---

## Success Metrics

- **Data Quality**: >95% of uploaded rows successfully imported on first attempt
- **User Adoption**: Admins can upload mappings without documentation after viewing sample file
- **Performance**: 1000-row Excel file processes in <10 seconds
- **Error Handling**: All skipped rows have clear, actionable error messages

---

## Dependencies

- Existing Admin authentication and authorization infrastructure (ADMIN role check)
- Excel file processing library (Apache POI)
- Email validation logic
- Database schema migration system

---

## Future Enhancements (Post-Phase 1)

1. **Browse/Search UI**: Table view with filtering by email, AWS account, or domain
2. **Manual CRUD**: Add/edit/delete individual mappings via UI forms
3. **Export to Excel**: Download current mappings as Excel file
4. **RBAC Integration**: Consume mappings in asset/vulnerability access control logic
5. **User synchronization**: Link mappings to User entities and show in user profile
6. **AWS integration**: Validate AWS account IDs against actual AWS Organization
7. **Domain validation**: Verify domains against corporate directory
8. **Mapping analytics**: Dashboard showing mapping distribution and coverage
9. **CSV support**: Accept CSV files in addition to Excel
10. **Mapping expiration**: Add validFrom/validTo dates for time-bound access

---

## Review Checklist

- [x] User scenarios are testable and complete
- [x] All functional requirements are measurable
- [x] Key entities identified with attributes and constraints
- [x] Out-of-scope items explicitly listed
- [x] No implementation details leaked (tech stack, APIs, code patterns)
- [x] Acceptance criteria are objective
- [x] All [NEEDS CLARIFICATION] items resolved
- [x] Success metrics defined
- [x] Dependencies identified
- [x] Future enhancements scoped separately

---

## Approval

- [ ] Product Owner: _______________
- [ ] Tech Lead: _______________
- [ ] QA Lead: _______________
- [ ] Date: _______________
