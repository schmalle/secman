# Quickstart: Nested Workgroups

**Feature**: 040-nested-workgroups
**Date**: 2025-11-02
**Purpose**: Quick implementation guide for developers working on hierarchical workgroups feature.

## Overview

This feature adds hierarchical organization to workgroups through self-referential parent-child relationships. Administrators can create nested structures up to 5 levels deep with automatic validation and child promotion on deletion.

**Key Capabilities**:
- Create child workgroups under existing workgroups
- Move workgroups between parents
- Delete workgroups with automatic child promotion
- View hierarchy with breadcrumb navigation
- Tree visualization with expand/collapse

## Prerequisites

- Kotlin 2.2.21 / Java 21
- Micronaut 4.10 running
- MariaDB 11.4 database
- Astro 5.14 + React 19 frontend
- ADMIN role for testing

## Database Setup

The schema migration is automatic via Hibernate, but here's what gets created:

```sql
-- Added to workgroup table
ALTER TABLE workgroup
ADD COLUMN parent_id BIGINT NULL,
ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE workgroup
ADD CONSTRAINT fk_workgroup_parent
FOREIGN KEY (parent_id) REFERENCES workgroup(id)
ON DELETE RESTRICT;

CREATE INDEX idx_workgroup_parent ON workgroup(parent_id);

ALTER TABLE workgroup
ADD CONSTRAINT uk_workgroup_parent_name UNIQUE (parent_id, name);
```

**Note**: Existing workgroups automatically become root-level (parent_id = NULL).

## Backend Implementation Steps

### 1. Update Domain Entity

**File**: `src/backendng/src/main/kotlin/com/secman/domain/Workgroup.kt`

Add fields:
```kotlin
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "parent_id")
var parent: Workgroup? = null

@OneToMany(mappedBy = "parent", cascade = [CascadeType.PERSIST, CascadeType.MERGE])
val children: MutableSet<Workgroup> = mutableSetOf()

@Version
var version: Long = 0
```

Add helper methods (see data-model.md for full implementation):
- `calculateDepth(): Int`
- `getAncestors(): List<Workgroup>`
- `isDescendantOf(Workgroup): Boolean`

### 2. Create Validation Service

**File**: `src/backendng/src/main/kotlin/com/secman/service/WorkgroupValidationService.kt`

Implement validation methods:
- `validateDepthLimit(parent: Workgroup?)`
- `validateNoCircularReference(workgroup: Workgroup, newParent: Workgroup)`
- `validateSiblingUniqueness(name: String, parent: Workgroup?, excludeId: Long?)`
- `validateMove(workgroup: Workgroup, newParent: Workgroup?)`

**Key Logic**:
```kotlin
// Depth check
val parentDepth = parent.calculateDepth()
if (parentDepth >= 5) throw ValidationException("Max depth exceeded")

// Circular reference check
if (newParent.isDescendantOf(workgroup)) {
    throw ValidationException("Would create circular reference")
}

// Sibling uniqueness
val siblings = workgroupRepository.findByParent(parent)
if (siblings.any { it.name.equals(name, ignoreCase = true) && it.id != excludeId }) {
    throw ValidationException("Duplicate sibling name")
}
```

### 3. Extend Repository

**File**: `src/backendng/src/main/kotlin/com/secman/repository/WorkgroupRepository.kt`

Add methods:
```kotlin
fun findByParent(parent: Workgroup): List<Workgroup>

@Query("SELECT w FROM Workgroup w WHERE w.parent IS NULL")
fun findRootLevelWorkgroups(): List<Workgroup>

// Recursive CTE for descendants (see data-model.md for full query)
@Query(value = "WITH RECURSIVE descendants AS (...)", nativeQuery = true)
fun findAllDescendants(@Param("workgroupId") workgroupId: Long): List<Workgroup>

// Recursive CTE for ancestors
@Query(value = "WITH RECURSIVE ancestors AS (...)", nativeQuery = true)
fun findAllAncestors(@Param("workgroupId") workgroupId: Long): List<Workgroup>
```

### 4. Update Service Layer

**File**: `src/backendng/src/main/kotlin/com/secman/service/WorkgroupService.kt`

Add methods:
```kotlin
@Transactional
fun createChild(parentId: Long, request: CreateChildWorkgroupRequest, user: User): Workgroup {
    val parent = findById(parentId) ?: throw NotFoundException()

    validationService.validateDepthLimit(parent)
    validationService.validateSiblingUniqueness(request.name, parent)

    val child = Workgroup(name = request.name, description = request.description, parent = parent)
    val saved = repository.save(child)

    logger.info("Workgroup created: id={}, name={}, parent={}, user={}",
        saved.id, saved.name, parent.id, user.username)

    return saved
}

@Transactional
fun move(id: Long, newParentId: Long?, user: User): Workgroup {
    val workgroup = findById(id) ?: throw NotFoundException()
    val newParent = newParentId?.let { findById(it) }

    validationService.validateMove(workgroup, newParent)

    val oldParentId = workgroup.parent?.id
    workgroup.parent = newParent

    val updated = repository.save(workgroup)

    logger.info("Workgroup moved: id={}, oldParent={}, newParent={}, user={}",
        id, oldParentId, newParentId, user.username)

    return updated
}

@Transactional
fun deleteWithPromotion(id: Long, user: User) {
    val workgroup = findById(id) ?: throw NotFoundException()
    val childCount = workgroup.children.size

    // Promote children to grandparent
    workgroup.children.forEach { child ->
        child.parent = workgroup.parent
    }

    repository.delete(workgroup)

    logger.info("Workgroup deleted: id={}, name={}, childrenPromoted={}, user={}",
        id, workgroup.name, childCount, user.username)
}
```

### 5. Create Controller Endpoints

**File**: `src/backendng/src/main/kotlin/com/secman/controller/WorkgroupController.kt`

Add endpoints:
```kotlin
@Post("/{id}/children")
@Secured("ADMIN")
fun createChild(@PathVariable id: Long, @Body @Valid request: CreateChildWorkgroupRequest,
                authentication: Authentication): HttpResponse<WorkgroupResponse> {
    val user = getCurrentUser(authentication)
    val workgroup = workgroupService.createChild(id, request, user)
    return HttpResponse.created(workgroup.toResponse())
}

@Get("/{id}/children")
@Secured(SecurityRule.IS_AUTHENTICATED)
fun getChildren(@PathVariable id: Long): List<WorkgroupResponse> {
    return workgroupService.getChildren(id).map { it.toResponse() }
}

@Put("/{id}/parent")
@Secured("ADMIN")
fun move(@PathVariable id: Long, @Body @Valid request: MoveWorkgroupRequest,
         authentication: Authentication): WorkgroupResponse {
    val user = getCurrentUser(authentication)
    val workgroup = workgroupService.move(id, request.newParentId, user)
    return workgroup.toResponse()
}

@Delete("/{id}")
@Secured("ADMIN")
@Status(HttpStatus.NO_CONTENT)
fun delete(@PathVariable id: Long, authentication: Authentication) {
    val user = getCurrentUser(authentication)
    workgroupService.deleteWithPromotion(id, user)
}

@Get("/{id}/ancestors")
@Secured(SecurityRule.IS_AUTHENTICATED)
fun getAncestors(@PathVariable id: Long): List<BreadcrumbItem> {
    return workgroupService.getAncestors(id).map { BreadcrumbItem(it.id!!, it.name) }
}

@Get("/root")
@Secured(SecurityRule.IS_AUTHENTICATED)
fun getRootWorkgroups(): List<WorkgroupResponse> {
    return workgroupService.getRootWorkgroups().map { it.toResponse() }
}
```

**Error Handling**:
```kotlin
@Error(ValidationException::class)
fun handleValidation(e: ValidationException): HttpResponse<ErrorResponse> {
    return HttpResponse.badRequest(ErrorResponse(e.message ?: "Validation error", "VALIDATION_ERROR"))
}

@Error(OptimisticLockException::class)
fun handleOptimisticLock(e: OptimisticLockException): HttpResponse<ErrorResponse> {
    return HttpResponse.status<ErrorResponse>(HttpStatus.CONFLICT)
        .body(ErrorResponse("Workgroup was modified. Please refresh and retry.", "OPTIMISTIC_LOCK_CONFLICT"))
}
```

## Frontend Implementation Steps

### 1. Update API Client

**File**: `src/frontend/src/services/workgroupApi.ts`

Add methods:
```typescript
export async function createChild(parentId: number, request: CreateChildWorkgroupRequest): Promise<Workgroup> {
  const response = await axios.post(`/api/workgroups/${parentId}/children`, request);
  return response.data;
}

export async function moveWorkgroup(id: number, newParentId: number | null): Promise<Workgroup> {
  const response = await axios.put(`/api/workgroups/${id}/parent`, { newParentId });
  return response.data;
}

export async function deleteWorkgroup(id: number): Promise<void> {
  await axios.delete(`/api/workgroups/${id}`);
}

export async function getChildren(parentId: number): Promise<Workgroup[]> {
  const response = await axios.get(`/api/workgroups/${parentId}/children`);
  return response.data;
}

export async function getAncestors(id: number): Promise<BreadcrumbItem[]> {
  const response = await axios.get(`/api/workgroups/${id}/ancestors`);
  return response.data;
}

export async function getRootWorkgroups(): Promise<Workgroup[]> {
  const response = await axios.get(`/api/workgroups/root`);
  return response.data;
}
```

### 2. Create Tree Component

**File**: `src/frontend/src/components/WorkgroupTree.tsx`

```tsx
interface WorkgroupTreeProps {
  workgroup: Workgroup;
  depth: number;
  onSelect?: (workgroup: Workgroup) => void;
}

export function WorkgroupTree({ workgroup, depth, onSelect }: WorkgroupTreeProps) {
  const [expanded, setExpanded] = useState(false);
  const [children, setChildren] = useState<Workgroup[]>([]);
  const [loading, setLoading] = useState(false);

  const toggleExpand = async () => {
    if (!expanded && children.length === 0) {
      setLoading(true);
      try {
        const data = await workgroupApi.getChildren(workgroup.id);
        setChildren(data);
      } catch (error) {
        console.error('Failed to load children:', error);
      } finally {
        setLoading(false);
      }
    }
    setExpanded(!expanded);
  };

  return (
    <div style={{ marginLeft: `${depth * 20}px` }} className="workgroup-tree-node">
      <div className="d-flex align-items-center">
        {workgroup.hasChildren && (
          <button
            className="btn btn-sm btn-link"
            onClick={toggleExpand}
            disabled={loading}
          >
            {loading ? '⏳' : expanded ? '▼' : '▶'}
          </button>
        )}
        <span
          className="workgroup-name"
          onClick={() => onSelect?.(workgroup)}
          style={{ cursor: 'pointer' }}
        >
          {workgroup.name}
        </span>
      </div>
      {expanded && (
        <div className="workgroup-children">
          {children.map(child => (
            <WorkgroupTree
              key={child.id}
              workgroup={child}
              depth={depth + 1}
              onSelect={onSelect}
            />
          ))}
        </div>
      )}
    </div>
  );
}
```

### 3. Create Breadcrumb Component

**File**: `src/frontend/src/components/WorkgroupBreadcrumb.tsx`

```tsx
interface WorkgroupBreadcrumbProps {
  workgroupId: number;
}

export function WorkgroupBreadcrumb({ workgroupId }: WorkgroupBreadcrumbProps) {
  const [ancestors, setAncestors] = useState<BreadcrumbItem[]>([]);

  useEffect(() => {
    workgroupApi.getAncestors(workgroupId).then(setAncestors);
  }, [workgroupId]);

  return (
    <nav aria-label="breadcrumb">
      <ol className="breadcrumb">
        <li className="breadcrumb-item">
          <a href="/workgroups">Root</a>
        </li>
        {ancestors.map(ancestor => (
          <li key={ancestor.id} className="breadcrumb-item">
            <a href={`/workgroups/${ancestor.id}`}>{ancestor.name}</a>
          </li>
        ))}
      </ol>
    </nav>
  );
}
```

### 4. Create Child Workgroup Modal

**File**: `src/frontend/src/components/CreateChildWorkgroupModal.tsx`

```tsx
interface CreateChildWorkgroupModalProps {
  parentId: number;
  parentName: string;
  show: boolean;
  onClose: () => void;
  onCreated: (workgroup: Workgroup) => void;
}

export function CreateChildWorkgroupModal({
  parentId, parentName, show, onClose, onCreated
}: CreateChildWorkgroupModalProps) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setSubmitting(true);

    try {
      const workgroup = await workgroupApi.createChild(parentId, { name, description });
      onCreated(workgroup);
      onClose();
      setName('');
      setDescription('');
    } catch (err: any) {
      setError(err.response?.data?.error || 'Failed to create workgroup');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className={`modal ${show ? 'show d-block' : ''}`} tabIndex={-1}>
      <div className="modal-dialog">
        <div className="modal-content">
          <div className="modal-header">
            <h5 className="modal-title">Create Child Workgroup under {parentName}</h5>
            <button type="button" className="btn-close" onClick={onClose}></button>
          </div>
          <form onSubmit={handleSubmit}>
            <div className="modal-body">
              {error && <div className="alert alert-danger">{error}</div>}
              <div className="mb-3">
                <label className="form-label">Name *</label>
                <input
                  type="text"
                  className="form-control"
                  value={name}
                  onChange={e => setName(e.target.value)}
                  required
                  maxLength={255}
                />
              </div>
              <div className="mb-3">
                <label className="form-label">Description</label>
                <textarea
                  className="form-control"
                  value={description}
                  onChange={e => setDescription(e.target.value)}
                  maxLength={1000}
                  rows={3}
                />
              </div>
            </div>
            <div className="modal-footer">
              <button type="button" className="btn btn-secondary" onClick={onClose}>
                Cancel
              </button>
              <button type="submit" className="btn btn-primary" disabled={submitting}>
                {submitting ? 'Creating...' : 'Create'}
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  );
}
```

### 5. Update Workgroups Page

**File**: `src/frontend/src/pages/workgroups.astro`

```astro
---
import Layout from '../layouts/Layout.astro';
---

<Layout title="Workgroups">
  <div class="container mt-4">
    <h1>Workgroups Hierarchy</h1>
    <div id="workgroup-tree-root"></div>
  </div>
</Layout>

<script>
  import { createRoot } from 'react-dom/client';
  import { WorkgroupTree } from '../components/WorkgroupTree';
  import { getRootWorkgroups } from '../services/workgroupApi';

  const rootElement = document.getElementById('workgroup-tree-root');
  if (rootElement) {
    const root = createRoot(rootElement);

    getRootWorkgroups().then(workgroups => {
      root.render(
        workgroups.map(wg => (
          <WorkgroupTree key={wg.id} workgroup={wg} depth={0} />
        ))
      );
    });
  }
</script>
```

## Testing Checklist

### Contract Tests (TDD - Write First!)

**File**: `src/backendng/src/test/kotlin/com/secman/contract/WorkgroupControllerTest.kt`

- [ ] POST /workgroups/{id}/children - success (201)
- [ ] POST /workgroups/{id}/children - depth exceeded (400)
- [ ] POST /workgroups/{id}/children - sibling name conflict (400)
- [ ] POST /workgroups/{id}/children - unauthorized (401)
- [ ] POST /workgroups/{id}/children - forbidden non-ADMIN (403)
- [ ] PUT /workgroups/{id}/parent - success (200)
- [ ] PUT /workgroups/{id}/parent - circular reference (400)
- [ ] PUT /workgroups/{id}/parent - optimistic lock conflict (409)
- [ ] DELETE /workgroups/{id} - success with child promotion (204)
- [ ] GET /workgroups/{id}/ancestors - success (200)

### Unit Tests

**File**: `src/backendng/src/test/kotlin/com/secman/service/WorkgroupValidationServiceTest.kt`

- [ ] validateDepthLimit - allows depth 1-4, rejects 5+
- [ ] validateNoCircularReference - prevents A→B→A cycles
- [ ] validateSiblingUniqueness - case-insensitive uniqueness
- [ ] calculateDepth - correct for various hierarchy levels

### Integration Tests

**File**: `src/backendng/src/test/kotlin/com/secman/integration/WorkgroupHierarchyIntegrationTest.kt`

- [ ] Create 5-level hierarchy successfully
- [ ] Recursive CTE queries return correct descendants
- [ ] Child promotion on delete works correctly
- [ ] Concurrent modifications handled with optimistic locking

### E2E Tests (Playwright)

**File**: `src/frontend/tests/workgroups-hierarchy.spec.ts`

- [ ] Admin can create child workgroup via UI
- [ ] Tree expands/collapses correctly
- [ ] Breadcrumb navigation shows correct path
- [ ] Validation errors displayed in modals

## Quick Manual Test

```bash
# Start backend
cd src/backendng
./gradlew run

# Start frontend
cd src/frontend
npm run dev

# As ADMIN user:
# 1. Navigate to /workgroups
# 2. Click "Add Child" on any workgroup
# 3. Fill name and description, submit
# 4. Verify child appears in tree
# 5. Try moving a workgroup to different parent
# 6. Try deleting a parent with children
# 7. Verify children are promoted correctly
```

## Common Issues & Solutions

**Issue**: "Duplicate sibling name" error
- **Cause**: Trying to create/move workgroup with name that exists among siblings
- **Solution**: Choose different name or move to different parent

**Issue**: "Maximum depth exceeded"
- **Cause**: Trying to create child at depth 5 or move subtree that would exceed depth 5
- **Solution**: Restructure hierarchy (move subtree to shallower parent first)

**Issue**: "Optimistic lock conflict" (409)
- **Cause**: Another admin modified the workgroup concurrently
- **Solution**: Refresh page and retry operation

**Issue**: Slow tree loading
- **Cause**: Too many workgroups or inefficient queries
- **Solution**: Verify `idx_workgroup_parent` index exists, check slow query log

## Performance Monitoring

**Key Metrics to Watch**:
- `POST /workgroups/{id}/children` latency: <500ms
- `GET /workgroups/{id}/children` latency: <200ms
- Recursive CTE queries (descendants/ancestors): <500ms
- Page load with 100 workgroups: <3s

**Database Queries to Monitor**:
```sql
-- Check index usage
EXPLAIN SELECT * FROM workgroup WHERE parent_id = 123;
-- Should use idx_workgroup_parent

-- Monitor recursive CTE performance
EXPLAIN WITH RECURSIVE descendants AS (...) SELECT * FROM descendants;
```

## Next Steps

After basic implementation:
1. Run full test suite: `./gradlew test` (backend), `npm test` (frontend)
2. Manual testing with ADMIN account
3. Performance testing with 500 workgroups
4. Security review of RBAC enforcement
5. Document any deviations from plan

**Implementation complete**: Ready for `/speckit.tasks` to generate task breakdown.
