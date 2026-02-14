## Summary

<!-- Brief description of the changes and motivation -->

## Changes

<!-- Bulleted list of changes made -->

-

## Security Review

<!-- MANDATORY: Every PR must include a security review. Check all that apply. -->

- [ ] Reviewed against OWASP Top 10 vulnerabilities
- [ ] Input validation checked (no unsanitized user input reaches DB/shell/HTML)
- [ ] Authentication/authorization verified (@Secured annotations, role checks)
- [ ] No sensitive data exposed in logs, responses, or error messages
- [ ] No injection vectors (SQL, command, XSS, path traversal)
- [ ] File uploads validate size, content-type, and prevent path traversal
- [ ] New API endpoints document their security model below:

**Security model for new endpoints (if applicable):**
<!-- Endpoint | Auth Required | Roles | Input Validation -->

**Security findings/mitigations:**
<!-- Document any security concerns found and how they were addressed -->

## Documentation

<!-- MANDATORY: Every PR must include documentation updates. -->

- [ ] CLAUDE.md updated (entities, endpoints, patterns, configuration)
- [ ] Relevant docs/ files updated (MCP.md, TESTING.md, DEPLOYMENT.md, ENVIRONMENT.md)
- [ ] New API endpoints documented (method, path, auth, request/response, roles)
- [ ] Configuration changes documented in ENVIRONMENT.md
- [ ] N/A - No documentation changes needed (justify below)

**Justification if N/A:**

## Test Script

<!-- MANDATORY: Every PR must include a test script. -->

- [ ] Test script added/updated in `scripts/test/`
- [ ] Test script is executable standalone
- [ ] Test script covers happy path and at least one error case
- [ ] Test script exits 0 on success, non-zero on failure
- [ ] N/A - No testable changes (justify below)

**Test script path:** `scripts/test/`
**Justification if N/A:**

## MCP Availability

<!-- MANDATORY: New/changed backend functions must be available via MCP. -->

- [ ] MCP tool added/updated for new/changed backend functions
- [ ] MCP tool registered with proper permissions in MCP controller
- [ ] MCP tool documented in docs/MCP.md (parameters, permissions, examples)
- [ ] MCP tool permissions align with REST API @Secured annotations
- [ ] N/A - No new/changed backend functions (justify below)

**New/updated MCP tools:**
**Justification if N/A:**

## Constitutional Compliance

<!-- Self-check against project constitution (.specify/memory/constitution.md) -->

- [ ] Security-First principle satisfied
- [ ] API-First principle satisfied (backward compatible)
- [ ] RBAC enforced at endpoint and UI level
- [ ] Schema changes use Hibernate auto-migration
- [ ] `./gradlew build` passes with no errors
