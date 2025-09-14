# MCP Server Integration - Research Findings

## MCP Protocol Version

**Decision**: Use MCP Specification 2025-06-18
**Rationale**: Latest stable version with OAuth 2.1 authorization, Tool Output Schemas, and performance improvements
**Alternatives considered**: Earlier versions lack critical security and performance features

## Kotlin/JVM SDK Selection

**Decision**: Use Official Kotlin SDK with custom Micronaut integration
**Rationale**: Official JetBrains support, multiplatform compatibility, direct control over Micronaut integration
**Alternatives considered**: Spring AI MCP (framework mismatch), pure Java SDK (less Kotlin-native), custom implementation (maintenance overhead)

### Dependencies Required
```kotlin
implementation("io.modelcontextprotocol:kotlin-sdk:$mcpVersion")
implementation("org.springframework.ai:spring-ai-mcp-server-webflux-spring-boot-starter") // For reactive patterns
```

## Authentication Architecture

**Decision**: OAuth 2.1 with JWT tokens and MCP session management
**Rationale**: Official MCP authentication standard, integrates with existing JWT infrastructure, meets security requirements
**Alternatives considered**: Basic auth (insufficient), API keys (static credential risk), custom tokens (non-standard)

### Key Security Requirements
- MUST NOT use sessions for authentication (only state management)
- MUST use secure, non-deterministic session IDs
- MUST verify JWT tokens explicitly issued for MCP server
- SHOULD implement OAuth 2.0 Dynamic Client Registration

## Connection Management

**Decision**: Reactive WebFlux with in-memory session storage
**Rationale**: Handles 200+ concurrent connections efficiently, lower latency than Redis for target scale, integrates with Micronaut reactive features
**Alternatives considered**: Redis storage (unnecessary complexity), blocking servlet model (poor concurrency)

### Performance Targets
- Support 500 concurrent connections (250% of requirement)
- <100ms response times for data queries
- <1GB memory footprint
- Session cleanup every 5 minutes

## Message Protocol

**Decision**: JSON-RPC 2.0 over Server-Sent Events (SSE)
**Rationale**: Official MCP protocol requirement, proven performance, excellent tooling support
**Alternatives considered**: WebSocket (more complex), custom JSON (non-compliant), gRPC (not MCP standard)

### Transport Configuration
- Primary: SSE for server-to-client streaming
- Fallback: HTTP POST for client-to-server requests
- Message buffering: 1000 events per client
- Compression: Enabled for large payloads

## Integration with Existing Architecture

**Decision**: Extend existing Micronaut controllers with MCP endpoints
**Rationale**: Leverages existing security, database access, and service layers
**Alternatives considered**: Separate MCP service (deployment complexity), embedded library (limited control)

### Tool Exposure Strategy
1. **Security Requirements**: CRUD operations with role-based filtering
2. **Risk Assessments**: Read-only access with assessment execution
3. **File Management**: Download existing requirement files
4. **Translation Services**: Access to OpenRouter integration
5. **Audit Logging**: Read-only access to user activity

## Error Handling and Observability

**Decision**: Structured logging with audit trails and MCP-specific error categorization
**Rationale**: MCP specification requirements, security compliance, debugging complex concurrent systems
**Alternatives considered**: Basic logging (insufficient), external logging only (performance impact)

### Error Categories
- `PERMISSION_DENIED`: Security/authorization failures
- `INVALID_PARAMS`: Validation errors
- `INTERNAL_ERROR`: Service failures
- `RESOURCE_NOT_FOUND`: Missing data/entities
- `RATE_LIMITED`: Connection/request throttling

## Database Schema Extensions

**Decision**: Add MCP-specific entities using existing Hibernate setup
**Rationale**: Maintains data consistency, leverages existing migration patterns, minimal schema changes
**Alternatives considered**: Separate database (sync complexity), external storage (query limitations)

### New Entities Required
- `McpApiKey`: API key management for client authentication
- `McpSession`: Active session tracking and state
- `McpAuditLog`: Tool calls and security events
- `McpToolPermission`: Role-based tool access control

## Performance and Scalability

**Decision**: Reactive architecture with connection pooling and resource limits
**Rationale**: Proven to handle target load, efficient resource utilization, aligns with existing patterns
**Alternatives considered**: Thread-per-connection (resource intensive), purely async without backpressure (memory issues)

### Resource Management
- Max concurrent connections: 500
- Session timeout: 60 minutes
- Message buffer per client: 1000 events
- Database connection pool: Shared with existing services
- Memory-based session storage with TTL cleanup

## Implementation Priority

**Decision**: Phased rollout starting with read-only operations
**Rationale**: Reduces risk, enables testing with real clients, validates architecture
**Alternatives considered**: Full feature implementation (higher risk), proof-of-concept only (insufficient for requirements)

### Phase Priorities
1. Core MCP server with requirements read access
2. Authentication and session management
3. Write operations (create/update requirements)
4. Advanced tools (risk assessments, file access)
5. Performance optimization and monitoring