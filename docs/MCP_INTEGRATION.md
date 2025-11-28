# MCP (Model Context Protocol) Integration Guide

**Last Updated:** 2025-11-28
**Version:** 1.2

This guide covers integrating Secman with AI assistants (Claude Desktop, ChatGPT, etc.) using the Model Context Protocol (MCP).

---

## Table of Contents

1. [Overview](#overview)
2. [Quick Start](#quick-start)
3. [API Key Management](#api-key-management)
4. [User Delegation](#user-delegation)
5. [Claude Desktop Setup](#claude-desktop-setup)
6. [Available MCP Tools](#available-mcp-tools)
7. [Authentication & Security](#authentication--security)
8. [Usage Examples](#usage-examples)
9. [Troubleshooting](#troubleshooting)
10. [Administration](#administration)

---

## Overview

Secman supports the Model Context Protocol (MCP), allowing AI assistants to programmatically access security requirements and risk assessments. This enables AI-assisted security management workflows.

### Prerequisites

- Secman backend running (default: port 8080)
- Valid Secman admin account (for API key creation)
- AI assistant that supports MCP (Claude Desktop, etc.)

### Architecture

```
[AI Assistant] <--MCP Protocol--> [MCP Server] <--REST API--> [Secman Backend]
     |                                 |
     |                           Node.js/Python
     |                           (local bridge)
     |
  Claude Desktop / ChatGPT / etc.
```

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
    "name": "claude-desktop-integration",
    "permissions": ["REQUIREMENTS_READ", "ASSESSMENTS_READ", "TAGS_READ"],
    "notes": "API key for Claude Desktop"
  }'
```

**Save the returned `apiKey` value** - you'll need it for configuration.

### 3. Install MCP Dependencies

```bash
cd /path/to/secman
npm install
chmod +x mcp-server.js
```

### 4. Configure Claude Desktop

Edit your Claude Desktop config:
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "secman": {
      "command": "node",
      "args": ["/path/to/secman/mcp-server.js"],
      "env": {
        "SECMAN_API_URL": "http://localhost:8080/api/mcp",
        "SECMAN_API_KEY": "your-api-key-here"
      }
    }
  }
}
```

### 5. Restart Claude Desktop

---

## API Key Management

### Creating Keys via Web UI

1. Navigate to: **Settings > MCP Integration > API Keys**
2. Click **Create New API Key**
3. Configure:
   - **Name**: Descriptive identifier
   - **Permissions**: Select required permissions
   - **Expiration**: Optional (90 days recommended)
4. Click **Generate Key**
5. Copy and securely store the key

### Creating Keys via API

```bash
curl -X POST http://localhost:8080/api/mcp/admin/api-keys \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "My Integration",
    "permissions": ["REQUIREMENTS_READ", "ASSESSMENTS_READ"],
    "expiresAt": "2025-12-31T23:59:59"
  }'
```

### Permission Types

| Permission | Description | Tools Enabled |
|------------|-------------|---------------|
| `REQUIREMENTS_READ` | Read security requirements | `get_requirements`, `search_requirements` |
| `REQUIREMENTS_WRITE` | Create/modify requirements | `create_requirement`, `update_requirement` |
| `ASSESSMENTS_READ` | Read risk assessments | `get_assessments`, `search_assessments` |
| `ASSESSMENTS_WRITE` | Create/modify assessments | `create_assessment`, `update_assessment` |
| `TAGS_READ` | Read tags and categories | `get_tags` |
| `SYSTEM_INFO` | Access system information | `get_system_info` |
| `USER_ACTIVITY` | View user activity logs | `get_user_activity` |

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

*Feature: 050-mcp-user-delegation*

User delegation allows external tools (like Claude Code, Cursor, or custom integrations) to authenticate on behalf of specific Secman users. This maintains role-based access control (RBAC) even when requests come through a shared API key.

### Overview

When enabled, a delegation-enabled API key can include the `X-MCP-User-Email` header to specify which user the request is being made on behalf of. Secman will:

1. Look up the user by email
2. Validate the email domain against allowed domains
3. Compute **effective permissions** as the intersection of:
   - Permissions implied by the user's roles
   - Permissions granted to the API key

This ensures defense-in-depth: the delegated user can never have more access than either their roles allow OR the API key allows.

### Creating a Delegation-Enabled API Key

```bash
curl -X POST http://localhost:8080/api/mcp/admin/api-keys \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "External Tool Integration",
    "permissions": ["REQUIREMENTS_READ", "ASSESSMENTS_READ", "ASSETS_READ", "VULNERABILITIES_READ"],
    "delegationEnabled": true,
    "allowedDelegationDomains": "@company.com,@subsidiary.com"
  }'
```

**Required fields when `delegationEnabled` is true:**
- `allowedDelegationDomains`: Comma-separated list of allowed email domains (must start with `@`)

### Using Delegation

When making requests with a delegation-enabled key, include the `X-MCP-User-Email` header:

```bash
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "X-MCP-API-Key: sk-your-delegation-enabled-key" \
  -H "X-MCP-User-Email: user@company.com" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "req-1",
    "method": "tools/call",
    "params": {
      "name": "get_requirements",
      "arguments": {"limit": 10}
    }
  }'
```

### Permission Mapping

User roles are mapped to MCP permissions as follows:

| User Role | Implied MCP Permissions |
|-----------|------------------------|
| `USER` | `REQUIREMENTS_READ`, `ASSETS_READ`, `VULNERABILITIES_READ`, `TAGS_READ` |
| `ADMIN` | All permissions |
| `VULN` | `VULNERABILITIES_READ`, `SCANS_READ`, `ASSETS_READ` |
| `RELEASE_MANAGER` | `REQUIREMENTS_READ`, `ASSESSMENTS_READ` |
| `REQ` | `REQUIREMENTS_READ`, `REQUIREMENTS_WRITE`, `FILES_READ`, `TAGS_READ` |
| `RISK` | `ASSESSMENTS_READ`, `ASSESSMENTS_WRITE`, `ASSESSMENTS_EXECUTE` |
| `SECCHAMPION` | `REQUIREMENTS_READ`, `ASSESSMENTS_READ`, `ASSETS_READ`, `VULNERABILITIES_READ`, `SCANS_READ` |

**Example:** If a user has `VULN` role and the API key has `[ASSETS_READ, VULNERABILITIES_READ, REQUIREMENTS_READ]`:
- User's implied permissions: `[VULNERABILITIES_READ, SCANS_READ, ASSETS_READ]`
- API key permissions: `[ASSETS_READ, VULNERABILITIES_READ, REQUIREMENTS_READ]`
- **Effective permissions**: `[VULNERABILITIES_READ, ASSETS_READ]` (intersection)

### Fallback Behavior

- **No header provided**: Request uses API key's base permissions (backward compatible)
- **Empty header**: Same as no header
- **Non-delegation key**: `X-MCP-User-Email` header is ignored

### Security Considerations

1. **Domain Restrictions**: Mandatory when delegation is enabled. Only emails matching allowed domains can be delegated.

2. **User Validation**: The delegated user must exist in Secman and be active.

3. **Audit Trail**: All delegated requests are logged with both the API key owner and delegated user email.

4. **Alert Threshold**: Configurable threshold for failed delegation attempts triggers security alerts. Configure in `application.yml`:

```yaml
secman:
  mcp:
    delegation:
      alert:
        threshold: 10        # failures to trigger alert
        window-minutes: 5    # time window
```

### Delegation Error Codes

| Error Code | Description |
|------------|-------------|
| `DELEGATION_NOT_ENABLED` | API key doesn't have delegation enabled |
| `DELEGATION_DOMAIN_REJECTED` | Email domain not in allowed list |
| `DELEGATION_USER_NOT_FOUND` | User with email doesn't exist |
| `DELEGATION_USER_INACTIVE` | User account is disabled |
| `DELEGATION_INVALID_EMAIL` | Email format is invalid |
| `DELEGATION_FAILED` | General delegation failure |

### Example: Claude Code Integration

For Claude Code or similar tools that authenticate users externally:

1. Create a delegation-enabled key with your organization's domain
2. Configure the tool to pass the authenticated user's email via `X-MCP-User-Email`
3. Users get permissions based on their Secman roles, not the shared API key

```json
{
  "mcpServers": {
    "secman": {
      "command": "node",
      "args": ["/path/to/secman/mcp-server.js"],
      "env": {
        "SECMAN_API_URL": "http://localhost:8080/api/mcp",
        "SECMAN_API_KEY": "sk-delegation-enabled-key",
        "SECMAN_USER_EMAIL": "${USER_EMAIL}"
      }
    }
  }
}
```

---

## Claude Desktop Setup

### Configuration File Location

| OS | Path |
|----|------|
| macOS | `~/Library/Application Support/Claude/claude_desktop_config.json` |
| Windows | `%APPDATA%\Claude\claude_desktop_config.json` |
| Linux | `~/.config/Claude/claude_desktop_config.json` |

### Node.js MCP Server Configuration

```json
{
  "mcpServers": {
    "secman": {
      "command": "node",
      "args": ["/absolute/path/to/secman/mcp-server.js"],
      "env": {
        "SECMAN_API_URL": "http://localhost:8080/api/mcp",
        "SECMAN_API_KEY": "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
      }
    }
  }
}
```

### Python MCP Server (Alternative)

1. Install dependencies:
   ```bash
   pip install requests
   ```

2. Update `src/misc/mcp.py` with your API key

3. Configure Claude Desktop:
   ```json
   {
     "mcpServers": {
       "secman": {
         "command": "python",
         "args": ["/path/to/secman/src/misc/mcp.py"],
         "env": {}
       }
     }
   }
   ```

### Testing the MCP Server

Before configuring Claude Desktop:

```bash
cd /path/to/secman
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0.0"}}}' | node mcp-server.js
```

You should see a JSON response indicating successful initialization.

---

## Available MCP Tools

### Requirements Management

#### `get_requirements`
Retrieve security requirements with filtering.

```json
{
  "name": "get_requirements",
  "arguments": {
    "limit": 10,
    "tags": ["GDPR", "ISO27001"],
    "status": "active"
  }
}
```

#### `search_requirements`
Full-text search across requirements.

```json
{
  "name": "search_requirements",
  "arguments": {
    "query": "authentication",
    "limit": 20
  }
}
```

### Risk Assessment Management

#### `get_assessments`
Retrieve risk assessments.

```json
{
  "name": "get_assessments",
  "arguments": {
    "limit": 20,
    "risk_level": "HIGH"
  }
}
```

#### `search_assessments`
Search risk assessments.

```json
{
  "name": "search_assessments",
  "arguments": {
    "query": "data privacy"
  }
}
```

### Utility Tools

#### `get_tags`
Retrieve all available tags and categories.

#### `search_all`
Universal search across all content types.

```json
{
  "name": "search_all",
  "arguments": {
    "query": "security vulnerability",
    "types": ["requirements", "assessments"]
  }
}
```

---

## Authentication & Security

### API Key Security

- Store keys in environment variables or encrypted configuration
- All API calls use HTTPS in production
- Set reasonable expiration dates (30-90 days)
- Rotate keys regularly
- Monitor usage via audit logs

### Rate Limiting

| Limit Type | Default |
|------------|---------|
| Per API Key | 1000 requests/hour |
| Concurrent Sessions | 10 per API key |

### Session Management

- **Timeout**: 60 minutes of inactivity
- **Connection Types**: HTTP, SSE, WebSocket
- **Cleanup**: Automatic for expired sessions

### Audit Logging

All MCP operations are logged with:
- Timestamp and duration
- User and API key information
- Tool called and parameters
- Success/failure status
- Client IP and User-Agent

---

## Usage Examples

### Claude Desktop Prompts

**"Show me all critical security requirements"**
```
Claude will use get_requirements with priority: "CRITICAL"
```

**"Find risk assessments related to data privacy"**
```
Claude will use search_assessments with query: "data privacy"
```

**"Create a new requirement for API security"**
```
Claude will use create_requirement (requires WRITE permission)
```

### Programmatic Access

#### Python
```python
import requests

api_key = "your-api-key"
base_url = "http://localhost:8080/api/mcp"

headers = {
    "X-MCP-API-Key": api_key,
    "Content-Type": "application/json"
}

# Call tool
response = requests.post(f"{base_url}/tools/call",
    headers=headers,
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

const client = axios.create({
  baseURL: 'http://localhost:8080/api/mcp',
  headers: {
    'X-MCP-API-Key': process.env.SECMAN_API_KEY,
    'Content-Type': 'application/json'
  }
});

const result = await client.post('/tools/call', {
  jsonrpc: '2.0',
  id: 'req-1',
  method: 'tools/call',
  params: {
    name: 'search_all',
    arguments: { query: 'security', limit: 20 }
  }
});
console.log(result.data);
```

---

## Troubleshooting

### Common Issues

#### "Authentication Failed"
- Verify API key is correct and not expired
- Check `X-MCP-API-Key` header is set
- Ensure key has required permissions

#### "Session Not Found"
- Sessions expire after 60 minutes
- Create new session via `/api/mcp/session`

#### "Permission Denied"
- Verify API key permissions include required tool
- Check rate limits haven't been exceeded

#### "MCP Server fails to start"
- Ensure Node.js 18+ is installed
- Run `npm install` in project directory
- Verify paths in Claude Desktop config are absolute
- Check `mcp-server.js` is executable

### Debug Commands

**Test API key:**
```bash
curl -H "X-MCP-API-Key: your-key" \
  http://localhost:8080/api/mcp/capabilities
```

**Test tool call:**
```bash
curl -X POST \
  -H "X-MCP-API-Key: your-key" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":"test","method":"tools/call","params":{"name":"get_requirements","arguments":{"limit":1}}}' \
  http://localhost:8080/api/mcp/tools/call
```

### Enable Debug Logging

Set environment variable:
```bash
export SECMAN_LOG_LEVEL=DEBUG
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

### Backup

Ensure backup includes MCP-related tables:
- `mcp_api_keys`
- `mcp_sessions`
- `mcp_audit_logs`
- `mcp_tool_permissions`

---

## Related Documentation

- [Environment Variables](./ENVIRONMENT.md) - Configuration reference
- [Deployment Guide](./DEPLOYMENT.md) - Production setup
- [MCP Specification](https://modelcontextprotocol.io) - Protocol details

---

*For questions or issues, check the Secman backend logs and Claude Desktop application logs.*
