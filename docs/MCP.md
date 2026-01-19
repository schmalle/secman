# MCP (Model Context Protocol) Integration Guide

**Last Updated:** 2026-01-14
**Version:** 3.1

This guide covers integrating Secman with AI assistants (Claude Desktop, Claude Code, ChatGPT, etc.) using the Model Context Protocol (MCP).

---

## Table of Contents

1. [Overview](#overview)
2. [Quick Start](#quick-start)
3. [Connection Methods](#connection-methods)
   - [Claude Code (Direct HTTP)](#claude-code-direct-http)
   - [Claude Desktop with mcp-remote](#claude-desktop-with-mcp-remote)
   - [Claude Desktop with Node.js Bridge](#claude-desktop-with-nodejs-bridge)
4. [API Key Management](#api-key-management)
5. [User Delegation](#user-delegation)
6. [Available MCP Tools](#available-mcp-tools)
7. [Authentication & Security](#authentication--security)
8. [Usage Examples](#usage-examples)
9. [Administration](#administration)
10. [Troubleshooting](#troubleshooting)

---

## Overview

Secman supports the Model Context Protocol (MCP), allowing AI assistants to programmatically access security data including:

- **Security Requirements** - Query, export, and manage security requirements
- **Asset Inventory** - Browse assets, get detailed profiles with vulnerabilities
- **Vulnerability Data** - Search vulnerabilities by severity, CVE, or affected asset
- **Scan Results** - Review network scan history and discovered services

The MCP server exposes **14+ tools** for comprehensive security management workflows.

### Prerequisites

- Secman backend running (default: port 8080)
- Valid Secman admin account (for API key creation)
- AI assistant that supports MCP (Claude Desktop, Claude Code, etc.)

### Architecture

Secman supports multiple connection methods:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Connection Methods                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  [Claude Code]  ──────HTTP────────────►  [Secman /mcp]                  │
│                     (Direct)                                             │
│                                                                          │
│  [Claude Desktop] ──stdio──► [mcp-remote] ──HTTP──► [Secman /mcp]       │
│                                 (Proxy)                                  │
│                                                                          │
│  [Claude Desktop] ──stdio──► [Node.js Bridge] ──HTTP──► [Secman /api]   │
│                                 (Legacy)                                 │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

| Client | Method | Requires Node.js | Recommended |
|--------|--------|------------------|-------------|
| **Claude Code** | Direct HTTP | No | Yes |
| **Claude Desktop** | Via `mcp-remote` proxy | Yes (npx) | Yes |
| **Claude Desktop** | Node.js Bridge | Yes | Legacy |

---

## Quick Start

### 1. Start Secman Backend

```bash
cd src/backendng
./gradlew run
```

Verify it's running:

```bash
curl http://localhost:8080/health
# Expected: {"status":"UP","service":"secman-backend-ng","version":"0.1"}
```

### 2. Create an MCP API Key

Get a JWT token first:

```bash
curl -X POST "http://localhost:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username": "adminuser", "password": "password"}'
```

Create the API key:

```bash
curl -X POST "http://localhost:8080/api/mcp/admin/api-keys" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "name": "claude-integration",
    "permissions": ["REQUIREMENTS_READ", "ASSETS_READ", "VULNERABILITIES_READ"],
    "notes": "API key for Claude integration"
  }'
```

**Save the returned `apiKey` value** (starts with `sk-`) - you'll need it for configuration.

### 3. Configure Your Client

See [Connection Methods](#connection-methods) for client-specific setup.

---

## Connection Methods

### Claude Code (Direct HTTP)

Claude Code natively supports HTTP transport - the simplest setup with no middleware required.

```bash
claude mcp add --transport http secman http://localhost:8080/mcp \
  --header "X-MCP-API-Key: sk-your-api-key-here" \
  --header "X-MCP-User-Email: your.email@company.com"
```

For a remote server:

```bash
claude mcp add --transport http secman https://secman.yourcompany.com/mcp \
  --header "X-MCP-API-Key: sk-your-api-key-here" \
  --header "X-MCP-User-Email: your.email@company.com"
```

### Claude Desktop with mcp-remote

Claude Desktop requires stdio-based servers. Use `mcp-remote` to proxy HTTP requests.

**Config file locations:**
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
- **Linux**: `~/.config/Claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "secman": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://localhost:8080/mcp",
        "--header",
        "X-MCP-API-Key: sk-your-api-key-here",
        "--header",
        "X-MCP-User-Email: your.email@company.com"
      ]
    }
  }
}
```

### Claude Desktop with Node.js Bridge

Uses the included Node.js MCP server (legacy method, full-featured):

1. Install dependencies:
   ```bash
   cd /path/to/secman
   npm install
   chmod +x mcp/mcp-server.js
   ```

2. Configure Claude Desktop:
   ```json
   {
     "mcpServers": {
       "secman": {
         "command": "node",
         "args": ["/path/to/secman/mcp/mcp-server.js"],
         "env": {
           "SECMAN_BASE_URL": "http://localhost:8080",
           "SECMAN_API_KEY": "sk-your-api-key-here",
           "SECMAN_USER_EMAIL": "your.email@company.com"
         }
       }
     }
   }
   ```

3. Restart Claude Desktop.

### Testing the Connection

Test the MCP endpoint directly:

```bash
# Test initialize handshake
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: sk-your-api-key-here" \
  -d '{
    "jsonrpc": "2.0",
    "id": "1",
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "capabilities": {},
      "clientInfo": {
        "name": "test-client",
        "version": "1.0.0"
      }
    }
  }'

# List available tools
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: sk-your-api-key-here" \
  -d '{
    "jsonrpc": "2.0",
    "id": "2",
    "method": "tools/list"
  }'
```

---

## API Key Management

### Creating Keys via Web UI

1. Navigate to: **Settings > MCP Integration > API Keys**
2. Click **Create New API Key**
3. Configure:
   - **Name**: Descriptive identifier
   - **Permissions**: Select required permissions
   - **Expiration**: Optional (90 days recommended)
   - **User Delegation**: Enable if needed for multi-user access
4. Click **Generate Key**
5. Copy and securely store the key (shown only once)

### Creating Keys via API

```bash
curl -X POST http://localhost:8080/api/mcp/admin/api-keys \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "My Integration",
    "permissions": ["REQUIREMENTS_READ", "ASSETS_READ"],
    "expiresAt": "2025-12-31T23:59:59"
  }'
```

### Permission Types

| Permission | Description | Tools Enabled |
|------------|-------------|---------------|
| `REQUIREMENTS_READ` | Read security requirements | `get_requirements`, `export_requirements` |
| `REQUIREMENTS_WRITE` | Create/modify requirements | `add_requirement` |
| `REQUIREMENTS_DELETE` | Delete requirements | `delete_all_requirements` |
| `ASSETS_READ` | Read asset inventory | `get_assets`, `get_all_assets_detail`, `get_asset_profile`, `get_asset_complete_profile` |
| `VULNERABILITIES_READ` | Read vulnerability data | `get_vulnerabilities`, `get_all_vulnerabilities_detail`, `get_asset_most_vulnerabilities`, `get_overdue_assets`, `get_my_exception_requests`, `get_pending_exception_requests`, `create_exception_request`, `approve_exception_request`, `reject_exception_request`, `cancel_exception_request` |
| `SCANS_READ` | Read scan history | `get_scans`, `get_asset_scan_results`, `search_products` |
| `USER_ACTIVITY` | User management and mappings | `list_users`, `add_user`, `delete_user`, `import_user_mappings`, `list_user_mappings` (all require delegation) |

### Managing Keys

**List all keys:**
```bash
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/mcp/admin/api-keys
```

**Revoke a key:**
```bash
curl -X DELETE \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  http://localhost:8080/api/mcp/admin/api-keys/KEY_ID
```

---

## User Delegation

User delegation allows MCP requests to be made on behalf of specific Secman users, maintaining role-based access control (RBAC) even when requests come through a shared API key.

### How It Works

When enabled, a delegation-enabled API key can include the `X-MCP-User-Email` header. Secman will:

1. Look up the user by email
2. Validate the email domain against allowed domains
3. Compute **effective permissions** as the intersection of:
   - Permissions implied by the user's roles
   - Permissions granted to the API key

This ensures the delegated user can never have more access than either their roles OR the API key allows.

### Creating a Delegation-Enabled Key

```bash
curl -X POST http://localhost:8080/api/mcp/admin/api-keys \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "External Tool Integration",
    "permissions": ["REQUIREMENTS_READ", "ASSETS_READ", "VULNERABILITIES_READ"],
    "delegationEnabled": true,
    "allowedDelegationDomains": "@company.com,@subsidiary.com"
  }'
```

### Permission Mapping

User roles are mapped to MCP permissions:

| User Role | Implied MCP Permissions |
|-----------|-------------------------|
| `USER` | `REQUIREMENTS_READ`, `ASSETS_READ`, `VULNERABILITIES_READ`, `TAGS_READ` |
| `ADMIN` | All permissions |
| `VULN` | `VULNERABILITIES_READ`, `SCANS_READ`, `ASSETS_READ` |
| `RELEASE_MANAGER` | `REQUIREMENTS_READ`, `ASSESSMENTS_READ` |
| `REQ` | `REQUIREMENTS_READ`, `REQUIREMENTS_WRITE`, `FILES_READ`, `TAGS_READ` |
| `SECCHAMPION` | `REQUIREMENTS_READ`, `ASSETS_READ`, `VULNERABILITIES_READ`, `SCANS_READ` |

**Example:** User has `VULN` role, API key has `[ASSETS_READ, VULNERABILITIES_READ, REQUIREMENTS_READ]`:
- User's implied permissions: `[VULNERABILITIES_READ, SCANS_READ, ASSETS_READ]`
- API key permissions: `[ASSETS_READ, VULNERABILITIES_READ, REQUIREMENTS_READ]`
- **Effective permissions**: `[VULNERABILITIES_READ, ASSETS_READ]` (intersection)

### Delegation Error Codes

| Error Code | Description |
|------------|-------------|
| `DELEGATION_NOT_ENABLED` | API key doesn't have delegation enabled |
| `DELEGATION_DOMAIN_REJECTED` | Email domain not in allowed list |
| `DELEGATION_USER_NOT_FOUND` | User with email doesn't exist |
| `DELEGATION_USER_INACTIVE` | User account is disabled |

---

## Available MCP Tools

### Requirements Management

#### `get_requirements`
Retrieve security requirements with filtering and pagination.

| Parameter | Type | Description |
|-----------|------|-------------|
| `limit` | number | Max results (default: 20, max: 100) |
| `offset` | number | Skip N results (default: 0) |
| `tags` | string[] | Filter by tags |
| `status` | enum | `DRAFT`, `ACTIVE`, `DEPRECATED`, `ARCHIVED` |
| `priority` | enum | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |

#### `export_requirements`
Export all requirements to Excel or Word format.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `format` | enum | Yes | `xlsx` (Excel) or `docx` (Word) |

#### `add_requirement`
Create a new security requirement.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `shortreq` | string | Yes | Short requirement text |
| `details` | string | No | Detailed description |
| `motivation` | string | No | Why this requirement exists |
| `norm` | string | No | Regulatory norm reference |
| `chapter` | string | No | Chapter/category |

#### `delete_all_requirements`
Delete ALL requirements. **ADMIN only.**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `confirm` | boolean | Yes | Must be `true` |

### Asset Management

#### `get_assets`
Retrieve asset inventory with filtering.

| Parameter | Type | Description |
|-----------|------|-------------|
| `page` | number | Page number (0-indexed) |
| `pageSize` | number | Items per page (max: 500) |
| `name` | string | Filter by name (partial match) |
| `type` | string | `SERVER`, `WORKSTATION`, `NETWORK`, `OTHER` |
| `ip` | string | Filter by IP (partial match) |
| `owner` | string | Filter by owner |

#### `get_all_assets_detail`
Retrieve all assets with comprehensive details and workgroup info.

#### `get_asset_profile`
Retrieve profile for a single asset including vulnerabilities and scan history.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `assetId` | number | Yes | The asset ID |
| `includeVulnerabilities` | boolean | No | Include vulnerabilities (default: true) |
| `includeScanHistory` | boolean | No | Include scan history (default: true) |

#### `get_asset_complete_profile`
Retrieve complete asset profile with all vulnerabilities and scan results.

### Vulnerability Management

#### `get_vulnerabilities`
Retrieve vulnerability data with filtering.

| Parameter | Type | Description |
|-----------|------|-------------|
| `page` | number | Page number |
| `pageSize` | number | Items per page (max: 500) |
| `cveId` | string | Filter by CVE ID |
| `severity` | string[] | `Critical`, `High`, `Medium`, `Low`, `Info` |
| `assetId` | number | Filter by asset ID |

#### `get_all_vulnerabilities_detail`
Retrieve vulnerabilities with severity/asset/days-open filtering.

| Parameter | Type | Description |
|-----------|------|-------------|
| `severity` | enum | `CRITICAL`, `HIGH`, `MEDIUM`, `LOW` |
| `assetId` | integer | Filter by asset ID |
| `minDaysOpen` | integer | Minimum days open |

#### `get_asset_most_vulnerabilities`
Get the asset(s) with the highest number of vulnerabilities, ranked by total count.

| Parameter | Type | Description |
|-----------|------|-------------|
| `topN` | integer | Number of top assets to return (default: 1, max: 10) |

Returns asset details with vulnerability counts by severity (critical, high, medium, low).

### Overdue Assets & Exception Handling

#### `get_overdue_assets`
Get assets with overdue vulnerabilities. **Requires ADMIN or VULN role and User Delegation.**

| Parameter | Type | Description |
|-----------|------|-------------|
| `page` | number | Page number (0-indexed, default: 0) |
| `size` | number | Page size (default: 20, max: 100) |
| `minSeverity` | enum | Minimum severity: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW` |
| `searchTerm` | string | Search by asset name (case-insensitive) |

Returns paginated list of assets with overdue vulnerability counts by severity.

#### `create_exception_request`
Create a vulnerability exception request. **Requires User Delegation.**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `vulnerabilityId` | number | Yes | ID of the vulnerability |
| `reason` | string | Yes | Business justification (50-2048 characters) |
| `expirationDate` | string | Yes | ISO-8601 datetime (must be future) |
| `scope` | enum | No | `SINGLE_VULNERABILITY` (default) or `CVE_PATTERN` |

ADMIN and SECCHAMPION roles get auto-approved requests.

#### `get_my_exception_requests`
Get your own exception requests. **Requires User Delegation.**

| Parameter | Type | Description |
|-----------|------|-------------|
| `status` | enum | Filter: `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `CANCELLED` |
| `page` | number | Page number (0-indexed, default: 0) |
| `size` | number | Page size (default: 20, max: 100) |

#### `get_pending_exception_requests`
Get all pending requests awaiting approval. **Requires ADMIN or SECCHAMPION role and User Delegation.**

| Parameter | Type | Description |
|-----------|------|-------------|
| `page` | number | Page number (0-indexed, default: 0) |
| `size` | number | Page size (default: 20, max: 100) |

Returns requests sorted by oldest first (FIFO processing).

#### `approve_exception_request`
Approve a pending request. **Requires ADMIN or SECCHAMPION role and User Delegation.**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `requestId` | number | Yes | ID of the request to approve |
| `comment` | string | No | Optional approval comment (max 1024 chars) |

Creates a VulnerabilityException on successful approval.

#### `reject_exception_request`
Reject a pending request. **Requires ADMIN or SECCHAMPION role and User Delegation.**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `requestId` | number | Yes | ID of the request to reject |
| `comment` | string | Yes | Rejection reason (10-1024 characters) |

#### `cancel_exception_request`
Cancel your own pending request. **Requires User Delegation.**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `requestId` | number | Yes | ID of your request to cancel |

Only the original requester can cancel; only PENDING requests can be cancelled.

### Scan Management

#### `get_scans`
Retrieve scan history.

| Parameter | Type | Description |
|-----------|------|-------------|
| `scanType` | enum | `nmap`, `masscan` |
| `uploadedBy` | string | Filter by uploader |
| `startDate` | string | ISO-8601 date |

#### `get_asset_scan_results`
Retrieve scan results (open ports, services).

| Parameter | Type | Description |
|-----------|------|-------------|
| `portMin` | integer | Minimum port (1-65535) |
| `portMax` | integer | Maximum port |
| `service` | string | Service name filter |
| `state` | enum | `open`, `filtered`, `closed` |

#### `search_products`
Search products discovered in network scans.

### Admin Tools

#### `list_users`
List all users. **Requires ADMIN role and User Delegation.**

#### `list_products`
List products. **Requires ADMIN or SECCHAMPION role.**

#### `add_user`
Create a new user. **Requires ADMIN role and User Delegation.**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `email` | string | Yes | User email address |
| `password` | string | Yes | User password |
| `roles` | string[] | No | User roles (default: `["USER"]`) |

#### `delete_user`
Delete a user by email. **Requires ADMIN role and User Delegation.**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `email` | string | Yes | Email of user to delete |

#### `delete_all_assets`
Delete ALL assets with cascade deletion of vulnerabilities, scan results, and exception requests. **Requires ADMIN role and User Delegation.**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `confirm` | boolean | Yes | Must be `true` to confirm deletion |

Returns counts of deleted assets, vulnerabilities, and scan results.

#### `add_vulnerability`
Add a vulnerability to an asset. Creates the asset if it doesn't exist. **Requires ADMIN or VULN role and User Delegation.**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `hostname` | string | Yes | Asset hostname/name |
| `cve` | string | Yes | CVE identifier (e.g., CVE-2024-1234) |
| `criticality` | enum | Yes | `CRITICAL`, `HIGH`, `MEDIUM`, or `LOW` |
| `daysOpen` | integer | No | Days the vulnerability has been open (default: 0) |
| `owner` | string | No | Owner to assign to newly created asset |

Returns vulnerability ID, asset details, and whether the asset/vulnerability were created or updated.

### User Mapping Management

#### `import_user_mappings`
Bulk import user mappings (email to AWS account / domain). **Requires ADMIN role and User Delegation.**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `mappings` | array | Yes | List of mapping objects (max 1000) |
| `mappings[].email` | string | Yes | User email address |
| `mappings[].awsAccountId` | string | No | AWS account ID (12 digits) |
| `mappings[].domain` | string | No | AD domain name |
| `dryRun` | boolean | No | Validate without persisting (default: false) |

**Note:** Each mapping must have at least one of `awsAccountId` or `domain`.

**Returns:**
- `totalProcessed`: Number of entries processed
- `created`: Active mappings created (user exists)
- `createdPending`: Pending mappings created (user doesn't exist yet)
- `skipped`: Duplicate mappings skipped
- `errors`: List of validation errors with index, email, and message
- `dryRun`: Whether this was a dry-run

#### `list_user_mappings`
List user mappings with pagination and filtering. **Requires ADMIN role and User Delegation.**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `email` | string | No | Filter by email (partial match, case-insensitive) |
| `page` | number | No | Page number (0-indexed, default: 0) |
| `size` | number | No | Page size (default: 20, max: 100) |

**Returns:**
- `mappings`: List of user mapping DTOs containing:
  - `id`: Mapping ID
  - `email`: User email
  - `awsAccountId`: AWS account ID (if set)
  - `domain`: AD domain (if set)
  - `userId`: Associated user ID (null for pending mappings)
  - `isFutureMapping`: Boolean indicating if user doesn't exist yet
  - `appliedAt`: Timestamp when mapping was applied
  - `createdAt`: Creation timestamp
  - `updatedAt`: Last update timestamp
- `page`: Current page number
- `size`: Page size
- `totalElements`: Total number of mappings
- `totalPages`: Total number of pages

---

## Authentication & Security

### API Key Security

- Store keys in environment variables or encrypted configuration
- Use HTTPS in production
- Set reasonable expiration dates (30-90 days)
- Rotate keys regularly
- Monitor usage via audit logs

### Origin Validation

The `/mcp` endpoint validates the `Origin` header per MCP specification:
- Requests without `Origin` header are allowed (non-browser clients)
- Localhost origins are always allowed
- Configure allowed origins in `application.yml`:

```yaml
secman:
  mcp:
    transport:
      allowed-origins:
        - https://your-domain.com
      validate-origin: true
```

### Session Management

| Setting | Value |
|---------|-------|
| Timeout | 60 minutes inactivity |
| Connection Types | HTTP, SSE, WebSocket |
| Cleanup | Automatic for expired sessions |

### Audit Logging

All MCP operations are logged with:
- Timestamp and duration
- User and API key information
- Tool called and parameters
- Success/failure status
- Client IP and User-Agent

---

## Usage Examples

### Claude Desktop/Code Prompts

**Requirements:**
- "Show me all critical security requirements" → `get_requirements` with `priority: "CRITICAL"`
- "Export requirements to Excel" → `export_requirements` with `format: "xlsx"`

**Assets:**
- "Show me all servers" → `get_assets` with `type: "SERVER"`
- "Get details for asset ID 42" → `get_asset_profile` with `assetId: 42`

**Vulnerabilities:**
- "Show me all critical vulnerabilities" → `get_vulnerabilities` with `severity: ["Critical"]`
- "Find vulnerabilities open more than 30 days" → `get_all_vulnerabilities_detail` with `minDaysOpen: 30`
- "What asset has the most vulnerabilities?" → `get_asset_most_vulnerabilities`
- "Show me the top 5 most vulnerable assets" → `get_asset_most_vulnerabilities` with `topN: 5`

**Overdue Assets & Exceptions:**
- "Show assets with overdue vulnerabilities" → `get_overdue_assets`
- "Find critical overdue assets" → `get_overdue_assets` with `minSeverity: "CRITICAL"`
- "Create an exception request for vulnerability 123" → `create_exception_request` with ID, reason, and expiration
- "Show my exception requests" → `get_my_exception_requests`
- "Show pending exception requests" → `get_pending_exception_requests`
- "Approve exception request 456" → `approve_exception_request` with `requestId: 456`
- "Reject exception request 789 with reason" → `reject_exception_request` with requestId and comment

**Scans:**
- "Find all open SSH ports" → `get_asset_scan_results` with `service: "ssh"`, `state: "open"`
- "Search for Apache servers" → `search_products` with `service: "Apache"`

**User Mappings:**
- "Import user mappings" → `import_user_mappings` with mappings array
- "Validate mappings before import" → `import_user_mappings` with `dryRun: true`
- "List all user mappings" → `list_user_mappings`
- "Find mappings for user@company.com" → `list_user_mappings` with `email: "user@company.com"`

### Programmatic Access

#### Python

```python
import requests

api_key = "sk-your-api-key"
base_url = "http://localhost:8080/mcp"

response = requests.post(base_url,
    headers={
        "X-MCP-API-Key": api_key,
        "Content-Type": "application/json"
    },
    json={
        "jsonrpc": "2.0",
        "id": "req-1",
        "method": "tools/call",
        "params": {
            "name": "get_requirements",
            "arguments": {"limit": 10}
        }
    }
)
print(response.json())
```

#### Node.js

```javascript
const axios = require('axios');

const response = await axios.post('http://localhost:8080/mcp', {
  jsonrpc: '2.0',
  id: 'req-1',
  method: 'tools/call',
  params: {
    name: 'get_requirements',
    arguments: { limit: 10 }
  }
}, {
  headers: {
    'X-MCP-API-Key': process.env.SECMAN_API_KEY,
    'Content-Type': 'application/json'
  }
});
console.log(response.data);
```

---

## Administration

### Monitoring

Navigate to **Admin > MCP Monitoring** for:
- Active sessions
- API key usage statistics
- Tool usage analytics
- Error logs

### Performance Tuning

In `application.yml`:

```yaml
mcp:
  max-concurrent-sessions: 200
  max-sessions-per-key: 10
  session-timeout-minutes: 60
  rate-limiting:
    default-requests-per-hour: 1000
    burst-limit: 100
  caching:
    enabled: true
    ttl-minutes: 15
```

### Database Tables

Ensure backup includes MCP-related tables:
- `mcp_api_keys`
- `mcp_sessions`
- `mcp_audit_logs`
- `mcp_tool_permissions`

---

## Troubleshooting

### "Authentication required" error
- Ensure `X-MCP-API-Key` header is present
- Verify the API key is valid and active
- Check the API key hasn't expired

### "Permission denied" error
- Verify your API key has the required permissions
- For admin tools, ensure User Delegation is enabled and `X-MCP-User-Email` is set
- The delegated user must have the required role

### "Origin not allowed" error
- This occurs when making requests from a browser
- Direct HTTP transport is designed for non-browser clients
- Localhost origins are always allowed for development

### "MCP Server fails to start" (Node.js bridge)
- Ensure Node.js 18+ is installed
- Run `npm install` in project directory
- Verify paths in Claude Desktop config are absolute
- Check `mcp-server.js` is executable

### Connection refused
- Ensure the secman backend is running
- Check firewall settings
- Verify the `/mcp` endpoint is accessible

### Debug Commands

**Test API key:**
```bash
curl -H "X-MCP-API-Key: your-key" \
  http://localhost:8080/api/mcp/capabilities
```

**Enable debug logging:**
```bash
export SECMAN_LOG_LEVEL=DEBUG
```

---

## See Also

- [Environment Variables](./ENVIRONMENT.md) - Configuration reference
- [Deployment Guide](./DEPLOYMENT.md) - Production setup
- [CLI Reference](./CLI.md) - Command-line tools
- [MCP Specification](https://modelcontextprotocol.io/specification/2024-11-05/basic/transports) - Protocol details

---

*For questions or issues, check the Secman backend logs and Claude Desktop/Code application logs.*
