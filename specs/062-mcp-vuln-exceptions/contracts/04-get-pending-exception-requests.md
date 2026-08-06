# MCP Tool Contract: get_pending_exception_requests

**Tool Name**: `get_pending_exception_requests`
**Operation**: READ
**Spec Reference**: FR-014 through FR-016

## Description

Get all pending exception requests awaiting approval. For use by approvers (ADMIN/SECCHAMPION).

## Access Control

- **Requires User Delegation**: Yes
- **Allowed Roles**: ADMIN, SECCHAMPION
- **Access Scope**: All pending requests system-wide

## Input Schema

```json
{
  "type": "object",
  "properties": {
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
      "reason": "Compensating controls in place. Patch scheduled for next maintenance window...",
      "expirationDate": "2026-04-11T00:00:00",
      "status": "PENDING",
      "autoApproved": false,
      "createdAt": "2026-01-10T10:00:00",
      "updatedAt": "2026-01-10T10:00:00"
    }
  ],
  "totalElements": 15,
  "totalPages": 1,
  "page": 0,
  "size": 20,
  "pendingCount": 15
}
```

### Error Responses

| Code | Message |
|------|---------|
| `DELEGATION_REQUIRED` | "User Delegation must be enabled to use this tool" |
| `APPROVAL_ROLE_REQUIRED` | "ADMIN or SECCHAMPION role required to view pending requests" |
| `EXECUTION_ERROR` | "Failed to retrieve pending requests: {message}" |

## Implementation Notes

- Delegates to `VulnerabilityExceptionRequestService.getPendingRequests()`
- Sorted by `createdAt` ascending (oldest first) for fair FIFO processing
- Includes `pendingCount` in response for badge display
