# Implementation Plan: Release Management UI Enhancement

**Branch**: `012-build-ui-for` | **Date**: 2025-10-07 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/012-build-ui-for/spec.md`

## Summary

Build a comprehensive, user-friendly UI for release management that enables compliance managers and auditors to create, view, compare, and manage requirement version snapshots. The feature leverages existing backend APIs from Feature 011 and focuses purely on frontend implementation using Astro 5.14, React 19, and Bootstrap 5.3.

**Primary Requirements**:
- View and browse all releases with filtering, search, and pagination
- Create new releases with semantic versioning validation (starts as DRAFT)
- View release details with paginated requirement snapshots
- Compare two releases with side-by-side diff and Excel export
- Manage release status lifecycle (DRAFT → PUBLISHED → ARCHIVED)
- Delete releases with granular RBAC (ADMIN deletes any, RELEASE_MANAGER deletes own)
- Integrate release selector into export pages

**Technical Approach**:
- Enhance existing React components (ReleaseManagement.tsx, ReleaseComparison.tsx, ReleaseSelector.tsx)
- Add new components for status management and detail views
- Implement traditional pagination with Bootstrap UI
- Use Axios for authenticated API calls
- Follow existing patterns from admin and vulnerability management UIs
- E2E testing with Playwright

## Technical Context

**Language/Version**: TypeScript 5.x / JavaScript ES2022 (Astro 5.14 + React 19)
**Primary Dependencies**: Astro 5.14, React 19, Bootstrap 5.3, Axios (existing), @popperjs/core (tooltips)
**Storage**: N/A (pure frontend - backend APIs from Feature 011)
**Testing**: Playwright for E2E tests
**Target Platform**: Modern browsers (Chrome, Firefox, Safari, Edge - latest 2 versions)
**Project Type**: web (frontend only - backend exists)
**Performance Goals**: 
- List page load <2s (100 releases)
- Detail page load <3s (1000 snapshots)
- Comparison <3s (1000 vs 1000 requirements)
- Status transitions <1s
**Constraints**: 
- Must integrate with existing Astro/React architecture
- Must follow existing Bootstrap 5.3 design patterns
- Must respect JWT authentication and RBAC
- Must be responsive (desktop/tablet - 768px+)
**Scale/Scope**: 
- 7 user stories (2 P1, 4 P2, 1 P3)
- 3-4 new/enhanced React components
- 2-3 Astro pages
- ~61 functional requirements
- Est. 15-20 E2E test scenarios

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Review against `.specify/memory/constitution.md` principles:

- [x] **Security-First**: RBAC enforced at UI level (role checks before showing create/delete/status buttons), username matching for RELEASE_MANAGER delete visibility, authenticated fetch for all API calls
- [x] **TDD**: Playwright E2E tests for all 7 user stories, component testing for form validation and permission checks, test scenarios explicitly defined in spec
- [x] **API-First**: All APIs exist from Feature 011 - no backend changes needed, RESTful endpoints documented, backward compatibility maintained
- [x] **Docker-First**: N/A (frontend only - no containerization changes)
- [x] **RBAC**: Three roles (USER, ADMIN, RELEASE_MANAGER) with clear permission boundaries, UI checks roles before rendering controls, backend permission checks respected
- [x] **Schema Evolution**: N/A (no database changes - using existing Release and RequirementSnapshot entities)

**All constitutional principles satisfied. No deviations.**

## Project Structure

### Documentation (this feature)

```
specs/012-build-ui-for/
├── plan.md              # This file
├── spec.md              # Feature specification (completed)
└── tasks.md             # Task breakdown (to be created via /speckit.tasks)
```

### Source Code (repository root)

```
src/frontend/
├── src/
│   ├── components/
│   │   ├── ReleaseManagement.tsx           # ENHANCE: Add status transitions, delete with ownership check
│   │   ├── ReleaseComparison.tsx           # ENHANCE: Add export comparison button
│   │   ├── ReleaseSelector.tsx             # EXISTING: Use in export pages
│   │   ├── ReleaseList.tsx                 # NEW: List view with filtering, search, pagination
│   │   ├── ReleaseDetail.tsx               # NEW: Detail view with metadata and snapshot table
│   │   ├── ReleaseCreateModal.tsx          # NEW: Creation form with validation
│   │   ├── ReleaseStatusActions.tsx        # NEW: Status transition buttons (Publish, Archive)
│   │   └── ReleaseDeleteConfirm.tsx        # NEW: Delete confirmation modal
│   ├── pages/
│   │   ├── releases/
│   │   │   ├── index.astro                 # ENHANCE: Use ReleaseList component
│   │   │   ├── [id].astro                  # NEW: Detail page using ReleaseDetail
│   │   │   └── compare.astro               # ENHANCE: Use enhanced ReleaseComparison
│   │   ├── export.astro                    # ENHANCE: Add ReleaseSelector integration
│   │   └── import-export.astro             # ENHANCE: Add ReleaseSelector integration
│   ├── services/
│   │   └── releaseService.ts               # NEW: API wrapper for release endpoints
│   └── utils/
│       └── auth.ts                         # EXISTING: authenticatedFetch utility
└── tests/
    └── e2e/
        ├── releases/
        │   ├── release-list.spec.ts        # NEW: Browse, filter, search, pagination
        │   ├── release-create.spec.ts      # NEW: Creation with validation
        │   ├── release-detail.spec.ts      # NEW: View details and snapshots
        │   ├── release-compare.spec.ts     # NEW: Comparison and export
        │   ├── release-status.spec.ts      # NEW: Status transitions
        │   ├── release-delete.spec.ts      # NEW: Delete with RBAC
        │   └── release-export.spec.ts      # NEW: Export integration
        └── helpers/
            └── releaseHelpers.ts           # NEW: Test utilities for releases
```

**Structure Decision**: Web application frontend enhancement. No backend changes needed. All source files under `src/frontend/` following existing Astro + React island architecture. Components are split for reusability (ReleaseList, ReleaseDetail, modals) and follow single-responsibility principle.

## Complexity Tracking

*No constitutional violations. This section tracks only necessary complexity.*

| Decision | Justification | Alternative Rejected |
|----------|---------------|----------------------|
| 8 separate components (vs monolithic) | Single-responsibility, reusability, testability | Monolithic component would be 2000+ LOC, untestable |
| Traditional pagination (vs infinite scroll) | Predictability, better UX for large datasets, easier testing | Infinite scroll adds complexity, unclear position |
| Separate service layer (releaseService.ts) | Centralized API logic, easier mocking for tests, DRY | Inline fetch calls in components duplicates code |
| Role checks in multiple components | Each component independently determines visibility | Central permission service adds indirection without benefit |

## Architecture

### Component Hierarchy

```
Pages (Astro)
│
├── /releases (index.astro)
│   └── <ReleaseList client:load />
│       ├── <ReleaseCreateModal />         # Conditional on role
│       ├── <ReleaseStatusActions />       # Per release, conditional on role
│       └── <ReleaseDeleteConfirm />       # Conditional on role + ownership
│
├── /releases/[id] ([id].astro)
│   └── <ReleaseDetail client:load />
│       ├── <ReleaseStatusActions />
│       ├── <ReleaseDeleteConfirm />
│       └── Snapshot table with pagination
│
├── /releases/compare (compare.astro)
│   └── <ReleaseComparison client:load />
│       └── Export comparison button
│
└── /export (export.astro, import-export.astro)
    └── <ReleaseSelector />                # Add to existing pages
```

### Data Flow

```
User Action → Component → releaseService.ts → authenticatedFetch → Backend API
                ↓
            State Update (useState/useEffect)
                ↓
            UI Re-render with feedback (loading/success/error)
```

### State Management

- **Local Component State** (useState): Form inputs, modals, loading states, errors
- **Server State** (useEffect + fetch): Release list, release details, snapshots, comparison results
- **No Global State**: Each page/component fetches its own data (acceptable for this feature size)

### API Endpoints (Existing - Feature 011)

All endpoints are RESTful and require JWT authentication:

**Release Management**:
- `POST /api/releases` - Create release (body: {version, name, description}) → returns Release with DRAFT status
- `GET /api/releases?status={status}` - List releases with optional status filter → returns Release[]
- `GET /api/releases/{id}` - Get release details → returns Release with metadata
- `PUT /api/releases/{id}/status` - Update release status (body: {status}) → returns Release
- `DELETE /api/releases/{id}` - Delete release (ADMIN: any, RELEASE_MANAGER: own only) → 204 No Content
- `GET /api/releases/{id}/requirements` - Get requirement snapshots → returns RequirementSnapshot[]
- `GET /api/releases/compare?fromReleaseId={id}&toReleaseId={id}` - Compare releases → returns comparison object

**Export Integration**:
- `GET /api/requirements/export/xlsx?releaseId={id}` - Export Excel (optional releaseId)
- `GET /api/requirements/export/docx?releaseId={id}` - Export Word (optional releaseId)
- `GET /api/requirements/export/xlsx/translated/{lang}?releaseId={id}` - Export translated Excel
- `GET /api/requirements/export/docx/translated/{lang}?releaseId={id}` - Export translated Word

**Comparison Export** (NEW - requires backend addition):
- `GET /api/releases/compare/export?fromReleaseId={id}&toReleaseId={id}` - Export comparison as Excel

**Note**: Comparison export endpoint does NOT exist yet. Will need to be added to backend or handled client-side by generating Excel from comparison data.

## Design Patterns

### Component Patterns

1. **Container/Presentational**: 
   - Container components (ReleaseList, ReleaseDetail) handle data fetching and state
   - Presentational components (ReleaseStatusActions, ReleaseDeleteConfirm) receive props and callbacks

2. **Modal Pattern**:
   - Bootstrap modal for create/delete confirmations
   - Controlled component pattern (open/close via state)

3. **Permission HOC** (inline):
   ```typescript
   {hasRole(['ADMIN', 'RELEASE_MANAGER']) && (
     <button>Create Release</button>
   )}
   
   {(isAdmin || (isReleaseManager && release.createdBy === currentUser)) && (
     <button>Delete</button>
   )}
   ```

4. **Loading/Error/Success States**:
   ```typescript
   {loading && <Spinner />}
   {error && <Alert variant="danger">{error}</Alert>}
   {success && data && <ReleaseTable releases={data} />}
   ```

### Pagination Pattern

```typescript
interface PaginationProps {
  currentPage: number;
  totalPages: number;
  onPageChange: (page: number) => void;
}

// Bootstrap pagination component
<Pagination>
  <Pagination.Prev disabled={currentPage === 1} onClick={() => onPageChange(currentPage - 1)} />
  {[...Array(totalPages)].map((_, i) => (
    <Pagination.Item key={i + 1} active={i + 1 === currentPage} onClick={() => onPageChange(i + 1)}>
      {i + 1}
    </Pagination.Item>
  ))}
  <Pagination.Next disabled={currentPage === totalPages} onClick={() => onPageChange(currentPage + 1)} />
</Pagination>
```

### Service Layer Pattern

```typescript
// services/releaseService.ts
export const releaseService = {
  async list(status?: string, page: number = 1, pageSize: number = 20) {
    const params = new URLSearchParams();
    if (status) params.append('status', status);
    params.append('page', page.toString());
    params.append('pageSize', pageSize.toString());
    const response = await authenticatedFetch(`/api/releases?${params}`);
    return response.json();
  },
  
  async create(data: {version: string, name: string, description?: string}) {
    const response = await authenticatedFetch('/api/releases', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(data)
    });
    return response.json();
  },
  
  async updateStatus(id: number, status: 'PUBLISHED' | 'ARCHIVED') {
    const response = await authenticatedFetch(`/api/releases/${id}/status`, {
      method: 'PUT',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({status})
    });
    return response.json();
  },
  
  async delete(id: number) {
    await authenticatedFetch(`/api/releases/${id}`, {method: 'DELETE'});
  },
  
  // ... more methods
};
```

## Testing Strategy

### Test Pyramid

```
        /\
       /  \
      / E2E \         ← 7 Playwright specs (~18 scenarios)
     /______\
    /        \
   / Integ.   \       ← Component integration (minimal - covered by E2E)
  /____________\
 /              \
/   Unit Tests   \    ← Form validation, permission logic
/_________________\
```

### E2E Test Coverage (Playwright)

**Priority 1 (MVP)**:
1. `release-list.spec.ts`:
   - List displays all releases with correct data
   - Status filter works (ALL, DRAFT, PUBLISHED, ARCHIVED)
   - Search filters by version/name
   - Pagination navigates correctly
   - Empty state displays when no releases
   - Click release navigates to detail

2. `release-create.spec.ts`:
   - Create button visible for ADMIN/RELEASE_MANAGER only
   - Modal opens with form fields
   - Semantic version validation works (reject "abc", "1.0")
   - Duplicate version rejected
   - Success creates release with DRAFT status
   - Release appears in list after creation

**Priority 2**:
3. `release-detail.spec.ts`:
   - Detail page shows all metadata
   - Snapshots table displays correctly
   - Pagination works for many snapshots
   - Export button downloads file

4. `release-compare.spec.ts`:
   - Dropdowns populated with releases
   - Comparison shows Added (green), Deleted (red), Modified (yellow)
   - Empty state when no differences
   - Cannot compare release with itself
   - Export comparison button works

5. `release-status.spec.ts`:
   - DRAFT shows "Publish" button for ADMIN/RELEASE_MANAGER
   - Publish transitions to PUBLISHED
   - PUBLISHED shows "Archive" button
   - Archive transitions to ARCHIVED
   - Status badges update immediately
   - USER does not see status buttons

6. `release-export.spec.ts`:
   - Export page has release selector
   - Defaults to "Current (latest)"
   - Selecting release passes releaseId to export
   - Works for Excel, Word, translated exports

**Priority 3**:
7. `release-delete.spec.ts`:
   - ADMIN sees delete on all releases
   - RELEASE_MANAGER sees delete only on own releases
   - Delete confirmation modal appears
   - Confirm deletes and removes from list
   - RELEASE_MANAGER cannot delete others' releases (403)

### Test Data Strategy

- **Setup**: Create test releases with different statuses (DRAFT, PUBLISHED, ARCHIVED) and users
- **Cleanup**: Delete test releases after each test
- **Fixtures**: Reusable test data (valid/invalid versions, release objects)
- **Helpers**: `releaseHelpers.ts` with functions like `createTestRelease()`, `loginAsAdmin()`, `loginAsReleaseManager()`

### Validation Testing

**Form Validation** (unit-testable functions):
- `validateSemanticVersion(version: string): boolean` - regex test for MAJOR.MINOR.PATCH
- `checkDuplicateVersion(version: string, existingReleases: Release[]): boolean`
- Inline validation in E2E tests confirms error messages appear

## Implementation Phases

### Phase 0: Foundation (Day 1)

**Goal**: Set up service layer and test infrastructure

- [ ] Create `services/releaseService.ts` with API wrapper methods
- [ ] Create `tests/e2e/helpers/releaseHelpers.ts` with test utilities
- [ ] Write Playwright config for release tests (if not already configured)
- [ ] Verify existing `authenticatedFetch` works with release endpoints
- [ ] Document any missing backend endpoints (comparison export)

**Validation**: Service methods return expected data types, test helpers create releases successfully

### Phase 1: User Story 1 - Release List (P1 - MVP) (Days 2-3)

**Goal**: Users can browse, filter, and search releases

**Components**:
- [ ] `ReleaseList.tsx` - Main list component with state management
  - Fetch releases on mount
  - Status filter dropdown (ALL, DRAFT, PUBLISHED, ARCHIVED)
  - Search box with debouncing
  - Pagination controls
  - Empty state
  - Release cards/table with badges
  - Click handler to navigate to detail

**Pages**:
- [ ] Enhance `pages/releases/index.astro` to use ReleaseList component

**Tests**:
- [ ] `release-list.spec.ts` - All 6 acceptance scenarios from User Story 1

**Validation**: 
- List page loads <2s with 100 releases
- Filtering, search, pagination work correctly
- Empty state displays appropriately
- All E2E tests pass

### Phase 2: User Story 2 - Create Release (P1 - MVP) (Days 3-4)

**Goal**: ADMIN/RELEASE_MANAGER can create releases

**Components**:
- [ ] `ReleaseCreateModal.tsx` - Modal with form
  - Version input with semantic versioning validation (regex: `^\d+\.\d+\.\d+$`)
  - Name input (required)
  - Description textarea (optional)
  - Form submission with loading state
  - Error handling (duplicate version, validation errors)
  - Success callback to refresh list

**Integration**:
- [ ] Add "Create Release" button to ReleaseList
- [ ] Show button only for ADMIN/RELEASE_MANAGER (role check)
- [ ] Open modal on click
- [ ] Refresh list after successful creation

**Tests**:
- [ ] `release-create.spec.ts` - All 7 acceptance scenarios from User Story 2

**Validation**:
- Creation completes <2s
- Releases created with DRAFT status
- Validation errors display inline
- All E2E tests pass

**Checkpoint**: At this point, MVP is functional (browse + create). Can demo to stakeholders.

### Phase 3: User Story 3 - Release Detail (P2) (Day 5)

**Goal**: Users can view release details and snapshots

**Components**:
- [ ] `ReleaseDetail.tsx` - Detail view component
  - Fetch release metadata by ID
  - Display all metadata fields
  - Fetch requirement snapshots with pagination (50 per page)
  - Snapshot table with key columns
  - Modal or expanded view for full snapshot details
  - Export button (reuse existing export logic with releaseId param)
  - Back to list button

**Pages**:
- [ ] Create `pages/releases/[id].astro` using dynamic routing

**Tests**:
- [ ] `release-detail.spec.ts` - All 5 acceptance scenarios from User Story 3

**Validation**:
- Detail page loads <3s with 1000 snapshots
- Pagination responds instantly
- All E2E tests pass

### Phase 4: User Story 4 - Compare Releases (P2) (Day 6)

**Goal**: Users can compare two releases

**Components**:
- [ ] Enhance `ReleaseComparison.tsx`:
  - Two release selector dropdowns (From/To)
  - Fetch comparison data when both selected
  - Display Added (green), Deleted (red), Modified (yellow) sections
  - Field-by-field diff for modified requirements
  - Empty state when no differences
  - Validation: cannot compare release with itself
  - **Export Comparison** button:
    - If backend endpoint exists: call `/api/releases/compare/export`
    - If not: generate Excel client-side using library (e.g., `xlsx` or `exceljs`)

**Tests**:
- [ ] `release-compare.spec.ts` - All 8 acceptance scenarios from User Story 4

**Validation**:
- Comparison completes <3s for 1000 vs 1000 requirements
- Visual highlighting works correctly
- Export generates correct Excel format
- All E2E tests pass

### Phase 5: User Story 5 - Status Management (P2) (Day 7)

**Goal**: ADMIN/RELEASE_MANAGER can transition release status

**Components**:
- [ ] `ReleaseStatusActions.tsx` - Status transition buttons
  - Props: release, currentUserRoles, onStatusChange callback
  - "Publish" button for DRAFT (ADMIN/RELEASE_MANAGER only)
  - "Archive" button for PUBLISHED (ADMIN/RELEASE_MANAGER only)
  - Confirmation modals for each transition
  - Loading state during API call
  - Success/error feedback

**Integration**:
- [ ] Add ReleaseStatusActions to ReleaseList (per release row/card)
- [ ] Add ReleaseStatusActions to ReleaseDetail page
- [ ] Update release state after successful transition

**Tests**:
- [ ] `release-status.spec.ts` - All 7 acceptance scenarios from User Story 5

**Validation**:
- Status transitions complete <1s
- UI updates immediately
- Workflow enforced (no reverse transitions)
- All E2E tests pass

### Phase 6: User Story 7 - Export Integration (P2) (Day 8)

**Goal**: Users can export requirements from specific releases

**Components**:
- [ ] `ReleaseSelector.tsx` already exists - verify it works correctly

**Integration**:
- [ ] Add ReleaseSelector to `pages/export.astro`
- [ ] Add ReleaseSelector to `pages/import-export.astro`
- [ ] Default selector to "Current (latest)"
- [ ] Pass selected releaseId to export API calls
- [ ] Maintain selection when switching export formats

**Tests**:
- [ ] `release-export.spec.ts` - All 5 acceptance scenarios from User Story 7

**Validation**:
- Exported files contain correct release data
- Spot-check 10 random requirements match release snapshots
- All E2E tests pass

### Phase 7: User Story 6 - Delete Release (P3) (Day 9)

**Goal**: ADMIN/RELEASE_MANAGER can delete releases

**Components**:
- [ ] `ReleaseDeleteConfirm.tsx` - Delete confirmation modal
  - Props: release, currentUserRoles, currentUsername, onDelete callback
  - Warning message about permanence
  - Confirm/Cancel buttons
  - Loading state during deletion

**Integration**:
- [ ] Add delete button to ReleaseList
  - Show for ADMIN on all releases
  - Show for RELEASE_MANAGER only if `release.createdBy === currentUsername`
- [ ] Add delete button to ReleaseDetail
  - Same permission logic
- [ ] Remove release from list after successful deletion

**Tests**:
- [ ] `release-delete.spec.ts` - All 7 acceptance scenarios from User Story 6

**Validation**:
- Deletion completes <1s
- 403 error when RELEASE_MANAGER tries to delete others' releases
- All E2E tests pass

### Phase 8: Polish & Cross-Cutting (Day 10)

**Goal**: Final polish and production readiness

- [ ] **Accessibility**:
  - Keyboard navigation (tab order, enter/escape)
  - ARIA labels for screen readers
  - Color contrast checks (ensure status colors meet WCAG AA)
  - Status text labels (not just color-coded)

- [ ] **Performance**:
  - Debounce search input (300ms)
  - Lazy load snapshots on detail page
  - Memoize expensive computations (comparison diff)
  - Optimize re-renders (React.memo for presentational components)

- [ ] **Error Handling**:
  - Network errors: "Failed to load releases. Please try again."
  - 403 errors: "You do not have permission to perform this action."
  - 404 errors: "Release not found."
  - Validation errors: Inline with clear messages

- [ ] **Loading States**:
  - Skeleton loaders for initial page loads
  - Spinner for actions (create, delete, status change)
  - Disabled buttons during loading

- [ ] **Documentation**:
  - Update `CLAUDE.md` with new components and pages
  - Add JSDoc comments to service methods
  - Document permission logic in README or separate doc

- [ ] **Code Cleanup**:
  - Remove console.log statements
  - Remove unused imports
  - Run ESLint and fix warnings
  - Format with Prettier (if configured)

**Validation**: All E2E tests pass, no console errors, lighthouse score >90

## Dependencies & Risks

### Dependencies

**Technical Dependencies** (all satisfied):
- ✅ Backend APIs from Feature 011 (all endpoints exist except comparison export)
- ✅ Astro 5.14 + React 19 architecture
- ✅ Bootstrap 5.3 styles
- ✅ authenticatedFetch utility
- ✅ JWT authentication with role claims

**Team Dependencies**:
- None (frontend-only feature)

### Risks & Mitigation

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Comparison export endpoint missing | High | Medium | Generate Excel client-side using `exceljs` library (add dependency) |
| Performance issues with 1000+ snapshots | Medium | Medium | Implement pagination (50/page), lazy loading, virtual scrolling if needed |
| RBAC logic complex in UI | Low | Medium | Extract to reusable hooks (`useHasRole`, `useCanDeleteRelease`), unit test |
| Existing components need significant refactoring | Medium | Low | Keep changes minimal, create new components if refactoring too complex |
| Test flakiness with async operations | Medium | Low | Use Playwright's auto-waiting, proper assertions (toBeVisible vs toContain) |

### Open Issues

1. **Comparison Export Endpoint**: Backend endpoint `/api/releases/compare/export` does NOT exist yet
   - **Decision Required**: Add backend endpoint OR generate Excel client-side?
   - **Recommendation**: Generate client-side for now (no backend dependency), add endpoint in future if performance issues
   - **Action**: Add `exceljs` dependency to `package.json`

2. **Status Transition Backend**: Need to verify backend supports `PUT /api/releases/{id}/status`
   - **Action**: Check Feature 011 implementation, add if missing (small backend change)

## Definition of Done

### Per Phase
- [ ] All components/pages implemented as designed
- [ ] All E2E tests for that phase pass
- [ ] No ESLint errors or warnings
- [ ] Code reviewed (self-review minimum)
- [ ] Manual smoke test in browser

### Overall Feature
- [ ] All 7 user stories implemented
- [ ] All 61 functional requirements met
- [ ] All 18+ E2E test scenarios pass
- [ ] Performance targets met (list <2s, detail <3s, comparison <3s, status <1s)
- [ ] RBAC enforced correctly (manual verification with 3 user roles)
- [ ] Accessibility tested (keyboard navigation, screen reader spot-check)
- [ ] Documentation updated (CLAUDE.md)
- [ ] No console errors in production build
- [ ] Lighthouse score >90 (Performance, Accessibility, Best Practices)

## Estimated Timeline

**Total**: 10 days (8 implementation + 2 buffer)

- Day 1: Foundation (Phase 0)
- Days 2-3: List (Phase 1 - P1)
- Days 3-4: Create (Phase 2 - P1)
- **Day 4: MVP Complete** ✅ (can demo)
- Day 5: Detail (Phase 3 - P2)
- Day 6: Comparison (Phase 4 - P2)
- Day 7: Status (Phase 5 - P2)
- Day 8: Export Integration (Phase 6 - P2)
- Day 9: Delete (Phase 7 - P3)
- Day 10: Polish (Phase 8)

**Velocity Assumptions**: 1 developer, 6-8 hours/day focused work, minimal blockers

## Success Metrics

From spec Success Criteria:

- **SC-001**: Release creation <30s (measured: form open → success confirmation)
- **SC-002**: Find specific release <15s using search/filter
- **SC-003**: Comparison displays <3s for 1000 vs 1000 requirements
- **SC-004**: Export correctness (spot-check 10 random requirements)
- **SC-005**: 90% of test users complete primary workflows on first attempt
- **SC-006**: Zero unauthorized actions succeed (verified by RBAC tests)
- **SC-007**: All interactions provide feedback within 200ms
- **SC-008**: All error scenarios display actionable messages

**Post-Launch Monitoring**:
- User feedback survey (ease of use, missing features)
- Browser console errors (track via monitoring tool if available)
- Page load times (check in production with real data volumes)

## Next Steps

1. **Review this plan** with team/stakeholders (if applicable)
2. **Resolve open issues** (comparison export decision, status endpoint verification)
3. **Create feature branch**: `git checkout -b 012-build-ui-for`
4. **Run `/speckit.tasks`** to generate granular task breakdown
5. **Begin Phase 0**: Set up service layer and test infrastructure
6. **Iterate through phases** following TDD: write E2E test → implement → verify → commit

---

**Plan Version**: 1.0  
**Last Updated**: 2025-10-07  
**Status**: Ready for task breakdown
