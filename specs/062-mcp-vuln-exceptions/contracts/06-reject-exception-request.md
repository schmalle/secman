# MCP Tool Contract: reject_exception_request

**Tool Name**: `reject_exception_request`
**Operation**: WRITE
**Spec Reference**: FR-018, FR-019, FR-020

## Description

Reject a pending exception request. Requires a justification comment.

## Access Control

- **Requires User Delegation**: Yes
- **Allowed Roles**: ADMIN, SECCHAMPION
- **Concurrency**: First-approver-wins with optimistic locking

## Input Schema

```json
{
  "type": "object",
  "required": ["requestId", "comment"],
  "properties": {
    "requestId": {
      "type": "number",
      "description": "ID of the exception request to reject"
    },
    "comment": {
      "type": "string",
      "description": "Required rejection reason (10-1024 characters)",
      "minLength": 10,
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
    "status": "REJECTED",
    "autoApproved": false,
    "reviewedByUsername": "secchampion@example.com",
    "reviewDate": "2026-01-11T16:00:00",
    "reviewComment": "Vulnerability too critical to accept. Immediate remediation required.",
    "createdAt": "2026-01-10T10:00:00",
    "updatedAt": "2026-01-11T16:00:00"
  },
  "message": "Exception request rejected"
}
```

### Error Responses

| Code | Message |
|------|---------|
| `DELEGATION_REQUIRED` | "User Delegation must be enabled to use this tool" |
| `APPROVAL_ROLE_REQUIRED` | "ADMIN or SECCHAMPION role required to reject requests" |
| `VALIDATION_ERROR` | "Rejection comment is required" |
| `VALIDATION_ERROR` | "Rejection comment must be at least 10 characters" |
| `NOT_FOUND` | "Exception request with ID {id} not found" |
| `INVALID_STATE` | "Cannot reject request in {status} status. Only PENDING requests can be rejected." |
| `CONCURRENT_MODIFICATION` | "Request was already reviewed by {username} at {timestamp}" |
| `EXECUTION_ERROR` | "Failed to reject request: {message}" |

## Implementation Notes

- Delegates to `VulnerabilityExceptionRequestService.rejectRequest()`
- Comment is REQUIRED (unlike approval where it's optional)
- Minimum 10 characters enforced for meaningful feedback
- No `VulnerabilityException` is created on rejection
