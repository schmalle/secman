# Implementation Plan: MCP List Users Tool

**Branch**: `060-mcp-list-users` | **Date**: 2026-01-04 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/060-mcp-list-users/spec.md`

## Summary

Implement a new MCP tool `list_users` that returns all secman users with their core attributes. The tool requires User Delegation to be enabled and verifies the delegated user has ADMIN role before returning user data. Follows existing MCP tool patterns (DeleteAllRequirementsTool for admin-only access pattern).

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA
**Storage**: MariaDB 11.4 (existing `users` table)
**Testing**: JUnit 5 + Mockk (only when explicitly requested per Constitution)
**Target Platform**: Linux server (existing secman backend)
**Project Type**: Web application (backend-only change for this feature)
**Performance Goals**: < 2 seconds for 1,000 users (SC-001)
**Constraints**: No pagination required (typical installations < 500 users)
**Scale/Scope**: Single new MCP tool, ~100 lines of code

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | Admin-only access via context.isAdmin check; password hash excluded from response |
| III. API-First | PASS | Follows existing MCP tool API pattern; no breaking changes |
| IV. User-Requested Testing | PASS | No tests planned unless requested |
| V. RBAC | PASS | ADMIN role enforced in tool execute(); delegation required |
| VI. Schema Evolution | PASS | No database schema changes required |

## Project Structure

### Documentation (this feature)

```text
specs/060-mcp-list-users/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
src/backendng/src/main/kotlin/com/secman/
├── mcp/
│   ├── McpToolRegistry.kt          # MODIFY: Register new tool
│   └── tools/
│       └── ListUsersTool.kt        # NEW: MCP tool implementation
└── dto/mcp/
    └── UserListDto.kt              # NEW: Response DTO (optional, may inline)
```

**Structure Decision**: Backend-only change following existing MCP tools pattern. New tool created in `mcp/tools/`, registered in `McpToolRegistry.kt`.

## Complexity Tracking

> No violations - implementation follows established patterns.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| N/A | N/A | N/A |
