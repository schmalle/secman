# Secman MCP Server - Quickstart Guide

This guide demonstrates how to set up and test the MCP server functionality for Secman, enabling AI assistants like Claude and ChatGPT to access security requirements and risk assessments.

## Prerequisites

- Secman backend running on `http://localhost:8080`
- Secman frontend running on `http://localhost:4321`
- MariaDB database with existing Secman schema
- Valid user account in Secman with appropriate permissions

## 1. Generate MCP API Key

### Via Web UI (Recommended)

1. Login to Secman at `http://localhost:4321`
2. Navigate to **Settings** → **API Keys**
3. Click **Generate New MCP API Key**
4. Configure the API key:
   ```
   Name: Claude Development Key
   Permissions:
     ☑️ REQUIREMENTS_READ
     ☑️ REQUIREMENTS_WRITE
     ☑️ ASSESSMENTS_READ
     ☑️ FILES_READ
   Expires: 30 days from now
   ```
5. Copy the generated API key (shown only once)
6. Save as `SECMAN_MCP_API_KEY` environment variable

### Via API (Alternative)

```bash
# Get JWT token first
JWT_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"adminuser","password":"password"}' | jq -r '.token')

# Create API key
API_KEY_RESPONSE=$(curl -s -X POST http://localhost:8080/api/mcp/admin/api-keys \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -d '{
    "name": "Claude Development Key",
    "permissions": ["REQUIREMENTS_READ", "REQUIREMENTS_WRITE", "ASSESSMENTS_READ", "FILES_READ"],
    "expiresAt": "2025-10-14T23:59:59Z"
  }')

SECMAN_MCP_API_KEY=$(echo $API_KEY_RESPONSE | jq -r '.apiKey')
echo "API Key: $SECMAN_MCP_API_KEY"
```

## 2. Test MCP Server Capabilities

### Check Server Status

```bash
curl -X GET http://localhost:8080/api/mcp/capabilities \
  -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY"
```

Expected response:
```json
{
  "tools": {
    "listChanged": true
  },
  "resources": {
    "subscribe": true,
    "listChanged": true
  },
  "prompts": {}
}
```

### Initialize MCP Session

```bash
SESSION_RESPONSE=$(curl -s -X POST http://localhost:8080/api/mcp/session \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY" \
  -d '{
    "capabilities": {
      "tools": {},
      "resources": {},
      "prompts": {}
    },
    "clientInfo": {
      "name": "Claude Development Client",
      "version": "1.0.0"
    }
  }')

SESSION_ID=$(echo $SESSION_RESPONSE | jq -r '.sessionId')
echo "Session ID: $SESSION_ID"
```

## 3. Test MCP Tools

### List Available Tools

```bash
curl -X POST http://localhost:8080/api/mcp/tools/list \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY" \
  -d '{
    "jsonrpc": "2.0",
    "id": "1",
    "method": "tools/list",
    "params": {}
  }'
```

### Get Security Requirements

```bash
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY" \
  -d '{
    "jsonrpc": "2.0",
    "id": "2",
    "method": "tools/call",
    "params": {
      "name": "get_requirements",
      "arguments": {
        "limit": 5,
        "status": "ACTIVE"
      }
    }
  }'
```

### Create New Security Requirement

```bash
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY" \
  -d '{
    "jsonrpc": "2.0",
    "id": "3",
    "method": "tools/call",
    "params": {
      "name": "create_requirement",
      "arguments": {
        "title": "MCP Integration Test Requirement",
        "description": "This requirement was created via MCP API to test the integration.",
        "category": "Integration Testing",
        "priority": "MEDIUM",
        "tags": ["mcp", "test", "api"]
      }
    }
  }'
```

### Search All Resources

```bash
curl -X POST http://localhost:8080/api/mcp/tools/call \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY" \
  -d '{
    "jsonrpc": "2.0",
    "id": "4",
    "method": "tools/call",
    "params": {
      "name": "search_all",
      "arguments": {
        "query": "authentication",
        "limit": 10,
        "includeRequirements": true,
        "includeAssessments": true
      }
    }
  }'
```

## 4. Test Real-time Communication (SSE)

### Establish SSE Connection

```bash
# In one terminal, start SSE stream
curl -N -X GET "http://localhost:8080/api/mcp/sse/$SESSION_ID" \
  -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY" \
  -H "Accept: text/event-stream"
```

The connection should show:
```
event: mcp-message
data: {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}

event: mcp-message
data: {"jsonrpc":"2.0","method":"notifications/tools/list_changed","params":{}}
```

## 5. Configure Claude for MCP Access

### Create MCP Configuration

Save this configuration in your Claude MCP settings:

```json
{
  "mcpServers": {
    "secman": {
      "command": "node",
      "args": ["/path/to/secman-mcp-client.js"],
      "env": {
        "SECMAN_MCP_API_KEY": "your-api-key-here",
        "SECMAN_BASE_URL": "http://localhost:8080"
      }
    }
  }
}
```

### Test Claude Integration

In Claude, you should be able to use commands like:

```
Can you show me the latest security requirements in Secman?

Please create a new security requirement for "Multi-factor Authentication"
with priority HIGH and tags "authentication", "security", "access-control".

What risk assessments have been completed for asset ID 5?

Search for requirements related to "data encryption".
```

## 6. Configure ChatGPT Integration

### Custom GPT Instructions

Create a custom GPT with these instructions:

```
You are a Security Requirements Assistant with access to the Secman system
via MCP (Model Context Protocol). You can:

1. Retrieve and search security requirements
2. Create new requirements based on user input
3. Access risk assessments and compliance data
4. Download requirement documentation files
5. Translate requirements to different languages

When users ask about security requirements, use the appropriate MCP tools to
fetch real-time data from the Secman system. Always provide source information
and direct links where available.

Available tools: get_requirements, create_requirement, update_requirement,
get_risk_assessments, execute_risk_assessment, search_all, translate_requirement
```

## 7. Validation Tests

### End-to-End Test Script

```bash
#!/bin/bash
# test-mcp-integration.sh

set -e

echo "Testing Secman MCP Server Integration..."

# Test 1: Server capabilities
echo "1. Testing server capabilities..."
curl -s -X GET http://localhost:8080/api/mcp/capabilities \
  -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY" | jq .

# Test 2: Session creation
echo "2. Creating MCP session..."
SESSION_ID=$(curl -s -X POST http://localhost:8080/api/mcp/session \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY" \
  -d '{"capabilities":{},"clientInfo":{"name":"Test Client"}}' | jq -r '.sessionId')

echo "Session ID: $SESSION_ID"

# Test 3: Tool list
echo "3. Listing available tools..."
curl -s -X POST http://localhost:8080/api/mcp/tools/list \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY" \
  -d '{"jsonrpc":"2.0","id":"1","method":"tools/list"}' | jq '.result.tools | length'

# Test 4: Requirements query
echo "4. Querying requirements..."
REQUIREMENT_COUNT=$(curl -s -X POST http://localhost:8080/api/mcp/tools/call \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY" \
  -d '{"jsonrpc":"2.0","id":"2","method":"tools/call","params":{"name":"get_requirements","arguments":{"limit":1}}}' \
  | jq '.result.content[0].text | fromjson | .requirements | length')

echo "Found $REQUIREMENT_COUNT requirements"

# Test 5: Session cleanup
echo "5. Cleaning up session..."
curl -s -X DELETE "http://localhost:8080/api/mcp/session/$SESSION_ID" \
  -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY"

echo "MCP Integration test completed successfully!"
```

## 8. Performance Validation

### Load Test with Multiple Sessions

```bash
#!/bin/bash
# load-test-mcp.sh

# Test concurrent MCP sessions
for i in {1..10}; do
  (
    SESSION_ID=$(curl -s -X POST http://localhost:8080/api/mcp/session \
      -H "Content-Type: application/json" \
      -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY" \
      -d '{"capabilities":{},"clientInfo":{"name":"LoadTest-'$i'"}}' | jq -r '.sessionId')

    # Make several tool calls
    for j in {1..5}; do
      curl -s -X POST http://localhost:8080/api/mcp/tools/call \
        -H "Content-Type: application/json" \
        -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY" \
        -d '{"jsonrpc":"2.0","id":"'$j'","method":"tools/call","params":{"name":"get_requirements","arguments":{"limit":1}}}' > /dev/null
    done

    # Cleanup
    curl -s -X DELETE "http://localhost:8080/api/mcp/session/$SESSION_ID" \
      -H "X-MCP-API-Key: $SECMAN_MCP_API_KEY"

    echo "Session $i completed"
  ) &
done

wait
echo "Load test completed"
```

## Success Criteria

✅ **Server Capabilities**: Returns valid MCP capability response
✅ **Authentication**: API key authentication works for all endpoints
✅ **Session Management**: Can create, use, and cleanup sessions
✅ **Tool Execution**: All MCP tools respond correctly with expected data
✅ **Real-time Communication**: SSE connection establishes and receives events
✅ **Error Handling**: Invalid requests return proper MCP error responses
✅ **Performance**: Handles 10 concurrent sessions without errors
✅ **Claude Integration**: Can execute tool calls through Claude interface
✅ **Data Consistency**: Tool responses match web UI data
✅ **Audit Logging**: All MCP activities are logged in audit trail

## Troubleshooting

### Common Issues

1. **403 Forbidden**: Check API key permissions and expiration
2. **Session Not Found**: Verify session ID and check for timeout
3. **Tool Not Available**: Ensure user role has required tool permissions
4. **SSE Connection Fails**: Check firewall and proxy settings
5. **Slow Responses**: Monitor database query performance and connection pool

### Debug Logging

Enable debug logging in `application.yml`:
```yaml
logger:
  levels:
    com.secman.mcp: DEBUG
    io.micronaut.security: DEBUG
```

### Health Checks

Monitor MCP server health:
```bash
curl -X GET http://localhost:8080/health
curl -X GET http://localhost:8080/api/mcp/capabilities
```