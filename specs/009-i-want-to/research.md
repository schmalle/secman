# Research: Enhanced MCP Tools for Security Data Access

**Feature**: 009-i-want-to
**Date**: 2025-10-05
**Status**: Complete

## Overview
This document consolidates research findings for extending the existing MCP server (Feature 006) with enhanced tools to access complete asset, scan result, and vulnerability data.

## Key Research Areas

### 1. Existing MCP Infrastructure (Feature 006)

**Decision**: Extend existing MCP tool pattern from Feature 006
**Rationale**:
- Feature 006 already established MCP server with tool registry pattern
- Existing tools: `get_assets`, `get_scans`, `get_vulnerabilities`, `search_products`, `get_asset_profile`
- Current limits: 500 items/page, 50K total results (need to increase to 1000/page, 100K total)
- Pattern: `McpTool` interface → `McpToolRegistry` → Micronaut DI injection
- Already has pagination, filtering, error handling patterns

**Alternatives Considered**:
- Build new MCP server from scratch → Rejected: Duplicates infrastructure, violates DRY
- Use REST API directly → Rejected: MCP provides standardized AI assistant integration

### 2. API Key Authentication & Authorization

**Decision**: Implement `McpApiKey` domain entity with permission scopes
**Rationale**:
- Feature 006 lacked authentication (assumed trusted environment)
- API keys map to User entities (user roles + workgroup memberships)
- Permission scopes: `ASSETS_READ`, `SCANS_READ`, `VULNERABILITIES_READ`
- Validation in new `McpAuthService` before tool execution
- Keys stored hashed in database (bcrypt), never plaintext

**Alternatives Considered**:
- JWT tokens → Rejected: MCP clients may not have web auth flow, API keys simpler for automation
- OAuth2 → Rejected: Excessive complexity for server-to-server AI assistant access
- No auth (trust MCP client) → Rejected: Violates security-first principle

### 3. Rate Limiting Implementation

**Decision**: Token bucket algorithm with Redis backend
**Rationale**:
- 5000 requests/minute, 100K requests/hour per API key
- Redis provides distributed state for multi-instance deployments
- Existing pattern in Micronaut ecosystem (`micronaut-redis`)
- Separate limits per API key, reset windows independent

**Alternatives Considered**:
- In-memory rate limiting → Rejected: Doesn't work across Docker container restarts
- Database-based → Rejected: Too slow for high-frequency checks
- Fixed window counters → Rejected: Allows burst at window boundaries (token bucket smoother)

### 4. Workgroup Access Control Integration

**Decision**: Reuse existing `AssetFilterService` from Feature 008
**Rationale**:
- Feature 008 already implemented workgroup filtering for Assets
- Same access rules apply: users see workgroup assets + owned assets
- Centralized logic prevents inconsistencies between REST API and MCP tools
- Service returns filtered query criteria, repositories apply filters

**Alternatives Considered**:
- Duplicate filtering logic in MCP tools → Rejected: Violates DRY, maintenance burden
- Post-query filtering → Rejected: Inefficient, breaks pagination counts

### 5. Real-Time Data vs. Caching

**Decision**: Direct database queries (no caching layer)
**Rationale**:
- Requirement FR-013: Real-time data mandatory
- Security data changes frequently (scans import vulnerabilities continuously)
- Stale data creates security blindspots
- Database indexed queries fast enough (<200ms p95 target)

**Alternatives Considered**:
- Short-lived cache (1-5 min) → Rejected: Explicit requirement for real-time data
- Materialized views → Rejected: Adds complexity, still not truly real-time

### 6. Pagination Limits Increase

**Decision**: Increase to 1000 items/page, 100K total results (from 500/50K)
**Rationale**:
- Feature 006 limits too restrictive for automation use cases
- AI assistants need bulk data export for analysis
- Higher rate limits (5000 req/min) support pagination overhead
- Database can handle larger result sets with proper indexing

**Alternatives Considered**:
- Keep 500/50K limits → Rejected: Insufficient for stated automation requirements
- Unlimited results → Rejected: DoS risk, memory exhaustion

### 7. Comprehensive Asset Data Exposure

**Decision**: Expose all Asset entity fields (Feature 008 additions included)
**Rationale**:
- FR-001 requires: name, type, IP, owner, description, groups, cloud metadata, AD domain, OS version, workgroup assignments, timestamps, creator/uploader
- Existing `get_assets` returns subset → new tools return complete data
- Includes relationships: workgroups (ManyToMany), manualCreator (FK), scanUploader (FK)

**Alternatives Considered**:
- Separate tools for different data subsets → Rejected: API proliferation, more requests needed
- DTO projection layer → Rejected: Unnecessary complexity, just serialize entity

### 8. Scan Results (Open Ports) Access Pattern

**Decision**: New `GetAssetScanResultsTool` to query ScanResult entities
**Rationale**:
- FR-002: Access port number, service name, product name, product version, discovery timestamp
- ScanResult has ManyToOne relationship to Asset
- Query by asset ID or filter across all assets
- Existing `get_scans` tool focuses on scan metadata, not individual port results

**Alternatives Considered**:
- Embed scan results in asset query → Rejected: Result size explosion for assets with many ports
- Reuse `search_products` tool → Rejected: Different use case (products vs. specific ports)

### 9. Vulnerability Data Access Pattern

**Decision**: Extend existing `get_vulnerabilities` tool with enhanced filtering
**Rationale**:
- Feature 006 already has `get_vulnerabilities` tool
- FR-003 requires: CVE ID, CVSS severity, vulnerable versions, days open, asset ref, scan timestamp
- Add filtering by: exception status (include/exclude excepted vulns)
- Respect VulnerabilityException entity (Feature 004)

**Alternatives Considered**:
- Create separate tool → Rejected: Duplicates existing functionality
- Always exclude exceptions → Rejected: Users may want to see excepted vulns for audit

### 10. Access Control Error Handling

**Decision**: Return explicit permission errors (not silent filtering)
**Rationale**:
- FR-005 clarification: Return error when accessing restricted data
- Helps AI assistants understand permission boundaries
- Prevents confusion (empty results vs. no permission)
- Error format: `McpToolResult.Error` with `INSUFFICIENT_PERMISSIONS` code

**Alternatives Considered**:
- Silent filtering (return accessible subset) → Rejected: Explicit clarification answer C chosen
- Return empty results → Rejected: Ambiguous (no data vs. no access)

## Technology Stack Decisions

### Backend (No Changes from Existing)
- **Language**: Kotlin 2.1.0
- **Framework**: Micronaut 4.4 with Hibernate JPA
- **Database**: MariaDB 11.4
- **MCP Protocol**: JSON-RPC over stdio (existing implementation)
- **Rate Limiting**: Micronaut Redis + Token Bucket algorithm
- **API Key Hashing**: BCrypt (Spring Security Crypto 6.4.4)

### Testing Strategy
- **Contract Tests**: JSON schema validation for MCP tool input/output
- **Unit Tests**: MockK for service layer (filtering, pagination, rate limiting)
- **Integration Tests**: Full MCP tool execution with test database
- **Coverage Target**: ≥80% (constitutional requirement)

## Performance Considerations

### Database Indexing
- Existing indexes on Asset: `name`, `type`, `ip`, `owner`, `groups`
- Existing indexes on ScanResult: `asset_id`, `(asset_id, discovered_at)`
- Existing indexes on Vulnerability: `asset_id`, `(asset_id, scan_timestamp)`
- No new indexes needed (reuse existing entities)

### Query Optimization
- Use repository methods with Pageable support where available
- Manual pagination for filters without native support (existing pattern from `get_assets`)
- Lazy loading for relationships (fetch only when needed)
- Limit result sets before serialization (enforce 100K max)

### Rate Limiting Performance
- Redis ops: O(1) for token bucket operations
- Minimal latency (<5ms p99 for rate limit check)
- Fail-open on Redis unavailability (log warning, allow request)

## Security Considerations

### API Key Management
- Keys generated with cryptographic randomness (SecureRandom)
- Stored as BCrypt hash (cost factor 12)
- Rotatable (users can generate new keys, revoke old ones)
- Scoped to user + workgroups (not global admin keys)

### Input Validation
- JSON schema validation on all tool inputs (existing MCP framework)
- SQL injection prevention: Micronaut Data parameterized queries
- Max string lengths enforced (255 chars for filters)
- Page/pageSize bounds checking

### Access Control Enforcement
- Check API key permissions before tool execution
- Apply workgroup filters via `AssetFilterService`
- Return explicit errors on permission violations
- Log all access attempts (audit trail)

## Migration Path from Feature 006

### Backward Compatibility
- Existing tools unchanged: `get_assets`, `get_scans`, `get_vulnerabilities`, `search_products`, `get_asset_profile`
- New tools additive only (no breaking changes)
- Existing MCP clients continue to work without modification
- New rate limiting non-breaking (existing clients assumed trusted, exempted via default API key)

### Phased Rollout
1. Phase 1: Implement API key infrastructure (auth service, rate limiting)
2. Phase 2: Create new enhanced tools (complete asset data, scan results)
3. Phase 3: Extend existing tools with new filters (exception status)
4. Phase 4: Documentation update for new tool capabilities

## Open Questions (Resolved via Clarifications)

All questions resolved in `/clarify` session 2025-10-05:
- ✅ Authentication mechanism: Dedicated API keys with permission scopes
- ✅ Rate limits: 5000 req/min, 100K req/hour per key
- ✅ Data freshness: Real-time (direct DB queries)
- ✅ Pagination limits: 1000 items/page, 100K total results
- ✅ Access control behavior: Explicit permission errors

## References

- Feature 006 Spec: `/specs/006-mcp-tools/spec.md`
- Feature 008 Spec: `/specs/008-workgroup-access/spec.md` (workgroup filtering)
- Feature 004 Spec: `/specs/004-vuln-role/spec.md` (vulnerability exceptions)
- Existing MCP Tools: `/src/backendng/src/main/kotlin/com/secman/mcp/tools/`
- Constitution: `/.specify/memory/constitution.md`

---

**Status**: All research complete, ready for Phase 1 (Design & Contracts)
