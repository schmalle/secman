# Research: Nested Workgroups

**Feature**: 040-nested-workgroups
**Date**: 2025-11-02
**Purpose**: Document design decisions, best practices, and alternatives considered for implementing hierarchical workgroup structures.

## Research Areas

### 1. Self-Referential Database Design

**Decision**: Use nullable `parent_id` foreign key with self-reference to `workgroup.id`

**Rationale**:
- Industry-standard pattern for hierarchical data (adjacency list model)
- Simple to implement with JPA/Hibernate (`@ManyToOne` self-reference)
- Efficient for shallow hierarchies (5 levels max in our case)
- Supports recursive CTEs in MariaDB for ancestor/descendant queries
- Backward compatible (existing records default to NULL = root-level)

**Alternatives Considered**:
- **Nested Sets (MPTT)**: More complex to maintain; requires updating multiple records for single insert/move operations. Overkill for shallow hierarchies.
- **Closure Table**: Separate table storing all ancestor-descendant pairs. Better for deep hierarchies but adds complexity and storage overhead unnecessary for 5-level limit.
- **Path Enumeration**: Store full path as string (e.g., "/1/5/12"). Faster reads but fragile on moves and harder to maintain referential integrity.

**References**:
- "SQL Antipatterns" by Bill Karwin - Chapter on hierarchical data
- MariaDB documentation on recursive CTEs: https://mariadb.com/kb/en/recursive-common-table-expressions-overview/

---

### 2. Optimistic Locking for Concurrent Modifications

**Decision**: Use JPA `@Version` annotation with Long version field

**Rationale**:
- Standard JPA pattern for optimistic concurrency control
- Prevents lost updates without database-level locks
- Minimal performance overhead (single version column)
- Hibernate automatically handles version increments and conflict detection
- Returns 409 Conflict on version mismatch, allowing UI to prompt user to refresh and retry

**Alternatives Considered**:
- **Pessimistic Locking**: Database row locks during modification. Better data integrity but causes blocking and potential deadlocks in distributed environments.
- **Timestamp-based versioning**: Using `updatedAt` timestamp instead of version number. Less reliable due to clock skew and millisecond precision limits.
- **No concurrency control**: Simplest but risks last-write-wins data loss when two admins modify same workgroup simultaneously.

**Implementation Pattern**:
```kotlin
@Entity
data class Workgroup(
    @Id @GeneratedValue
    val id: Long? = null,

    @Version
    var version: Long = 0,  // Hibernate manages this

    @ManyToOne
    @JoinColumn(name = "parent_id")
    var parent: Workgroup? = null,

    // ... other fields
)
```

**References**:
- Hibernate documentation on optimistic locking: https://docs.jboss.org/hibernate/orm/6.0/userguide/html_single/Hibernate_User_Guide.html#locking-optimistic

---

### 3. Circular Reference Prevention

**Decision**: Validate on move/reparent by checking if target parent is a descendant of the workgroup being moved

**Rationale**:
- Prevents infinite loops in hierarchy traversal
- Simple to implement with recursive descendant query
- Validation happens at service layer before database commit
- Returns clear 400 Bad Request with explanation when circular reference detected

**Validation Algorithm**:
```kotlin
fun isDescendantOf(workgroup: Workgroup, potentialAncestor: Workgroup): Boolean {
    var current = workgroup.parent
    while (current != null) {
        if (current.id == potentialAncestor.id) return true
        current = current.parent
    }
    return false
}

fun canSetParent(workgroup: Workgroup, newParent: Workgroup): Boolean {
    return newParent.id != workgroup.id &&  // Can't be own parent
           !isDescendantOf(newParent, workgroup)  // New parent can't be descendant
}
```

**Alternatives Considered**:
- **Database constraint**: CHECK constraint to prevent self-reference. Insufficient - doesn't catch multi-level cycles (A → B → C → A).
- **Application-level graph traversal**: DFS/BFS on full graph. Overkill for simple validation and requires loading entire hierarchy.

---

### 4. Sibling Name Uniqueness Constraint

**Decision**: Composite unique constraint on `(parent_id, name)` with case-insensitive comparison

**Rationale**:
- Prevents confusing duplicate names among siblings
- Allows same name in different branches (e.g., "Engineering/Frontend" and "Sales/Frontend")
- Database-enforced constraint prevents race conditions
- Natural UX mapping (mirrors file system behavior)

**Database Schema**:
```sql
ALTER TABLE workgroup
ADD CONSTRAINT uk_workgroup_parent_name
UNIQUE (parent_id, name);

-- Note: MariaDB unique constraint treats NULL parent_id values as distinct,
-- allowing multiple root-level workgroups with same name.
-- Additional application-level validation needed for root-level uniqueness.
```

**Additional Application Validation**:
- For root-level workgroups (parent_id = NULL), perform explicit duplicate check in service layer
- Use case-insensitive comparison: `name.lowercase()` before uniqueness check

**Alternatives Considered**:
- **Global uniqueness**: Forces artificial naming like "Engineering-Frontend-Team1". Overly restrictive.
- **Full path uniqueness**: Allows "Dept A > Team X" and "Dept B > Team X" but not "Dept A > Sub1 > Team X" and "Dept A > Sub2 > Team X". More complex validation with unclear UX benefit.
- **No uniqueness**: Maximum flexibility but confusing when multiple siblings share names. Poor UX.

---

### 5. Deletion Strategy: Child Promotion

**Decision**: When deleting parent with children, promote children to grandparent level (or root if parent was root)

**Rationale**:
- Preserves organizational data - no cascade delete of child workgroups
- Maintains referential integrity for assets and users assigned to child workgroups
- Simpler than requiring admin to manually reassign children before deletion
- Clear user expectation: "flatten" the hierarchy at the deleted level

**Implementation**:
```kotlin
@Transactional
fun deleteWorkgroup(id: Long) {
    val workgroup = findById(id) ?: throw NotFoundException()

    // Promote children to grandparent
    workgroup.children.forEach { child ->
        child.parent = workgroup.parent  // May be null for root-level
    }

    repository.delete(workgroup)
}
```

**Edge Cases Handled**:
- Root-level deletion: Children become new root-level workgroups (parent = null)
- Middle-tier deletion: Children move up one level
- Leaf deletion: No children to promote, simple delete

**Alternatives Considered** (per spec clarification):
- **Cascade delete**: Deletes entire subtree. Risk of unintended data loss.
- **Block deletion**: Prevent delete if children exist. Forces admin to manually handle children first - more steps, worse UX.
- **Orphan to root**: Always convert children to root-level. Inconsistent with hierarchy semantics.

---

### 6. Recursive Hierarchy Queries in MariaDB

**Decision**: Use Common Table Expressions (CTEs) with recursion for ancestor/descendant queries

**Rationale**:
- Native MariaDB support (since 10.2)
- Efficient for bounded-depth hierarchies (5 levels)
- Single query replaces multiple round-trips
- Supports both top-down (descendants) and bottom-up (ancestors) traversal

**Example Queries**:

**Find all descendants** (for delete validation, permission inheritance):
```sql
WITH RECURSIVE descendants AS (
    SELECT id, parent_id, name, 1 AS depth
    FROM workgroup
    WHERE id = :workgroupId

    UNION ALL

    SELECT w.id, w.parent_id, w.name, d.depth + 1
    FROM workgroup w
    INNER JOIN descendants d ON w.parent_id = d.id
    WHERE d.depth < 5  -- Safety limit
)
SELECT * FROM descendants;
```

**Find all ancestors** (for breadcrumb navigation):
```sql
WITH RECURSIVE ancestors AS (
    SELECT id, parent_id, name, 1 AS depth
    FROM workgroup
    WHERE id = :workgroupId

    UNION ALL

    SELECT w.id, w.parent_id, w.name, a.depth + 1
    FROM workgroup w
    INNER JOIN ancestors a ON a.parent_id = w.id
    WHERE a.depth < 5  -- Safety limit
)
SELECT * FROM ancestors ORDER BY depth DESC;
```

**Performance Considerations**:
- Index on `parent_id` essential for join performance
- Depth limit prevents runaway recursion
- For 500 workgroups with average branching factor of 3-5, CTE executes in <100ms

**Alternatives Considered**:
- **Application-level recursion**: Multiple database queries in loop. O(depth) round-trips, slower and more complex.
- **Stored procedures**: Better performance but harder to maintain, less portable, bypasses JPA layer.

**References**:
- MariaDB Recursive CTE documentation: https://mariadb.com/kb/en/recursive-common-table-expressions-overview/

---

### 7. Frontend Tree Rendering

**Decision**: React component with lazy-loaded children and local expand/collapse state

**Rationale**:
- Lazy loading prevents loading entire hierarchy upfront (better for 500 workgroup scale)
- Expand/collapse state stored in component state, not backend (better UX, no server round-trips)
- Bootstrap accordion/collapse components provide accessible UI out of the box
- Tree structure mirrors backend hierarchy naturally

**Component Structure**:
```typescript
interface WorkgroupTreeProps {
  workgroup: Workgroup;
  depth: number;
}

function WorkgroupTree({ workgroup, depth }: WorkgroupTreeProps) {
  const [expanded, setExpanded] = useState(false);
  const [children, setChildren] = useState<Workgroup[]>([]);

  const loadChildren = async () => {
    if (!expanded && children.length === 0) {
      const response = await workgroupApi.getChildren(workgroup.id);
      setChildren(response.data);
    }
    setExpanded(!expanded);
  };

  return (
    <div style={{ marginLeft: `${depth * 20}px` }}>
      <button onClick={loadChildren}>
        {expanded ? '▼' : '▶'} {workgroup.name}
      </button>
      {expanded && children.map(child =>
        <WorkgroupTree key={child.id} workgroup={child} depth={depth + 1} />
      )}
    </div>
  );
}
```

**Alternatives Considered**:
- **Full tree fetch upfront**: Simpler but poor performance for large hierarchies. Acceptable for <100 workgroups but doesn't scale to 500.
- **Third-party tree library**: react-complex-tree, react-virtualized-tree. More features but additional dependency and learning curve.
- **Server-side rendering**: Faster initial load but loses interactivity (expand/collapse requires page refresh).

---

### 8. Depth Validation

**Decision**: Calculate depth on-the-fly during create/move operations using parent chain traversal

**Rationale**:
- Always accurate (no denormalized depth field to maintain)
- Simple implementation (count hops to root)
- Acceptable performance for 5-level limit (max 5 iterations)
- Validation fails fast before database write

**Implementation**:
```kotlin
fun calculateDepth(workgroup: Workgroup): Int {
    var depth = 1
    var current = workgroup.parent
    while (current != null && depth < 10) {  // Safety limit > max depth
        depth++
        current = current.parent
    }
    return depth
}

fun validateDepth(parent: Workgroup?, maxDepth: Int = 5): Boolean {
    if (parent == null) return true  // Root level always valid
    return calculateDepth(parent) < maxDepth
}
```

**Alternatives Considered**:
- **Denormalized depth column**: Faster reads but requires maintaining depth on every move operation. Complexity not justified for shallow hierarchies.
- **Path materialization**: Store full path IDs in array column. Faster queries but harder to maintain referential integrity and more storage.

---

### 9. Audit Logging

**Decision**: Minimal logging using existing application logging framework (SLF4J + Logback)

**Rationale**:
- Meets constitutional requirement for "standard audit logging"
- Logs who, what, when for hierarchy changes
- No additional database schema or complexity
- Sufficient for troubleshooting and basic accountability

**Logging Pattern**:
```kotlin
@Transactional
fun createChild(parentId: Long, request: CreateWorkgroupRequest, user: User): Workgroup {
    val parent = findById(parentId) ?: throw NotFoundException()
    val child = Workgroup(name = request.name, parent = parent)

    val saved = repository.save(child)

    logger.info("Workgroup created: id={}, name={}, parent={}, user={}",
        saved.id, saved.name, parent.id, user.username)

    return saved
}
```

**Log Format**:
- **Create**: `Workgroup created: id={id}, name={name}, parent={parentId}, user={username}`
- **Move**: `Workgroup moved: id={id}, oldParent={oldParentId}, newParent={newParentId}, user={username}`
- **Delete**: `Workgroup deleted: id={id}, name={name}, childrenPromoted={count}, user={username}`

**Alternatives Considered** (per spec clarification):
- **Detailed state tracking**: Log old/new parent IDs, before/after snapshots. More forensic capability but marked out-of-scope.
- **Separate audit table**: Dedicated audit_log table with structured data. Better queryability but added complexity not needed for minimal logging requirement.
- **No logging**: Rely on database transaction logs only. Poor visibility for security audits and troubleshooting.

---

## Summary of Key Design Decisions

| Area | Decision | Key Benefit |
|------|----------|-------------|
| Data Model | Self-referential FK with adjacency list | Simple, standard, efficient for shallow trees |
| Concurrency | Optimistic locking with @Version | Prevents lost updates, good UX |
| Circular References | Service-layer validation on move | Prevents infinite loops, clear errors |
| Uniqueness | Composite constraint (parent_id, name) | File-system-like UX, prevents sibling duplicates |
| Deletion | Promote children to grandparent | Preserves data, maintains integrity |
| Hierarchy Queries | Recursive CTEs in MariaDB | Single-query efficiency, native support |
| Frontend | React lazy-loaded tree | Scales to 500 workgroups, responsive UX |
| Depth Validation | On-the-fly calculation | Always accurate, simple maintenance |
| Audit Logging | SLF4J structured logs | Meets requirements, minimal complexity |

---

## Performance Expectations

Based on research and design decisions:

- **Hierarchy operations** (create child, move, delete): <500ms for typical cases (2-3 level depth)
- **Descendant query** (100 workgroups, 5 levels): <200ms with indexed parent_id
- **Ancestor query** (breadcrumb, 5 levels): <50ms (at most 5 rows)
- **Page load** (100 workgroups, lazy tree): <3 seconds including network latency
- **Concurrent modification**: Optimistic lock conflict <1% for typical admin usage patterns

All targets achievable without aggressive optimization given 500 workgroup scale and 5-level depth limit.

---

## Open Questions / Future Enhancements

1. **Bulk move operations**: Currently out-of-scope. If needed, implement transactional batch update with depth re-validation.
2. **Permission inheritance** (User Story 5): Deferred to future iteration. Requires recursive permission checks in service layer.
3. **Import/export**: Out-of-scope. If needed, JSON format with nested structure mirroring hierarchy.
4. **Soft delete**: Not required for MVP. If needed, add `deleted_at` timestamp and filter queries.
5. **Hierarchy visualization**: MVP uses indented list. Future enhancement: graph visualization (D3.js, Cytoscape.js).

---

**Research Complete**: All technical unknowns resolved. Ready for Phase 1 (data model and contracts).
