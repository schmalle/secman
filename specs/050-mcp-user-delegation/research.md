# Research: MCP User Delegation

**Feature**: 050-mcp-user-delegation
**Date**: 2025-11-28

## Research Tasks

### 1. Existing MCP Authentication Flow

**Question**: How does the current MCP authentication work?

**Findings**:
- Authentication via `X-MCP-API-Key` header containing the full API key secret (sk-xxx format)
- `McpAuthenticationService.authenticateApiKey()` validates the key by hashing and comparing
- API key contains embedded permissions as comma-separated enum values
- `McpApiKey.getPermissionSet()` returns `Set<McpPermission>` for authorization checks
- Rate limiting and brute-force protection already implemented

**Decision**: Extend existing flow by adding delegation check after API key validation
**Rationale**: Minimal disruption to working authentication; delegation is additive

### 2. User Lookup by Email Performance

**Question**: How to achieve <100ms user lookup by email?

**Findings**:
- `UserRepository` already exists with `findByEmail()` method
- `users` table has index on `email` column
- Existing pattern: direct repository lookup, no caching layer

**Decision**: Use existing `findByEmail()` with eager loading of roles
**Rationale**: Index-backed query meets performance requirement; adding cache would add complexity without benefit

### 3. Permission Intersection Implementation

**Question**: How to compute intersection of user roles and API key permissions?

**Findings**:
- User roles: `Set<String>` (USER, ADMIN, VULN, RELEASE_MANAGER, SECCHAMPION)
- MCP permissions: `Set<McpPermission>` (15 enum values like REQUIREMENTS_READ, ASSETS_READ)
- No direct 1:1 mapping exists between roles and MCP permissions

**Decision**: Map user roles to MCP permission sets, then intersect with API key permissions
**Rationale**: Preserves existing permission granularity while applying defense-in-depth

**Mapping**:
| User Role | Implied MCP Permissions |
|-----------|------------------------|
| USER | REQUIREMENTS_READ, ASSETS_READ, VULNERABILITIES_READ, TAGS_READ |
| ADMIN | All permissions |
| VULN | VULNERABILITIES_READ, SCANS_READ, ASSETS_READ |
| RELEASE_MANAGER | REQUIREMENTS_READ, ASSESSMENTS_READ |
| SECCHAMPION | REQUIREMENTS_READ, ASSESSMENTS_READ, ASSETS_READ |

### 4. Domain Restriction Storage

**Question**: How to store and validate allowed delegation domains?

**Findings**:
- Domain restrictions are comma-separated strings (e.g., "@company.com,@subsidiary.com")
- Validation requires case-insensitive suffix matching
- Empty/null means delegation disabled (mandatory restriction per clarification)

**Decision**: Store as `VARCHAR(500)` column, validate on API key save and on delegation request
**Rationale**: Simple storage, consistent with existing `permissions` field pattern

### 5. Threshold-Based Alerting

**Question**: How to implement delegation failure alerting?

**Findings**:
- Existing `McpAuditLog` tracks all events with timestamps
- `McpAuditLogRepository` has query methods for counting events
- No existing alerting infrastructure

**Decision**: Track failures in sliding window, trigger alert when threshold exceeded
**Rationale**: Lightweight; uses existing audit infrastructure; configurable via application.yml

**Configuration**:
```yaml
mcp:
  delegation:
    alert:
      threshold: 10          # failures to trigger alert
      window-minutes: 5      # time window for threshold
```

### 6. Audit Log Extension

**Question**: How to track delegated user in audit logs?

**Findings**:
- `McpAuditLog` has `userId` field for API key owner
- Adding `delegatedUserEmail` allows tracking both the key owner and delegated user
- `contextData` JSON field could hold it, but dedicated column enables filtering

**Decision**: Add `delegated_user_email VARCHAR(255)` column to `mcp_audit_logs`
**Rationale**: Enables direct SQL filtering by delegated user; clearer than JSON extraction

### 7. Frontend API Key Form Extension

**Question**: How to add delegation fields to existing form?

**Findings**:
- `mcp-api-keys.astro` uses React component for form
- Form already has permission checkboxes, expiration fields
- Bootstrap 5.3 styling

**Decision**: Add delegation toggle with conditional domain input field
**Rationale**: Follows existing UI patterns; conditional display keeps UI clean

**UI Flow**:
1. Toggle "Enable User Delegation" checkbox
2. When enabled, show required "Allowed Domains" text input
3. Validate at least one domain before allowing save

## Alternatives Considered

### Permission Model Alternatives

| Alternative | Rejected Because |
|-------------|------------------|
| User permissions only | API key becomes bypass mechanism; no defense-in-depth |
| Union of permissions | Grants broader access than intended; security risk |
| Separate delegation permissions | Over-engineering; intersection model is simpler |

### Storage Alternatives

| Alternative | Rejected Because |
|-------------|------------------|
| Separate `mcp_delegation_configs` table | Extra join; delegation is attribute of key, not separate entity |
| JSON column for delegation settings | Harder to query; dedicated columns are cleaner |

### Alerting Alternatives

| Alternative | Rejected Because |
|-------------|------------------|
| Real-time alerts per failure | Alert fatigue; floods admins on misconfiguration |
| No alerting (audit only) | Misses abuse patterns; spec requires alerting (FR-013) |
| Auto-disable key on threshold | Too aggressive; manual intervention preferred per spec |

## Open Questions Resolved

All technical questions resolved. No NEEDS CLARIFICATION markers remain.
