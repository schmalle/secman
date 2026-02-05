# Data Model: MCP E2E Test - User-Asset-Workgroup Workflow

**Feature Branch**: `074-mcp-e2e-test`
**Date**: 2026-02-04

## Entities

This feature primarily uses **existing entities** with no schema changes required. The MCP tools expose existing functionality through a new interface.

### Existing Entities (Reference Only)

#### User
**Location**: `src/backendng/src/main/kotlin/com/secman/domain/User.kt`

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | Long | PK, auto-generated | |
| username | String | Unique, not null | |
| email | String | Unique, not null | |
| password | String | Not null (hashed) | BCrypt encoded |
| roles | Set<String> | Not null | USER, ADMIN, VULN, etc. |
| workgroups | Set<Workgroup> | ManyToMany | Join table: user_workgroups |

#### Asset
**Location**: `src/backendng/src/main/kotlin/com/secman/domain/Asset.kt`

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | Long | PK, auto-generated | |
| name | String | Unique, not null | Hostname/identifier |
| type | String | Not null | SERVER, WORKSTATION, etc. |
| ip | String | Nullable | IP address |
| owner | String | Nullable | Asset owner |
| description | String | Nullable | |
| workgroups | Set<Workgroup> | ManyToMany | Join table: asset_workgroups |
| vulnerabilities | Set<Vulnerability> | OneToMany | Cascade: none (manual delete) |

#### Vulnerability
**Location**: `src/backendng/src/main/kotlin/com/secman/domain/Vulnerability.kt`

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | Long | PK, auto-generated | |
| cve | String | Not null | CVE identifier |
| asset | Asset | ManyToOne, not null | FK to Asset |
| criticality | String | Not null | CRITICAL, HIGH, MEDIUM, LOW |
| detectedAt | LocalDateTime | Not null | When vulnerability was detected |
| status | String | Not null | OPEN, REMEDIATED, etc. |

#### Workgroup
**Location**: `src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt`

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | Long | PK, auto-generated | |
| name | String | Unique, not null | |
| description | String | Nullable | |
| criticality | String | Nullable | Business criticality |
| users | Set<User> | ManyToMany | Join table: user_workgroups |
| assets | Set<Asset> | ManyToMany | Join table: asset_workgroups |

### New Domain Element: McpPermission (Enum Extension)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/McpPermission.kt`

**New Value to Add**:
```kotlin
enum class McpPermission {
    // ... existing values ...
    WORKGROUPS_WRITE,  // NEW: Grants workgroup management via MCP
}
```

**Rationale**: Existing permissions don't cover workgroup management. Adding a dedicated permission maintains granular access control.

## State Transitions

### Test Script Workflow State Machine

```
┌─────────────┐
│   START     │
└──────┬──────┘
       │
       ▼
┌─────────────────────────┐
│  Resolve Credentials    │ (1Password → env vars)
└──────────┬──────────────┘
           │
           ▼
┌─────────────────────────┐
│  Authenticate           │ (POST /api/auth/login → JWT)
└──────────┬──────────────┘
           │
           ▼
┌─────────────────────────┐
│  Create TEST User       │ (add_user MCP tool)
└──────────┬──────────────┘
           │
           ▼
┌─────────────────────────┐
│  Create Asset+Vuln      │ (add_vulnerability MCP tool)
└──────────┬──────────────┘
           │
           ▼
┌─────────────────────────┐
│  Create Workgroup       │ (create_workgroup MCP tool)
└──────────┬──────────────┘
           │
           ▼
┌─────────────────────────┐
│  Assign Asset           │ (assign_assets_to_workgroup)
└──────────┬──────────────┘
           │
           ▼
┌─────────────────────────┐
│  Assign User            │ (assign_users_to_workgroup)
└──────────┬──────────────┘
           │
           ▼
┌─────────────────────────┐
│  Switch User Context    │ (X-MCP-User-Email header)
└──────────┬──────────────┘
           │
           ▼
┌─────────────────────────┐
│  Verify Asset Access    │ (get_assets → expect 1 result)
└──────────┬──────────────┘
           │
           ▼
┌─────────────────────────┐
│  CLEANUP (always)       │
│  - delete_workgroup     │
│  - delete_asset         │
│  - delete_user          │
└──────────┬──────────────┘
           │
           ▼
┌─────────────┐
│    END      │
└─────────────┘
```

## Validation Rules

### MCP Tool Input Validation

#### create_workgroup
| Parameter | Type | Required | Validation |
|-----------|------|----------|------------|
| name | String | Yes | Non-empty, max 255 chars |
| description | String | No | Max 1000 chars |

#### delete_workgroup
| Parameter | Type | Required | Validation |
|-----------|------|----------|------------|
| workgroupId | Long | Yes | Must exist, positive integer |

#### assign_assets_to_workgroup
| Parameter | Type | Required | Validation |
|-----------|------|----------|------------|
| workgroupId | Long | Yes | Must exist |
| assetIds | List<Long> | Yes | Non-empty, all must exist |

#### assign_users_to_workgroup
| Parameter | Type | Required | Validation |
|-----------|------|----------|------------|
| workgroupId | Long | Yes | Must exist |
| userIds | List<Long> | Yes | Non-empty, all must exist |

#### delete_asset
| Parameter | Type | Required | Validation |
|-----------|------|----------|------------|
| assetId | Long | Yes | Must exist, positive integer |

## Relationships Diagram

```
┌─────────────┐       ┌─────────────────────┐       ┌─────────────┐
│    User     │◄─────►│   user_workgroups   │◄─────►│  Workgroup  │
│             │       │   (join table)      │       │             │
└─────────────┘       └─────────────────────┘       └──────┬──────┘
                                                          │
                                                          │
                      ┌─────────────────────┐             │
                      │  asset_workgroups   │◄────────────┘
                      │   (join table)      │
                      └──────────┬──────────┘
                                 │
                                 ▼
                      ┌─────────────────────┐
                      │       Asset         │
                      │                     │
                      └──────────┬──────────┘
                                 │
                                 │ 1:N
                                 ▼
                      ┌─────────────────────┐
                      │   Vulnerability     │
                      │                     │
                      └─────────────────────┘
```

## Access Control Model

```
User accesses Asset IF ANY of:
├── User has ADMIN role (universal)
├── Asset in User's workgroup(s)
├── Asset manually created by User
├── Asset discovered via User's scan upload
├── Asset's cloudAccountId matches User's AWS mappings
└── Asset's adDomain matches User's domain mappings
```

For this E2E test, we verify the **workgroup path**: assigning User to Workgroup containing Asset grants access.
