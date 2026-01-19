# Implementation Plan: MCP and CLI User Mapping Upload

**Branch**: `064-mcp-cli-user-mapping` | **Date**: 2026-01-19 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/064-mcp-cli-user-mapping/spec.md`

## Summary

Expose the existing user mapping upload functionality (currently available via web UI at `/admin/user-mappings`) through MCP and CLI interfaces. This includes:
1. Two new MCP tools: `import_user_mappings` and `list_user_mappings`
2. CLI enhancements: `--format` option for list output, improved documentation
3. Documentation updates for both interfaces

## Technical Context

**Language/Version**: Kotlin 2.3.0 / Java 25
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, PicoCLI (CLI), MCP Protocol
**Storage**: MariaDB 11.4 (existing `user_mapping` table)
**Testing**: JUnit 5, Mockk (user-requested only per constitution)
**Target Platform**: Linux server / JVM
**Project Type**: Web (backend + CLI + frontend)
**Performance Goals**: 100 mappings imported in <10 seconds (SC-001)
**Constraints**: Reuse existing `UserMappingCliService` and `UserMappingRepository`
**Scale/Scope**: Batch import up to 1000 mappings per operation (reasonable default)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | MCP tools require User Delegation + ADMIN role (FR-002, FR-003, FR-013); CLI requires SECMAN_ADMIN_EMAIL |
| III. API-First | PASS | MCP tools follow established McpTool interface pattern; REST endpoints not impacted |
| IV. User-Requested Testing | PASS | No test tasks included unless explicitly requested |
| V. Role-Based Access Control | PASS | ADMIN role enforced via context.isAdmin check in MCP tools |
| VI. Schema Evolution | PASS | No schema changes required; uses existing `user_mapping` table |

**All gates pass. Proceeding with Phase 0.**

## Project Structure

### Documentation (this feature)

```text
specs/064-mcp-cli-user-mapping/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
└── tasks.md             # Phase 2 output (created by /speckit.tasks)
```

### Source Code (repository root)

```text
src/backendng/src/main/kotlin/com/secman/
├── mcp/
│   ├── tools/
│   │   ├── ImportUserMappingsTool.kt    # NEW: MCP import tool
│   │   └── ListUserMappingsTool.kt      # NEW: MCP list tool
│   └── McpToolRegistry.kt               # MODIFY: Register new tools
├── domain/
│   └── UserMapping.kt                   # EXISTING: No changes
├── repository/
│   └── UserMappingRepository.kt         # EXISTING: May need pagination query
└── service/
    └── UserMappingService.kt            # EXISTING: Reuse for MCP

src/cli/src/main/kotlin/com/secman/cli/
├── commands/
│   ├── ListCommand.kt                   # MODIFY: Add --format option
│   └── ImportCommand.kt                 # EXISTING: Documentation only
└── service/
    └── UserMappingCliService.kt         # EXISTING: Reuse for MCP

docs/
├── MCP_TOOLS.md                         # MODIFY: Add new tools documentation
└── CLI_USER_MAPPINGS.md                 # NEW: CLI user mappings guide
```

**Structure Decision**: Existing web application structure. New MCP tools follow established pattern in `src/backendng/src/main/kotlin/com/secman/mcp/tools/`. CLI enhancements in existing CLI module.

## Complexity Tracking

> No Constitution Check violations requiring justification.

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| Reuse existing services | Yes | `UserMappingCliService` already has import/list logic; MCP tools wrap this |
| Batch size limit | 1000 mappings | Reasonable default matching existing patterns; prevents timeout |
| MCP permission | USER_ACTIVITY | Follows pattern of other admin tools (list_users, add_user) |

## Constitution Re-Check (Post-Phase 1 Design)

| Principle | Status | Post-Design Notes |
|-----------|--------|-------------------|
| I. Security-First | PASS | MCP input schema validates all fields; existing validation reused |
| III. API-First | PASS | Contracts documented in `/contracts/mcp-tools.md` and `/contracts/cli-commands.md` |
| IV. User-Requested Testing | PASS | No test planning performed |
| V. Role-Based Access Control | PASS | Both MCP tools enforce ADMIN via `context.isAdmin` check |
| VI. Schema Evolution | PASS | No new tables or columns; reuses existing `user_mapping` schema |

**All gates pass after Phase 1 design.**
