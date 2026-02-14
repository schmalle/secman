<!--
Sync Impact Report:
- Version change: v2.0.0 → v3.0.0
- Principles added:
  * Principle VII: Mandatory Security Review (NEW - every code change requires security review)
  * Principle VIII: Mandatory Documentation (NEW - every change requires documentation updates)
  * Principle IX: Mandatory Test Scripts (NEW - every change requires runnable test script)
  * Principle X: Mandatory MCP Availability (NEW - new/changed functions must be MCP tools)
- Development Workflow updated:
  * Added security review gate to Pull Requests
  * Added documentation update gate to Pull Requests
  * Added test script gate to Pull Requests
  * Added MCP availability gate to Pull Requests
- Templates requiring updates:
  ✅ CLAUDE.md - Principles updated with new requirements
  ✅ AGENTS.md - Guidelines updated with new requirements
  ✅ .github/copilot-instructions.md - Instructions updated
  ✅ .github/PULL_REQUEST_TEMPLATE.md - Created with compliance checklist
  ✅ docs/TESTING.md - Updated with mandatory test script section
  ✅ docs/MCP.md - Updated with MCP availability requirement
  ✅ SECURITY.md - Updated with mandatory review process
- Follow-up TODOs: None
-->

# Secman Constitution

## Core Principles

### I. Security-First

All features MUST implement security as a primary concern, not an afterthought.

**Requirements**:
- File uploads MUST validate size, format, and content-type before processing
- All user input MUST be sanitized to prevent injection attacks
- RBAC MUST be enforced at both API endpoint level (@Secured annotations) and UI level (role checks)
- Sensitive data MUST NOT be logged or exposed in error messages
- Authentication tokens MUST be stored securely (sessionStorage for JWT)
- A secutity review must be done before finalizing the implementation

**Rationale**: Security vulnerabilities in a security requirements management tool are unacceptable and undermine the entire purpose of the system.

### III. API-First

All backend functionality MUST be exposed through well-defined RESTful APIs with backward compatibility guarantees.

**Requirements**:
- RESTful API design principles MUST be followed
- OpenAPI/Swagger documentation MUST be maintained
- Breaking changes require MAJOR version bump
- All endpoints MUST return consistent error formats
- API responses MUST include appropriate HTTP status codes
- Backward compatibility MUST be maintained within major versions

**Rationale**: API-first design enables frontend flexibility, third-party integrations, and MCP tool support.

### IV. User-Requested Testing

Test planning and preparation MUST ONLY occur when explicitly requested by the user.

**Requirements**:
- NEVER proactively prepare test cases, test plans, or test task lists unless the user explicitly requests testing
- Test-related tasks in tasks.md MUST be clearly marked as OPTIONAL and only included when requested
- Testing frameworks and infrastructure (JUnit, Playwright, pytest) remain required per TDD principle, but planning of specific test cases requires user request
- When tests ARE requested, they MUST follow TDD principles (written first, fail before implementation)

**Rationale**: Respects user autonomy and avoids unnecessary preparation work. Users may have different testing strategies, timelines, or may wish to focus on implementation first. This principle separates the requirement to WRITE tests (TDD) from the requirement to PLAN tests (user-driven).

### V. Role-Based Access Control (RBAC)

Access control MUST be consistently enforced across all layers.

**Requirements**:
- All API endpoints MUST use @Secured annotations
- Roles: USER, ADMIN, VULN, RELEASE_MANAGER
- Frontend MUST check roles before rendering UI elements
- Workgroup-based filtering MUST be applied to data queries
- Users MUST only see resources they have access to (workgroups + owned items)
- Authorization checks MUST happen at service layer, not just controller

**Rationale**: Fine-grained access control is essential for multi-tenant security and compliance.

### VI. Schema Evolution

Database schema changes MUST be managed through automated migration with appropriate constraints.

**Requirements**:
- Hibernate auto-migration MUST be used (ddl-auto configured appropriately)
- Database constraints MUST be defined in entity annotations
- Foreign key relationships MUST be explicit
- Indexes MUST be created for frequently queried columns
- Migration MUST be testable in development before production deployment
- Schema changes MUST NOT cause data loss without explicit approval
- flyway migration scripts must be created
- all functionality must be referenced in the corresponding documentation (where needed for end users)

**Rationale**: Automated migration reduces deployment errors and ensures schema-code consistency.

### VII. Mandatory Security Review

Every code change MUST undergo a security review before it is considered complete.

**Requirements**:
- All code changes MUST be reviewed against OWASP Top 10 vulnerabilities before merge
- Security review MUST cover: input validation, authentication/authorization checks, data exposure risks, injection vectors (SQL, command, XSS), and sensitive data handling
- Security findings MUST be documented in the pull request description under a "Security Review" section
- Changes to authentication, authorization, or data access patterns require explicit security sign-off
- New API endpoints MUST document their security model (authentication requirement, role restrictions, input validation)
- File upload changes MUST verify size limits, content-type validation, and path traversal prevention

**Rationale**: A security management tool must hold itself to the highest security standards. Every change is an opportunity to introduce vulnerabilities, and systematic review prevents regression.

### VIII. Mandatory Documentation

Every code change MUST include corresponding documentation updates.

**Requirements**:
- CLAUDE.md MUST be updated when adding or changing entities, endpoints, patterns, or configuration
- docs/ files MUST be updated for user-facing changes (API → MCP.md, tests → TESTING.md, deployment → DEPLOYMENT.md, env → ENVIRONMENT.md)
- New API endpoints MUST be documented with method, path, authentication, request/response format, and role restrictions
- Changes to existing APIs MUST update corresponding documentation
- Configuration changes MUST be documented in ENVIRONMENT.md with variable names, defaults, and descriptions
- A code change without documentation is incomplete and MUST NOT be merged

**Rationale**: Undocumented changes create knowledge gaps that compound over time, making the system harder to maintain and onboard new contributors.

### IX. Mandatory Test Scripts

Every code change MUST include a corresponding test script that validates the change end-to-end.

**Requirements**:
- Every code change MUST include a runnable test script in `scripts/test/` that validates the changed functionality
- Test scripts MUST be executable standalone (no manual setup beyond starting the backend)
- Test scripts MUST document expected inputs, expected outputs, and success/failure criteria
- Test scripts MUST cover the happy path and at least one error case
- Test scripts SHOULD use curl/httpie for API testing and shell scripts for CLI testing
- Test script naming convention: `test-<feature-name>.sh`
- Test scripts MUST exit with code 0 on success and non-zero on failure
- Existing test scripts MUST be updated when tested functionality changes

**Rationale**: Automated test scripts provide repeatable verification that catches regressions early and serves as executable documentation of expected behavior.

### X. Mandatory MCP Availability

Every new or changed backend function that exposes data or performs actions MUST be available as an MCP tool.

**Requirements**:
- New service functions that query, create, update, or delete data MUST have a corresponding MCP tool
- MCP tools MUST be registered in the appropriate MCP controller with proper permission checks
- MCP tool parameters MUST match the service function's input requirements
- MCP tools MUST include clear descriptions for the tool and each parameter
- MCP tool permissions MUST align with the REST API endpoint's @Secured annotations
- docs/MCP.md MUST be updated with new tool documentation (parameters, permissions, examples)
- Existing MCP tools MUST be updated when underlying service function signature or behavior changes
- MCP tools requiring user context MUST enforce User Delegation

**Rationale**: MCP availability ensures all functionality is accessible to AI assistants and automation, maintaining parity between REST API and MCP interfaces.

## Technology Stack

**Backend**:
- Language: Kotlin 2.3.0 / Java 25
- Framework: Micronaut 4.10
- ORM: Hibernate JPA
- Database: MariaDB 11.4
- File Processing: Apache POI 5.3 (Excel), Apache Commons CSV 1.11.0 (CSV)

**Frontend**:
- Framework: Astro 5.14 with React 19 islands
- UI: Bootstrap 5.3
- API Client: Axios


## Development Workflow

### Git Workflow

- **Branching**: Feature branches MUST use pattern `###-feature-name`
- **Commits**: Conventional commits REQUIRED: `type(scope): description`
  - Types: feat, fix, docs, test, refactor, chore
  - Example: `feat(assets): add workgroup-based filtering`
- **Pull Requests**: MUST pass all gates before merge:
  - All tests passing (backend + frontend + helper)
  - Linting passing
  - Code review approved
  - Security review completed and documented in PR description
  - Documentation updated (CLAUDE.md, docs/, API docs as applicable)
  - Test script included in `scripts/test/` for the changed functionality
  - MCP tools added/updated for new/changed backend functions


### Documentation

- **Feature Specs**: MUST be created in `specs/###-feature/` before implementation
- **API Documentation**: OpenAPI/Swagger MUST be kept current
- **README**: MUST be updated when setup instructions change
- **CLAUDE.md**: MUST be updated with new entities, endpoints, and patterns

## Governance

### Constitution Authority

- This constitution supersedes all other development practices
- All code reviews MUST verify constitutional compliance
- Violations MUST be justified in "Complexity Tracking" section of plan.md
- Team leads MAY grant temporary exceptions for time-critical issues (documented)

### Amendment Process

1. Proposal MUST be documented with rationale
2. Team discussion REQUIRED before approval
3. Version MUST be incremented per semantic versioning:
   - MAJOR: Backward incompatible governance changes (e.g., removing principles)
   - MINOR: New principles or substantial expansions
   - PATCH: Clarifications, typos, non-semantic refinements
4. Migration plan REQUIRED for breaking changes
5. All dependent documentation MUST be updated

### Compliance Review

- All PRs MUST include constitutional compliance self-check
- Code reviews MUST verify security-first implementation
- Quarterly constitutional compliance audits REQUIRED
- Technical debt MUST be tracked and justified against principles

### Runtime Guidance

For detailed implementation patterns and examples, see `CLAUDE.md`.

**Version**: 3.0.0 | **Ratified**: 2025-10-07 | **Last Amended**: 2026-02-14
