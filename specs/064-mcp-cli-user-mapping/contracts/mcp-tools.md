# MCP Tool Contracts: User Mapping Management

**Feature**: 064-mcp-cli-user-mapping
**Date**: 2026-01-19

## Tool: import_user_mappings

**Description**: Bulk import user mappings (ADMIN only, requires User Delegation)

**Operation**: WRITE

### Input Schema

```json
{
  "type": "object",
  "properties": {
    "mappings": {
      "type": "array",
      "description": "List of user mapping entries to import",
      "items": {
        "type": "object",
        "properties": {
          "email": {
            "type": "string",
            "description": "User email address (required)"
          },
          "awsAccountId": {
            "type": "string",
            "description": "AWS account ID (12 digits, optional)"
          },
          "domain": {
            "type": "string",
            "description": "AD domain (optional)"
          }
        },
        "required": ["email"]
      },
      "maxItems": 1000
    },
    "dryRun": {
      "type": "boolean",
      "description": "If true, validate without creating mappings",
      "default": false
    }
  },
  "required": ["mappings"]
}
```

### Output Schema (Success)

```json
{
  "totalProcessed": 10,
  "created": 5,
  "createdPending": 2,
  "skipped": 2,
  "errors": [
    {
      "index": 7,
      "email": "invalid-email",
      "message": "Invalid email format"
    }
  ],
  "dryRun": false
}
```

### Error Codes

| Code | Condition |
|------|-----------|
| `DELEGATION_REQUIRED` | User Delegation not enabled |
| `ADMIN_REQUIRED` | Delegated user lacks ADMIN role |
| `VALIDATION_ERROR` | Invalid input (missing mappings array, etc.) |
| `EXECUTION_ERROR` | Database or system error |

### Example Usage

```json
// Request
{
  "tool": "import_user_mappings",
  "arguments": {
    "mappings": [
      {
        "email": "user1@example.com",
        "awsAccountId": "123456789012"
      },
      {
        "email": "user2@example.com",
        "domain": "corp.example.com"
      },
      {
        "email": "user3@example.com",
        "awsAccountId": "123456789012",
        "domain": "corp.example.com"
      }
    ],
    "dryRun": false
  }
}

// Response
{
  "isError": false,
  "content": {
    "totalProcessed": 3,
    "created": 2,
    "createdPending": 1,
    "skipped": 0,
    "errors": [],
    "dryRun": false
  }
}
```

---

## Tool: list_user_mappings

**Description**: List user mappings with pagination and filtering (ADMIN only, requires User Delegation)

**Operation**: READ

### Input Schema

```json
{
  "type": "object",
  "properties": {
    "email": {
      "type": "string",
      "description": "Filter by email address (partial match, optional)"
    },
    "page": {
      "type": "number",
      "description": "Page number (0-indexed)",
      "default": 0,
      "minimum": 0
    },
    "size": {
      "type": "number",
      "description": "Page size",
      "default": 20,
      "minimum": 1,
      "maximum": 100
    }
  }
}
```

### Output Schema (Success)

```json
{
  "mappings": [
    {
      "id": 1,
      "email": "user@example.com",
      "awsAccountId": "123456789012",
      "domain": null,
      "userId": 42,
      "isFutureMapping": false,
      "appliedAt": "2026-01-15T10:30:00Z",
      "createdAt": "2026-01-15T10:00:00Z",
      "updatedAt": "2026-01-15T10:30:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 150,
  "totalPages": 8
}
```

### Error Codes

| Code | Condition |
|------|-----------|
| `DELEGATION_REQUIRED` | User Delegation not enabled |
| `ADMIN_REQUIRED` | Delegated user lacks ADMIN role |
| `EXECUTION_ERROR` | Database or system error |

### Example Usage

```json
// Request - List all mappings
{
  "tool": "list_user_mappings",
  "arguments": {}
}

// Request - Filter by email with pagination
{
  "tool": "list_user_mappings",
  "arguments": {
    "email": "john",
    "page": 0,
    "size": 50
  }
}

// Response
{
  "isError": false,
  "content": {
    "mappings": [
      {
        "id": 5,
        "email": "john.doe@example.com",
        "awsAccountId": "123456789012",
        "domain": "corp.example.com",
        "userId": 10,
        "isFutureMapping": false,
        "appliedAt": "2026-01-10T14:20:00Z",
        "createdAt": "2026-01-10T14:00:00Z",
        "updatedAt": "2026-01-10T14:20:00Z"
      }
    ],
    "page": 0,
    "size": 50,
    "totalElements": 1,
    "totalPages": 1
  }
}
```
