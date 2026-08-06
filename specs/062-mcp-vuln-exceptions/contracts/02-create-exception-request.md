# MCP Tool Contract: create_exception_request

**Tool Name**: `create_exception_request`
**Operation**: WRITE
**Spec Reference**: FR-006 through FR-010

## Description

Create a new vulnerability exception request. Auto-approves for ADMIN/SECCHAMPION users.

## Access Control

- **Requires User Delegation**: Yes
- **Allowed Roles**: Any authenticated user
- **Auto-Approval**: ADMIN and SECCHAMPION roles get immediate approval

## Input Schema

```json
{
  "type": "object",
  "required": ["vulnerabilityId", "reason", "expirationDate"],
  "properties": {
    "vulnerabilityId": {
      "type": "number",
      "description": "ID of the vulnerability to request exception for"
    },
    "reason": {
      "type": "string",
      "description": "Business justification (50-2048 characters)",
      "minLength": 50,
      "maxLength": 2048
    },
    "expirationDate": {
      "type": "string",
      "format": "date-time",
      "description": "When the exception should expire (ISO-8601, must be future date)"
    },
    "scope": {
      "type": "string",
      "enum": ["SINGLE_VULNERABILITY", "CVE_PATTERN"],
      "default": "SINGLE_VULNERABILITY",
      "description": "Exception scope (default: SINGLE_VULNERABILITY)"
    }
  }
}
```

## Response Format

### Success Response

```json
{
  "request": {
    "id": 789,
    "vulnerabilityId": 456,
    "vulnerabilityCve": "CVE-2024-1234",
    "assetName": "server-prod-01",
    "assetIp": "192.168.1.100",
    "requestedByUsername": "user@example.com",
    "scope": "SINGLE_VULNERABILITY",
    "reason": "Compensating controls in place. Patch scheduled for next maintenance window...",
    "expirationDate": "2026-04-11T00:00:00",
    "status": "PENDING",
    "autoApproved": false,
    "createdAt": "2026-01-11T15:30:00",
    "updatedAt": "2026-01-11T15:30:00"
  },
  "message": "Exception request created successfully. Status: PENDING"
}
```

### Auto-Approved Response

```json
{
  "request": {
    "id": 790,
    "status": "APPROVED",
    "autoApproved": true,
    "reviewedByUsername": "admin@example.com",
    "reviewDate": "2026-01-11T15:30:00"
  },
  "message": "Exception request auto-approved (ADMIN/SECCHAMPION role)"
}
```

### Error Responses

| Code | Message |
|------|---------|
| `DELEGATION_REQUIRED` | "User Delegation must be enabled to use this tool" |
| `NOT_FOUND` | "Vulnerability with ID {id} not found" |
| `VALIDATION_ERROR` | "Reason must be at least 50 characters" |
| `VALIDATION_ERROR` | "Expiration date must be in the future" |
| `CONFLICT` | "Active exception request already exists for this vulnerability" |
| `EXECUTION_ERROR` | "Failed to create exception request: {message}" |

## Implementation Notes

- Delegates to `VulnerabilityExceptionRequestService.createRequest()`
- Parses `expirationDate` as ISO-8601 datetime
- Default scope is `SINGLE_VULNERABILITY` per clarification session
- Auto-approval determined by user roles in context
