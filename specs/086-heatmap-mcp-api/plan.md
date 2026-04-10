# Implementation Plan: Heatmap MCP and API Exposure

**Branch**: `086-heatmap-mcp-api` | **Date**: 2026-04-10 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/086-heatmap-mcp-api/spec.md`

## Summary

Expose the existing vulnerability heatmap data through two new MCP tools (`get_vulnerability_heatmap`, `refresh_vulnerability_heatmap`) and a CORS-enabled external REST endpoint. All channels enforce the same unified access control. MCP documentation is updated to include the new tools.

## Technical Context

**Language/Version**: Kotlin 2.3.20 / Java 21  
**Primary Dependencies**: Micronaut 4.10, Hibernate JPA, MCP Server (JSON-RPC 2.0)  
**Storage**: MariaDB 11.4 (existing `asset_heatmap_entry` table — no schema changes)  
**Testing**: JUnit 6, Mockk (user-requested only per Constitution IV)  
**Target Platform**: Linux server (JVM)  
**Project Type**: Web service  
**Performance Goals**: Heatmap query < 2s for 5,000 assets  
**Constraints**: Must reuse existing MCP auth (API key + user delegation) and access control patterns  
**Scale/Scope**: ~2,000 assets currently; designed for up to 5,000

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Security-First | PASS | MCP tools use existing API key + user delegation auth. External endpoint validates API key. No new attack surface beyond existing MCP patterns. |
| III. API-First | PASS | RESTful external endpoint. MCP tools follow JSON-RPC 2.0. Backward compatible (additive only). |
| IV. User-Requested Testing | PASS | No test tasks generated unless user requests them. |
| V. RBAC | PASS | MCP tools enforce access control via `McpExecutionContext.getFilterableAssetIds()`. External endpoint uses delegated user identity for filtering. ADMIN-only refresh enforced at execution time. |
| VI. Schema Evolution | PASS | No database changes. Existing `asset_heatmap_entry` table reused. MCP.md and CLAUDE.md documentation updated. |

**Post-Phase 1 re-check**: All gates still pass. No new entities, no new permissions types, no new auth mechanisms.

## Project Structure

### Documentation (this feature)

```text
specs/086-heatmap-mcp-api/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 research decisions
├── data-model.md        # Data model (existing entities, response shapes)
├── quickstart.md        # Usage examples
├── contracts/
│   ├── mcp-tools.md     # MCP tool input/output contracts
│   └── external-api.md  # External REST endpoint contract
└── checklists/
    └── requirements.md  # Spec quality checklist
```

### Source Code (repository root)

```text
src/backendng/src/main/kotlin/com/secman/
├── mcp/tools/
│   ├── GetVulnerabilityHeatmapTool.kt    # NEW: MCP query tool
│   └── RefreshVulnerabilityHeatmapTool.kt # NEW: MCP refresh tool
├── mcp/
│   └── McpToolRegistry.kt                # MODIFY: Register 2 new tools
├── controller/
│   └── ExternalHeatmapController.kt      # NEW: CORS-enabled REST endpoint
└── config/
    └── (application.yml)                  # MODIFY: Add external CORS config

docs/
└── MCP.md                                # MODIFY: Add heatmap tool documentation
```

**Structure Decision**: Follows existing project layout. MCP tools go in `mcp/tools/`, external REST controller in `controller/`. No new packages or modules.

## Implementation Tasks

### Task 1: Create GetVulnerabilityHeatmapTool (FR-001, FR-002, FR-008, FR-009)

Create `GetVulnerabilityHeatmapTool.kt` implementing `McpTool`:
- `name`: `get_vulnerability_heatmap`
- `operation`: READ
- `inputSchema`: Empty object (no parameters)
- `execute()`: Query `AssetHeatmapRepository` with access control filtering using `context.getFilterableAssetIds()`. For ADMIN/SECCHAMPION, return all entries. For others, filter by accessible asset IDs. Build response with entries, summary counts, and lastCalculatedAt.
- Inject: `AssetHeatmapRepository`, `AssetHeatmapService`

### Task 2: Create RefreshVulnerabilityHeatmapTool (FR-003, FR-004, FR-008)

Create `RefreshVulnerabilityHeatmapTool.kt` implementing `McpTool`:
- `name`: `refresh_vulnerability_heatmap`
- `operation`: WRITE
- `inputSchema`: Empty object (no parameters)
- `execute()`: Check `context.hasRole("ADMIN")` — return error if not admin. Call `AssetHeatmapService.recalculateHeatmap()` and return entry count.
- Inject: `AssetHeatmapService`

### Task 3: Register Tools in McpToolRegistry (FR-008)

Modify `McpToolRegistry.kt`:
- Add constructor parameters for both new tool singletons
- Add both to the lazy `tools` map initialization
- Map `get_vulnerability_heatmap` to `VULNERABILITY_READ` permission
- Map `refresh_vulnerability_heatmap` to `VULNERABILITY_WRITE` permission

### Task 4: Create ExternalHeatmapController (FR-005, FR-006)

Create `ExternalHeatmapController.kt`:
- `@Controller("/api/external/vulnerability-heatmap")`
- `GET` endpoint that accepts `X-MCP-API-Key` and `X-MCP-User-Email` headers
- Validates API key via `McpAuthenticationService`
- Resolves delegated user and applies access control (same logic as `VulnerabilityHeatmapController`)
- Returns `AssetHeatmapResponseDto`
- CORS handled via application.yml config for the `/api/external/**` path

### Task 5: Configure CORS for External Endpoint (FR-005)

Update `application.yml`:
- Add CORS configuration for `/api/external/**` path pattern
- Configurable allowed origins (default: secman frontend origin)
- Allow methods: GET, OPTIONS
- Allow headers: X-MCP-API-Key, X-MCP-User-Email, Content-Type
- Credentials: disabled (API key auth, not cookies)

### Task 6: Update MCP Documentation (FR-007)

Update `docs/MCP.md`:
- Add `get_vulnerability_heatmap` to the available tools table
- Add `refresh_vulnerability_heatmap` to the available tools table
- Add usage examples (curl, AI assistant) in the appropriate documentation section
- Document access control behavior and permission requirements
- Update tool count in the overview section

### Task 7: Update CLAUDE.md

Update `CLAUDE.md`:
- Add external heatmap endpoint to API Endpoints section
- Add MCP tool names to MCP section
- Note the CORS configuration for external endpoints

## Complexity Tracking

No constitution violations. No complexity justification needed.
