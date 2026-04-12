# Secman MCP Client (Go Example)

A standalone Go client that communicates with the Secman MCP server using JSON-RPC 2.0 over HTTP. No external dependencies required — uses only the Go standard library.

## Setup

```bash
export SECMAN_BASE_URL=http://localhost:8080   # default
export SECMAN_API_KEY=sk-your-api-key          # required
export SECMAN_USER_EMAIL=admin@example.com     # optional, for user delegation
```

## Usage

```bash
cd scripts/mcp

# List all available MCP tools
go run main.go capabilities

# List assets
go run main.go assets
go run main.go assets --name "prod" --type SERVER --page 0 --pageSize 10

# List vulnerabilities
go run main.go vulnerabilities
go run main.go vulnerabilities --severity CRITICAL --minDaysOpen 30

# List requirements
go run main.go requirements
go run main.go requirements --status ACTIVE --priority HIGH

# List users (requires ADMIN delegation)
go run main.go users

# List scans
go run main.go scans --type nmap

# Call any tool with raw JSON arguments
go run main.go call get_asset_profile --args '{"assetId": 42}'
go run main.go call search_products --args '{"service": "ssh"}'
go run main.go call add_requirement --args '{"shortreq": "Enable MFA for all users"}'
```

## Authentication

The client authenticates via the `X-MCP-API-Key` header. API keys are managed through the Secman admin UI or the MCP admin API.

For tools that require specific roles (e.g., `list_users` requires ADMIN), set `SECMAN_USER_EMAIL` to enable user delegation. The delegated user must have the appropriate roles.

## Building

```bash
go build -o secman-mcp-client .
./secman-mcp-client capabilities
```
