# MCP Test Client

A Python command-line tool for testing the Secman MCP API, including the user delegation feature (Feature 050).

## Installation

```bash
cd tests/mcpclient
pip install -r requirements.txt
```

## Usage

### Basic Commands

```bash
# Get server capabilities
python mcp_client.py --api-key YOUR_API_KEY capabilities

# Call a tool
python mcp_client.py --api-key YOUR_API_KEY call get_requirements --args '{"limit": 5}'

# Call a tool with specific arguments
python mcp_client.py --api-key YOUR_API_KEY call search_requirements --args '{"query": "authentication"}'
```

### User Delegation Testing

The `--delegate` flag enables user delegation by sending the `X-MCP-User-Email` header:

```bash
# Get capabilities with delegation
python mcp_client.py --api-key YOUR_API_KEY --delegate user@company.com capabilities

# Call a tool with delegation
python mcp_client.py --api-key YOUR_API_KEY --delegate user@company.com call get_tags

# Run full delegation test suite
python mcp_client.py --api-key YOUR_API_KEY --delegate user@company.com test-delegation

# Test invalid delegation scenarios
python mcp_client.py --api-key YOUR_API_KEY test-invalid
```

### Options

| Option | Short | Description |
|--------|-------|-------------|
| `--api-key` | `-k` | MCP API key for authentication (required) |
| `--base-url` | `-u` | Base URL for MCP API (default: `http://localhost:8080/api/mcp`) |
| `--delegate` | `-d` | Email address for user delegation |
| `--verbose` | `-v` | Enable verbose output (shows request/response details) |
| `--no-color` | | Disable colored output |

### Commands

| Command | Description |
|---------|-------------|
| `capabilities` | Get MCP server capabilities and available tools |
| `call <tool>` | Call an MCP tool with optional arguments |
| `test-delegation` | Run delegation test suite (requires `--delegate`) |
| `test-invalid` | Test various invalid delegation scenarios |

## Delegation Feature (050)

When using a delegation-enabled API key with the `--delegate` flag:

1. The client sends the `X-MCP-User-Email` header with the specified email
2. The server validates:
   - API key has delegation enabled
   - Email domain matches allowed domains
   - User exists and is active
3. Effective permissions are computed as the intersection of:
   - User's role-implied permissions
   - API key's granted permissions

### Expected Behavior

| Scenario | Expected Result |
|----------|-----------------|
| Valid delegation | Request succeeds with effective permissions |
| Invalid email format | `DELEGATION_INVALID_EMAIL` error |
| Domain not allowed | `DELEGATION_DOMAIN_REJECTED` error |
| User not found | `DELEGATION_USER_NOT_FOUND` error |
| Key without delegation | Header ignored, uses API key permissions |

## Examples

### Example 1: Check what tools are available to a delegated user

```bash
$ python mcp_client.py -k sk-xxxx -d john@company.com capabilities

============================================================
                    MCP Capabilities
============================================================

Delegation: john@company.com

Success!

Server Info:
  name: Secman MCP Server
  version: 1.0.0
  protocol: mcp/1.0
  delegationActive: True
  delegatedUser: john@company.com

Available Tools (5):
  - get_requirements: Retrieve security requirements with filtering...
  - search_requirements: Full-text search across requirements...
  - get_tags: Retrieve all available tags...
  ...
```

### Example 2: Run delegation test suite

```bash
$ python mcp_client.py -k sk-xxxx -d john@company.com test-delegation

============================================================
                  Delegation Test Suite
============================================================

API Key: sk-xxxx...
Delegate Email: john@company.com

Test 1: Get capabilities with delegation
  ✓ Delegation active in response: PASS
      delegatedUser=john@company.com
  ✓ Capabilities returned: PASS
      12 tools available

Test 2: Call tool with delegation
  ✓ Tool call: PASS
      Tool executed successfully

Test 3: Compare capabilities (delegation vs no delegation)
  ✓ Capabilities comparison: PASS
      Without delegation: 15 tools, With delegation: 12 tools
  ✓ Permission intersection: PASS
      Delegated permissions <= API key permissions

============================================================
All delegation tests passed!
============================================================
```

### Example 3: Verbose mode for debugging

```bash
$ python mcp_client.py -k sk-xxxx -d john@company.com -v capabilities

============================================================
Request: GET http://localhost:8080/api/mcp/capabilities
Headers:
  X-MCP-API-Key: sk-xxxx...
  Content-Type: application/json
  Accept: application/json
  X-MCP-User-Email: john@company.com
============================================================

Response Status: 200
Response Body: {
  "capabilities": { ... },
  "serverInfo": { ... }
}
...
```

## Troubleshooting

### Connection Refused
Make sure the Secman backend is running:
```bash
cd src/backendng && ./gradlew run
```

### Authentication Failed
- Verify your API key is correct and active
- Check the key hasn't expired

### Delegation Errors
- `DELEGATION_NOT_ENABLED`: The API key doesn't have delegation enabled
- `DELEGATION_DOMAIN_REJECTED`: User's email domain isn't in the allowed list
- `DELEGATION_USER_NOT_FOUND`: No user exists with that email
- `DELEGATION_INVALID_EMAIL`: Email format is invalid

## Creating a Delegation-Enabled API Key

```bash
curl -X POST http://localhost:8080/api/mcp/admin/api-keys \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Delegation Test Key",
    "permissions": ["REQUIREMENTS_READ", "ASSESSMENTS_READ", "TAGS_READ"],
    "delegationEnabled": true,
    "allowedDelegationDomains": "@company.com"
  }'
```
