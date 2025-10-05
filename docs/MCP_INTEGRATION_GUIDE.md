# MCP (Model Context Protocol) Integration Guide for Secman

## Overview

Secman now supports the Model Context Protocol (MCP), allowing AI assistants like Claude, ChatGPT, and other AI tools to programmatically access your security requirements and risk assessments. This integration enables powerful AI-assisted security management workflows.

## Table of Contents

1. [Getting Started](#getting-started)
2. [API Key Management](#api-key-management)
3. [Setting Up Your AI Assistant](#setting-up-your-ai-assistant)
4. [Available MCP Tools](#available-mcp-tools)
5. [Authentication & Security](#authentication--security)
6. [Usage Examples](#usage-examples)
7. [Troubleshooting](#troubleshooting)
8. [Administration](#administration)

## Getting Started

### Prerequisites

- Secman backend running on port 8080
- Secman frontend running on port 4321
- Valid Secman user account with admin privileges (for API key creation)
- AI assistant that supports MCP (Claude Desktop, ChatGPT with plugins, etc.)

### Quick Setup

1. **Login to Secman**: Access the web interface at `http://localhost:4321`
2. **Create MCP API Key**: Navigate to MCP Settings → API Keys → Create New Key
3. **Configure Your AI Assistant**: Add the MCP server configuration
4. **Start Using MCP Tools**: Your AI assistant can now access Secman data

## API Key Management

### Creating an API Key

1. **Web Interface Method**:
   ```
   Navigate to: Settings → MCP Integration → API Keys
   Click: "Create New API Key"
   Fill in:
   - Name: "Claude Desktop Integration"
   - Permissions: Select required permissions
   - Expiration: Optional (recommended: 90 days)
   Click: "Generate Key"
   ```

2. **REST API Method**:
   ```bash
   curl -X POST http://localhost:8080/api/mcp/admin/api-keys \
     -H "Authorization: Bearer YOUR_JWT_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
       "name": "Claude Desktop Integration",
       "permissions": ["REQUIREMENTS_READ", "ASSESSMENTS_READ"],
       "expiresAt": "2024-12-31T23:59:59"
     }'
   ```

### API Key Format

- **Key ID**: 32-character alphanumeric identifier
- **Secret**: Starts with `sk-` followed by 48 secure characters
- **Example**: `sk-abc123def456...` (keep this secret!)

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

## Setting Up Your AI Assistant

### Claude Desktop Configuration

1. **Locate Claude Config**:
   - macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
   - Windows: `%APPDATA%\Claude\claude_desktop_config.json`

2. **Add MCP Server**:
   ```json
   {
     "mcpServers": {
       "secman": {
         "command": "node",
         "args": ["/path/to/secman-mcp-client.js"],
         "env": {
           "SECMAN_API_URL": "http://localhost:8080/api/mcp",
           "SECMAN_API_KEY": "your-api-key-here"
         }
       }
     }
   }
   ```

3. **Restart Claude Desktop**

### Generic MCP Client Configuration

For other MCP-compatible clients:

```json
{
  "server_url": "http://localhost:8080/api/mcp",
  "api_key": "your-api-key-here",
  "capabilities": {
    "tools": {},
    "resources": {},
    "prompts": {}
  },
  "client_info": {
    "name": "Your AI Assistant",
    "version": "1.0.0"
  }
}
```

## Available MCP Tools

### Requirements Management

#### `get_requirements`
Retrieve security requirements with filtering and pagination.

**Parameters**:
- `limit` (optional): Number of results (max 100, default 20)
- `offset` (optional): Starting position (default 0)
- `tags` (optional): Filter by tags
- `status` (optional): Filter by status

**Example**:
```json
{
  "name": "get_requirements",
  "arguments": {
    "limit": 10,
    "tags": ["GDPR", "security"],
    "status": "active"
  }
}
```

#### `search_requirements`
Full-text search across requirements.

**Parameters**:
- `query`: Search terms
- `limit` (optional): Number of results (default 20)
- `include_description` (optional): Include description in search (default true)

#### `create_requirement` (Write Permission Required)
Create a new security requirement.

**Parameters**:
- `title`: Requirement title
- `description`: Detailed description
- `tags` (optional): Array of tags
- `priority` (optional): Priority level (LOW, MEDIUM, HIGH, CRITICAL)

### Risk Assessment Management

#### `get_assessments`
Retrieve risk assessments.

**Parameters**:
- `limit` (optional): Number of results (default 20)
- `offset` (optional): Starting position
- `status` (optional): Filter by assessment status
- `risk_level` (optional): Filter by risk level

#### `search_assessments`
Search risk assessments by content.

**Parameters**:
- `query`: Search terms
- `limit` (optional): Number of results
- `include_findings` (optional): Include findings in search

#### `create_assessment` (Write Permission Required)
Create a new risk assessment.

**Parameters**:
- `title`: Assessment title
- `description`: Assessment description
- `risk_level`: Risk level (LOW, MEDIUM, HIGH, CRITICAL)
- `findings`: Assessment findings

### Utility Tools

#### `get_tags`
Retrieve all available tags and categories.

#### `search_all`
Universal search across all content types.

**Parameters**:
- `query`: Search terms
- `limit` (optional): Results per content type
- `types` (optional): Content types to search ["requirements", "assessments"]

#### `get_system_info` (Admin Permission Required)
Get system status and statistics.

#### `get_user_activity` (Admin Permission Required)
Get user activity logs and statistics.

## Authentication & Security

### API Key Security

- **Storage**: Store API keys securely in environment variables or encrypted configuration
- **Transmission**: All API calls use HTTPS in production
- **Expiration**: Set reasonable expiration dates (30-90 days recommended)
- **Rotation**: Regularly rotate API keys
- **Monitoring**: Monitor API key usage in Secman's audit logs

### Rate Limiting

Default limits:
- **Per API Key**: 1000 requests/hour
- **Per Tool**: Configurable per permission
- **Concurrent Sessions**: 10 per API key

### Session Management

- **Timeout**: Sessions expire after 60 minutes of inactivity
- **Connection Types**: HTTP, Server-Sent Events (SSE), WebSocket
- **Cleanup**: Automatic cleanup of expired sessions

### Audit Logging

All MCP operations are logged with:
- Timestamp and duration
- User and API key information
- Tool called and parameters
- Success/failure status
- Client IP and User-Agent
- Request/response sizes

## Usage Examples

### Claude Desktop Examples

#### "Show me all critical security requirements"
Claude will use the `get_requirements` tool with:
```json
{
  "name": "get_requirements",
  "arguments": {
    "priority": "CRITICAL",
    "limit": 50
  }
}
```

#### "Find risk assessments related to data privacy"
Claude will use the `search_assessments` tool:
```json
{
  "name": "search_assessments",
  "arguments": {
    "query": "data privacy",
    "limit": 20
  }
}
```

#### "Create a new requirement for API security"
Claude will use the `create_requirement` tool:
```json
{
  "name": "create_requirement",
  "arguments": {
    "title": "API Security Authentication",
    "description": "All APIs must implement proper authentication...",
    "tags": ["API", "authentication", "security"],
    "priority": "HIGH"
  }
}
```

### Programmatic Examples

#### Python MCP Client
```python
import requests

# Setup
api_key = "your-api-key-here"
base_url = "http://localhost:8080/api/mcp"

headers = {
    "X-MCP-API-Key": api_key,
    "Content-Type": "application/json"
}

# Create session
session_response = requests.post(f"{base_url}/session",
    headers=headers,
    json={
        "capabilities": {"tools": {}, "resources": {}, "prompts": {}},
        "clientInfo": {"name": "Python Client", "version": "1.0.0"}
    }
)

# Call tool
tool_response = requests.post(f"{base_url}/tools/call",
    headers=headers,
    json={
        "jsonrpc": "2.0",
        "id": "req-1",
        "method": "tools/call",
        "params": {
            "name": "get_requirements",
            "arguments": {"limit": 10, "tags": ["GDPR"]}
        }
    }
)

print(tool_response.json())
```

#### Node.js MCP Client
```javascript
const axios = require('axios');

const client = axios.create({
  baseURL: 'http://localhost:8080/api/mcp',
  headers: {
    'X-MCP-API-Key': process.env.SECMAN_API_KEY,
    'Content-Type': 'application/json'
  }
});

// Create session
const session = await client.post('/session', {
  capabilities: { tools: {}, resources: {}, prompts: {} },
  clientInfo: { name: 'Node.js Client', version: '1.0.0' }
});

// Call tool
const result = await client.post('/tools/call', {
  jsonrpc: '2.0',
  id: 'req-1',
  method: 'tools/call',
  params: {
    name: 'search_all',
    arguments: { query: 'security vulnerability', limit: 20 }
  }
});

console.log(result.data);
```

## Troubleshooting

### Common Issues

#### "Authentication Failed"
- **Check API Key**: Ensure key is correct and hasn't expired
- **Check Headers**: Verify `X-MCP-API-Key` header is set
- **Check Permissions**: Ensure API key has required permissions

#### "Session Not Found"
- **Session Timeout**: Sessions expire after 60 minutes
- **Create New Session**: Call `/api/mcp/session` to create new session
- **Check Session ID**: Ensure session ID is correctly passed

#### "Permission Denied"
- **Check Tool Permissions**: Verify API key has permission for the tool
- **Check Parameter Restrictions**: Some tools have parameter limitations
- **Check Rate Limits**: You may have exceeded rate limits

#### "Tool Not Found"
- **Check Tool Name**: Ensure tool name is spelled correctly
- **Check Capabilities**: Call `/api/mcp/capabilities` to see available tools
- **Check Server Status**: Verify Secman backend is running

### Debug Mode

Enable debug logging:

1. **Backend Logging**: Set `SECMAN_LOG_LEVEL=DEBUG` environment variable
2. **Client Logging**: Enable verbose logging in your MCP client
3. **Network Debugging**: Use tools like `curl` to test API endpoints directly

### Testing API Keys

```bash
# Test authentication
curl -H "X-MCP-API-Key: your-key-here" \
  http://localhost:8080/api/mcp/capabilities

# Test tool call
curl -X POST \
  -H "X-MCP-API-Key: your-key-here" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":"test","method":"tools/call","params":{"name":"get_requirements","arguments":{"limit":1}}}' \
  http://localhost:8080/api/mcp/tools/call
```

## Administration

### Monitoring MCP Usage

#### Web Interface
Navigate to: `Admin → MCP Monitoring`

- **Active Sessions**: View current active MCP sessions
- **API Key Usage**: Monitor API key activity and rate limits
- **Tool Statistics**: See which tools are used most frequently
- **Error Logs**: Review failed requests and errors

#### Key Metrics
- Total API calls per day/hour
- Success/failure rates
- Average response times
- Most active users/keys
- Popular tools and operations

### Managing API Keys

#### Viewing Keys
```bash
curl -H "Authorization: Bearer your-jwt-token" \
  http://localhost:8080/api/mcp/admin/api-keys
```

#### Revoking Keys
```bash
curl -X DELETE \
  -H "Authorization: Bearer your-jwt-token" \
  http://localhost:8080/api/mcp/admin/api-keys/KEY_ID
```

#### Key Statistics
```bash
curl -H "Authorization: Bearer your-jwt-token" \
  http://localhost:8080/api/mcp/admin/statistics
```

### Security Best Practices

1. **Regular Key Rotation**: Rotate API keys every 30-90 days
2. **Minimal Permissions**: Grant only necessary permissions
3. **Monitor Usage**: Regularly review API key usage logs
4. **Set Expiration**: Always set expiration dates for API keys
5. **Network Security**: Use HTTPS in production environments
6. **Rate Limiting**: Configure appropriate rate limits
7. **Audit Reviews**: Regularly review audit logs for suspicious activity

### Backup and Recovery

#### Export MCP Configuration
```bash
curl -H "Authorization: Bearer your-jwt-token" \
  http://localhost:8080/api/mcp/admin/export-config > mcp-config.json
```

#### Database Backup
Ensure your backup strategy includes MCP-related tables:
- `mcp_api_keys`
- `mcp_sessions`
- `mcp_audit_logs`
- `mcp_tool_permissions`

### Performance Tuning

#### Connection Limits
Adjust in `application.yml`:
```yaml
mcp:
  max-concurrent-sessions: 200
  max-sessions-per-key: 10
  session-timeout-minutes: 60
```

#### Rate Limiting
```yaml
mcp:
  rate-limiting:
    default-requests-per-hour: 1000
    burst-limit: 100
```

#### Caching
```yaml
mcp:
  caching:
    enabled: true
    ttl-minutes: 15
    max-cache-size: 10000
```

## Support and Resources

### Documentation
- **API Reference**: `/docs/mcp-api.html`
- **OpenAPI Spec**: `/api/mcp/openapi.json`
- **Tool Documentation**: `/docs/mcp-tools.html`

### Community
- **GitHub Issues**: Report bugs and feature requests
- **Discussions**: Community support and questions
- **Examples**: Sample implementations and integrations

### Professional Support
Contact your system administrator or Secman support team for:
- Custom tool development
- Enterprise integration support
- Performance optimization
- Security auditing

---

*This guide covers Secman MCP Integration v1.0. For the latest updates and features, check the official documentation.*