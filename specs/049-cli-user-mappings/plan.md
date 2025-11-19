# Implementation Plan: CLI User Mapping Management

**Branch**: `049-cli-user-mappings` | **Date**: 2025-11-19 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/049-cli-user-mappings/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/commands/plan.md` for the execution workflow.

## Summary

Add CLI commands to the existing CLI application (`src/cli/`) to enable ADMIN users to manage user mappings (domain-to-user and AWS-account-to-user). This extends the access control system by allowing administrators to assign Active Directory domains and AWS account IDs to users via command-line interface, supporting individual operations, batch imports from CSV/JSON files, and listing/removal of mappings. Mappings are stored in the database with pending/active status and automatically applied when users are created. The solution reuses existing infrastructure (Micronaut CLI framework, JPA repositories, existing UserMapping entity) and follows established patterns from the notification CLI commands.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, Picocli 4.7 (CLI framework), Apache Commons CSV 1.11.0, Jackson (JSON parsing)
**Storage**: MariaDB 12 (reuses existing UserMapping entity from feature 042)
**Testing**: JUnit 5, Micronaut Test (when requested by user per Constitution IV)
**Target Platform**: JVM CLI application (runs on server, invoked via Gradle)
**Project Type**: Extension to existing CLI application (src/cli/)
**Performance Goals**: Process 100+ mappings/minute in batch mode, <5 second response for individual commands
**Constraints**: ADMIN-role authentication required, idempotent operations (duplicate handling), partial batch success mode
**Scale/Scope**: Support for 1000+ users, 100+ domains, 500+ AWS accounts in typical enterprise deployment

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Security-First ✅

**Compliance**:
- ✅ FR-021: All CLI operations restricted to ADMIN role only
- ✅ FR-003/FR-004: Input validation for email addresses and AWS account IDs
- ✅ FR-020: Comprehensive audit logging (operation, actor, timestamp, entities)
- ✅ No sensitive data exposed in error messages (emails logged but sanitized)
- ✅ Authentication via existing Micronaut security integration

**Implementation Notes**:
- CLI commands will use `@Secured(SecurityRule.IS_AUTHENTICATED)` and verify ADMIN role
- Input validation in service layer before database operations
- Audit logs written to same infrastructure as other admin operations

### III. API-First ⚠️ PARTIAL

**Status**: This feature is CLI-only, which is acceptable as it's an administrative tool

**Justification**:
- User mapping management already has REST API (POST/PUT/DELETE /api/user-mappings - feature 042)
- CLI provides convenience wrapper for administrators, not new functionality
- Web UI already exists for user mapping management
- CLI targets automation/scripting use cases (batch imports, scheduled jobs)

**Compliance**: Not a gate violation - administrative CLI tools complement but don't replace APIs

### IV. User-Requested Testing ✅

**Compliance**:
- Test planning will ONLY occur if user explicitly requests testing
- Implementation will follow TDD if tests are requested
- JUnit/Micronaut Test framework already available in CLI module

### V. Role-Based Access Control (RBAC) ✅

**Compliance**:
- ✅ FR-021: ADMIN role required for all operations
- ✅ Authentication check at CLI command entry point
- ✅ Service layer validates role before database operations
- ✅ Audit logs capture actor identity

**Implementation Notes**:
- Reuse existing SecurityService for role validation
- Commands fail fast with authorization error if non-ADMIN user attempts execution

### VI. Schema Evolution ✅

**Compliance**:
- ✅ Reuses existing UserMapping entity (already has domain/awsAccountId fields)
- ✅ Add `status` ENUM field (ACTIVE, PENDING) via Hibernate migration
- ✅ Add database indexes for email lookups (performance optimization)
- ✅ No data loss - new column with default value (ACTIVE for existing records)

**Schema Changes Required**:
```sql
-- Hibernate will auto-generate similar DDL
ALTER TABLE user_mapping ADD COLUMN status VARCHAR(20) DEFAULT 'ACTIVE';
CREATE INDEX idx_user_mapping_email ON user_mapping(user_email);
CREATE INDEX idx_user_mapping_status ON user_mapping(status);
```

### Technology Stack Alignment ✅

**Backend**: Kotlin 2.2.21 + Micronaut 4.10 + Hibernate JPA ✅
**Database**: MariaDB 12 ✅
**File Processing**: Apache Commons CSV 1.11.0, Jackson for JSON ✅

### Git Workflow ✅

**Branch**: `049-cli-user-mappings` ✅
**Commits**: Will follow conventional commits (feat(cli): add user mapping commands)
**Documentation**: This plan.md + CLI documentation file per FR-012

## Project Structure

### Documentation (this feature)

```text
specs/049-cli-user-mappings/
├── plan.md              # This file
├── research.md          # Phase 0 output (CLI patterns, batch processing)
├── data-model.md        # Phase 1 output (UserMapping schema)
├── quickstart.md        # Phase 1 output (CLI usage examples)
├── contracts/           # Phase 1 output (command signatures, batch file schemas)
│   ├── commands.md      # CLI command specifications
│   └── batch-formats.md # CSV/JSON file format specifications
└── tasks.md             # Phase 2 output (NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/cli/
├── src/main/kotlin/com/secman/cli/
│   ├── commands/
│   │   ├── SendNotificationsCommand.kt          # Existing
│   │   └── ManageUserMappingsCommand.kt         # NEW - main command group
│   ├── service/
│   │   ├── NotificationService.kt               # Existing
│   │   └── UserMappingCliService.kt             # NEW - CLI-specific service
│   └── model/
│       └── BatchMappingResult.kt                # NEW - result DTO
│
src/backendng/src/main/kotlin/com/secman/
├── domain/
│   └── UserMapping.kt                           # MODIFY - add status field
├── repository/
│   └── UserMappingRepository.kt                 # EXISTS - reuse from feature 042
└── service/
    └── UserMappingService.kt                    # EXISTS - extend for pending logic

src/cli/src/main/resources/
└── cli-docs/
    └── USER_MAPPING_COMMANDS.md                 # NEW - comprehensive CLI docs

tests/ (if requested)
└── cli/
    ├── commands/
    │   └── ManageUserMappingsCommandTest.kt
    └── service/
        └── UserMappingCliServiceTest.kt
```

**Structure Decision**: Extends existing CLI module (`src/cli/`) with new command group. Reuses backend domain/repository/service layers from feature 042. Follows established pattern from `SendNotificationsCommand` for authentication, logging, and error handling.

## Complexity Tracking

No constitution violations - all gates passed or justified.

| Item | Status | Notes |
|------|--------|-------|
| API-First (CLI-only feature) | ⚠️ Acknowledged | CLI complements existing REST API, targets admin automation |
| Schema Changes (status field) | ✅ Safe | Non-breaking - default value for existing records |
| Reuse of Feature 042 entities | ✅ Good | Reduces complexity, maintains consistency |
