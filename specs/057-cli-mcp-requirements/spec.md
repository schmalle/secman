# Feature Specification: CLI and MCP Requirements Management

**Feature Branch**: `057-cli-mcp-requirements`
**Created**: 2025-12-29
**Status**: Draft
**Input**: User description: "add following functionality to the CLI interface, a) delete all requirements, b) export all requirements to excel, c) export all requirements to word, d) add single requirement. Make the same functionality also available via MCP."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Export Requirements to Excel via CLI (Priority: P1)

A security administrator needs to export all requirements to an Excel file for offline review, sharing with stakeholders, or importing into external systems. They run a CLI command and receive a downloadable Excel file saved to their local machine.

**Why this priority**: Export to Excel is the most commonly needed functionality - enables sharing requirements with non-technical stakeholders, external auditors, and teams without system access. Provides immediate business value.

**Independent Test**: Can be fully tested by running the CLI export command and verifying a valid Excel file is created with all requirements data intact.

**Acceptance Scenarios**:

1. **Given** the system has 50 requirements stored, **When** the user runs `secman export-requirements --format xlsx --output requirements.xlsx`, **Then** an Excel file is created at the specified path containing all 50 requirements with proper columns (Chapter, Norm, Short req, Details, Motivation, Example, UseCase).

2. **Given** the system has requirements across multiple chapters, **When** the user exports to Excel, **Then** requirements are sorted by chapter and then by ID.

3. **Given** the user does not specify an output path, **When** the user runs `secman export-requirements --format xlsx`, **Then** the file is saved to the current directory with a timestamped filename (e.g., `requirements_export_20251229.xlsx`).

4. **Given** the system has no requirements, **When** the user runs the export command, **Then** the command completes successfully with a message indicating 0 requirements exported.

---

### User Story 2 - Export Requirements to Word via CLI (Priority: P1)

A security administrator needs to export all requirements to a Word document for formal documentation, compliance audits, or management review. The document should be professionally formatted with chapter groupings and proper styling.

**Why this priority**: Word export is equally critical for formal documentation - required for compliance audits, management presentations, and official security documentation.

**Independent Test**: Can be fully tested by running the CLI export command and verifying a valid Word document is created with formatted requirements.

**Acceptance Scenarios**:

1. **Given** the system has requirements stored, **When** the user runs `secman export-requirements --format docx --output requirements.docx`, **Then** a Word document is created with a title page, table of contents placeholder, and all requirements grouped by chapter.

2. **Given** requirements have associated norms and use cases, **When** the user exports to Word, **Then** the document includes norm references and use case information for each requirement.

3. **Given** the output path is a directory that doesn't exist, **When** the user runs the export command, **Then** the command fails with a clear error message about the invalid path.

---

### User Story 3 - Add Single Requirement via CLI (Priority: P2)

A security administrator needs to quickly add a new requirement from the command line without using the web UI. This supports scripted workflows, batch imports, and automation scenarios.

**Why this priority**: Adding requirements via CLI enables automation and scripting - important for CI/CD integration and bulk operations, but less frequently used than export functionality.

**Independent Test**: Can be fully tested by running the add command and verifying the requirement appears in the system.

**Acceptance Scenarios**:

1. **Given** a valid authentication, **When** the user runs `secman add-requirement --shortreq "Password must be at least 12 characters" --chapter "Authentication"`, **Then** the requirement is created in the system with the specified values.

2. **Given** the user provides all optional fields, **When** the user runs the add command with `--details`, `--motivation`, `--example`, `--norm`, and `--usecase` options, **Then** all fields are saved to the requirement.

3. **Given** the user omits the required `--shortreq` field, **When** the user runs the add command, **Then** the command fails with a validation error explaining the missing required field.

4. **Given** the user provides an invalid chapter name that doesn't match existing chapters, **When** the user runs the add command, **Then** the requirement is created with the new chapter (chapters are auto-created).

---

### User Story 4 - Delete All Requirements via CLI (Priority: P3)

An administrator needs to clear all requirements from the system - typically used in development/testing environments or when performing a complete data reset before importing a new requirements set.

**Why this priority**: Delete all is a destructive operation used infrequently - primarily for testing, development, or complete system resets. Lower priority due to potential for data loss.

**Independent Test**: Can be fully tested by running the delete command and verifying all requirements are removed from the system.

**Acceptance Scenarios**:

1. **Given** the user has ADMIN role, **When** the user runs `secman delete-all-requirements --confirm`, **Then** all requirements are deleted from the system.

2. **Given** the user runs the command without `--confirm` flag, **When** the command executes, **Then** the command fails with a message requiring explicit confirmation to prevent accidental deletion.

3. **Given** the user does not have ADMIN role, **When** the user runs the delete command, **Then** the command fails with an authorization error.

4. **Given** there are 100 requirements in the system, **When** the user runs the delete command with confirmation, **Then** the command completes and reports the count of deleted requirements.

---

### User Story 5 - Export Requirements via MCP (Priority: P2)

An AI assistant integrated via MCP needs to export requirements in structured format to provide security documentation to users or integrate with external tools.

**Why this priority**: MCP export enables AI assistants to help users generate documentation - important for AI-assisted workflows but builds on existing MCP infrastructure.

**Independent Test**: Can be fully tested by calling the MCP tool endpoint and verifying export data is returned in the expected format.

**Acceptance Scenarios**:

1. **Given** an authenticated MCP session with REQUIREMENTS_READ permission, **When** the AI calls `export_requirements` tool with `format: "xlsx"`, **Then** the tool returns base64-encoded Excel file content.

2. **Given** an MCP session, **When** the AI calls the export tool with `format: "docx"`, **Then** the tool returns base64-encoded Word document content.

3. **Given** an MCP session without proper permissions, **When** the AI calls the export tool, **Then** the tool returns an authorization error.

---

### User Story 6 - Add Requirement via MCP (Priority: P2)

An AI assistant needs to create new requirements based on user conversations, automatically extracting security requirements from discussions and adding them to the system.

**Why this priority**: Enables AI-assisted requirement creation - enhances AI assistant capabilities for security requirement management.

**Independent Test**: Can be fully tested by calling the MCP add tool and verifying the requirement is created.

**Acceptance Scenarios**:

1. **Given** an authenticated MCP session with REQUIREMENTS_WRITE permission, **When** the AI calls `add_requirement` tool with required fields, **Then** the requirement is created and the tool returns the created requirement ID.

2. **Given** an MCP session, **When** the AI calls add_requirement with invalid data, **Then** the tool returns a validation error with specific field issues.

---

### User Story 7 - Delete All Requirements via MCP (Priority: P3)

An AI assistant with admin privileges needs to clear all requirements as part of an automated workflow or at explicit user request.

**Why this priority**: Destructive operation rarely needed via MCP - lowest priority due to risk and infrequent use case.

**Independent Test**: Can be fully tested by calling the MCP delete tool and verifying all requirements are removed.

**Acceptance Scenarios**:

1. **Given** an authenticated MCP session with ADMIN role and REQUIREMENTS_DELETE permission, **When** the AI calls `delete_all_requirements` tool with `confirm: true`, **Then** all requirements are deleted and the tool returns the deletion count.

2. **Given** an MCP session without ADMIN role, **When** the AI calls the delete tool, **Then** the tool returns an authorization error.

---

### Edge Cases

- What happens when exporting with very large requirement sets (10,000+ requirements)? System processes in batches and may take longer but completes successfully.
- How does the system handle concurrent export requests from multiple CLI sessions? Each session receives its own export file; no conflicts.
- What happens when the user's disk is full during export? Command fails with clear disk space error.
- How does the system handle requirements with special characters (unicode, newlines) in export? Special characters are preserved in both Excel and Word exports.
- What happens when MCP export is called but translation service is needed but unavailable? Standard (non-translated) export is returned; translation-specific endpoints are separate.
- How does delete-all handle requirements with foreign key relationships (snapshots in releases)? Requirements are deleted; snapshots remain as historical records (cascade behavior).

## Requirements *(mandatory)*

### Functional Requirements

**CLI Commands:**

- **FR-001**: CLI MUST provide an `export-requirements` command that exports all requirements to a file
- **FR-002**: CLI MUST support `--format` option with values `xlsx` (Excel) and `docx` (Word)
- **FR-003**: CLI MUST support `--output` option to specify the output file path
- **FR-004**: CLI MUST default to current directory with timestamped filename when `--output` is omitted
- **FR-005**: CLI MUST provide an `add-requirement` command to create new requirements
- **FR-006**: CLI MUST require `--shortreq` parameter for add-requirement command
- **FR-007**: CLI MUST support optional parameters: `--details`, `--motivation`, `--example`, `--norm`, `--usecase`, `--chapter`
- **FR-008**: CLI MUST provide a `delete-all-requirements` command restricted to ADMIN role
- **FR-009**: CLI MUST require explicit `--confirm` flag for delete-all-requirements
- **FR-010**: CLI MUST display count of affected requirements before and after operations

**MCP Tools:**

- **FR-011**: MCP MUST provide an `export_requirements` tool that returns file content in base64 encoding
- **FR-012**: MCP export tool MUST support `format` parameter with values `xlsx` and `docx`
- **FR-013**: MCP MUST provide an `add_requirement` tool to create requirements
- **FR-014**: MCP add_requirement MUST validate all input fields before creation
- **FR-015**: MCP MUST provide a `delete_all_requirements` tool restricted to ADMIN role
- **FR-016**: MCP delete tool MUST require `confirm: true` parameter

**Authentication & Authorization:**

- **FR-017**: CLI commands MUST require authentication via `--username` and `--password` or environment variables (SECMAN_USERNAME, SECMAN_PASSWORD)
- **FR-018**: Export commands MUST be accessible to ADMIN, REQ, and SECCHAMPION roles
- **FR-019**: Add requirement command MUST be accessible to ADMIN, REQ, and SECCHAMPION roles
- **FR-020**: Delete all requirements MUST be restricted to ADMIN role only
- **FR-021**: MCP tools MUST respect API key permissions (REQUIREMENTS_READ for export, REQUIREMENTS_WRITE for add)

**Data Format:**

- **FR-022**: Excel export MUST match existing web export format (columns: Chapter, Norm, Short req, DetailsEN, MotivationEN, ExampleEN, UseCase)
- **FR-023**: Word export MUST match existing web export format (title page, chapter groupings, styled requirements)
- **FR-024**: CLI MUST preserve all requirement data during export including linked norms and use cases

### Key Entities

- **Requirement**: Core entity with shortreq (required), details, motivation, example, norm, chapter, and relationships to UseCase and Norm entities
- **CLI Command**: New commands (`export-requirements`, `add-requirement`, `delete-all-requirements`) registered in SecmanCli.kt
- **MCP Tool**: New tools (`export_requirements`, `add_requirement`, `delete_all_requirements`) registered in McpToolRegistry

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can export 1,000 requirements to Excel in under 30 seconds
- **SC-002**: Users can export 1,000 requirements to Word in under 60 seconds
- **SC-003**: CLI commands complete with clear success/failure messages within 5 seconds for operations under 100 requirements
- **SC-004**: MCP tools return results within 10 seconds for standard operations
- **SC-005**: All CLI commands provide helpful usage information via `--help` flag
- **SC-006**: Exported files are 100% compatible with the existing web UI import functionality (round-trip compatibility)
- **SC-007**: Add requirement via CLI achieves same data integrity as web UI creation (all validations pass)
- **SC-008**: Delete operation reports accurate count of deleted requirements matching database state

## Assumptions

- The existing backend endpoints for export (`/api/requirements/export/xlsx`, `/api/requirements/export/docx`) will be reused by CLI commands
- The existing backend endpoint for delete-all (`DELETE /api/requirements/all`) will be reused by CLI commands
- The existing backend endpoint for create (`POST /api/requirements`) will be reused by CLI commands
- CLI authentication follows the same pattern as existing CLI commands (username/password or environment variables)
- MCP tools follow the existing tool registration pattern in McpToolRegistry
- Export files are generated on the server and transferred to the client (not generated locally)
- Large exports (10,000+ requirements) may require streaming or chunked responses
- The existing RequirementService provides all necessary business logic for add/delete operations
