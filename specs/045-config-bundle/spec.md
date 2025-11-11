# Feature Specification: Configuration Bundle Export/Import

**Feature Branch**: `045-config-bundle`
**Created**: 2025-11-10
**Status**: Draft
**Input**: User description: "if the logged in user has the role ADMIN i want for the User Management, for the Identity Providers, Crowdstrike config and for the MCP API keys the possibility to export and import this dataset in a bundle. Make this available under I/O in the sidebar."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Export Configuration Bundle (Priority: P1)

As a system administrator, I need to export all system configuration data including users, identity providers, CrowdStrike settings, and MCP API keys into a single bundle file so that I can backup the configuration or migrate it to another environment.

**Why this priority**: Core functionality - without export capability, there's no bundle to import. This enables configuration backup and disaster recovery.

**Independent Test**: Can be fully tested by exporting configuration and verifying the output file contains all expected data in the correct format.

**Acceptance Scenarios**:

1. **Given** an authenticated user with ADMIN role and existing configuration data, **When** they navigate to the Configuration Bundle page and click "Export Configuration", **Then** a JSON file downloads containing all users, identity providers, CrowdStrike configs, and MCP API key metadata
2. **Given** sensitive data exists in the configuration (passwords, API secrets), **When** the configuration is exported, **Then** all sensitive data is either masked, encrypted, or excluded from the export
3. **Given** an ADMIN user exports configuration, **When** they examine the exported file, **Then** it contains metadata including export timestamp, version, and the exporting user's information

---

### User Story 2 - Import Configuration Bundle (Priority: P1)

As a system administrator, I need to import a previously exported configuration bundle to restore settings or migrate configuration from another environment, with proper validation and conflict handling.

**Why this priority**: Equally critical - completes the backup/restore cycle and enables environment migration.

**Independent Test**: Can be tested by importing a valid configuration bundle and verifying all entities are created/updated correctly with proper conflict handling.

**Acceptance Scenarios**:

1. **Given** an ADMIN user with a valid configuration bundle file, **When** they upload the file for import, **Then** the system validates the file format and shows a preview of what will be imported
2. **Given** the import preview shows entities that require secrets (OAuth client secrets, API keys), **When** the admin proceeds with import, **Then** they are prompted to enter the required secrets before import continues
3. **Given** entities in the bundle already exist in the system (users, identity providers), **When** importing, **Then** the system skips existing entities and reports them in the import summary
4. **Given** an import is completed, **When** reviewing the results, **Then** the admin sees a detailed report showing successful imports, skipped items, and any newly generated credentials (like MCP API keys)

---

### User Story 3 - Validate Bundle Before Import (Priority: P2)

As a system administrator, I need to validate a configuration bundle before importing it to identify potential conflicts and required information, ensuring a smooth import process.

**Why this priority**: Improves user experience by preventing failed imports and allowing administrators to prepare necessary information.

**Independent Test**: Can be tested by uploading various bundle files (valid, invalid, with conflicts) and verifying appropriate validation messages.

**Acceptance Scenarios**:

1. **Given** a configuration bundle file, **When** an admin uploads it for validation, **Then** the system checks file format, schema version, and data integrity without making any changes
2. **Given** a bundle with entities that already exist, **When** validating, **Then** the system identifies and lists all potential conflicts
3. **Given** a bundle requiring secret re-entry, **When** validating, **Then** the system lists all secrets that will need to be provided during import

---

### User Story 4 - Access Control for Bundle Operations (Priority: P2)

As a security administrator, I need to ensure only users with ADMIN role can access configuration bundle export/import functionality to maintain system security.

**Why this priority**: Critical for security but the functionality itself is more important than the restriction in early testing phases.

**Independent Test**: Can be tested by attempting to access bundle operations with different user roles and verifying proper authorization.

**Acceptance Scenarios**:

1. **Given** a user without ADMIN role, **When** they try to access the Configuration Bundle page, **Then** they receive an authorization error
2. **Given** a user without ADMIN role, **When** they attempt to call the export/import API endpoints directly, **Then** they receive a 403 Forbidden response
3. **Given** an ADMIN user, **When** they navigate to the I/O section in the sidebar, **Then** they can see and access the Configuration Bundle option

---

### User Story 5 - Audit Trail for Bundle Operations (Priority: P3)

As a compliance officer, I need all configuration bundle export and import operations to be logged with details about who performed them and when, for audit and compliance purposes.

**Why this priority**: Important for compliance but not blocking core functionality.

**Independent Test**: Can be tested by performing export/import operations and checking audit logs for proper recording.

**Acceptance Scenarios**:

1. **Given** an ADMIN exports a configuration bundle, **When** checking audit logs, **Then** there is an entry showing the user, timestamp, and export action
2. **Given** an ADMIN imports a configuration bundle, **When** checking audit logs, **Then** there is a detailed entry showing user, timestamp, items imported, and any conflicts

---

### Edge Cases

- What happens when importing a bundle from a different version of the system?
- How does the system handle circular dependencies in workgroup hierarchies during import?
- What happens if the encryption keys differ between export and import environments?
- How does the system handle importing users that would leave no ADMIN in the system?
- What happens when importing with database constraints (unique emails, usernames)?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST allow ADMIN users to export all configuration data (users, workgroups, user mappings, identity providers, CrowdStrike config, MCP API keys) into a single bundle file
- **FR-002**: System MUST mask or exclude sensitive data (passwords, API secrets, OAuth client secrets) in exports
- **FR-003**: System MUST validate bundle file format and schema version before import
- **FR-004**: System MUST detect and report conflicts (existing entities) during import validation
- **FR-005**: System MUST maintain referential integrity when importing related entities (users-workgroups, user-mappings)
- **FR-006**: System MUST generate new MCP API keys during import (cannot import secrets)
- **FR-007**: System MUST prompt for required secrets (OAuth client secrets, CrowdStrike credentials) during import
- **FR-008**: System MUST provide detailed import results showing successful, skipped, and failed items
- **FR-009**: System MUST restrict bundle operations to users with ADMIN role only
- **FR-010**: System MUST log all export and import operations for audit purposes
- **FR-011**: System MUST handle bundle files up to 50MB in size
- **FR-012**: System MUST preserve workgroup hierarchies and relationships during import/export
- **FR-013**: System MUST prevent importing configurations that would leave the system without any ADMIN user
- **FR-014**: Bundle operations MUST be accessible from the I/O section in the sidebar for ADMIN users
- **FR-015**: System MUST include bundle format version for future compatibility

### Key Entities *(include if feature involves data)*

- **Configuration Bundle**: Container for all exported configuration data, includes version, timestamp, and exporter information
- **User Export Data**: User information excluding passwords, includes username, email, roles, workgroup assignments
- **Identity Provider Export Data**: OAuth/SAML configuration with masked client secrets
- **CrowdStrike Config Export Data**: Falcon API configuration with encrypted or masked credentials
- **MCP API Key Export Data**: API key metadata without actual secrets (name, permissions, expiry, owner reference)
- **Workgroup Export Data**: Workgroup definitions including hierarchy and user/asset associations
- **User Mapping Export Data**: AWS account, domain, and IP mappings linked to users

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: ADMIN users can complete a full configuration export in under 30 seconds for systems with up to 1000 users
- **SC-002**: Configuration import with 100 users and related entities completes within 2 minutes
- **SC-003**: 100% of sensitive data (passwords, API secrets) is excluded or masked in exports
- **SC-004**: Import validation identifies 100% of conflicts before actual import execution
- **SC-005**: 95% of administrators successfully complete import on first attempt with clear guidance for secret re-entry
- **SC-006**: System maintains 100% referential integrity - no orphaned relationships after import
- **SC-007**: Reduce configuration migration time between environments by 80% compared to manual recreation
- **SC-008**: All bundle operations are logged with sufficient detail for compliance audit requirements