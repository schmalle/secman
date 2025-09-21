<!--
Sync Impact Report - Secman Constitution v1.0.0
=================================================
Version change: N/A → 1.0.0 (initial ratification)
Modified principles: N/A (initial creation)
Added sections: All sections (initial creation)
Removed sections: N/A
Templates requiring updates:
  ✅ .specify/templates/plan-template.md (checked - references constitution version format)
  ✅ .specify/templates/spec-template.md (checked - no direct constitutional dependencies)
  ✅ .specify/templates/tasks-template.md (checked - compatible with TDD principle)
Follow-up TODOs: None - all placeholders resolved
-->

# Secman Constitution

## Core Principles

### I. Security-First Development
All code and design decisions MUST prioritize security as the primary concern. Security requirements are non-negotiable and supersede convenience or speed of development. Every feature implementation requires security review including authentication, authorization, input validation, and data protection. Vulnerabilities identified in code review immediately block deployment until resolved.

### II. Test-Driven Quality
TDD is mandatory for all development: Tests written → User approved → Tests fail → Then implement. The Red-Green-Refactor cycle is strictly enforced. Contract tests are required for all API endpoints. Integration tests are mandatory for MCP endpoints, OAuth flows, and database operations. Tests must fail before implementation begins.

### III. MCP Integration Standards
All AI assistant capabilities MUST be exposed through the Model Context Protocol server. MCP endpoints require API key authentication and follow JSON-RPC 2.0 specification. Session management with proper cleanup is mandatory. Real-time capabilities via Server-Sent Events are required for live updates. MCP tools must be stateless and resumable.

### IV. API-First Architecture
Every feature starts with API design using OpenAPI specification. REST endpoints follow standard conventions with proper HTTP status codes. Authentication via JWT tokens is required for protected endpoints. CORS configuration must be explicit and restrictive. All API responses must include proper error handling and validation.

### V. Configuration Over Hardcoding
All environment-specific values MUST be externalized to configuration files or environment variables. Database connections, OAuth provider settings, JWT secrets, and API keys are never hardcoded. Development, testing, and production environments require separate configuration profiles. Default configurations must be secure and fail-safe.

## Security Requirements

Security controls are mandatory and cannot be compromised:
- JWT tokens with proper expiration and secret rotation
- BCrypt password hashing with appropriate cost factors
- OAuth2 state verification to prevent CSRF attacks
- Input validation and sanitization on all endpoints
- SQL injection prevention through parameterized queries
- File upload restrictions and virus scanning
- Audit logging for all authentication and authorization events
- Secure headers (CORS, CSP, HSTS) on all responses

## Development Workflow

Code quality gates must pass before merge:
- All tests pass (unit, integration, contract, end-to-end)
- Security review completed for any authentication/authorization changes
- MCP endpoint compatibility verified against protocol specification
- Performance benchmarks met (<200ms API response times)
- Database migration scripts tested on staging environment
- Configuration changes validated across all environments
- Documentation updated for API changes and new features

## Governance

This constitution supersedes all other development practices and must be followed strictly. Any amendments require documentation of the change, approval justification, and migration plan for existing code. All pull requests and code reviews must verify constitutional compliance. Complexity additions must be justified against simpler alternatives.

**Version**: 1.0.0 | **Ratified**: 2025-09-21 | **Last Amended**: 2025-09-21