# How to Use Secman with Claude Desktop

This guide explains how to integrate Secman's security requirement management system with Claude Desktop using the Model Context Protocol (MCP).

## Prerequisites

1. **Running Secman Backend**: Ensure the Secman backend is running on `http://localhost:8080`
2. **Claude Desktop App**: Install Claude Desktop from Anthropic
3. **Admin Access**: You need admin credentials to create MCP API keys

## Step 1: Start the Secman Backend

```bash
cd src/backendng
gradle run
```

The backend should start and be accessible at `http://localhost:8080`. You can verify it's running by checking:

```bash
curl http://localhost:8080/health
```

Expected response:
```json
{"status":"UP","service":"secman-backend-ng","version":"0.1"}
```

## Step 2: Create an MCP API Key

You need to create an API key specifically for Claude Desktop integration.

### Login and Create API Key

1. **Get JWT Token**:
```bash
curl -X POST "http://localhost:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username": "adminuser", "password": "password"}'
```

2. **Create MCP API Key**:
```bash
curl -X POST "http://localhost:8080/api/mcp/admin/api-keys" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -d '{
    "name": "claude-desktop-integration",
    "permissions": ["REQUIREMENTS_READ", "ASSESSMENTS_READ", "TAGS_READ"],
    "notes": "API key for Claude Desktop MCP integration"
  }'
```

**Save the returned `apiKey` value** - you'll need it for Claude Desktop configuration.

Example response:
```json
{
  "keyId": "abc123",
  "apiKey": "sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "name": "claude-desktop-integration",
  "permissions": ["REQUIREMENTS_READ", "ASSESSMENTS_READ", "TAGS_READ"],
  "createdAt": "2025-09-17T21:57:31.804266"
}
```

## Step 3: Quick Setup (Recommended)

Run the automated setup script:

```bash
cd /path/to/secman
./setup-mcp.sh
```

This script will:
- Check Node.js installation
- Install MCP dependencies
- Test the MCP server
- Show you the exact Claude Desktop configuration

**OR** Manual Installation:

```bash
cd /path/to/secman
npm install
chmod +x mcp-server.js
```

## Step 4: Configure Claude Desktop

Create or edit your Claude Desktop configuration file:

**Location:**
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

**Configuration:**
```json
{
  "mcpServers": {
    "secman": {
      "command": "node",
      "args": ["/Users/flake/sources/misc/secman/mcp-server.js"],
      "env": {}
    }
  }
}
```

**Replace the path** `/Users/flake/sources/misc/secman/mcp-server.js` with the actual path to your Secman installation.

**Note**: The API key is configured inside the `mcp-server.js` file. The current working key is: `sk-mv5Nioy54KJO4tw1JQYDGQMSTadbFakyLlE1UmrkzNCSYV2M`

## Step 4: Alternative Python MCP Server Configuration

For a more robust integration, you can create a Python MCP server that acts as a bridge:

### Option A: Using the provided Python script

1. **Install dependencies**:
```bash
pip install requests
```

2. **Update the Python script** (`src/misc/mcp.py`) with your API key:
```python
headers = {
    "X-MCP-API-Key": "YOUR_API_KEY_HERE",
    "Content-Type": "application/json"
}
```

3. **Configure Claude Desktop** to use the Python script:
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

## Step 5: Test the MCP Server

Before configuring Claude Desktop, test that the MCP server works:

```bash
cd /path/to/secman
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0.0"}}}' | node mcp-server.js
```

You should see a JSON response indicating successful initialization.

## Step 6: Available MCP Tools

Once configured, Claude Desktop will have access to these Secman tools:

### 1. Get Requirements
```json
{
  "name": "get_requirements",
  "description": "Retrieve security requirements from Secman",
  "parameters": {
    "limit": 10,
    "tags": ["GDPR", "ISO27001"],
    "priority": "HIGH"
  }
}
```

### 2. Search Requirements
```json
{
  "name": "search_requirements",
  "description": "Search through security requirements",
  "parameters": {
    "query": "authentication",
    "category": "access_control"
  }
}
```

### 3. Get Tags
```json
{
  "name": "get_tags",
  "description": "Retrieve available requirement tags"
}
```

## Step 7: Test the Integration

1. **Restart Claude Desktop** after updating the configuration
2. **Create a new conversation**
3. **Test with a prompt like**:
   ```
   "Show me the security requirements from Secman"
   ```
   or
   ```
   "Get the top 5 security requirements"
   ```

Claude should now be able to:
- Retrieve security requirements from your Secman database
- Search through requirements by keywords
- Access requirement metadata and tags
- Provide contextualized security guidance based on your data

## Troubleshooting

### Common Issues

1. **MCP Server fails to start**:
   - Ensure Node.js is installed (version 18 or higher)
   - Run `npm install` in the project directory
   - Check that the path in Claude Desktop config is correct
   - Verify the `mcp-server.js` file is executable: `chmod +x mcp-server.js`

2. **"npm error 404" for @modelcontextprotocol packages**:
   - This error from your screenshot indicates the MCP dependencies couldn't be found
   - Run `npm install` to install the proper MCP SDK
   - Ensure you're using the Node.js MCP server we created

3. **"Invalid API key" errors**:
   - The API key is hardcoded in `mcp-server.js`, update it if needed
   - Check that the backend is running on localhost:8080
   - Ensure the API key hasn't expired

4. **Connection errors**:
   - Verify backend is accessible at `http://localhost:8080`
   - Check firewall settings
   - Ensure no port conflicts
   - Test the backend directly: `curl http://localhost:8080/health`

5. **Permission denied errors**:
   - Verify the API key has the required permissions
   - Check that you're using an admin account to create keys

### Verification Commands

**Test API key works**:
```bash
curl -X GET "http://localhost:8080/api/mcp/capabilities" \
  -H "X-MCP-API-Key: YOUR_API_KEY"
```

**Test tool execution**:
```bash
curl -X POST "http://localhost:8080/api/mcp/tools/call" \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: YOUR_API_KEY" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-1",
    "method": "tools/call",
    "params": {
      "name": "get_requirements",
      "arguments": {"limit": 5}
    }
  }'
```

## Security Considerations

1. **API Key Security**: Store API keys securely and rotate them regularly
2. **Network Security**: Consider using HTTPS in production
3. **Permission Scoping**: Only grant the minimum required permissions
4. **Audit Logging**: Monitor API key usage through Secman's audit logs

## Production Deployment

For production use:

1. **Use HTTPS**: Configure TLS termination
2. **API Gateway**: Consider using an API gateway for rate limiting
3. **Authentication**: Implement more robust authentication mechanisms
4. **Monitoring**: Set up monitoring and alerting for MCP usage

## Support

If you encounter issues:

1. Check the Secman backend logs
2. Verify Claude Desktop logs in the application data directory
3. Test API endpoints directly with curl
4. Review the MCP specification at [modelcontextprotocol.io](https://modelcontextprotocol.io)