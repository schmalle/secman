# Quickstart: CLI and MCP Requirements Management

**Feature**: 057-cli-mcp-requirements
**Date**: 2025-12-29

## Prerequisites

- Java 21 JDK installed
- Secman backend running (default: http://localhost:8080)
- Valid user credentials with appropriate roles:
  - Export: ADMIN, REQ, or SECCHAMPION
  - Add: ADMIN, REQ, or SECCHAMPION
  - Delete: ADMIN only

---

## CLI Commands

### Build CLI

```bash
cd /Users/flake/sources/misc/secman
./gradlew :cli:shadowJar
```

### Set Credentials (Recommended)

```bash
export SECMAN_USERNAME=your-username
export SECMAN_PASSWORD=your-password
```

### Export Requirements to Excel

```bash
# Export to current directory with timestamp
secman export-requirements --format xlsx

# Export to specific file
secman export-requirements --format xlsx --output /path/to/requirements.xlsx

# With verbose output
secman export-requirements --format xlsx --verbose
```

### Export Requirements to Word

```bash
# Export to Word document
secman export-requirements --format docx --output security-requirements.docx
```

### Add a Requirement

```bash
# Minimal - just the required field
secman add-requirement --shortreq "All passwords must be at least 12 characters"

# Full - all optional fields
secman add-requirement \
  --shortreq "Multi-factor authentication required for admin access" \
  --chapter "Authentication" \
  --details "All administrative functions must require MFA verification" \
  --motivation "Prevents unauthorized access even if passwords are compromised" \
  --example "TOTP, hardware tokens, or push notifications" \
  --norm "ISO 27001 A.9.4.2" \
  --usecase "Admin Login"
```

### Delete All Requirements

```bash
# Requires ADMIN role and explicit confirmation
secman delete-all-requirements --confirm

# With verbose output to see count
secman delete-all-requirements --confirm --verbose
```

---

## MCP Tools

### Configuration

Add to your MCP client configuration:

```json
{
  "tools": {
    "export_requirements": {
      "permission": "REQUIREMENTS_READ"
    },
    "add_requirement": {
      "permission": "REQUIREMENTS_WRITE"
    },
    "delete_all_requirements": {
      "permission": "REQUIREMENTS_WRITE",
      "requires_admin": true
    }
  }
}
```

### Export via MCP

```json
{
  "tool": "export_requirements",
  "arguments": {
    "format": "xlsx"
  }
}
```

Response contains base64-encoded file:
```json
{
  "content": {
    "data": "UEsDBBQAAAA...",
    "filename": "requirements_export_20251229.xlsx",
    "requirementCount": 150
  }
}
```

### Add Requirement via MCP

```json
{
  "tool": "add_requirement",
  "arguments": {
    "shortreq": "Sensitive data must be encrypted at rest",
    "chapter": "Data Protection",
    "norm": "GDPR Article 32"
  }
}
```

### Delete All via MCP

```json
{
  "tool": "delete_all_requirements",
  "arguments": {
    "confirm": true
  }
}
```

---

## Common Scenarios

### Scenario 1: Monthly Compliance Export

```bash
# Export both formats for compliance documentation
DATE=$(date +%Y%m%d)
secman export-requirements --format xlsx --output "compliance_${DATE}.xlsx"
secman export-requirements --format docx --output "compliance_${DATE}.docx"
```

### Scenario 2: Bulk Import via Script

```bash
#!/bin/bash
# Import requirements from CSV
while IFS=, read -r shortreq chapter norm; do
  secman add-requirement \
    --shortreq "$shortreq" \
    --chapter "$chapter" \
    --norm "$norm"
done < requirements.csv
```

### Scenario 3: Test Environment Reset

```bash
# Clear all requirements in test environment
export SECMAN_USERNAME=admin
export SECMAN_PASSWORD=test-password
secman delete-all-requirements --confirm --backend-url http://test-server:8080
```

---

## Troubleshooting

### Authentication Errors

```
Error: Authentication failed
```

**Solution**: Check username/password. Verify account is active and has appropriate role.

### Permission Denied

```
Error: Insufficient permissions. ADMIN role required.
```

**Solution**: Delete operations require ADMIN role. Use an admin account.

### Network Errors

```
Error: Connection refused
```

**Solution**: Verify backend is running at specified URL. Check `--backend-url` parameter.

### Export File Issues

```
Error: Cannot write to output path
```

**Solution**: Check directory exists and has write permissions. Use absolute path.

---

## Next Steps

1. Run `./gradlew build` to verify implementation
2. Test each command with `--verbose` flag
3. Verify MCP tools appear in tool registry
4. Update CLAUDE.md with new CLI commands
