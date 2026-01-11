# MCP Direct Connection (Streamable HTTP Transport)

This document describes how to connect to secman's MCP server using different clients and transports.

## Overview

Secman supports the MCP Streamable HTTP transport at `/mcp`, allowing connections without the Node.js bridge.

| Client | Method | Requires Node.js |
|--------|--------|------------------|
| **Claude Code** | Direct HTTP | No |
| **Claude Desktop** | Via `mcp-remote` proxy | Yes (npx) |
| **Claude Desktop** | Node.js Bridge | Yes |

## Claude Code Configuration (Recommended)

Claude Code natively supports HTTP transport. Add the server with:

```bash
claude mcp add --transport http secman http://localhost:8080/mcp \
  --header "X-MCP-API-Key: sk-your-api-key-here" \
  --header "X-MCP-User-Email: your.email@company.com"
```

Or for a remote server:

```bash
claude mcp add --transport http secman https://secman.yourcompany.com/mcp \
  --header "X-MCP-API-Key: sk-your-api-key-here" \
  --header "X-MCP-User-Email: your.email@company.com"
```

## Claude Desktop Configuration

Claude Desktop requires stdio-based servers in its config file. Use one of these options:

### Option 1: Using mcp-remote Proxy (Recommended for HTTP)

This uses the `mcp-remote` npm package to proxy HTTP requests:

**macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
**Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

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

### Option 2: Node.js Bridge (Full-featured)

Uses the included Node.js MCP server:

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

## Getting an API Key

1. Log in to secman as an admin user
2. Navigate to **Settings** > **MCP API Keys**
3. Click **Create API Key**
4. Select the required permissions:
   - `REQUIREMENTS_READ` - View requirements
   - `REQUIREMENTS_WRITE` - Create/update requirements
   - `ASSETS_READ` - View assets
   - `SCANS_READ` - View scan results
   - `VULNERABILITIES_READ` - View vulnerabilities
   - `USER_ACTIVITY` - List users (requires delegation)
5. Enable **User Delegation** if you need admin tools
6. Copy the generated API key (starts with `sk-`)

## Available Tools

Once connected, the following tools are available based on your API key permissions:

### Requirements
- `get_requirements` - Retrieve security requirements
- `export_requirements` - Export to Excel or Word
- `add_requirement` - Create new requirements
- `delete_all_requirements` - Delete all requirements (admin)

### Assets
- `get_assets` - List assets with filtering
- `get_all_assets_detail` - Detailed asset information
- `get_asset_profile` - Single asset profile
- `get_asset_complete_profile` - Complete asset details

### Vulnerabilities
- `get_vulnerabilities` - List vulnerabilities
- `get_all_vulnerabilities_detail` - Detailed vulnerability info

### Scans
- `get_scans` - List scan history
- `get_asset_scan_results` - Scan results for assets
- `search_products` - Search discovered products

### Admin (requires User Delegation)
- `list_users` - List all users (admin only)
- `list_products` - List products (admin/secchampion)

## Testing the Connection

You can test the MCP endpoint using curl:

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

# Call a tool
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "X-MCP-API-Key: sk-your-api-key-here" \
  -d '{
    "jsonrpc": "2.0",
    "id": "3",
    "method": "tools/call",
    "params": {
      "name": "get_requirements",
      "arguments": {
        "limit": 10
      }
    }
  }'
```

## Troubleshooting

### "Authentication required" error
- Ensure `X-MCP-API-Key` header is present
- Verify the API key is valid and active
- Check the API key hasn't expired

### "Permission denied" error
- Verify your API key has the required permissions
- For admin tools, ensure User Delegation is enabled and `X-MCP-User-Email` is set
- The delegated user must have the required role (e.g., ADMIN for `list_users`)

### "Origin not allowed" error
- This occurs when making requests from a browser
- Direct HTTP transport is designed for non-browser clients like Claude Desktop
- Localhost origins are always allowed for development

### Connection refused
- Ensure the secman backend is running on the specified URL
- Check firewall settings
- Verify the `/mcp` endpoint is accessible

## Security Considerations

1. **API Key Security**: Store API keys securely. Never commit them to version control.

2. **HTTPS in Production**: Use HTTPS URLs in production environments:
   ```json
   "url": "https://secman.yourcompany.com/mcp"
   ```

3. **User Delegation**: When enabled, the `X-MCP-User-Email` header determines the effective permissions. The final permissions are the intersection of the API key permissions and the delegated user's roles.

4. **Origin Validation**: The server validates Origin headers per MCP specification. Requests without Origin headers (like those from Claude Desktop) are allowed.

## Protocol Reference

The direct HTTP connection implements the MCP Streamable HTTP transport specification:
- Protocol version: `2024-11-05`
- Transport: JSON-RPC 2.0 over HTTP POST
- Endpoint: `/mcp`

For more information, see the [MCP Specification](https://modelcontextprotocol.io/specification/2024-11-05/basic/transports).
