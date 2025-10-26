# API Contracts: Outdated Assets View

**Feature**: 034-outdated-assets
**Date**: 2025-10-26
**Purpose**: Complete API contract specifications for the Outdated Assets feature

## Overview

This directory contains detailed API contract specifications for all endpoints in the Outdated Assets View feature. Each contract defines request/response formats, authentication requirements, business rules, and acceptance criteria.

## Contracts

### Core Functionality

1. **[01-get-outdated-assets.md](01-get-outdated-assets.md)**
   - **Endpoint**: `GET /api/outdated-assets`
   - **Purpose**: Get paginated, filtered, sorted list of outdated assets
   - **User Story**: US1 (View Outdated Assets), US4 (Filter and Search), US5 (Workgroup Access)
   - **Priority**: P1

2. **[05-get-asset-vulnerabilities.md](05-get-asset-vulnerabilities.md)**
   - **Endpoint**: `GET /api/outdated-assets/{assetId}/vulnerabilities`
   - **Purpose**: Get detailed vulnerability list for a specific asset
   - **User Story**: US2 (View Asset Details and Vulnerabilities)
   - **Priority**: P1

### Refresh Operations

3. **[02-trigger-manual-refresh.md](02-trigger-manual-refresh.md)**
   - **Endpoint**: `POST /api/outdated-assets/refresh`
   - **Purpose**: Trigger manual materialized view refresh
   - **User Story**: US3 (Manual Refresh of Outdated Assets)
   - **Priority**: P2

4. **[03-refresh-progress-sse.md](03-refresh-progress-sse.md)**
   - **Endpoint**: `GET /api/outdated-assets/refresh-progress` (SSE)
   - **Purpose**: Stream real-time progress updates during refresh
   - **User Story**: US3 (Manual Refresh with Progress Indicator)
   - **Priority**: P2

5. **[04-get-refresh-status.md](04-get-refresh-status.md)**
   - **Endpoint**: `GET /api/outdated-assets/refresh-status/{jobId}`
   - **Purpose**: Poll refresh job status (alternative to SSE)
   - **User Story**: US3 (Manual Refresh)
   - **Priority**: P2

## Endpoint Summary Table

| Endpoint | Method | Auth | Roles | Purpose | Priority |
|----------|--------|------|-------|---------|----------|
| `/api/outdated-assets` | GET | Required | ADMIN, VULN | List outdated assets | P1 |
| `/api/outdated-assets/{assetId}/vulnerabilities` | GET | Required | ADMIN, VULN | Get asset vulnerabilities | P1 |
| `/api/outdated-assets/refresh` | POST | Required | ADMIN, VULN | Trigger refresh | P2 |
| `/api/outdated-assets/refresh-progress` | GET (SSE) | Required | ADMIN, VULN | Stream progress | P2 |
| `/api/outdated-assets/refresh-status/{jobId}` | GET | Required | ADMIN, VULN | Poll job status | P2 |

## Authentication & Authorization

All endpoints require:
- **Authentication**: Valid JWT token in Authorization header
- **Roles**: ADMIN or VULN role

### Access Control Rules

**ADMIN Users**:
- See all outdated assets
- Can trigger refresh
- Can view all asset vulnerabilities

**VULN Users**:
- See only outdated assets from assigned workgroups
- Can trigger refresh
- Can view vulnerabilities only for workgroup assets

## Common Response Codes

| Code | Status | Meaning |
|------|--------|---------|
| 200 | OK | Success |
| 202 | Accepted | Async operation started |
| 400 | Bad Request | Invalid parameters |
| 401 | Unauthorized | Missing/invalid JWT |
| 403 | Forbidden | Insufficient permissions |
| 404 | Not Found | Resource not found |
| 409 | Conflict | Duplicate operation |
| 500 | Internal Server Error | Server error |

## Request/Response Patterns

### Pagination (Micronaut Data)

All list endpoints use Micronaut Data's `Page<T>` response format:

```json
{
  "content": [...],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20
  },
  "totalElements": 234,
  "totalPages": 12,
  "last": false,
  "first": true,
  "empty": false
}
```

### Error Format

```json
{
  "message": "Human-readable error message",
  "field": "fieldName",  // Optional
  "violations": [...]    // Optional (validation errors)
}
```

### Date/Time Format

All timestamps use ISO 8601 format:
- `2025-10-26T14:35:00` (LocalDateTime)
- Timezone: Server local time (UTC recommended in production)

## Testing Requirements

Each contract specifies test cases covering:
1. ✅ Successful operation
2. ✅ Authentication failures (401)
3. ✅ Authorization failures (403)
4. ✅ Validation errors (400)
5. ✅ Resource not found (404)
6. ✅ Business rule violations (409, etc.)
7. ✅ Edge cases from spec

## Implementation Checklist

- [ ] Create DTOs for all request/response bodies
- [ ] Implement controllers with `@Secured` annotations
- [ ] Implement service layer with business logic
- [ ] Implement repository queries
- [ ] Write contract tests (all endpoints)
- [ ] Write unit tests (service layer)
- [ ] Write integration tests (E2E)
- [ ] Validate performance (<2s for 10k assets)
- [ ] Test workgroup access control
- [ ] Test SSE connection handling

## Dependencies

### Backend
- Micronaut Security (JWT auth)
- Micronaut Data JPA (pagination, queries)
- Micronaut HTTP (REST endpoints)
- Micronaut Reactor (SSE streaming)

### Frontend
- Axios (HTTP client)
- EventSource API (SSE)
- React (UI components)

## Related Documentation

- [spec.md](../spec.md) - Feature specification
- [data-model.md](../data-model.md) - Entity schemas
- [research.md](../research.md) - Technical research
- [plan.md](../plan.md) - Implementation plan

## Notes

- All endpoints follow existing Secman API patterns
- RBAC logic mirrors existing VulnerabilityController
- SSE pattern mirrors ExceptionBadgeUpdateHandler
- Performance requirements: <2s page load, <30s refresh, <1s filter
