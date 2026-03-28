# Secman MCP Python Examples

Python example clients for the Secman MCP server using the Streamable HTTP transport (`/mcp`).

## Prerequisites

- Python 3.10+
- `requests` library: `pip install requests`
- Running Secman backend with MCP enabled
- A delegation-enabled MCP API key
- A valid Secman user email

## Setup

```bash
pip install requests

export SECMAN_API_KEY="sk-your-api-key"
export SECMAN_USER_EMAIL="admin@company.com"
export SECMAN_BASE_URL="http://localhost:8080"  # optional, this is the default
```

> **Important:** `SECMAN_USER_EMAIL` is mandatory. The server requires the `X-MCP-User-Email` header for all data-accessing endpoints (`tools/list`, `tools/call`). Requests without this header will receive a `DELEGATION_REQUIRED` error.

## Simple Client (`simple_client.py`)

Minimal example showing the initialize → tools/list → tools/call flow:

```bash
python simple_client.py
```

## Full Client Library (`secman_mcp_client.py`)

### As a Library

```python
from secman_mcp_client import SecmanMcpClient

client = SecmanMcpClient(
    api_key="sk-your-key",
    user_email="admin@company.com",
    base_url="http://localhost:8080",
    verbose=True  # print request/response details
)

# Initialize handshake
client.initialize()

# List available tools
tools = client.list_tools()

# Call a tool
result = client.call_tool("get_requirements", {"limit": 10})

# Ping
client.ping()

# Full connectivity test
client.test_connection()
```

### As a CLI

```bash
# List tools
python secman_mcp_client.py --api-key sk-xxx --user-email admin@co.com list-tools

# Call a tool
python secman_mcp_client.py --api-key sk-xxx --user-email admin@co.com call get_requirements --args '{"limit": 5}'

# Ping
python secman_mcp_client.py --api-key sk-xxx --user-email admin@co.com ping

# Test connection
python secman_mcp_client.py --api-key sk-xxx --user-email admin@co.com test

# Verbose mode
python secman_mcp_client.py --api-key sk-xxx --user-email admin@co.com -v list-tools
```

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SECMAN_API_KEY` | Yes | - | MCP API key (starts with `sk-`) |
| `SECMAN_USER_EMAIL` | Yes | - | User email for delegation |
| `SECMAN_BASE_URL` | No | `http://localhost:8080` | Secman server URL |

## Error Handling

| Error Code | Meaning |
|------------|---------|
| `-32001` | Missing `X-MCP-API-Key` header |
| `-32002` | Invalid or expired API key |
| `-32003` | Permission denied (user lacks required role) |
| `-32007` | Missing `X-MCP-User-Email` header (delegation required) |

## Troubleshooting

### `DELEGATION_REQUIRED` error
The `X-MCP-User-Email` header is missing. Ensure `SECMAN_USER_EMAIL` is set.

### `DELEGATION_NOT_ENABLED` error
The API key does not have delegation enabled. Create a new key with `delegationEnabled: true`.

### `DELEGATION_DOMAIN_REJECTED` error
The user's email domain is not in the API key's allowed domains list.

### `DELEGATION_USER_NOT_FOUND` error
No Secman user exists with the specified email address.

### Connection refused
Ensure the Secman backend is running: `cd src/backendng && ./gradlew run`
