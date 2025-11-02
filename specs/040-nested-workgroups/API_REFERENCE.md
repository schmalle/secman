# Nested Workgroups API Reference

## Overview

This document describes the REST API endpoints for the Nested Workgroups feature (Feature 040). These endpoints extend the existing Workgroup API with hierarchical organization capabilities.

**Base URL:** `/api/workgroups`

**Authentication:** All endpoints require JWT authentication. Include the JWT token in the `Authorization` header:
```
Authorization: Bearer <jwt_token>
```

**Authorization Roles:**
- **ADMIN**: Required for creating, moving, and deleting workgroups
- **Authenticated User**: Can view workgroups and hierarchy (read-only operations)

## Table of Contents

1. [Endpoints Summary](#endpoints-summary)
2. [Data Transfer Objects](#data-transfer-objects)
3. [Endpoint Details](#endpoint-details)
4. [Validation Rules](#validation-rules)
5. [Error Responses](#error-responses)
6. [Examples](#examples)

---

## Endpoints Summary

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/workgroups/{id}/children` | ADMIN | Create a child workgroup under a parent |
| GET | `/api/workgroups/{id}/children` | Authenticated | Get direct children of a workgroup |
| GET | `/api/workgroups/root` | Authenticated | Get all root-level workgroups (no parent) |
| GET | `/api/workgroups/{id}/ancestors` | Authenticated | Get breadcrumb path from root to workgroup |
| GET | `/api/workgroups/{id}/descendants` | Authenticated | Get all descendants (recursive children) |
| PUT | `/api/workgroups/{id}/parent` | ADMIN | Move a workgroup to a new parent |
| DELETE | `/api/workgroups/{id}` | ADMIN | Delete workgroup with child promotion |

---

## Data Transfer Objects

### WorkgroupResponse

Represents a workgroup with hierarchy metadata.

```typescript
interface WorkgroupResponse {
  id: number;                      // Unique identifier
  name: string;                    // Workgroup name (unique among siblings)
  description?: string;            // Optional description
  parentId?: number;               // Parent workgroup ID (null for root-level)
  depth: number;                   // Hierarchy depth (1-5)
  childCount: number;              // Number of direct children
  hasChildren: boolean;            // True if childCount > 0
  ancestors: BreadcrumbItem[];     // Ordered list of ancestors (root → parent)
  createdAt: string;               // ISO 8601 timestamp
  updatedAt: string;               // ISO 8601 timestamp
  version: number;                 // Optimistic locking version
}
```

### BreadcrumbItem

Represents an ancestor in the breadcrumb path.

```typescript
interface BreadcrumbItem {
  id: number;        // Workgroup ID
  name: string;      // Workgroup name
}
```

### CreateChildWorkgroupRequest

Request body for creating a child workgroup.

```typescript
interface CreateChildWorkgroupRequest {
  name: string;          // Required: 3-100 characters, unique among siblings
  description?: string;  // Optional: 0-500 characters
}
```

### MoveWorkgroupRequest

Request body for moving a workgroup to a new parent.

```typescript
interface MoveWorkgroupRequest {
  newParentId: number | null;  // Required: New parent ID (null for root level)
}
```

---

## Endpoint Details

### 1. Create Child Workgroup

Create a new workgroup as a child of an existing parent workgroup.

**Endpoint:** `POST /api/workgroups/{id}/children`

**Authorization:** `ADMIN` role required

**Path Parameters:**
- `id` (number, required): Parent workgroup ID

**Request Body:** `CreateChildWorkgroupRequest`

```json
{
  "name": "API Services",
  "description": "Team responsible for backend API development"
}
```

**Response:** `200 OK` with `WorkgroupResponse`

```json
{
  "id": 42,
  "name": "API Services",
  "description": "Team responsible for backend API development",
  "parentId": 15,
  "depth": 3,
  "childCount": 0,
  "hasChildren": false,
  "ancestors": [
    {"id": 1, "name": "Engineering"},
    {"id": 15, "name": "Backend Team"}
  ],
  "createdAt": "2025-11-02T10:30:00Z",
  "updatedAt": "2025-11-02T10:30:00Z",
  "version": 0
}
```

**Validation:**
- Parent workgroup must exist (404 if not found)
- Parent depth must be < 5 (400 if at maximum depth)
- Child name must be unique among siblings (400 if duplicate)
- Name: 3-100 characters (400 if invalid)
- Description: 0-500 characters (400 if invalid)

**Error Responses:**
- `400 Bad Request`: Validation failure (name too short/long, duplicate name, depth limit)
- `401 Unauthorized`: Missing or invalid JWT token
- `403 Forbidden`: User lacks ADMIN role
- `404 Not Found`: Parent workgroup does not exist

---

### 2. Get Workgroup Children

Retrieve all direct children of a workgroup (non-recursive).

**Endpoint:** `GET /api/workgroups/{id}/children`

**Authorization:** Authenticated user

**Path Parameters:**
- `id` (number, required): Parent workgroup ID

**Response:** `200 OK` with `WorkgroupResponse[]`

```json
[
  {
    "id": 42,
    "name": "API Services",
    "description": "Backend API team",
    "parentId": 15,
    "depth": 3,
    "childCount": 0,
    "hasChildren": false,
    "ancestors": [
      {"id": 1, "name": "Engineering"},
      {"id": 15, "name": "Backend Team"}
    ],
    "createdAt": "2025-11-02T10:30:00Z",
    "updatedAt": "2025-11-02T10:30:00Z",
    "version": 0
  },
  {
    "id": 43,
    "name": "Database Team",
    "description": "Database operations",
    "parentId": 15,
    "depth": 3,
    "childCount": 2,
    "hasChildren": true,
    "ancestors": [
      {"id": 1, "name": "Engineering"},
      {"id": 15, "name": "Backend Team"}
    ],
    "createdAt": "2025-11-02T11:00:00Z",
    "updatedAt": "2025-11-02T11:15:00Z",
    "version": 0
  }
]
```

**Notes:**
- Returns empty array `[]` if workgroup has no children
- Children are ordered by name (ascending)

**Error Responses:**
- `401 Unauthorized`: Missing or invalid JWT token
- `404 Not Found`: Parent workgroup does not exist

---

### 3. Get Root Workgroups

Retrieve all workgroups at the root level (no parent).

**Endpoint:** `GET /api/workgroups/root`

**Authorization:** Authenticated user

**Response:** `200 OK` with `WorkgroupResponse[]`

```json
[
  {
    "id": 1,
    "name": "Engineering",
    "description": "Engineering division",
    "parentId": null,
    "depth": 1,
    "childCount": 3,
    "hasChildren": true,
    "ancestors": [],
    "createdAt": "2025-01-15T08:00:00Z",
    "updatedAt": "2025-10-20T14:30:00Z",
    "version": 5
  },
  {
    "id": 2,
    "name": "Operations",
    "description": "Operations division",
    "parentId": null,
    "depth": 1,
    "childCount": 2,
    "hasChildren": true,
    "ancestors": [],
    "createdAt": "2025-01-15T08:05:00Z",
    "updatedAt": "2025-09-10T09:00:00Z",
    "version": 3
  }
]
```

**Notes:**
- Returns empty array `[]` if no root-level workgroups exist
- Root-level workgroups have `parentId: null` and `depth: 1`

**Error Responses:**
- `401 Unauthorized`: Missing or invalid JWT token

---

### 4. Get Workgroup Ancestors

Retrieve the breadcrumb path from root to the specified workgroup.

**Endpoint:** `GET /api/workgroups/{id}/ancestors`

**Authorization:** Authenticated user

**Path Parameters:**
- `id` (number, required): Workgroup ID

**Response:** `200 OK` with `BreadcrumbItem[]`

```json
[
  {"id": 1, "name": "Engineering"},
  {"id": 15, "name": "Backend Team"},
  {"id": 42, "name": "API Services"}
]
```

**Notes:**
- Ordered from root to current workgroup (current is last item)
- Returns array with single item `[{id, name}]` for root-level workgroups
- Used for breadcrumb navigation in UI

**Error Responses:**
- `401 Unauthorized`: Missing or invalid JWT token
- `404 Not Found`: Workgroup does not exist

---

### 5. Get Workgroup Descendants

Retrieve all descendants of a workgroup (recursive children at all levels).

**Endpoint:** `GET /api/workgroups/{id}/descendants`

**Authorization:** Authenticated user

**Path Parameters:**
- `id` (number, required): Workgroup ID

**Response:** `200 OK` with `WorkgroupResponse[]`

```json
[
  {
    "id": 42,
    "name": "API Services",
    "parentId": 15,
    "depth": 3,
    "childCount": 2,
    "hasChildren": true,
    "ancestors": [
      {"id": 1, "name": "Engineering"},
      {"id": 15, "name": "Backend Team"}
    ],
    "createdAt": "2025-11-02T10:30:00Z",
    "updatedAt": "2025-11-02T10:30:00Z",
    "version": 0
  },
  {
    "id": 50,
    "name": "Auth Service",
    "parentId": 42,
    "depth": 4,
    "childCount": 0,
    "hasChildren": false,
    "ancestors": [
      {"id": 1, "name": "Engineering"},
      {"id": 15, "name": "Backend Team"},
      {"id": 42, "name": "API Services"}
    ],
    "createdAt": "2025-11-02T12:00:00Z",
    "updatedAt": "2025-11-02T12:00:00Z",
    "version": 0
  }
]
```

**Notes:**
- Returns all descendants recursively (children, grandchildren, etc.)
- Returns empty array `[]` if workgroup has no descendants
- Includes the workgroup itself in the results
- Uses MariaDB recursive CTE for efficient traversal

**Error Responses:**
- `401 Unauthorized`: Missing or invalid JWT token
- `404 Not Found`: Workgroup does not exist

---

### 6. Move Workgroup

Move a workgroup to a new parent (or root level).

**Endpoint:** `PUT /api/workgroups/{id}/parent`

**Authorization:** `ADMIN` role required

**Path Parameters:**
- `id` (number, required): Workgroup ID to move

**Request Body:** `MoveWorkgroupRequest`

```json
{
  "newParentId": 20
}
```

Or to move to root level:

```json
{
  "newParentId": null
}
```

**Response:** `200 OK` with `WorkgroupResponse`

```json
{
  "id": 42,
  "name": "API Services",
  "description": "Backend API team",
  "parentId": 20,
  "depth": 2,
  "childCount": 2,
  "hasChildren": true,
  "ancestors": [
    {"id": 20, "name": "Product Engineering"}
  ],
  "createdAt": "2025-11-02T10:30:00Z",
  "updatedAt": "2025-11-02T15:45:00Z",
  "version": 1
}
```

**Validation:**
- New parent must exist (if not null) (404 if not found)
- Cannot move to self (400 if id == newParentId)
- Cannot move to own descendant (400 if circular reference)
- Resulting depth must not exceed 5 levels (400 if depth limit exceeded)
- Name must be unique among new siblings (400 if duplicate)
- Children move with the workgroup (entire subtree is relocated)

**Error Responses:**
- `400 Bad Request`: Validation failure (circular reference, depth limit, duplicate name)
- `401 Unauthorized`: Missing or invalid JWT token
- `403 Forbidden`: User lacks ADMIN role
- `404 Not Found`: Workgroup or new parent does not exist

---

### 7. Delete Workgroup (with Child Promotion)

Delete a workgroup and promote its children to the grandparent level.

**Endpoint:** `DELETE /api/workgroups/{id}`

**Authorization:** `ADMIN` role required

**Path Parameters:**
- `id` (number, required): Workgroup ID to delete

**Response:** `204 No Content`

**Behavior:**
- Deletes the specified workgroup
- **Child Promotion:**
  - If workgroup has a parent: Children are promoted to grandparent (become siblings of deleted workgroup)
  - If workgroup is root-level: Children are promoted to root level
- **User Assignments:** All user assignments to the deleted workgroup are removed
- **Asset Assignments:** All asset assignments to the deleted workgroup are removed
- **Children Preserved:** Child workgroups are NOT deleted (only promoted)

**Example:**

**Before deletion:**
```
Engineering (id=1)
└── Backend Team (id=15)
    ├── API Services (id=42)
    └── Database Team (id=43)
```

**Request:** `DELETE /api/workgroups/15`

**After deletion:**
```
Engineering (id=1)
├── API Services (id=42)   ← Promoted
└── Database Team (id=43)  ← Promoted
```

**Error Responses:**
- `401 Unauthorized`: Missing or invalid JWT token
- `403 Forbidden`: User lacks ADMIN role
- `404 Not Found`: Workgroup does not exist

---

## Validation Rules

### Hierarchy Constraints

1. **Maximum Depth:** 5 levels
   - Depth is calculated from root (root = 1)
   - Creating/moving workgroups must not exceed depth 5

2. **Sibling Name Uniqueness:**
   - Workgroup names must be unique among siblings (children of the same parent)
   - Case-insensitive comparison
   - Composite unique constraint: `(parent_id, name)`

3. **Circular Reference Prevention:**
   - A workgroup cannot be its own parent
   - A workgroup cannot be moved to any of its descendants

### Field Constraints

- **Name:**
  - Minimum: 3 characters
  - Maximum: 100 characters
  - Must be unique among siblings

- **Description:**
  - Optional
  - Maximum: 500 characters

---

## Error Responses

All error responses follow a consistent format:

```json
{
  "message": "Error description",
  "status": 400,
  "path": "/api/workgroups/15/children",
  "_embedded": {
    "errors": [
      {
        "message": "Detailed validation error message"
      }
    ]
  }
}
```

### Common Error Codes

| Status Code | Description | Example Scenario |
|-------------|-------------|------------------|
| 400 | Bad Request | Validation failure (name too short, depth limit, circular reference, duplicate name) |
| 401 | Unauthorized | Missing or invalid JWT token |
| 403 | Forbidden | User lacks required role (ADMIN) |
| 404 | Not Found | Workgroup or parent does not exist |
| 409 | Conflict | Optimistic locking failure (concurrent modification) |
| 500 | Internal Server Error | Unexpected server error |

### Validation Error Messages

- `"Workgroup name must be between 3 and 100 characters"`
- `"Description must not exceed 500 characters"`
- `"Cannot create child: parent is at maximum depth (5)"`
- `"A workgroup named 'X' already exists under parent 'Y'"`
- `"Cannot set parent: would create circular reference"`
- `"Workgroup cannot be its own parent"`
- `"Cannot move workgroup: resulting depth would exceed maximum (5)"`
- `"Parent workgroup not found: {id}"`

---

## Examples

### Example 1: Create a Three-Level Hierarchy

**Step 1:** Create root-level workgroup

```http
POST /api/workgroups
Authorization: Bearer <token>
Content-Type: application/json

{
  "name": "Engineering",
  "description": "Engineering Division"
}
```

**Response:** `200 OK`

```json
{
  "id": 1,
  "name": "Engineering",
  "description": "Engineering Division",
  "parentId": null,
  "depth": 1,
  "childCount": 0,
  "hasChildren": false,
  "ancestors": [],
  "createdAt": "2025-11-02T08:00:00Z",
  "updatedAt": "2025-11-02T08:00:00Z",
  "version": 0
}
```

**Step 2:** Create child workgroup under Engineering

```http
POST /api/workgroups/1/children
Authorization: Bearer <token>
Content-Type: application/json

{
  "name": "Backend Team",
  "description": "Backend development team"
}
```

**Response:** `200 OK`

```json
{
  "id": 15,
  "name": "Backend Team",
  "description": "Backend development team",
  "parentId": 1,
  "depth": 2,
  "childCount": 0,
  "hasChildren": false,
  "ancestors": [
    {"id": 1, "name": "Engineering"}
  ],
  "createdAt": "2025-11-02T08:05:00Z",
  "updatedAt": "2025-11-02T08:05:00Z",
  "version": 0
}
```

**Step 3:** Create grandchild workgroup under Backend Team

```http
POST /api/workgroups/15/children
Authorization: Bearer <token>
Content-Type: application/json

{
  "name": "API Services",
  "description": "REST API development"
}
```

**Response:** `200 OK`

```json
{
  "id": 42,
  "name": "API Services",
  "description": "REST API development",
  "parentId": 15,
  "depth": 3,
  "childCount": 0,
  "hasChildren": false,
  "ancestors": [
    {"id": 1, "name": "Engineering"},
    {"id": 15, "name": "Backend Team"}
  ],
  "createdAt": "2025-11-02T08:10:00Z",
  "updatedAt": "2025-11-02T08:10:00Z",
  "version": 0
}
```

**Final Hierarchy:**

```
Engineering (id=1)
└── Backend Team (id=15)
    └── API Services (id=42)
```

---

### Example 2: Move Workgroup to Different Parent

**Initial Hierarchy:**

```
Engineering (id=1)
└── Backend Team (id=15)

Operations (id=2)
└── Security Team (id=30)
```

**Request:** Move "Security Team" from "Operations" to "Engineering"

```http
PUT /api/workgroups/30/parent
Authorization: Bearer <token>
Content-Type: application/json

{
  "newParentId": 1
}
```

**Response:** `200 OK`

```json
{
  "id": 30,
  "name": "Security Team",
  "parentId": 1,
  "depth": 2,
  "childCount": 0,
  "hasChildren": false,
  "ancestors": [
    {"id": 1, "name": "Engineering"}
  ],
  "createdAt": "2025-10-01T10:00:00Z",
  "updatedAt": "2025-11-02T09:00:00Z",
  "version": 1
}
```

**Final Hierarchy:**

```
Engineering (id=1)
├── Backend Team (id=15)
└── Security Team (id=30)

Operations (id=2)
```

---

### Example 3: Delete Workgroup with Child Promotion

**Initial Hierarchy:**

```
Engineering (id=1)
└── Backend Team (id=15)
    ├── API Services (id=42)
    └── Database Team (id=43)
```

**Request:** Delete "Backend Team"

```http
DELETE /api/workgroups/15
Authorization: Bearer <token>
```

**Response:** `204 No Content`

**Final Hierarchy:**

```
Engineering (id=1)
├── API Services (id=42)   ← Promoted to grandparent level
└── Database Team (id=43)  ← Promoted to grandparent level
```

**Verify promotion:** `GET /api/workgroups/1/children`

```json
[
  {
    "id": 42,
    "name": "API Services",
    "parentId": 1,
    "depth": 2,
    "childCount": 0,
    "hasChildren": false,
    "ancestors": [
      {"id": 1, "name": "Engineering"}
    ],
    "createdAt": "2025-11-02T08:10:00Z",
    "updatedAt": "2025-11-02T09:30:00Z",
    "version": 1
  },
  {
    "id": 43,
    "name": "Database Team",
    "parentId": 1,
    "depth": 2,
    "childCount": 0,
    "hasChildren": false,
    "ancestors": [
      {"id": 1, "name": "Engineering"}
    ],
    "createdAt": "2025-11-02T08:15:00Z",
    "updatedAt": "2025-11-02T09:30:00Z",
    "version": 1
  }
]
```

---

## Integration Notes

### Optimistic Locking

All workgroup modifications use optimistic locking via the `version` field:

1. Client fetches workgroup (includes `version`)
2. Client modifies workgroup
3. Client sends update request with original `version`
4. Server validates `version` matches current database value
5. If mismatch: `409 Conflict` error (concurrent modification detected)
6. If match: Update succeeds, `version` incremented

**Handling 409 Conflicts:**
- Refetch the workgroup to get latest version
- Retry the operation with updated data
- Inform user of conflict if manual resolution needed

### Concurrent Operations

The API is designed for concurrent access:

- **Create Child:** Uses database-level unique constraint `(parent_id, name)` to prevent duplicate sibling names
- **Move Workgroup:** Uses optimistic locking to detect concurrent modifications
- **Delete Workgroup:** Transaction-safe child promotion ensures consistency

### Performance Considerations

- **Recursive Queries:** Descendant retrieval uses MariaDB recursive CTEs (efficient for moderate hierarchies)
- **Lazy Loading:** Frontend should load children on-demand (use `/workgroups/{id}/children` per node)
- **Batch Operations:** For bulk hierarchy changes, consider wrapping in database transactions

---

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2025-11-02 | Initial release of nested workgroups API |

---

**Feature:** 040-nested-workgroups
**API Version:** 1.0
**Last Updated:** 2025-11-02
**Maintainer:** SecMan Backend Team
