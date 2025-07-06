# Secman MCP Server

This document describes the Model Context Protocol (MCP) server implementation for the Secman application.

## Overview

The Secman MCP server allows AI assistants like Claude to interact with the Secman risk management system through a standardized protocol. It provides access to requirements, assets, risks, and other security management data while maintaining proper authentication and authorization.

## Features

### Resources (Read-only Data)

- **Requirements**: Access to all security requirements
- **Standards**: Compliance standards and frameworks
- **Assets**: Asset inventory and classifications
- **Risks**: Risk definitions and descriptions
- **Risk Assessments**: Risk assessment data for assets
- **Users**: User management (admin only)

### Tools (Executable Operations)

- **search_requirements**: Search requirements by text or criteria
- **create_requirement**: Create new requirements (admin only)
- **search_assets**: Search assets by name or description
- **get_risk_assessment**: Get risk assessments for specific assets
- **generate_compliance_report**: Generate compliance reports for standards

### Prompts (Workflow Templates)

- **create_requirement_with_compliance**: Guide for creating requirements with compliance mapping
- **assess_asset_risk**: Workflow for conducting risk assessments
- **generate_compliance_report**: Template for generating compliance reports
- **review_security_requirements**: Process for reviewing requirement completeness
- **analyze_asset_inventory**: Framework for analyzing asset coverage

## Architecture

### Core Components

1. **MCPServer**: Main server class implementing JSON-RPC 2.0 protocol
2. **ResourceProvider**: Manages access to Secman entities as MCP resources
3. **ToolProvider**: Exposes Secman operations as executable MCP tools
4. **PromptProvider**: Provides workflow templates for common tasks
5. **AuthenticationHandler**: Manages user authentication and authorization
6. **MCPController**: HTTP transport controller for web-based access

### Transport Support

#### STDIO Transport (Claude Desktop)

- Used for direct integration with Claude Desktop app
- Communicates via standard input/output
- Ideal for local development and personal use

#### HTTP Transport (Remote Access)

- RESTful API endpoints for remote MCP clients
- Supports CORS for web-based integrations
- Suitable for production deployments

## Setup and Configuration

### 1. Claude Desktop Integration

#### Install Dependencies

```bash
cd src/backend
sbt compile
```

#### Generate API Key

1. Log into Secman web interface
2. Visit `/mcp/api-key` endpoint to get your API key
3. Note the format: `username:api-key-hash`

#### Configure Claude Desktop

1. Edit `~/Library/Application Support/Claude/claude_desktop_config.json`
2. Add the following configuration:

```json
{
  "mcpServers": {
    "secman": {
      "command": "java",
      "args": [
        "-cp",
        "target/scala-2.13/classes:lib/*",
        "mcp.MCPServerLauncher"
      ],
      "cwd": "/path/to/secman/src/backend",
      "env": {
        "SECMAN_API_KEY": "your-username:your-api-key-here"
      }
    }
  }
}
```

3. Restart Claude Desktop

### 2. HTTP Transport Setup

#### Start Secman Server

```bash
cd src/backend
sbt run
```

#### Test MCP Endpoints

```bash
# Check server health
curl http://localhost:9000/mcp/health

# Check capabilities  
curl http://localhost:9000/mcp/capabilities

# Send MCP request (requires authentication)
curl -X POST http://localhost:9000/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer username:api-key" \
  -d '{"jsonrpc":"2.0","id":"1","method":"ping"}'
```

## Usage Examples

### Basic Resource Access

```json
// List available resources
{
  "jsonrpc": "2.0",
  "id": "1", 
  "method": "resources/list"
}

// Read specific resource
{
  "jsonrpc": "2.0",
  "id": "2",
  "method": "resources/read",
  "params": {
    "uri": "secman://requirements"
  }
}
```

### Tool Execution

```json
// Search requirements
{
  "jsonrpc": "2.0",
  "id": "3",
  "method": "tools/call",
  "params": {
    "name": "search_requirements",
    "arguments": {
      "query": "encryption",
      "standardId": 1
    }
  }
}

// Generate compliance report
{
  "jsonrpc": "2.0", 
  "id": "4",
  "method": "tools/call",
  "params": {
    "name": "generate_compliance_report",
    "arguments": {
      "standardId": 1,
      "format": "detailed"
    }
  }
}
```

### Prompt Usage

```json
// Get workflow prompt
{
  "jsonrpc": "2.0",
  "id": "5", 
  "method": "prompts/get",
  "params": {
    "name": "assess_asset_risk",
    "arguments": {
      "asset_name": "Web Server",
      "risk_type": "security"
    }
  }
}
```

## Authentication

### API Key Format

API keys use the format: `username:hash`

Where:

- `username`: Your Secman username
- `hash`: Generated API key hash

### Authentication Methods

1. **Session-based** (HTTP transport): Use existing web session
2. **Bearer token** (HTTP transport): Include `Authorization: Bearer api-key` header
3. **Environment variable** (STDIO transport): Set `SECMAN_API_KEY=api-key`

### Permission Levels

- **Normal User**: Access to resources and read-only tools
- **Admin User**: Full access including user management and write operations

## Development

### Running Tests

```bash
cd src/backend
sbt test
```

### Adding New Tools

1. Define tool in `ToolProvider.listTools()`
2. Implement handler in `ToolProvider.callTool()`
3. Add tests in `test/mcp/ToolProviderTest.java`

### Adding New Resources

1. Define resource in `ResourceProvider.listResources()`
2. Implement reader in `ResourceProvider.readResource()`
3. Add tests in `test/mcp/ResourceProviderTest.java`

## Troubleshooting

### Common Issues

#### "Authentication required" Error

- Verify API key is correctly set in environment or headers
- Check that API key format is `username:hash`
- Ensure user exists and is active in Secman

#### "Method not found" Error

- Check that method name is spelled correctly
- Verify user has permission to access the method
- Review available methods with `tools/list` or `resources/list`

#### STDIO Connection Issues

- Verify Java classpath includes all dependencies
- Check that working directory is correct
- Review Claude Desktop logs for error details

#### HTTP Transport Issues

- Confirm Secman server is running on correct port
- Check CORS headers for browser-based clients
- Verify Content-Type is set to `application/json`

### Debugging

#### Enable Debug Logging

Set log level in `logback.xml`:

```xml
<logger name="mcp" level="DEBUG"/>
```

#### Monitor Sessions

Check active sessions via health endpoint:

```bash
curl http://localhost:9000/mcp/health
```

#### Test Protocol Compliance

Use the MCP test suite to validate protocol compliance:

```bash
# Example test script
node test-mcp-client.js http://localhost:9000/mcp
```

## Security Considerations

### Best Practices

- Use HTTPS in production for HTTP transport
- Rotate API keys regularly
- Monitor MCP access logs
- Implement rate limiting for HTTP endpoints
- Validate all input parameters

### Production Deployment

- Deploy behind reverse proxy (nginx, Apache)
- Use TLS termination at proxy level
- Implement request logging and monitoring
- Set up health checks and alerting
- Consider API gateway for advanced features

## API Reference

### Endpoints

#### STDIO Transport

- No endpoints - uses standard input/output

#### HTTP Transport

- `POST /mcp` - Main MCP protocol endpoint
- `GET /mcp` - Server-Sent Events endpoint
- `OPTIONS /mcp` - CORS preflight support
- `GET /mcp/health` - Health check
- `GET /mcp/capabilities` - Server capabilities
- `GET /mcp/api-key` - Get API key for authenticated user

### Error Codes

Standard JSON-RPC error codes plus MCP-specific extensions:

- `-32700`: Parse error
- `-32600`: Invalid request
- `-32601`: Method not found
- `-32602`: Invalid params
- `-32603`: Internal error
- `-32000`: Unauthorized (MCP extension)
- `-32001`: Forbidden (MCP extension)
- `-32002`: Resource not found (MCP extension)
- `-32003`: Tool error (MCP extension)

##
