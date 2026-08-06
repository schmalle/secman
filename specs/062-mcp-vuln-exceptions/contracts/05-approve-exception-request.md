# MCP Tool Contract: approve_exception_request

**Tool Name**: `approve_exception_request`
**Operation**: WRITE
**Spec Reference**: FR-017, FR-020, FR-021

## Description

Approve a pending exception request. Creates a corresponding VulnerabilityException on success.

## Access Control

- **Requires User Delegation**: Yes
- **Allowed Roles**: ADMIN, SECCHAMPION
- **Concurrency**: First-approver-wins with optimistic locking

## Input Schema

```json
{
  "type": "object",
  "required": ["requestId"],
  "properties": {
    "requestId": {
      "type": "number",
      "description": "ID of the exception request to approve"
    },
    "comment": {
      "type": "string",
      "description": "Optional approval comment (max 1024 characters)",
      "maxLength": 1024
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
    "reason": "Compensating controls in place...",
    "expirationDate": "2026-04-11T00:00:00",
    "status": "APPROVED",
    "autoApproved": false,
    "reviewedByUsername": "admin@example.com",
    "reviewDate": "2026-01-11T16:00:00",
    "reviewComment": "Approved - controls verified",
    "createdAt": "2026-01-10T10:00:00",
    "updatedAt": "2026-01-11T16:00:00"
  },
  "message": "Exception request approved successfully"
}
```

### Error Responses

| Code | Message |
|------|---------|
| `DELEGATION_REQUIRED` | "User Delegation must be enabled to use this tool" |
| `APPROVAL_ROLE_REQUIRED` | "ADMIN or SECCHAMPION role required to approve requests" |
| `NOT_FOUND` | "Exception request with ID {id} not found" |
| `INVALID_STATE` | "Cannot approve request in {status} status. Only PENDING requests can be approved." |
| `CONCURRENT_MODIFICATION` | "Request was already reviewed by {username} at {timestamp}" |
| `EXECUTION_ERROR` | "Failed to approve request: {message}" |

## Implementation Notes

- Delegates to `VulnerabilityExceptionRequestService.approveRequest()`
- Automatically creates `VulnerabilityException` on approval
- Uses optimistic locking to prevent race conditions
- Reviewer is the delegated user from context
