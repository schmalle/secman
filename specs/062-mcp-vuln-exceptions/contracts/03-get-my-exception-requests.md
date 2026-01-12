# MCP Tool Contract: get_my_exception_requests

**Tool Name**: `get_my_exception_requests`
**Operation**: READ
**Spec Reference**: FR-011 through FR-013

## Description

Get the current user's own exception requests with optional status filtering and pagination.

## Access Control

- **Requires User Delegation**: Yes
- **Allowed Roles**: Any authenticated user
- **Access Scope**: Only returns requests created by the delegated user

## Input Schema

```json
{
  "type": "object",
  "properties": {
    "status": {
      "type": "string",
      "enum": ["PENDING", "APPROVED", "REJECTED", "EXPIRED", "CANCELLED"],
      "description": "Filter by request status"
    },
    "page": {
      "type": "number",
      "description": "Page number (0-indexed, default: 0)"
    },
    "size": {
      "type": "number",
      "description": "Page size (default: 20, max: 100)"
    }
  }
}
```

## Response Format

### Success Response

```json
{
  "requests": [
    {
      "id": 789,
      "vulnerabilityId": 456,
      "vulnerabilityCve": "CVE-2024-1234",
      "assetName": "server-prod-01",
      "assetIp": "192.168.1.100",
      "requestedByUsername": "user@example.com",
      "scope": "SINGLE_VULNERABILITY",
      "reason": "Compensating controls in place...",
      "expirationDate": "2026-04-11T00:00:00",
      "status": "PENDING",
      "autoApproved": false,
      "reviewedByUsername": null,
      "reviewDate": null,
      "reviewComment": null,
      "createdAt": "2026-01-11T15:30:00",
      "updatedAt": "2026-01-11T15:30:00"
    }
  ],
  "totalElements": 5,
  "totalPages": 1,
  "page": 0,
  "size": 20
}
```

### Empty Response

```json
{
  "requests": [],
  "totalElements": 0,
  "totalPages": 0,
  "page": 0,
  "size": 20
}
```

### Error Responses

| Code | Message |
|------|---------|
| `DELEGATION_REQUIRED` | "User Delegation must be enabled to use this tool" |
| `EXECUTION_ERROR` | "Failed to retrieve exception requests: {message}" |

## Implementation Notes

- Delegates to `VulnerabilityExceptionRequestService.getUserRequests()`
- Uses `context.delegatedUserId` to filter requests
- Sorted by `createdAt` descending (newest first)
