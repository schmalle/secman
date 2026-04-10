# Research: Heatmap MCP and API Exposure

**Date**: 2026-04-10  
**Feature**: 086-heatmap-mcp-api

## Decision 1: MCP Tool Implementation Pattern

**Decision**: Implement two singleton MCP tools (`GetVulnerabilityHeatmapTool`, `RefreshVulnerabilityHeatmapTool`) following the existing `McpTool` interface pattern with lazy registration in `McpToolRegistry`.

**Rationale**: The project has 50+ existing MCP tools all following the same pattern. Consistency reduces maintenance burden and ensures the new tools inherit existing authentication, permission gating, and execution context.

**Alternatives considered**:
- Generic MCP handler (rejected: breaks tool discovery via `tools/list`)
- Extending an existing tool with sub-commands (rejected: MCP protocol expects distinct tools)

## Decision 2: Access Control for MCP Heatmap Tool

**Decision**: Use `McpExecutionContext.getFilterableAssetIds()` for row-level filtering. ADMIN/SECCHAMPION users see all entries; others see only entries matching their accessible asset IDs. This mirrors the existing `VulnerabilityHeatmapController` logic.

**Rationale**: The `asset_heatmap_entry` table stores `asset_id` per row, which can be compared against the pre-computed accessible asset ID set from the execution context. This is the same pattern used by `GetAssetsTool` and `GetVulnerabilitiesTool`.

**Alternatives considered**:
- Delegating to the controller via internal HTTP call (rejected: unnecessary overhead, bypasses MCP context)
- Querying the repository directly without context filtering (rejected: violates RBAC)

## Decision 3: External REST Endpoint Strategy

**Decision**: Add a dedicated CORS-enabled REST endpoint at `GET /api/external/vulnerability-heatmap` that accepts `X-MCP-API-Key` + `X-MCP-User-Email` headers for authentication. This is separate from the existing cookie-auth endpoint.

**Rationale**: No existing REST endpoint supports dual authentication (cookie + API key). Adding API key support to the existing endpoint would risk breaking the cookie-auth flow. A dedicated external endpoint keeps concerns separate and can have its own CORS policy (configurable allowed origins). The MCP API key infrastructure is reused — no new auth mechanism needed.

**Alternatives considered**:
- Modifying existing endpoint to accept both auth methods (rejected: adds complexity to the existing security filter chain, risk of breaking cookie-based auth)
- Telling external consumers to use MCP `tools/call` JSON-RPC (rejected: MCP protocol is more complex for simple REST consumers; external web pages expect standard REST, not JSON-RPC 2.0)
- No dedicated endpoint, only MCP exposure (rejected: spec FR-005/FR-006 explicitly require REST endpoint for web page consumption)

## Decision 4: CORS Configuration

**Decision**: The external endpoint will use a configurable origin allowlist from `application.yml` under `secman.cors.external-api`. Defaults to allowing the secman frontend origin.

**Rationale**: MCP endpoints already allow all origins (`.*`), but a REST data endpoint should restrict origins to trusted internal applications. This follows the principle of least privilege for cross-origin access.

**Alternatives considered**:
- Allow all origins like MCP (rejected: unnecessary exposure for REST data)
- No CORS, require server-side proxy (rejected: adds infrastructure burden for consuming teams)

## Decision 5: MCP Tool Permissions

**Decision**: `get_vulnerability_heatmap` requires `VULNERABILITY_READ` permission. `refresh_vulnerability_heatmap` requires `VULNERABILITY_WRITE` permission and ADMIN role check at execution time.

**Rationale**: Heatmap data is derived from vulnerability data. Using existing vulnerability permission categories avoids creating new permission types. The refresh tool additionally checks for ADMIN role at execution time (matching the REST `POST /api/vulnerability-heatmap/refresh` behavior).

**Alternatives considered**:
- New `HEATMAP_READ`/`HEATMAP_WRITE` permissions (rejected: over-engineering; heatmap is a view of vulnerability data)
- No permission gating, only role check (rejected: would bypass the registry-level authorization)
