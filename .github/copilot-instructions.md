# secman Development Guidelines

Auto-generated from all feature plans. Last updated: 2026-02-14

## Active Technologies
- Kotlin 2.1.0 (Backend), TypeScript/JavaScript (Frontend with React 19 + Astro 5.14) + Micronaut 4.4, Hibernate JPA, MariaDB 11.4 (Backend), Axios, Bootstrap 5.3 (Frontend) (017-user-mapping-management)
- Kotlin 2.1.0 (JVM target 21) (023-create-in-the)
- File-based (configuration: ~/.secman/crowdstrike.conf, exports: JSON/CSV to user-specified paths) (023-create-in-the)

## Project Structure
```
src/
tests/
```

## Commands
npm test [ONLY COMMANDS FOR ACTIVE TECHNOLOGIES][ONLY COMMANDS FOR ACTIVE TECHNOLOGIES] npm run lint

## Code Style
Kotlin 2.1.0 (Backend), TypeScript/JavaScript (Frontend with React 19 + Astro 5.14): Follow standard conventions

## Recent Changes
- 023-create-in-the: Added Kotlin 2.1.0 (JVM target 21)
- 017-user-mapping-management: Added Kotlin 2.1.0 (Backend), TypeScript/JavaScript (Frontend with React 19 + Astro 5.14) + Micronaut 4.4, Hibernate JPA, MariaDB 11.4 (Backend), Axios, Bootstrap 5.3 (Frontend)

<!-- MANUAL ADDITIONS START -->

## Mandatory Change Requirements

Every code change MUST satisfy ALL of the following before it is considered complete:

### 1. Security Review (MANDATORY)
- Review every change against OWASP Top 10 vulnerabilities
- Check input validation, authentication/authorization, data exposure, injection vectors
- Document security findings in the PR description under a "Security Review" section
- Changes to auth, authorization, or data access require explicit security sign-off
- New API endpoints must document their security model (authentication, roles, validation)

### 2. Documentation (MANDATORY)
- Update CLAUDE.md when adding/changing entities, endpoints, patterns, or configuration
- Update relevant docs/ files for user-facing changes (MCP.md, TESTING.md, DEPLOYMENT.md, ENVIRONMENT.md)
- New API endpoints must be documented with method, path, auth, request/response, and roles
- A code change without documentation is incomplete and must not be merged

### 3. Test Script (MANDATORY)
- Include a runnable test script in `scripts/test/` that validates the changed functionality
- Test scripts must be executable standalone and document expected inputs/outputs
- Cover the happy path and at least one error case
- Naming convention: `test-<feature-name>.sh`
- Exit code 0 on success, non-zero on failure

### 4. MCP Availability (MANDATORY)
- Every new or changed backend function that exposes data or performs actions MUST be available as an MCP tool
- Register in the appropriate MCP controller with proper permissions
- Update docs/MCP.md with tool documentation (parameters, permissions, examples)
- MCP tool permissions must align with REST API @Secured annotations
- MCP tools requiring user context must enforce User Delegation

<!-- MANUAL ADDITIONS END -->
