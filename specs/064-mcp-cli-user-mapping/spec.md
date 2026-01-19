# Feature Specification: MCP and CLI User Mapping Upload

**Feature Branch**: `064-mcp-cli-user-mapping`
**Created**: 2026-01-19
**Status**: Draft
**Input**: User description: "under the path /admin/user-mappings secman has a upload functionality for aws account / user mapping. I want to make this functionality available also via MCP and also via the CLI interface. Extend the existing functionality including documentation to add this feature."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - MCP Bulk Import User Mappings (Priority: P1)

An administrator needs to programmatically import user-to-AWS-account and user-to-domain mappings through the MCP interface. They provide a list of mapping entries (email, AWS account ID, domain) and the system processes them in bulk, returning detailed results including successful imports, skipped duplicates, and validation errors.

**Why this priority**: MCP is the primary programmatic interface for AI-assisted automation. Bulk import capability enables efficient large-scale user provisioning without manual UI interaction.

**Independent Test**: Can be fully tested by calling the MCP tool with a set of mapping entries and verifying the returned import results match expected outcomes.

**Acceptance Scenarios**:

1. **Given** an authenticated admin user via MCP delegation, **When** they call the `import_user_mappings` tool with valid mapping entries, **Then** the mappings are created and the tool returns a summary with imported count, skipped count, and any errors.

2. **Given** an authenticated admin user via MCP delegation, **When** they call the `import_user_mappings` tool with a dry-run flag, **Then** the tool validates all entries without creating any mappings and returns what would happen.

3. **Given** an authenticated non-admin user via MCP delegation, **When** they call the `import_user_mappings` tool, **Then** the tool returns an error indicating ADMIN role is required.

4. **Given** an authenticated admin user via MCP delegation, **When** they call the tool with some valid and some invalid entries, **Then** valid entries are imported and invalid entries are reported with specific error messages.

---

### User Story 2 - CLI Bulk Import from File (Priority: P1)

An administrator needs to import user mappings from a file (CSV or JSON) via the command line for scripted deployments and automated provisioning workflows. The CLI reads the file, processes the mappings, and outputs detailed results.

**Why this priority**: CLI is essential for DevOps automation, CI/CD pipelines, and scripted administration tasks. This functionality already partially exists but needs documentation enhancement and parity with MCP capabilities.

**Independent Test**: Can be fully tested by running the CLI import command with a test file and verifying the output matches expected import results.

**Acceptance Scenarios**:

1. **Given** a valid CSV file with user mappings, **When** the admin runs `./bin/secman manage-user-mappings import --file mappings.csv`, **Then** the mappings are imported and a summary is displayed.

2. **Given** a valid JSON file with user mappings, **When** the admin runs `./bin/secman manage-user-mappings import --file mappings.json`, **Then** the mappings are imported and a summary is displayed.

3. **Given** a file with invalid entries, **When** the admin runs the import command with `--dry-run`, **Then** validation results are shown without any database changes.

---

### User Story 3 - MCP List User Mappings (Priority: P2)

An administrator needs to retrieve existing user mappings through MCP to audit current configurations, verify import results, or build automation workflows that depend on mapping state.

**Why this priority**: Listing capability complements import functionality and enables verification workflows, but is secondary to the core import feature.

**Independent Test**: Can be fully tested by calling the MCP tool and verifying the returned list contains expected mappings with correct attributes.

**Acceptance Scenarios**:

1. **Given** an authenticated admin user via MCP delegation with existing mappings in the system, **When** they call `list_user_mappings` tool, **Then** a paginated list of all mappings is returned.

2. **Given** an authenticated admin user via MCP delegation, **When** they call `list_user_mappings` with email filter, **Then** only mappings for that email are returned.

---

### User Story 4 - CLI List User Mappings (Priority: P2)

An administrator needs to view current user mappings via command line for verification, auditing, or scripted health checks.

**Why this priority**: Complements CLI import and enables verification of import results from the same interface.

**Independent Test**: Can be fully tested by running the CLI list command and verifying output contains expected mappings.

**Acceptance Scenarios**:

1. **Given** existing user mappings in the system, **When** the admin runs `./bin/secman manage-user-mappings list`, **Then** all mappings are displayed in a readable format.

2. **Given** existing user mappings in the system, **When** the admin runs `./bin/secman manage-user-mappings list --email user@example.com`, **Then** only mappings for that email are displayed.

---

### User Story 5 - Documentation Update (Priority: P3)

The system documentation must be updated to include comprehensive guides for using the MCP and CLI user mapping functionality, including examples, error handling, and best practices.

**Why this priority**: Documentation is essential for adoption but can be completed after core functionality is implemented.

**Independent Test**: Documentation can be reviewed for completeness by verifying all commands and tools are documented with examples.

**Acceptance Scenarios**:

1. **Given** the completed feature, **When** a user reads the documentation, **Then** they can find clear instructions for importing user mappings via MCP.

2. **Given** the completed feature, **When** a user reads the documentation, **Then** they can find clear instructions for importing user mappings via CLI with all supported file formats.

---

### Edge Cases

- What happens when the import file is empty? System returns a message indicating no data was found.
- What happens when all entries in the import are duplicates? System reports all entries were skipped with appropriate message.
- What happens when the MCP tool is called without user delegation? System returns DELEGATION_REQUIRED error.
- What happens when the file format cannot be auto-detected? System returns an error asking to specify format explicitly.
- What happens when import includes mappings for users who don't exist? System creates "future mappings" that will be applied when users are created.

## Requirements *(mandatory)*

### Functional Requirements

**MCP Tool: import_user_mappings**

- **FR-001**: System MUST provide an MCP tool named `import_user_mappings` for bulk importing user mappings.
- **FR-002**: The tool MUST require User Delegation to be enabled.
- **FR-003**: The tool MUST require ADMIN role for execution.
- **FR-004**: The tool MUST accept a list of mapping entries, each containing: email (required), awsAccountId (optional), domain (optional).
- **FR-005**: The tool MUST validate that at least one of awsAccountId or domain is provided per entry.
- **FR-006**: The tool MUST support a dry-run mode that validates without persisting changes.
- **FR-007**: The tool MUST return detailed results: imported count, skipped count (duplicates), errors with specific messages per entry.
- **FR-008**: The tool MUST validate email format (contains @, between 3-255 characters).
- **FR-009**: The tool MUST validate AWS account ID format (exactly 12 numeric digits).
- **FR-010**: The tool MUST validate domain format (alphanumeric with dots and hyphens, no leading/trailing special characters).
- **FR-011**: The tool MUST skip duplicate mappings (same email + awsAccountId + domain combination).

**MCP Tool: list_user_mappings**

- **FR-012**: System MUST provide an MCP tool named `list_user_mappings` for retrieving existing mappings.
- **FR-013**: The tool MUST require User Delegation and ADMIN role.
- **FR-014**: The tool MUST support pagination with configurable page size.
- **FR-015**: The tool MUST support filtering by email address.
- **FR-016**: The tool MUST return mapping details: id, email, awsAccountId, domain, userId, appliedAt, isFutureMapping, createdAt, updatedAt.

**CLI Enhancement**

- **FR-017**: The existing CLI import command MUST be documented with comprehensive examples.
- **FR-018**: The CLI list command MUST support `--format` option for output format (table, json).
- **FR-019**: The CLI import command MUST display clear progress and summary information.

**Documentation**

- **FR-020**: System MUST provide documentation for MCP tools in the appropriate documentation location.
- **FR-021**: System MUST provide documentation for CLI commands including file format specifications.
- **FR-022**: Documentation MUST include examples for common use cases.

### Key Entities

- **UserMapping**: Represents a mapping between an email address and AWS account ID and/or domain. Key attributes: id, email, awsAccountId, domain, userId (null for future mappings), appliedAt (null for unapplied), isFutureMapping.
- **ImportResult**: Represents the outcome of an import operation. Key attributes: imported count, skipped count, errors list, totalProcessed.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Administrators can import 100 user mappings via MCP in a single operation with results returned within 10 seconds.
- **SC-002**: Administrators can import user mappings from CSV files via CLI with 100% feature parity with the web UI.
- **SC-003**: Both MCP and CLI interfaces provide identical validation rules as the existing web UI upload functionality.
- **SC-004**: Dry-run mode accurately predicts import outcomes with 100% accuracy compared to actual imports.
- **SC-005**: All import errors provide specific, actionable messages indicating which entry failed and why.
- **SC-006**: Documentation enables new users to successfully import mappings via MCP or CLI on first attempt.

## Assumptions

- The existing `UserMappingImportService` and `UserMappingService` can be reused for MCP tool implementation.
- The existing CLI `ImportCommand` provides the foundation for file-based import functionality.
- MCP tools follow the established pattern using `McpTool` interface with `McpExecutionContext`.
- Documentation will be added to the existing `docs/` directory structure.
