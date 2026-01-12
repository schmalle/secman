# MCP Tool Contract: cancel_exception_request

**Tool Name**: `cancel_exception_request`
**Operation**: WRITE
**Spec Reference**: FR-022 through FR-024

## Description

Cancel the user's own pending exception request.

## Access Control

- **Requires User Delegation**: Yes
- **Allowed Roles**: Any authenticated user (original requester only)
- **Ownership Check**: Only the user who created the request can cancel it

## Input Schema

```json
{
  "type": "object",
  "required": ["requestId"],
  "properties": {
    "requestId": {
      "type": "number",
      "description": "ID of the exception request to cancel"
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
    "status": "CANCELLED",
    "autoApproved": false,
    "createdAt": "2026-01-10T10:00:00",
    "updatedAt": "2026-01-11T16:30:00"
  },
  "message": "Exception request cancelled successfully"
}
```

### Error Responses

| Code | Message |
|------|---------|
| `DELEGATION_REQUIRED` | "User Delegation must be enabled to use this tool" |
| `NOT_FOUND` | "Exception request with ID {id} not found" |
| `FORBIDDEN` | "Only the original requester can cancel this request" |
| `INVALID_STATE` | "Cannot cancel request in {status} status. Only PENDING requests can be cancelled." |
| `EXECUTION_ERROR` | "Failed to cancel request: {message}" |

## Implementation Notes

- Delegates to `VulnerabilityExceptionRequestService.cancelRequest()`
- Ownership verified by comparing `context.delegatedUserId` with `request.requestedByUser.id`
- Only PENDING status requests can be cancelled
- Auto-approved requests that were later auto-approved can also be cancelled (will delete the exception)
