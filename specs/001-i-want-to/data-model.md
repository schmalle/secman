# MCP Server Integration - Data Model

## New Entities

### McpApiKey
Manages API keys for MCP client authentication.

**Fields:**
- `id: Long` - Primary key
- `keyId: String` - Public identifier for the API key (unique)
- `keyHash: String` - Hashed version of the API key secret
- `name: String` - Human-readable name for the key
- `userId: Long` - Foreign key to User entity
- `permissions: Set<McpPermission>` - Set of allowed MCP operations
- `createdAt: LocalDateTime` - Key creation timestamp
- `lastUsedAt: LocalDateTime?` - Last usage timestamp
- `expiresAt: LocalDateTime?` - Optional expiration date
- `isActive: Boolean` - Enable/disable flag

**Relationships:**
- Many-to-One with User (owner)
- One-to-Many with McpSession (active sessions using this key)
- One-to-Many with McpAuditLog (activities using this key)

**Validation Rules:**
- `keyId` must be unique across all keys
- `name` must be unique per user
- `keyHash` must be non-empty and properly hashed
- `permissions` cannot be empty
- Active keys must have future or null expiration

### McpSession
Represents active MCP client connections and their state.

**Fields:**
- `id: Long` - Primary key
- `sessionId: String` - Secure session identifier (unique)
- `apiKeyId: Long` - Foreign key to McpApiKey
- `clientInfo: String` - Client identification (User-Agent, version)
- `capabilities: String` - JSON of negotiated capabilities
- `connectionType: McpConnectionType` - SSE, WebSocket, or HTTP
- `createdAt: LocalDateTime` - Session start time
- `lastActivity: LocalDateTime` - Last request/response timestamp
- `isActive: Boolean` - Session status flag

**Relationships:**
- Many-to-One with McpApiKey (authentication)
- One-to-Many with McpAuditLog (session activities)

**Validation Rules:**
- `sessionId` must be cryptographically secure and unique
- `lastActivity` must be >= `createdAt`
- Active sessions must have recent activity (configurable timeout)
- `capabilities` must be valid JSON

**State Transitions:**
- CREATED → ACTIVE (after successful authentication)
- ACTIVE → INACTIVE (on timeout or explicit disconnect)
- INACTIVE → ARCHIVED (after cleanup period)

### McpAuditLog
Comprehensive audit trail for MCP server operations.

**Fields:**
- `id: Long` - Primary key
- `sessionId: Long?` - Foreign key to McpSession (nullable for system events)
- `apiKeyId: Long?` - Foreign key to McpApiKey (nullable for anonymous events)
- `eventType: McpEventType` - Type of event (TOOL_CALL, AUTH_SUCCESS, AUTH_FAILURE, etc.)
- `toolName: String?` - Name of MCP tool called (nullable)
- `method: String?` - HTTP method or MCP method
- `resourcePath: String?` - Requested resource or endpoint
- `requestParams: String?` - JSON of request parameters (sanitized)
- `responseStatus: String` - Success/failure status
- `errorMessage: String?` - Error details if applicable
- `clientIp: String` - Client IP address
- `userAgent: String` - Client User-Agent header
- `executionTimeMs: Long` - Request processing time
- `timestamp: LocalDateTime` - Event timestamp

**Relationships:**
- Many-to-One with McpSession (optional)
- Many-to-One with McpApiKey (optional)

**Validation Rules:**
- `eventType` must be valid enum value
- `executionTimeMs` must be non-negative
- Security events must have `clientIp` and `userAgent`
- Tool calls must have `toolName` and `method`

### McpToolPermission
Role-based access control for MCP tools and operations.

**Fields:**
- `id: Long` - Primary key
- `toolName: String` - MCP tool identifier
- `operation: McpOperation` - READ, WRITE, DELETE, EXECUTE
- `resourcePattern: String` - Resource access pattern (e.g., "requirements.*", "assessments.own")
- `roleId: Long` - Foreign key to Role entity (existing RBAC)
- `isAllowed: Boolean` - Permission granted/denied
- `conditions: String?` - JSON of additional conditions (e.g., time-based, IP restrictions)

**Relationships:**
- Many-to-One with Role (existing entity)

**Validation Rules:**
- `toolName` must match registered MCP tools
- `operation` must be supported by the tool
- `resourcePattern` must be valid glob pattern
- `conditions` must be valid JSON if present

## Extended Entities

### User (existing entity extensions)
**New Fields:**
- `mcpEnabled: Boolean` - User can access MCP features
- `mcpQuotaDaily: Int` - Daily API call limit
- `mcpQuotaUsed: Int` - Current day usage count
- `mcpLastReset: LocalDate` - Last quota reset date

**New Relationships:**
- One-to-Many with McpApiKey (owned API keys)

## Enums

### McpPermission
- `REQUIREMENTS_READ` - Read security requirements
- `REQUIREMENTS_WRITE` - Create/update requirements
- `REQUIREMENTS_DELETE` - Delete requirements
- `ASSESSMENTS_READ` - Read risk assessments
- `ASSESSMENTS_EXECUTE` - Run new assessments
- `FILES_READ` - Download requirement files
- `TRANSLATION_USE` - Access translation services
- `AUDIT_READ` - View audit logs (admin only)

### McpConnectionType
- `SSE` - Server-Sent Events
- `WEBSOCKET` - WebSocket connection
- `HTTP` - HTTP polling/requests

### McpEventType
- `AUTH_SUCCESS` - Successful authentication
- `AUTH_FAILURE` - Authentication failure
- `SESSION_CREATE` - New session established
- `SESSION_EXPIRE` - Session timeout
- `TOOL_CALL` - MCP tool invocation
- `PERMISSION_DENIED` - Access denied
- `ERROR` - System error
- `RATE_LIMITED` - Request throttled

### McpOperation
- `READ` - Read-only access
- `WRITE` - Create/update operations
- `DELETE` - Deletion operations
- `EXECUTE` - Execute operations (assessments, etc.)

## Database Migration Requirements

### New Tables
1. `mcp_api_keys` - API key management
2. `mcp_sessions` - Active session tracking
3. `mcp_audit_log` - Comprehensive audit trail
4. `mcp_tool_permissions` - Role-based tool access

### Indexes Required
- `mcp_api_keys(key_id)` - Unique index for key lookup
- `mcp_api_keys(user_id, is_active)` - User's active keys
- `mcp_sessions(session_id)` - Unique index for session lookup
- `mcp_sessions(api_key_id, is_active)` - Active sessions per key
- `mcp_audit_log(timestamp)` - Chronological queries
- `mcp_audit_log(api_key_id, timestamp)` - User activity queries
- `mcp_tool_permissions(role_id, tool_name)` - Permission lookups

### Constraints
- Foreign key constraints on all relationships
- Unique constraints on natural keys
- Check constraints on enum fields
- TTL indexes for session cleanup (if using MongoDB-style TTL)

## Data Retention Policy

### Short-term Storage (Active Operation)
- **McpSession**: 60 minutes idle timeout, 24 hours maximum
- **McpApiKey**: User-controlled expiration, max 1 year

### Long-term Storage (Audit/Compliance)
- **McpAuditLog**: 90 days for regular events, 1 year for security events
- **McpToolPermission**: Permanent (configuration data)

### Cleanup Strategy
- Automated session cleanup every 5 minutes
- Audit log archival monthly
- Expired API key soft deletion (retain for audit trail)