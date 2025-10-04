# Research: MCP Tools for Security Data

## Overview
This document consolidates research findings for exposing security data (assets, scans, vulnerabilities, products) via MCP tools.

## Existing MCP Infrastructure Analysis

### Decision: Leverage Existing MCP Framework
**Rationale**: The codebase already has a complete MCP implementation with:
- `McpController` for tool execution endpoints
- `McpToolRegistry` for tool discovery and permission mapping
- `McpTool` interface for standardized tool implementation
- `McpPermission` enum for permission-based access control
- `McpAuditService` for logging tool calls
- `McpToolPermissionService` for fine-grained access control
- `McpApiKeyRepository` for API key management

**Pattern**: Each new tool implements the `McpTool` interface with:
```kotlin
@Singleton
class ToolName(
    @Inject private val service: ServiceName
) : McpTool {
    override val name: String
    override val description: String
    override val operation: McpOperation
    override val inputSchema: Map<String, Any>
    override suspend fun execute(arguments: Map<String, Any>): McpToolResult
}
```

**Alternatives Considered**:
- Building a separate MCP server → Rejected: Duplicates existing infrastructure
- Exposing data via standard REST only → Rejected: Doesn't meet MCP integration requirement

## Repository Pattern Analysis

### Decision: Use Micronaut Data JPA Repositories
**Rationale**: Existing repositories follow consistent pattern:
- Extend `JpaRepository<Entity, Long>`
- Custom query methods using method naming conventions
- No manual SQL for common operations
- Compatible with Hibernate auto-migration

**Existing Repositories to Use**:
- `AssetRepository`: Already has filtering methods (findByName, findByType, findByIp)
- `ScanRepository`: Needs new query methods for filtering
- `ScanResultRepository`: Needs asset-based queries
- `ScanPortRepository`: Needs service/product discovery queries
- `VulnerabilityRepository`: Needs severity and CVE filtering

**Pattern for New Query Methods**:
```kotlin
fun findByAssetId(assetId: Long): List<ScanResult>
fun findBySeverityIn(severities: List<String>): List<Vulnerability>
fun findByServiceContainingIgnoreCase(service: String): List<ScanPort>
```

**Alternatives Considered**:
- Custom JPQL queries → Defer to implementation if method naming insufficient
- Native SQL → Rejected: Breaks database abstraction

## Pagination Strategy

### Decision: Micronaut Data Pageable
**Rationale**: Framework provides built-in pagination support
- `Pageable.from(page, size)` for pagination parameters
- Repository methods accept `Pageable` parameter
- Returns `Page<T>` with total count and results
- Enforces limits at repository level

**Implementation Pattern**:
```kotlin
fun findAll(pageable: Pageable): Page<Asset>
fun findByType(type: String, pageable: Pageable): Page<Asset>
```

**Limits** (from clarifications):
- Max items per page: 500
- Max total results per query: 50,000
- Validation in MCP tools before repository calls

**Alternatives Considered**:
- Manual LIMIT/OFFSET → Rejected: Pageable provides pagination metadata
- Client-side pagination → Rejected: Violates performance requirements

## Rate Limiting Strategy

### Decision: Implement in McpToolPermissionService
**Rationale**: Existing service already handles permission checks; extend for rate limits
- Track requests per API key in memory (sliding window)
- Limits: 1,000 req/min, 50,000 req/hour (from clarifications)
- Return rate limit errors before tool execution
- Log rate limit violations in audit

**Data Structure**:
```kotlin
data class RateLimitTracker(
    val minuteWindow: ConcurrentHashMap<Long, AtomicInteger>,  // timestamp -> count
    val hourWindow: ConcurrentHashMap<Long, AtomicInteger>
)
```

**Alternatives Considered**:
- Database-backed counters → Rejected: Too slow for per-request checks
- Redis → Rejected: Adds dependency, overkill for medium scale
- Token bucket algorithm → Defer: Sliding window simpler to implement

## Snapshot Isolation Strategy

### Decision: READ_COMMITTED Transaction Isolation
**Rationale**: MariaDB/Hibernate default provides sufficient isolation
- Queries see committed data only
- No dirty reads during scan imports
- Performance acceptable for medium scale (10K assets)

**Implementation**:
- Default `@Transactional(readOnly = true)` for queries
- No additional isolation configuration needed
- MariaDB InnoDB handles MVCC automatically

**Alternatives Considered**:
- SERIALIZABLE isolation → Rejected: Performance penalty for marginal benefit
- Application-level snapshots → Rejected: Database handles this efficiently

## MCP Permission Model

### Decision: Add New Permission Types
**Current Permissions**: REQUIREMENTS_READ, REQUIREMENTS_WRITE, ASSESSMENTS_READ, etc.

**New Permissions Needed**:
```kotlin
ASSETS_READ,
SCANS_READ,
VULNERABILITIES_READ
```

**Integration Points**:
- Add to `McpPermission` enum
- Update `McpToolRegistry.isToolAuthorized()` mapping
- API key creation UI allows selecting new permissions

**Alternatives Considered**:
- Reuse existing permissions → Rejected: Security data is distinct domain
- Single SECURITY_READ permission → Rejected: Too coarse-grained

## Test Strategy

### Decision: Three-Layer Testing per Constitution
**Pattern from Existing GetRequirementsTool**:

1. **Contract Tests** (Phase 1):
   - Validate JSON schema for each tool
   - Test parameter validation (limits, types)
   - Verify error response format
   - Location: `specs/006-*/contracts/`

2. **Unit Tests** (Phase 4):
   - Mock repositories
   - Test tool logic in isolation
   - Verify pagination, filtering, error handling
   - Location: `src/backendng/src/test/kotlin/com/secman/mcp/tools/`

3. **Integration Tests** (Phase 4):
   - Real database with test data
   - End-to-end MCP tool execution
   - Permission enforcement validation
   - Location: `src/backendng/src/test/kotlin/com/secman/integration/`

**Coverage Target**: ≥80% per Constitution

**Alternatives Considered**:
- E2E tests only → Rejected: Doesn't meet constitutional TDD requirement
- Repository tests → Deferred: Micronaut Data tests framework methods

## Performance Optimization

### Decision: Database Indexes
**Required Indexes** (from entity analysis):
- `Asset`: Existing indexes on name, ip, type
- `Scan`: Existing indexes on scan_date, uploaded_by
- `ScanResult`: Existing composite index (asset_id, discovered_at)
- `ScanPort`: Existing index on scan_result_id
- `Vulnerability`: Existing composite index (asset_id, scan_timestamp)

**New Indexes Needed**:
- `ScanPort.service` for product discovery queries
- `Vulnerability.cvss_severity` for severity filtering

**Query Optimization**:
- Use JOIN FETCH for entity relationships
- Lazy loading with `@JsonIgnore` prevents N+1
- Pageable limits result sets

**Alternatives Considered**:
- Caching layer → Deferred: Premature for medium scale
- Denormalization → Rejected: Normalized schema matches domain model

## Tool Design Decisions

### Decision: 7 MCP Tools
Based on functional requirements:

1. **get_assets**: Asset inventory query (FR-001)
2. **get_scans**: Scan history retrieval (FR-002)
3. **get_scan_results**: Scan results with ports (FR-003, FR-004)
4. **get_vulnerabilities**: Vulnerability analysis (FR-005)
5. **search_products**: Product discovery (FR-006)
6. **get_asset_profile**: Cross-reference query (FR-010)
7. **get_scan_ports**: Port-level detail query (FR-004)

**Naming Convention**: `get_*` for retrieval, `search_*` for filtered queries

**Alternatives Considered**:
- Single `query` tool with type parameter → Rejected: Less discoverable
- Separate tools for each filter combination → Rejected: Too granular

## Summary of Technical Decisions

| Aspect | Decision | Key Rationale |
|--------|----------|---------------|
| MCP Framework | Extend existing | Complete infrastructure already present |
| Repository Layer | Micronaut Data JPA | Consistent with codebase patterns |
| Pagination | Pageable (500/page, 50K max) | Framework support + clarified limits |
| Rate Limiting | In-memory sliding window | Fast, sufficient for scale |
| Transaction Isolation | READ_COMMITTED (default) | MariaDB MVCC handles consistency |
| Permissions | Add 3 new permissions | Domain-specific access control |
| Testing | 3-layer (contract/unit/integration) | Constitutional requirement |
| Performance | Indexes + query optimization | Meets <5s target at medium scale |
| Tool Count | 7 tools | Maps to functional requirements |

All decisions align with secman Constitution v1.0.0 principles.
