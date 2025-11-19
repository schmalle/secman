# Feature Specification: CLI User Mapping Management

**Feature Branch**: `049-cli-user-mappings`
**Created**: 2025-11-19
**Status**: Draft
**Input**: User description: "implement in the command line interface an option to map domains or aws accounts to user or more users identified via an email. Meaning i can assign n domains to m users and same for aws accounts. Make the command line extension easily understandable and add a document describing the command line commands."

## Clarifications

### Session 2025-11-19

- Q: Who is authorized to execute CLI user mapping commands (create, list, remove)? → A: Only users with ADMIN role can manage mappings
- Q: When a mapping is created for a user email that doesn't exist in the system yet (future user), what should happen? → A: Store as pending mapping, automatically apply when user is created
- Q: For batch import operations (CSV/JSON file), should the operation be atomic (all-or-nothing) or partial (apply valid entries, skip invalid)? → A: Partial success - process all valid entries, report invalid ones
- Q: What level of detail should audit logs capture for mapping operations? → A: Standard - operation, actor, timestamp, affected entities (user emails, domains/accounts)
- Q: When attempting to create a duplicate mapping (same user-domain or user-AWS account pair that already exists), what should happen? → A: Skip silently, report in results as "already exists"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Map Domains to Users (Priority: P1)

As a system administrator, I need to assign Active Directory domains to users via the CLI so that those users can automatically access assets belonging to those domains without manual workgroup assignment.

**Why this priority**: Domain-based access control is the most common use case in enterprise environments where assets are organized by AD domains. This delivers immediate value by automating access management.

**Independent Test**: Can be fully tested by running the domain mapping command with multiple emails and domains, then verifying that users can access assets from those domains via the web interface.

**Acceptance Scenarios**:

1. **Given** I have a list of user emails and domains to assign, **When** I run the CLI command to map domains to users, **Then** the system creates or updates the mappings and confirms success
2. **Given** a user has domain mappings, **When** assets from those domains are imported, **Then** the user can access those assets in the web interface
3. **Given** I want to map one domain to multiple users, **When** I specify multiple email addresses, **Then** all specified users receive access to that domain
4. **Given** I want to map multiple domains to one user, **When** I specify multiple domains for one email, **Then** the user receives access to all specified domains
5. **Given** I am not logged in as an ADMIN user, **When** I attempt to run any CLI mapping command, **Then** the system rejects the operation with an authorization error
6. **Given** I create a mapping for a user email that doesn't exist yet, **When** I run the CLI command, **Then** the system creates a pending mapping and automatically applies it when that user is created
7. **Given** I attempt to create a mapping that already exists, **When** I run the CLI command, **Then** the system skips the duplicate and reports it as "already exists" without failing

---

### User Story 2 - Map AWS Accounts to Users (Priority: P1)

As a system administrator, I need to assign AWS account IDs to users via the CLI so that those users can automatically access cloud assets belonging to those AWS accounts.

**Why this priority**: AWS account-based access control is equally critical for cloud environments. Many organizations manage access by AWS account boundaries, making this a parallel P1 requirement.

**Independent Test**: Can be fully tested by running the AWS account mapping command with multiple emails and account IDs, then verifying that users can access cloud assets from those accounts via the web interface.

**Acceptance Scenarios**:

1. **Given** I have a list of user emails and AWS account IDs to assign, **When** I run the CLI command to map AWS accounts to users, **Then** the system creates or updates the mappings and confirms success
2. **Given** a user has AWS account mappings, **When** cloud assets from those accounts are imported, **Then** the user can access those assets in the web interface
3. **Given** I want to map one AWS account to multiple users, **When** I specify multiple email addresses, **Then** all specified users receive access to that AWS account
4. **Given** I want to map multiple AWS accounts to one user, **When** I specify multiple account IDs for one email, **Then** the user receives access to all specified AWS accounts

---

### User Story 3 - List Existing Mappings (Priority: P2)

As a system administrator, I need to view existing domain and AWS account mappings via the CLI so that I can verify current access assignments and identify what changes need to be made.

**Why this priority**: Visibility into current state is essential for management but is secondary to actually creating mappings. Provides operational convenience.

**Independent Test**: Can be fully tested by creating some mappings, then running the list command and verifying the output matches what was created.

**Acceptance Scenarios**:

1. **Given** there are existing user mappings in the system, **When** I run the CLI command to list mappings, **Then** I see all mappings displayed in a readable format
2. **Given** I want to see mappings for a specific user, **When** I filter by email address, **Then** I see only that user's domain and AWS account mappings
3. **Given** there are no mappings in the system, **When** I run the list command, **Then** I see a message indicating no mappings exist

---

### User Story 4 - Remove User Mappings (Priority: P2)

As a system administrator, I need to remove domain and AWS account mappings from users via the CLI so that I can revoke access when users change roles or leave the organization.

**Why this priority**: Access revocation is important for security but is less frequent than granting access. Essential for lifecycle management but secondary to creation.

**Independent Test**: Can be fully tested by creating mappings, removing them via CLI, then verifying the user can no longer access assets from those domains/accounts.

**Acceptance Scenarios**:

1. **Given** a user has domain mappings, **When** I run the CLI command to remove specific domains from that user, **Then** those domain mappings are deleted and the user loses access to assets from those domains
2. **Given** a user has AWS account mappings, **When** I run the CLI command to remove specific AWS accounts from that user, **Then** those account mappings are deleted and the user loses access to assets from those accounts
3. **Given** I want to remove all mappings for a user, **When** I specify the user email without specific domains/accounts, **Then** all mappings for that user are removed
4. **Given** I attempt to remove a non-existent mapping, **When** I run the remove command, **Then** the system provides a clear message that the mapping doesn't exist

---

### User Story 5 - Batch Operations from File (Priority: P3)

As a system administrator, I need to apply multiple user mappings from a file via the CLI so that I can efficiently manage large-scale access changes without running individual commands.

**Why this priority**: Batch operations improve efficiency for large deployments but aren't necessary for basic functionality. Nice-to-have for operational efficiency.

**Independent Test**: Can be fully tested by creating a file with multiple mapping entries, running the batch command, and verifying all mappings were created correctly.

**Acceptance Scenarios**:

1. **Given** I have a CSV or JSON file containing multiple user-to-domain mappings, **When** I run the CLI batch import command, **Then** all valid mappings are created and any errors are reported
2. **Given** I have a file containing both domain and AWS account mappings, **When** I run the batch import, **Then** both types of mappings are processed correctly
3. **Given** the batch file contains invalid entries, **When** I run the batch import, **Then** valid entries are processed and invalid entries are reported with specific error messages

---

### Edge Cases

- Mappings for non-existent users are stored as pending and automatically applied when the user is created
- Case-insensitive matching is applied for both email addresses and domain names per DNS standards
- Duplicate mapping attempts are skipped silently and reported as "already exists" in operation results
- How does the system handle invalid AWS account ID formats?
- What happens when mapping operations are run while user imports or asset imports are in progress?
- How are mappings affected when a user is deleted from the system?
- What happens when domain or AWS account strings contain special characters or whitespace?
- How does the system handle extremely long lists of mappings in a single command?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide a CLI command to create domain-to-user mappings where one or more domains can be assigned to one or more users identified by email addresses
- **FR-002**: System MUST provide a CLI command to create AWS account-to-user mappings where one or more AWS account IDs can be assigned to one or more users identified by email addresses
- **FR-003**: System MUST validate email addresses follow standard email format (contains @, valid domain structure)
- **FR-004**: System MUST validate AWS account IDs are 12-digit numeric strings
- **FR-005**: System MUST handle case-insensitive matching for email addresses (treat user@example.com and User@Example.com as the same user)
- **FR-006**: System MUST handle case-insensitive matching for domain names per DNS standards
- **FR-007**: System MUST prevent duplicate mappings by skipping attempts to create existing user-domain or user-AWS account pairs and reporting them as "already exists"
- **FR-008**: System MUST allow creating mappings for users that don't yet exist in the system as pending mappings, and automatically apply them when the user is created
- **FR-009**: System MUST provide a CLI command to list all existing mappings with filtering options by user email
- **FR-010**: System MUST provide a CLI command to remove specific domain or AWS account mappings from users
- **FR-011**: System MUST provide clear success and error messages for all CLI operations
- **FR-012**: System MUST provide a comprehensive CLI documentation file describing all available commands, parameters, and usage examples
- **FR-013**: System MUST support batch operations to apply multiple mappings from a file (CSV or JSON format) with partial success mode (process all valid entries, skip and report invalid ones)
- **FR-014**: System MUST validate each entry in batch operations independently without rolling back the entire batch on individual failures
- **FR-015**: System MUST report batch operation results including success count, skip count, and detailed error messages for each failed entry
- **FR-016**: CLI commands MUST be intuitive and follow standard CLI conventions (flags, help text, argument syntax)
- **FR-017**: System MUST provide help text for each CLI command accessible via --help flag
- **FR-018**: System MUST persist all mappings to the database for use by the access control system
- **FR-019**: System MUST apply mappings immediately upon creation (no manual refresh required)
- **FR-020**: System MUST log all mapping changes for audit purposes including operation type, actor (admin user), timestamp, and affected entities (user emails, domains, or AWS account IDs)
- **FR-021**: System MUST restrict all CLI mapping operations (create, list, remove, batch) to users with ADMIN role only
- **FR-022**: System MUST distinguish between active and pending mappings when listing, clearly indicating which mappings are pending user creation

### Key Entities

- **User Mapping (Domain)**: Represents the association between a user (identified by email) and one or more Active Directory domains. Contains: user email, domain name, creation timestamp, creator information, status (active/pending)
- **User Mapping (AWS Account)**: Represents the association between a user (identified by email) and one or more AWS account IDs. Contains: user email, AWS account ID, creation timestamp, creator information, status (active/pending)
- **CLI Command**: The command-line interface operations that manipulate user mappings. Supports: create, list, remove, batch import operations with appropriate parameters and flags

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Administrators can create domain-to-user mappings via CLI in under 30 seconds for typical use cases (1-5 users, 1-3 domains)
- **SC-002**: Administrators can create AWS account-to-user mappings via CLI in under 30 seconds for typical use cases (1-5 users, 1-3 accounts)
- **SC-003**: Batch import operations can process at least 100 mappings per minute
- **SC-004**: All CLI commands provide clear feedback within 5 seconds for standard operations
- **SC-005**: CLI documentation enables administrators to complete mapping tasks without additional support in 90% of cases
- **SC-006**: Zero duplicate mappings exist in the system after any CLI operation
- **SC-007**: 100% of mapping changes are logged with operation type, actor identity, timestamp, and affected entity details (user emails, domains/accounts)
- **SC-008**: Users can access assets based on new mappings within 1 minute of CLI command completion
- **SC-009**: All CLI error messages clearly identify what went wrong and how to correct it
- **SC-010**: Administrators report the CLI as "easy to use" or "very easy to use" in usability testing
