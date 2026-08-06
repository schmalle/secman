# Implementation Plan: CLI and MCP Requirements Management

**Branch**: `057-cli-mcp-requirements` | **Date**: 2025-12-29 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/057-cli-mcp-requirements/spec.md`

## Summary

Add requirements management capabilities to both CLI and MCP interfaces:
- **CLI**: `export-requirements` (Excel/Word), `add-requirement`, `delete-all-requirements`
- **MCP**: `export_requirements`, `add_requirement`, `delete_all_requirements`

All functionality reuses existing backend endpoints - no new API development required.

## Technical Context

**Language/Version**: Kotlin 2.2.21 / Java 21
**Primary Dependencies**: Micronaut 4.10, Picocli 4.7, Apache POI 5.3 (Excel), Apache Commons CSV
**Storage**: MariaDB 11.4 via Hibernate JPA (existing Requirement entity)
**Testing**: JUnit 5, Mockk, Testcontainers (per constitution: only when requested)
**Target Platform**: Linux server, CLI JAR distribution
**Project Type**: Web application (backend + frontend + CLI module)
**Performance Goals**: 1,000 requirements export in <30s (Excel), <60s (Word)
**Constraints**: Authentication required, ADMIN-only delete, files generated server-side
**Scale/Scope**: Supports 10,000+ requirements

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| I. Security-First | ✅ PASS | Authentication required (FR-017), RBAC enforced (FR-018-020), delete requires ADMIN |
| III. API-First | ✅ PASS | Reuses existing REST endpoints, no breaking changes |
| IV. User-Requested Testing | ✅ PASS | No test tasks created proactively |
| V. RBAC | ✅ PASS | @Secured annotations on existing endpoints, role checks in CLI |
| VI. Schema Evolution | ✅ PASS | No schema changes - uses existing Requirement entity |

**Gate Result**: PASS - Proceed to Phase 0

## Project Structure

### Documentation (this feature)

```text
specs/057-cli-mcp-requirements/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (reuses existing)
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
src/
├── backendng/                              # Backend (existing)
│   └── src/main/kotlin/com/secman/
│       ├── controller/
│       │   └── RequirementController.kt    # Existing export endpoints
│       ├── mcp/
│       │   ├── McpToolRegistry.kt          # Register new tools
│       │   └── tools/
│       │       ├── ExportRequirementsTool.kt    # NEW
│       │       ├── AddRequirementTool.kt        # NEW
│       │       └── DeleteAllRequirementsTool.kt # NEW
│       └── service/
│           └── RequirementService.kt       # Existing, may need minor updates
├── cli/                                    # CLI module
│   └── src/main/kotlin/com/secman/cli/
│       ├── SecmanCli.kt                    # Add new command routing
│       ├── commands/
│       │   ├── ExportRequirementsCommand.kt     # NEW
│       │   ├── AddRequirementCommand.kt         # NEW
│       │   └── DeleteAllRequirementsCommand.kt  # NEW
│       └── service/
│           └── RequirementCliService.kt         # NEW
└── frontend/                               # No changes required
```

**Structure Decision**: Extends existing web application structure. CLI module already exists with established patterns (Picocli commands with service layer). MCP tools follow existing registration pattern in McpToolRegistry.

## Complexity Tracking

> No constitution violations. Implementation follows established patterns.

| Area | Complexity | Justification |
|------|------------|---------------|
| CLI Commands | Low | Follow AddVulnerabilityCommand pattern exactly |
| MCP Tools | Low | Follow GetRequirementsTool pattern exactly |
| Backend | Minimal | Reuse existing endpoints, no new API needed |

---

## Constitution Check (Post-Design)

*Re-evaluation after Phase 1 design completion.*

| Principle | Status | Post-Design Evidence |
|-----------|--------|---------------------|
| I. Security-First | ✅ PASS | All endpoints require authentication; delete requires ADMIN + confirmation flag; no sensitive data in logs |
| III. API-First | ✅ PASS | Reuses existing REST endpoints; MCP tools follow established schema patterns; backward compatible |
| IV. User-Requested Testing | ✅ PASS | No test tasks included in plan; testing only when explicitly requested |
| V. RBAC | ✅ PASS | Permission mapping defined in contracts; CLI checks roles via backend; MCP uses permission registry |
| VI. Schema Evolution | ✅ PASS | No database changes; uses existing Requirement entity |

**Final Gate Result**: PASS - Ready for task generation (`/speckit.tasks`)

---

## Generated Artifacts

| Artifact | Path | Status |
|----------|------|--------|
| Implementation Plan | `specs/057-cli-mcp-requirements/plan.md` | Complete |
| Research | `specs/057-cli-mcp-requirements/research.md` | Complete |
| Data Model | `specs/057-cli-mcp-requirements/data-model.md` | Complete |
| API Contracts | `specs/057-cli-mcp-requirements/contracts/api-contracts.md` | Complete |
| Quickstart Guide | `specs/057-cli-mcp-requirements/quickstart.md` | Complete |

---

## Next Steps

1. Run `/speckit.tasks` to generate implementation task list
2. Implement CLI commands following Picocli patterns
3. Implement MCP tools following McpTool interface
4. Update SecmanCli.kt to route new commands
5. Update McpToolRegistry to register new tools
6. Update CLAUDE.md with new CLI commands documentation
