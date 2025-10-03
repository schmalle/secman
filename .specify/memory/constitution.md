<!--
Sync Impact Report:
- Version change: none → 1.0.0
- Modified principles: N/A (initial version)
- Added sections: All core sections (initial creation)
- Removed sections: N/A
- Templates requiring updates:
  ✅ .specify/templates/plan-template.md - reviewed, no changes needed
  ✅ .specify/templates/spec-template.md - reviewed, no changes needed
  ✅ .specify/templates/tasks-template.md - reviewed, no changes needed
  ✅ .specify/templates/agent-file-template.md - reviewed, no changes needed
- Follow-up TODOs: None
-->

# secman Constitution

## Core Principles

### I. Security-First Development
Every feature MUST be evaluated for security implications before implementation. All user input MUST be validated and sanitized. Authentication and authorization MUST be enforced at both API and UI layers. Security events MUST be logged for audit purposes.

**Rationale**: As a security requirement and risk assessment management tool, secman handles sensitive organizational data. Security cannot be an afterthought but must be embedded in every development decision to maintain user trust and regulatory compliance.

### II. Test-Driven Development (NON-NEGOTIABLE)
Tests MUST be written before implementation. The Red-Green-Refactor cycle MUST be strictly followed: write failing tests, get user approval, implement to pass tests, then refactor. All new features require both unit tests and integration tests.

**Rationale**: TDD ensures code correctness, prevents regressions, and serves as living documentation. For a tool managing critical security requirements, untested code is unacceptable risk.

### III. API-First Architecture
Backend services MUST expose RESTful APIs with clear contracts. API changes MUST maintain backward compatibility or follow proper versioning. API documentation MUST be generated and kept current. Frontend components MUST interact with backend exclusively through documented APIs.

**Rationale**: API-first design enables frontend/backend team independence, facilitates testing, supports future integrations (MCP server, mobile apps), and ensures clear separation of concerns in the full-stack architecture.

### IV. Docker-First Deployment
All services MUST be containerized and deployable via Docker Compose. Environment configuration MUST use .env files, never hardcoded values. Multi-architecture support (AMD64/ARM64) MUST be maintained. Development and production environments MUST use identical container configurations with different .env files.

**Rationale**: Docker ensures consistent environments across development, testing, and production, eliminates "works on my machine" issues, and simplifies onboarding and deployment for the alpha-stage project.

### V. Role-Based Access Control
All features MUST respect user roles (normaluser, adminuser). Authorization checks MUST occur at both API endpoints and UI components. Administrative functions MUST be restricted to adminuser role. Privilege escalation MUST be prevented and logged.

**Rationale**: RBAC is fundamental to secman's purpose as an enterprise security tool. Improper access control would undermine the entire value proposition and create liability.

### VI. Database Schema Evolution
Database migrations MUST be automated and versioned. Schema changes MUST be backward-compatible during deployment. Data integrity constraints MUST be enforced at the database level. Manual schema modifications are FORBIDDEN in production.

**Rationale**: Micronaut with Hibernate auto-creates tables, but production stability requires controlled migrations. The MariaDB backend stores critical security data that cannot be corrupted by ad-hoc schema changes.

## Development Workflow

### Code Review Requirements
All pull requests MUST pass automated tests before review. Security-sensitive changes MUST receive explicit security review. Breaking changes MUST include migration documentation and backward compatibility plan where feasible.

### Quality Gates
- Tests MUST achieve minimum 80% code coverage for new code
- Linting MUST pass (backend: Kotlin conventions, frontend: ESLint)
- Docker builds MUST succeed for both AMD64 and ARM64
- Security scans MUST show no critical vulnerabilities
- API documentation MUST be regenerated for endpoint changes

### Commit Standards
Commits MUST follow conventional commit format: `type(scope): description`. Breaking changes MUST be marked with `BREAKING CHANGE:` in commit body. Security fixes MUST use `fix(security):` prefix.

## Technology Constraints

### Stack Stability
Core stack is FROZEN for 1.0 release: Micronaut/Kotlin backend, Astro/React frontend, MariaDB 11.4, Docker. Technology additions MUST be justified against security, maintenance burden, and team expertise.

### Dependency Management
Dependencies MUST be pinned to specific versions. Security updates MUST be applied within 7 days of disclosure. New dependencies MUST be approved based on: active maintenance, security track record, license compatibility (AGPL-3.0), and necessity.

### Performance Standards
API endpoints MUST respond within 200ms (p95) for typical payloads. Frontend pages MUST achieve Lighthouse score ≥90. Database queries MUST use indexes for all filtering/sorting operations. Export operations (Word generation) MAY take longer but MUST provide progress feedback.

## Governance

This Constitution supersedes all other development practices. Amendments require:
1. Documented rationale for the change
2. Impact analysis on existing features
3. Migration plan for affected code
4. Approval from project maintainer (Markus "flake" Schmall)

All code reviews MUST verify constitutional compliance. Violations MUST be documented in plan.md Complexity Tracking section with explicit justification. Unjustifiable violations MUST block merge.

**Version**: 1.0.0 | **Ratified**: 2025-10-03 | **Last Amended**: 2025-10-03
