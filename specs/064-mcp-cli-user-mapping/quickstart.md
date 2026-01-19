# Quickstart: MCP and CLI User Mapping Upload

**Feature**: 064-mcp-cli-user-mapping
**Date**: 2026-01-19

## Prerequisites

- Secman backend running
- ADMIN role user account
- For CLI: `SECMAN_ADMIN_EMAIL` environment variable set
- For MCP: API key with User Delegation enabled

## MCP Quick Start

### 1. Import User Mappings

```json
// Call import_user_mappings tool
{
  "tool": "import_user_mappings",
  "arguments": {
    "mappings": [
      {
        "email": "developer@example.com",
        "awsAccountId": "123456789012"
      },
      {
        "email": "developer@example.com",
        "domain": "dev.example.com"
      }
    ],
    "dryRun": false
  }
}
```

### 2. Verify Import

```json
// Call list_user_mappings tool
{
  "tool": "list_user_mappings",
  "arguments": {
    "email": "developer"
  }
}
```

## CLI Quick Start

### 1. Set Environment

```bash
export SECMAN_ADMIN_EMAIL="admin@example.com"
export SECMAN_BASE_URL="http://localhost:8080"
```

### 2. Import from CSV

Create `mappings.csv`:
```csv
email,type,value
developer@example.com,AWS_ACCOUNT,123456789012
developer@example.com,DOMAIN,dev.example.com
manager@example.com,DOMAIN,mgmt.example.com
```

Import:
```bash
# Dry-run first
./bin/secman manage-user-mappings import --file mappings.csv --dry-run

# Actual import
./bin/secman manage-user-mappings import --file mappings.csv
```

### 3. Import from JSON

Create `mappings.json`:
```json
[
  {
    "email": "developer@example.com",
    "domains": ["dev.example.com"],
    "awsAccounts": ["123456789012"]
  },
  {
    "email": "manager@example.com",
    "domains": ["mgmt.example.com"]
  }
]
```

Import:
```bash
./bin/secman manage-user-mappings import --file mappings.json
```

### 4. List Mappings

```bash
# All mappings
./bin/secman manage-user-mappings list

# Filter by email
./bin/secman manage-user-mappings list --email developer@example.com

# JSON output for scripting
./bin/secman manage-user-mappings list --format json
```

## Common Workflows

### Bulk Provisioning via MCP

```python
# Example: Using Claude or MCP client to provision users
mappings = []
for user in new_users:
    mappings.append({
        "email": user["email"],
        "awsAccountId": user.get("aws_account"),
        "domain": user.get("domain")
    })

result = mcp_client.call_tool("import_user_mappings", {
    "mappings": mappings,
    "dryRun": False
})

print(f"Created: {result['created']}, Skipped: {result['skipped']}")
```

### CI/CD Pipeline Integration

```yaml
# .github/workflows/deploy.yml
- name: Import user mappings
  run: |
    export SECMAN_ADMIN_EMAIL="${{ secrets.SECMAN_ADMIN_EMAIL }}"
    ./bin/secman manage-user-mappings import --file ./config/user-mappings.csv
```

### Audit Script

```bash
#!/bin/bash
# Export all mappings for audit
./bin/secman manage-user-mappings list --format json > /var/log/secman/mappings-$(date +%Y%m%d).json
```

## Troubleshooting

### MCP: "DELEGATION_REQUIRED" Error

User Delegation must be enabled for the API key. Check MCP key configuration.

### MCP: "ADMIN_REQUIRED" Error

The delegated user lacks ADMIN role. Verify user roles in Secman UI.

### CLI: "Not authenticated" Error

Ensure `SECMAN_ADMIN_EMAIL` is set to a valid admin user email.

### Import: "Duplicate mapping" Skipped

The exact combination of email + AWS account ID + domain already exists. This is expected behavior; duplicates are skipped.

### Import: "Invalid email format" Error

Email must contain `@` and be between 3-255 characters.

### Import: "Invalid AWS account ID" Error

AWS account ID must be exactly 12 numeric digits (e.g., `123456789012`).
