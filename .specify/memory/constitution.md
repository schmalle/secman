<!--
Sync Impact Report:
- Version change: Initial creation → v1.0.0
- Principles migrated from CLAUDE.md:
  1. Security-First
  2. TDD (Test-Driven Development)
  3. API-First
  4. Docker-First
  5. RBAC (Role-Based Access Control)
  6. Schema Evolution
- Added sections: Development Workflow, Technology Stack
- Templates requiring updates:
  ✅ plan-template.md - Constitution Check gate references this file
  ✅ spec-template.md - Requirements align with security and testing principles
  ✅ tasks-template.md - Task categorization reflects TDD and testing discipline
- Follow-up TODOs: Update runtime guidance in CLAUDE.md to reference this constitution
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

**Rationale**: Security vulnerabilities in a security requirements management tool are unacceptable and undermine the entire purpose of the system.

### II. Test-Driven Development (NON-NEGOTIABLE)

Tests MUST be written before implementation. The Red-Green-Refactor cycle is strictly enforced.

**Requirements**:
- Contract tests written first for all new API endpoints
- Integration tests written for cross-component interactions
- Unit tests written for business logic
- Tests MUST fail before implementation begins
- Test coverage target: ≥80%
- Backend: JUnit 5 + MockK required
- Frontend: Playwright for E2E testing required
- Helper tools: pytest required

**Rationale**: TDD ensures code correctness, prevents regression, and serves as living documentation.

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

### IV. Docker-First

All components MUST be containerized and deployable via Docker Compose.

**Requirements**:
- Dockerfile MUST be provided for each service
- Multi-arch support REQUIRED (AMD64/ARM64)
- Environment configuration via .env files (never hardcoded)
- docker-compose.yml MUST define all services and dependencies
- Health checks MUST be implemented for all services
- Volumes MUST be used for persistent data

**Rationale**: Containerization ensures consistent deployment, simplifies development setup, and enables portability.

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

**Rationale**: Automated migration reduces deployment errors and ensures schema-code consistency.

## Technology Stack

**Backend**:
- Language: Kotlin 2.1.0 / Java 21
- Framework: Micronaut 4.4
- ORM: Hibernate JPA
- Database: MariaDB 11.4
- File Processing: Apache POI 5.3 (Excel)
- Testing: JUnit 5 + MockK

**Frontend**:
- Framework: Astro 5.14 with React 19 islands
- UI: Bootstrap 5.3
- API Client: Axios
- Testing: Playwright (E2E)

**Helper Tools**:
- Language: Python 3.11+
- Libraries: falconpy (CrowdStrike), openpyxl (Excel), argparse (CLI)
- Testing: pytest

**Infrastructure**:
- Deployment: Docker Compose
- Multi-arch: AMD64 + ARM64

## Development Workflow

### Git Workflow

- **Branching**: Feature branches MUST use pattern `###-feature-name`
- **Commits**: Conventional commits REQUIRED: `type(scope): description`
  - Types: feat, fix, docs, test, refactor, chore
  - Example: `feat(assets): add workgroup-based filtering`
- **Pull Requests**: MUST pass all gates before merge:
  - All tests passing (backend + frontend + helper)
  - Linting passing
  - Docker build successful
  - Code review approved

### Testing Gates

- **Pre-commit**: Linters MUST pass (backend + frontend + helper)
- **Pre-merge**: Full test suite MUST pass
- **Pre-deployment**: E2E tests MUST pass in staging environment

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
   - MAJOR: Backward incompatible governance changes
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

**Version**: 1.0.0 | **Ratified**: 2025-10-07 | **Last Amended**: 2025-10-07