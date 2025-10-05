# Data Model: Workgroup-Based Access Control

**Feature**: 008-create-an-additional
**Date**: 2025-10-04
**Status**: Complete

## Entity Definitions

### Workgroup (NEW)

**Purpose**: Represents an organizational unit or team grouping for access control

**Table**: `workgroup`

**Fields**:
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `id` | Long | PK, Auto-increment | Unique identifier |
| `name` | String(100) | NOT NULL, Unique (case-insensitive) | Workgroup name (e.g., "Engineering Team") |
| `description` | String(512) | NULL | Optional description of workgroup purpose |
| `created_at` | Instant | NOT NULL, Immutable | Timestamp when workgroup was created |
| `updated_at` | Instant | NOT NULL | Timestamp of last modification |

**Validation Rules**:
- Name: 1-100 characters, alphanumeric + spaces + hyphens only (FR-006)
- Name uniqueness: Case-insensitive (e.g., "DevOps" and "devops" conflict)
- Description: Max 512 characters (optional)

**Indexes**:
- Primary key on `id`
- Application-level uniqueness check via `findByNameIgnoreCase()`

**Relationships**:
- `users`: ManyToMany → User (via `user_workgroups` join table)
- `assets`: ManyToMany → Asset (via `asset_workgroups` join table)

**Lifecycle**:
- Created: By ADMIN only via POST /api/workgroups
- Updated: By ADMIN only via PUT /api/workgroups/{id}
- Deleted: By ADMIN only via DELETE /api/workgroups/{id}
  - On delete: All join table entries removed (FR-026)
  - Users and Assets persist with workgroup memberships cleared

---

### User (MODIFIED)

**Purpose**: Extend existing User entity with workgroup membership

**Table**: `users` (existing)

**New Relationship**:
```kotlin
@ManyToMany(fetch = FetchType.EAGER)
@JoinTable(
    name = "user_workgroups",
    joinColumns = [JoinColumn(name = "user_id")],
    inverseJoinColumns = [JoinColumn(name = "workgroup_id")]
)
var workgroups: MutableSet<Workgroup> = mutableSetOf()
```

**Join Table**: `user_workgroups` (auto-created)
| Column | Type | Constraints |
|--------|------|-------------|
| `user_id` | Long | FK → users.id, ON DELETE CASCADE |
| `workgroup_id` | Long | FK → workgroup.id, ON DELETE CASCADE |
| Primary Key: (`user_id`, `workgroup_id`) | | |

**Access Control Logic** (FR-010, FR-018a):
- ADMIN role: Implicit access to ALL workgroups (not stored in join table)
- VULN role: Respects workgroup restrictions (same as USER)
- USER role: Access only to assigned workgroups

**No Other Changes**: Existing User fields unchanged

---

### Asset (MODIFIED)

**Purpose**: Extend existing Asset entity with workgroup membership and dual ownership tracking

**Table**: `asset` (existing)

**New Fields**:
| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| `manual_creator_id` | Long | FK → users.id, NULL | User who manually created asset via UI |
| `scan_uploader_id` | Long | FK → users.id, NULL | User who uploaded scan that discovered asset |

**New Relationship**:
```kotlin
@ManyToMany(fetch = FetchType.EAGER)
@JoinTable(
    name = "asset_workgroups",
    joinColumns = [JoinColumn(name = "asset_id")],
    inverseJoinColumns = [JoinColumn(name = "workgroup_id")]
)
var workgroups: MutableSet<Workgroup> = mutableSetOf()

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "manual_creator_id", nullable = true)
var manualCreator: User? = null

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "scan_uploader_id", nullable = true)
var scanUploader: User? = null
```

**Join Table**: `asset_workgroups` (auto-created)
| Column | Type | Constraints |
|--------|------|-------------|
| `asset_id` | Long | FK → asset.id, ON DELETE CASCADE |
| `workgroup_id` | Long | FK → workgroup.id, ON DELETE CASCADE |
| Primary Key: (`asset_id`, `workgroup_id`) | | |

**Dual Ownership Rules** (FR-020, Clarification 1):
- **Manual Creation**: When user creates asset via UI, set `manual_creator_id`
- **Scan Import**: When scan discovers asset, set `scan_uploader_id` to uploader
- **Merge Scenario**: If scan discovers existing manually-created asset, both FKs populated
- **User Deletion** (FR-027): FKs become NULL, asset persists

**Personal Visibility Rule** (FR-016):
User can see asset if:
```
user.isAdmin() OR
user.id IN asset.workgroups.users.id OR
user.id == asset.manual_creator_id OR
user.id == asset.scan_uploader_id
```

**No Other Changes**: Existing Asset fields (`name`, `type`, `ip`, `owner`, `description`, `groups`, `cloudAccountId`, etc.) unchanged

---

## Relationship Diagram

```
┌─────────────┐              ┌──────────────┐              ┌─────────────┐
│    User     │──────────────│user_workgroups│──────────────│  Workgroup  │
│             │ ManyToMany   │  (join table) │  ManyToMany  │             │
│ - id        │              │  - user_id    │              │ - id        │
│ - username  │              │  - workgroup  │              │ - name      │
│ - roles     │              └───────────────┘              │ - description│
│ - workgroups│                                             │ - users     │
└──────┬──────┘                                             │ - assets    │
       │                                                    └──────┬──────┘
       │ manualCreator (nullable)                                 │
       │ scanUploader (nullable)                                  │
       │                  ┌──────────────┐                        │
       └──────────────────│    Asset     │────────────────────────┘
                          │              │         ManyToMany
                          │ - id         │     ┌──────────────────┐
                          │ - name       │─────│asset_workgroups  │
                          │ - type       │     │  (join table)    │
                          │ - owner      │     │  - asset_id      │
                          │ - manual_creator_id │  - workgroup_id │
                          │ - scan_uploader_id  │                 │
                          │ - workgroups │     └──────────────────┘
                          └──────┬───────┘
                                 │
                                 │ OneToMany
                                 │
                    ┌────────────┴─────────────┐
                    │                          │
              ┌─────▼──────┐          ┌───────▼───────┐
              │Vulnerability│          │  ScanResult   │
              │             │          │               │
              │ - asset_id  │          │ - asset_id    │
              └─────────────┘          └───────────────┘
```

---

## State Transitions

### Workgroup Lifecycle
```
[NULL] ──(ADMIN creates)──> [ACTIVE] ──(ADMIN updates)──> [ACTIVE]
                                      └──(ADMIN deletes)──> [DELETED]
```

### Asset Ownership Lifecycle
```
Manual Creation:
[No owner] ──(User creates)──> [manual_creator_id = User.id, scan_uploader_id = NULL]

Scan Discovery:
[No owner] ──(User uploads scan)──> [manual_creator_id = NULL, scan_uploader_id = User.id]

Merge (scan discovers manual asset):
[manual_creator_id = User1] ──(User2 uploads scan)──> [manual_creator_id = User1, scan_uploader_id = User2]

User Deletion:
[manual_creator_id = User.id] ──(User deleted)──> [manual_creator_id = NULL] (asset persists)
```

---

## Validation Rules Summary

| Entity | Field | Rule | Error Message |
|--------|-------|------|---------------|
| Workgroup | name | 1-100 chars, alphanumeric + space + hyphen | "Name must be 1-100 characters and contain only letters, numbers, spaces, and hyphens" |
| Workgroup | name | Unique (case-insensitive) | "Workgroup name already exists (case-insensitive): {name}" |
| Workgroup | description | Max 512 chars (optional) | "Description must not exceed 512 characters" |
| User | workgroups | No validation (managed by ADMIN) | N/A |
| Asset | workgroups | No validation (managed by ADMIN) | N/A |
| Asset | manual_creator_id | Must reference existing User (or NULL) | "Invalid manual creator user ID" |
| Asset | scan_uploader_id | Must reference existing User (or NULL) | "Invalid scan uploader user ID" |

---

## Database Migration Notes

**Hibernate Auto-Create Will Generate**:
1. New table: `workgroup` (4 columns + timestamps)
2. New join table: `user_workgroups` (2 FK columns, composite PK)
3. New join table: `asset_workgroups` (2 FK columns, composite PK)
4. ALTER TABLE `asset`: Add columns `manual_creator_id`, `scan_uploader_id` (both NULL)
5. Foreign key constraints: `manual_creator_id → users.id`, `scan_uploader_id → users.id`

**Manual Migration Required**: None (Hibernate auto-migration handles all changes)

**Data Migration Strategy**:
- Existing assets: `manual_creator_id` and `scan_uploader_id` default to NULL (acceptable per FR-027)
- Admin must manually assign workgroups post-deployment
- No data loss risk

---

## Query Performance Considerations

**Indexes to Monitor** (auto-created by Hibernate):
- `workgroup.id` (PK)
- `user_workgroups.user_id` (FK)
- `user_workgroups.workgroup_id` (FK)
- `asset_workgroups.asset_id` (FK)
- `asset_workgroups.workgroup_id` (FK)
- `asset.manual_creator_id` (FK)
- `asset.scan_uploader_id` (FK)

**Potential Performance Impact**:
- EAGER fetch of workgroups: Acceptable (small collections, <100 workgroups expected)
- Join queries for filtering: Leverages FK indexes, should perform well
- If >1000 workgroups: Consider LAZY fetch + dedicated fetch queries

**Monitoring Metrics**:
- Average workgroups per user: <10 (target)
- Average workgroups per asset: <5 (target)
- Asset list query time: <100ms p95 (target per constitution)

---

## Backward Compatibility

✅ **No Breaking Changes**:
- All new fields nullable
- Existing API endpoints unchanged
- New endpoints additive only
- Frontend gracefully handles missing workgroup data (shows as "No workgroups")

✅ **Schema Compatibility**:
- Hibernate auto-create adds tables/columns without data loss
- Existing queries unaffected

✅ **Client Compatibility**:
- Old clients: Ignore new workgroup fields (graceful degradation)
- New clients: Display workgroup UI elements
