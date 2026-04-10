# Feature Specification: Heatmap MCP and API Exposure

**Feature Branch**: `086-heatmap-mcp-api`  
**Created**: 2026-04-10  
**Status**: Draft  
**Input**: User description: "make the heatmap available also via MCP (include it in the docs), and make a webservice endpoint also available for other webpages consuming the heatmap data"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - AI Assistant Queries Heatmap via MCP (Priority: P1)

An AI assistant connected to secman via the MCP server wants to retrieve vulnerability heatmap data for an organization's assets. The assistant uses the MCP tool to get a summary of asset health (red/yellow/green distribution) and per-asset severity counts, scoped to the delegated user's accessible assets.

**Why this priority**: MCP is the primary integration channel for AI-driven workflows. Exposing heatmap data here enables automated security reporting, dashboards, and risk assessment conversations.

**Independent Test**: Can be tested by calling the MCP `tools/call` endpoint with the heatmap tool name and verifying the response contains heatmap entries with severity counts and heat levels.

**Acceptance Scenarios**:

1. **Given** a valid MCP API key with a delegated ADMIN user, **When** the assistant calls the `get_vulnerability_heatmap` tool, **Then** the response contains all assets with their severity counts, heat levels, and a summary.
2. **Given** a valid MCP API key with a delegated user who has only workgroup access, **When** the assistant calls the `get_vulnerability_heatmap` tool, **Then** the response contains only the assets accessible to that delegated user.
3. **Given** a valid MCP API key with a delegated user, **When** the heatmap table is empty, **Then** the response returns an empty entries list with zero-count summary and a null last-calculated timestamp.

---

### User Story 2 - External Web Page Consumes Heatmap Data (Priority: P2)

An internal web application (separate from the secman frontend) wants to display heatmap data by calling a secman REST endpoint. The endpoint returns structured heatmap data that can be rendered as a dashboard widget, embedded chart, or summary card.

**Why this priority**: Enables heatmap data reuse across internal tools and dashboards without requiring teams to access the secman UI directly.

**Independent Test**: Can be tested by making an authenticated HTTP GET request from an external origin and verifying CORS headers allow the request and the response contains valid heatmap JSON.

**Acceptance Scenarios**:

1. **Given** an authenticated request from a different origin, **When** the external page calls the heatmap web API, **Then** the response includes appropriate CORS headers and returns heatmap data in JSON format.
2. **Given** a valid API key passed via header, **When** the external page requests heatmap data, **Then** the system returns heatmap data scoped to the authenticated user's access.
3. **Given** a request without authentication, **When** the external page calls the heatmap web API, **Then** the system returns a 401 Unauthorized response.

---

### User Story 3 - Admin Triggers Heatmap Refresh via MCP (Priority: P3)

An admin user's AI assistant triggers a heatmap recalculation through the MCP server, ensuring fresh data is available without needing a full CrowdStrike import cycle.

**Why this priority**: Complements the query tool by allowing admins to ensure data freshness before generating reports.

**Independent Test**: Can be tested by calling the MCP `refresh_vulnerability_heatmap` tool with an admin-delegated API key and verifying the heatmap table is repopulated.

**Acceptance Scenarios**:

1. **Given** an MCP API key with a delegated ADMIN user, **When** the assistant calls `refresh_vulnerability_heatmap`, **Then** the heatmap is recalculated and the response includes the number of entries created.
2. **Given** an MCP API key with a delegated non-admin user, **When** the assistant calls `refresh_vulnerability_heatmap`, **Then** the system returns an error indicating insufficient permissions.

---

### User Story 4 - MCP Documentation Updated (Priority: P2)

A developer or AI integration engineer reads the MCP documentation to discover and understand the heatmap tools — their parameters, response format, and access control rules.

**Why this priority**: Documentation is essential for discoverability. Without it, external consumers cannot effectively use the new tools.

**Independent Test**: Can be tested by reading the MCP documentation and confirming the heatmap tools are listed with descriptions, parameters, response format, and examples.

**Acceptance Scenarios**:

1. **Given** the MCP documentation, **When** a developer searches for heatmap tools, **Then** they find `get_vulnerability_heatmap` and `refresh_vulnerability_heatmap` with complete usage instructions.
2. **Given** the tool listing endpoint, **When** a client calls `tools/list`, **Then** the response includes the heatmap tools with their descriptions and input schemas.

---

### Edge Cases

- What happens when the heatmap table has not been populated yet (no CrowdStrike import has occurred)? System returns an empty result set, not an error.
- How does the system handle a heatmap refresh request while another refresh is already in progress? System should either queue or reject with a descriptive message.
- What happens when an MCP API key has no delegated user set (missing `X-MCP-User-Email`)? Request is rejected per existing MCP authentication rules.
- How does the web API handle requests from origins not in the allowed CORS list? Standard CORS rejection — browser blocks the response.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST provide an MCP tool named `get_vulnerability_heatmap` that returns per-asset severity counts, heat levels, a summary, and last-calculated timestamp.
- **FR-002**: The MCP heatmap tool MUST respect the same unified access control rules as the existing heatmap UI — ADMIN/SECCHAMPION users see all assets; others see only their accessible assets.
- **FR-003**: System MUST provide an MCP tool named `refresh_vulnerability_heatmap` that triggers a full heatmap recalculation, restricted to users with ADMIN role.
- **FR-004**: The refresh MCP tool MUST return the number of heatmap entries created after recalculation.
- **FR-005**: The existing heatmap REST endpoint MUST support cross-origin requests from configured allowed origins via CORS headers.
- **FR-006**: The heatmap REST endpoint MUST support API key authentication (via header) in addition to session/cookie authentication, enabling consumption by external web applications.
- **FR-007**: The MCP documentation MUST be updated to include both heatmap tools with their descriptions, input schemas, response formats, and usage examples.
- **FR-008**: The MCP `tools/list` response MUST include the heatmap tools when the API key has appropriate permissions.
- **FR-009**: System MUST return an empty result set (not an error) when the heatmap table has no data, with a null `lastCalculatedAt` field.

### Key Entities

- **Heatmap Entry**: Per-asset record with severity counts (critical, high, medium, low), total count, heat level (RED/YELLOW/GREEN), and asset metadata (name, type).
- **Heatmap Response**: Collection of heatmap entries plus a summary (total assets, red/yellow/green counts) and a last-calculated timestamp.
- **MCP Tool Definition**: Tool name, description, input schema (parameters), and execution logic registered in the tool registry.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: AI assistants can retrieve heatmap data via MCP in under 2 seconds for datasets up to 5,000 assets.
- **SC-002**: External web applications can successfully consume heatmap data from the REST endpoint with proper CORS handling.
- **SC-003**: Both new MCP tools appear in the `tools/list` response and are documented in MCP.md.
- **SC-004**: Access control is consistent — the same user sees identical heatmap data whether accessing via UI, MCP, or REST API.
- **SC-005**: Admin-only operations (refresh) are properly restricted across all access channels (UI, MCP, REST).

## Assumptions

- The existing heatmap calculation logic and data model (`asset_heatmap_entry` table) are stable and will be reused as-is.
- The MCP server's existing authentication pattern (`X-MCP-API-Key` + `X-MCP-User-Email` for user delegation) applies to the new heatmap tools.
- CORS configuration will use an allowlist of trusted origins configured in application settings, defaulting to the secman frontend origin.
- API key authentication for the REST endpoint will reuse the existing MCP API key infrastructure rather than introducing a new auth mechanism.
- The heatmap refresh via MCP follows the same logic as the existing `POST /api/vulnerability-heatmap/refresh` endpoint.
