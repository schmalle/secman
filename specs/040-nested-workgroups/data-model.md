# Data Model: Nested Workgroups

**Feature**: 040-nested-workgroups
**Date**: 2025-11-02
**Purpose**: Define entity changes, database schema, and validation rules for hierarchical workgroup implementation.

## Entity Changes

### Workgroup Entity (Modified)

**Location**: `src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt`

**Existing Fields** (unchanged):
- `id: Long` - Primary key
- `name: String` - Workgroup name
- `description: String?` - Optional description
- `users: MutableSet<User>` - Many-to-many relationship with users
- `assets: MutableSet<Asset>` - Many-to-many relationship with assets
- `createdAt: LocalDateTime` - Creation timestamp
- `updatedAt: LocalDateTime` - Last modification timestamp

**New Fields**:
- `parent: Workgroup?` - Self-referential many-to-one relationship to parent workgroup (nullable for root-level)
- `children: MutableSet<Workgroup>` - One-to-many relationship to child workgroups (inverse of parent)
- `version: Long` - Optimistic locking version field (default 0)

**JPA Annotations**:
```kotlin
@Entity
@Table(
    name = "workgroup",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["parent_id", "name"])
    ],
    indexes = [
        Index(name = "idx_workgroup_parent", columnList = "parent_id")
    ]
)
data class Workgroup(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 255)
    var name: String,

    @Column(length = 1000)
    var description: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    var parent: Workgroup? = null,

    @OneToMany(mappedBy = "parent", cascade = [CascadeType.PERSIST, CascadeType.MERGE])
    val children: MutableSet<Workgroup> = mutableSetOf(),

    @Version
    var version: Long = 0,

    @ManyToMany(mappedBy = "workgroups")
    val users: MutableSet<User> = mutableSetOf(),

    @ManyToMany(mappedBy = "workgroups")
    val assets: MutableSet<Asset> = mutableSetOf(),

    @Column(nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    @PreUpdate
    fun onUpdate() {
        updatedAt = LocalDateTime.now()
    }

    /**
     * Calculate the depth of this workgroup in the hierarchy.
     * Root-level workgroups have depth 1.
     */
    fun calculateDepth(): Int {
        var depth = 1
        var current = this.parent
        while (current != null) {
            depth++
            current = current.parent
            if (depth > 10) break  // Safety limit to prevent infinite loops
        }
        return depth
    }

    /**
     * Get all ancestors from root to immediate parent.
     * Returns empty list for root-level workgroups.
     */
    fun getAncestors(): List<Workgroup> {
        val ancestors = mutableListOf<Workgroup>()
        var current = this.parent
        while (current != null) {
            ancestors.add(0, current)  // Prepend to maintain root-to-parent order
            current = current.parent
            if (ancestors.size > 10) break  // Safety limit
        }
        return ancestors
    }

    /**
     * Check if this workgroup is a descendant of the given workgroup.
     */
    fun isDescendantOf(potentialAncestor: Workgroup): Boolean {
        var current = this.parent
        while (current != null) {
            if (current.id == potentialAncestor.id) return true
            current = current.parent
        }
        return false
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Workgroup) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int = id?.hashCode() ?: 0

    override fun toString(): String = "Workgroup(id=$id, name='$name', parentId=${parent?.id})"
}
```

**Validation Rules**:
1. `name` cannot be null or blank
2. `name` length: 1-255 characters
3. `description` length: max 1000 characters (nullable)
4. `parent` can be null (root-level workgroup)
5. Sibling uniqueness: `name` must be unique among children of the same parent
6. Depth limit: depth cannot exceed 5 levels
7. No circular references: parent cannot be a descendant of the child

---

## Database Schema Changes

### Table: `workgroup` (Modified)

**New Columns**:
```sql
ALTER TABLE workgroup
ADD COLUMN parent_id BIGINT NULL,
ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Foreign key constraint
ALTER TABLE workgroup
ADD CONSTRAINT fk_workgroup_parent
FOREIGN KEY (parent_id) REFERENCES workgroup(id)
ON DELETE RESTRICT;  -- Prevent accidental deletion of parents with children

-- Index for hierarchy queries
CREATE INDEX idx_workgroup_parent ON workgroup(parent_id);

-- Unique constraint for sibling name uniqueness
ALTER TABLE workgroup
ADD CONSTRAINT uk_workgroup_parent_name UNIQUE (parent_id, name);
```

**Schema Notes**:
- `parent_id` is nullable: NULL indicates root-level workgroup
- `ON DELETE RESTRICT`: Database enforces that parents with children cannot be deleted (application handles promotion logic)
- `version` column: Auto-incremented by Hibernate for optimistic locking
- Unique constraint on `(parent_id, name)`: Prevents sibling duplicates
  - MariaDB treats NULL `parent_id` values as distinct, allowing multiple root-level workgroups with same name
  - Application-level validation needed for root-level uniqueness

**Migration Safety**:
- Non-destructive: adds nullable columns
- Existing workgroups: `parent_id` defaults to NULL (become root-level)
- `version` defaults to 0 for all existing records
- No data loss or manual migration required

---

## Validation Service

### WorkgroupValidationService (New)

**Location**: `src/backendng/src/main/kotlin/com/secman/service/WorkgroupValidationService.kt`

**Purpose**: Centralized validation logic for hierarchy operations

**Methods**:

```kotlin
@Singleton
class WorkgroupValidationService(
    private val workgroupRepository: WorkgroupRepository
) {
    private val logger = LoggerFactory.getLogger(WorkgroupValidationService::class.java)
    private val maxDepth = 5

    /**
     * Validate that creating a child under this parent won't exceed depth limit.
     * @throws ValidationException if depth would exceed limit
     */
    fun validateDepthLimit(parent: Workgroup?) {
        if (parent == null) return  // Root level always valid

        val parentDepth = parent.calculateDepth()
        if (parentDepth >= maxDepth) {
            throw ValidationException(
                "Cannot create child: parent is at maximum depth ($maxDepth)"
            )
        }
    }

    /**
     * Validate that setting this parent won't create a circular reference.
     * @throws ValidationException if circular reference detected
     */
    fun validateNoCircularReference(workgroup: Workgroup, newParent: Workgroup) {
        // Can't be own parent
        if (workgroup.id == newParent.id) {
            throw ValidationException("Workgroup cannot be its own parent")
        }

        // New parent can't be a descendant
        if (newParent.isDescendantOf(workgroup)) {
            throw ValidationException(
                "Cannot set parent: would create circular reference"
            )
        }
    }

    /**
     * Validate that the name is unique among siblings.
     * @throws ValidationException if sibling with same name exists
     */
    fun validateSiblingUniqueness(name: String, parent: Workgroup?, excludeId: Long? = null) {
        val siblings = if (parent != null) {
            workgroupRepository.findByParent(parent)
        } else {
            workgroupRepository.findRootLevelWorkgroups()
        }

        val duplicate = siblings.firstOrNull {
            it.name.equals(name, ignoreCase = true) && it.id != excludeId
        }

        if (duplicate != null) {
            val parentName = parent?.name ?: "root level"
            throw ValidationException(
                "A workgroup named '$name' already exists under $parentName"
            )
        }
    }

    /**
     * Validate that a workgroup can be moved to a new parent.
     * Checks depth, circular references, and name uniqueness.
     */
    fun validateMove(workgroup: Workgroup, newParent: Workgroup?) {
        // Check circular references
        if (newParent != null) {
            validateNoCircularReference(workgroup, newParent)
        }

        // Check depth limit (workgroup + all descendants must fit)
        val workgroupSubtreeDepth = calculateSubtreeDepth(workgroup)
        val newParentDepth = newParent?.calculateDepth() ?: 0
        val resultingDepth = newParentDepth + workgroupSubtreeDepth

        if (resultingDepth > maxDepth) {
            throw ValidationException(
                "Cannot move: resulting depth ($resultingDepth) exceeds maximum ($maxDepth)"
            )
        }

        // Check name uniqueness in new parent's children
        validateSiblingUniqueness(workgroup.name, newParent, excludeId = workgroup.id)
    }

    /**
     * Calculate the maximum depth of the subtree rooted at this workgroup.
     */
    private fun calculateSubtreeDepth(root: Workgroup): Int {
        fun maxDepth(node: Workgroup, currentDepth: Int): Int {
            if (node.children.isEmpty()) return currentDepth
            return node.children.maxOf { maxDepth(it, currentDepth + 1) }
        }
        return maxDepth(root, 1)
    }
}

class ValidationException(message: String) : RuntimeException(message)
```

---

## Repository Extensions

### WorkgroupRepository (Modified)

**Location**: `src/backendng/src/main/kotlin/com/secman/repository/WorkgroupRepository.kt`

**New Methods**:

```kotlin
@Repository
interface WorkgroupRepository : CrudRepository<Workgroup, Long> {

    // Existing methods (unchanged)
    fun findAll(): List<Workgroup>
    fun findByUsersContaining(user: User): List<Workgroup>

    // NEW: Hierarchy-specific queries

    /**
     * Find all children of a parent workgroup.
     */
    fun findByParent(parent: Workgroup): List<Workgroup>

    /**
     * Find all root-level workgroups (no parent).
     */
    @Query("SELECT w FROM Workgroup w WHERE w.parent IS NULL")
    fun findRootLevelWorkgroups(): List<Workgroup>

    /**
     * Find all descendants of a workgroup using recursive CTE.
     * Returns all workgroups in the subtree, including the root.
     */
    @Query(value = """
        WITH RECURSIVE descendants AS (
            SELECT id, parent_id, name, 1 AS depth
            FROM workgroup
            WHERE id = :workgroupId

            UNION ALL

            SELECT w.id, w.parent_id, w.name, d.depth + 1
            FROM workgroup w
            INNER JOIN descendants d ON w.parent_id = d.id
            WHERE d.depth < 10
        )
        SELECT id, parent_id, name, description, version, created_at, updated_at
        FROM workgroup
        WHERE id IN (SELECT id FROM descendants)
    """, nativeQuery = true)
    fun findAllDescendants(@Param("workgroupId") workgroupId: Long): List<Workgroup>

    /**
     * Find all ancestors of a workgroup using recursive CTE.
     * Returns all workgroups from root to immediate parent, ordered from root.
     */
    @Query(value = """
        WITH RECURSIVE ancestors AS (
            SELECT id, parent_id, name, 1 AS depth
            FROM workgroup
            WHERE id = :workgroupId

            UNION ALL

            SELECT w.id, w.parent_id, w.name, a.depth + 1
            FROM workgroup w
            INNER JOIN ancestors a ON a.parent_id = w.id
            WHERE a.depth < 10
        )
        SELECT id, parent_id, name, description, version, created_at, updated_at
        FROM workgroup
        WHERE id IN (SELECT id FROM ancestors) AND id != :workgroupId
        ORDER BY depth DESC
    """, nativeQuery = true)
    fun findAllAncestors(@Param("workgroupId") workgroupId: Long): List<Workgroup>

    /**
     * Count total number of descendants (for admin dashboards).
     */
    @Query(value = """
        WITH RECURSIVE descendants AS (
            SELECT id FROM workgroup WHERE id = :workgroupId
            UNION ALL
            SELECT w.id FROM workgroup w
            INNER JOIN descendants d ON w.parent_id = d.id
        )
        SELECT COUNT(*) - 1 FROM descendants
    """, nativeQuery = true)
    fun countDescendants(@Param("workgroupId") workgroupId: Long): Long
}
```

---

## DTOs and API Models

### CreateChildWorkgroupRequest

```kotlin
data class CreateChildWorkgroupRequest(
    @field:NotBlank(message = "Name is required")
    @field:Size(min = 1, max = 255, message = "Name must be 1-255 characters")
    val name: String,

    @field:Size(max = 1000, message = "Description must not exceed 1000 characters")
    val description: String? = null
)
```

### MoveWorkgroupRequest

```kotlin
data class MoveWorkgroupRequest(
    @field:NotNull(message = "New parent ID is required")
    val newParentId: Long?  // Nullable to allow moving to root level
)
```

### WorkgroupResponse (Modified)

```kotlin
data class WorkgroupResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val parentId: Long?,  // NEW
    val depth: Int,  // NEW: calculated depth
    val childCount: Int,  // NEW: number of direct children
    val hasChildren: Boolean,  // NEW: for lazy-loading trees
    val ancestors: List<BreadcrumbItem>,  // NEW: for breadcrumb navigation
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val version: Long  // NEW: for optimistic locking
)

data class BreadcrumbItem(
    val id: Long,
    val name: String
)
```

### WorkgroupTreeNode (Frontend)

```kotlin
data class WorkgroupTreeNode(
    val id: Long,
    val name: String,
    val depth: Int,
    val hasChildren: Boolean,
    val children: List<WorkgroupTreeNode>? = null  // Null when not loaded
)
```

---

## State Transitions

### Workgroup Lifecycle

**States**: Not explicitly modeled (workgroups are always active). Implicit states based on hierarchy position:
- Root-level: `parent == null`
- Child: `parent != null`
- Leaf: `children.isEmpty()`
- Internal node: `parent != null && children.isNotEmpty()`

**Transitions**:

1. **Create as root** → Root-level
   - Preconditions: Name unique among root-level workgroups
   - Validation: Name format, description length

2. **Create as child** → Child
   - Preconditions: Parent exists, parent depth < 5, name unique among siblings
   - Validation: Depth limit, sibling uniqueness
   - Side effect: Parent's `childCount` increments

3. **Move to different parent** → Child (new parent)
   - Preconditions: New parent not a descendant, depth limit satisfied, name unique in new location
   - Validation: Circular reference check, depth check, sibling uniqueness
   - Side effect: Old parent's `childCount` decrements, new parent's increments
   - Conflict resolution: Optimistic lock version check

4. **Move to root** → Root-level
   - Preconditions: Name unique among root-level workgroups
   - Validation: Root-level uniqueness
   - Side effect: Old parent's `childCount` decrements

5. **Delete (leaf)** → Deleted
   - Preconditions: No children
   - Side effect: Parent's `childCount` decrements (if parent exists)

6. **Delete (with children)** → Deleted, children promoted
   - Preconditions: None (promotion always allowed)
   - Side effect: Children's `parent` updated to grandparent, grandparent's `childCount` updates
   - Special case: If deleted workgroup was root-level, children become root-level

---

## Indexing Strategy

**Indexes Required**:
1. `idx_workgroup_parent` on `parent_id` - Critical for hierarchy queries (ancestors, descendants, children lookups)
2. `uk_workgroup_parent_name` unique constraint on `(parent_id, name)` - Enforces sibling uniqueness
3. Primary key on `id` (already exists) - Lookups by ID

**Query Performance**:
- Children lookup: O(1) with index on `parent_id`
- Ancestor query: O(depth) with recursive CTE ≈ O(5) = constant for 5-level limit
- Descendant query: O(subtree size) with recursive CTE ≈ O(branching_factor^depth)
- Sibling uniqueness check: O(1) with unique constraint enforcement

**No additional indexes needed** - 500 workgroup scale with 5-level depth doesn't require aggressive optimization.

---

## Data Model Summary

**Entity Changes**: 3 new fields added to Workgroup (parent, children, version)
**New Service**: WorkgroupValidationService for centralized hierarchy validation
**Repository Methods**: 5 new methods for hierarchy queries
**Database Migration**: 2 columns, 1 foreign key, 1 index, 1 unique constraint
**DTOs**: 2 new request DTOs, 3 new fields in response DTO

**Backward Compatibility**: ✅ Fully backward compatible
- Existing workgroups become root-level (parent = null)
- Existing API endpoints unchanged
- New fields optional in responses
- No breaking changes to User or Asset relationships

---

**Data Model Complete**: Ready for API contract generation (Phase 1 continued).
