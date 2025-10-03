# Tasks: Admin Sidebar Visibility Control

**Input**: Design documents from `/specs/001-make-the-admin/`
**Prerequisites**: plan.md ✓, research.md ✓, data-model.md ✓, contracts/sidebar-behavior.contract.md ✓, quickstart.md ✓

## Execution Flow (main)
```
1. Load plan.md from feature directory
   ✓ Loaded: Web application (frontend-only change)
   ✓ Tech stack: Astro 5.13.5, React 19.1.1, TypeScript, Playwright
   ✓ Files to modify: Sidebar.astro, Sidebar.tsx
   ✓ Files to create: admin-sidebar-visibility.spec.ts
2. Load optional design documents:
   ✓ data-model.md: No new entities (uses existing User)
   ✓ contracts/: UI behavior contract (sidebar-behavior.contract.md)
   ✓ research.md: Client-side role check using window.currentUser
   ✓ quickstart.md: 3 test scenarios + manual validation
3. Generate tasks by category:
   ✓ Setup: None needed (existing project)
   ✓ Tests: 3 E2E test scenarios (parallel)
   ✓ Core: 2 component implementations (parallel)
   ✓ Integration: None needed (no backend changes)
   ✓ Polish: Test execution + manual QA
4. Apply task rules:
   ✓ Different test scenarios = [P] for parallel
   ✓ Different components = [P] for parallel
   ✓ Tests before implementation (TDD)
5. Number tasks sequentially (T001, T002...)
6. Generate dependency graph
7. Create parallel execution examples
8. Validate task completeness:
   ✓ All contract scenarios have tests? YES (3 scenarios → 3 tests)
   ✓ All components have implementations? YES (2 components → 2 tasks)
   ✓ Tests before implementation? YES (T001-T003 before T004-T005)
9. Return: SUCCESS (tasks ready for execution)
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Include exact file paths in descriptions

## Path Conventions
This is a web application with:
- **Backend**: `src/backendng/` (no changes for this feature)
- **Frontend**: `src/frontend/` (all changes here)
- **Tests**: `src/frontend/tests/` (E2E tests)

---

## Phase 3.1: Setup
✅ **No setup tasks required** - existing project with all dependencies installed

---

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3
**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation**

- [x] **T001** [P] Write E2E test for admin user seeing Admin menu item
  - **File**: `src/frontend/tests/admin-sidebar-visibility.spec.ts`
  - **Test**: Login as `adminuser`, verify Admin menu visible in sidebar
  - **Assertions**:
    - `page.locator('a[href="/admin"]').toBeVisible()`
    - Admin menu is clickable and navigates to `/admin`
  - **Expected**: FAIL (implementation not done yet)
  - **Reference**: Similar pattern in `src/frontend/tests/admin-ui-access.spec.ts:23-41`
  - **Status**: ✅ Complete

- [x] **T002** [P] Write E2E test for regular user NOT seeing Admin menu item
  - **File**: `src/frontend/tests/admin-sidebar-visibility.spec.ts`
  - **Test**: Login as `regularuser`, verify Admin menu NOT visible
  - **Assertions**:
    - `page.locator('a[href="/admin"]').not.toBeVisible()` OR
    - `page.locator('a[href="/admin"]').toHaveCount(0)`
  - **Expected**: FAIL (implementation not done yet)
  - **Can run in parallel with**: T001 (same file, different test case)
  - **Status**: ✅ Complete

- [x] **T003** [P] Write E2E test for Admin menu visibility persisting across navigation
  - **File**: `src/frontend/tests/admin-sidebar-visibility.spec.ts`
  - **Test**: Login as admin, navigate to multiple pages, verify Admin menu stays visible
  - **Assertions**:
    - Navigate to `/requirements` → Admin menu visible
    - Navigate to `/assets` → Admin menu visible
    - Navigate to `/standards` → Admin menu visible
  - **Expected**: FAIL (implementation not done yet)
  - **Can run in parallel with**: T001, T002 (same file, different test case)
  - **Status**: ✅ Complete

**Validation Gate**: All tests T001-T003 MUST FAIL before proceeding to T004

---

## Phase 3.3: Core Implementation (ONLY after tests are failing)

- [x] **T004** [P] Implement role-based visibility in Sidebar.astro
  - **File**: `src/frontend/src/components/Sidebar.astro`
  - **Line**: ~17 (sidebarItems array) and ~44-59 (render loop)
  - **Changes**:
    1. Add client-side `<script>` block to check `window.currentUser.roles`
    2. Listen for `userLoaded` event (dispatched by Layout.astro)
    3. Filter or hide Admin menu item (`href="/admin"`) if user lacks ROLE_ADMIN
    4. Default to hidden if `window.currentUser` is null/undefined
  - **Implementation pattern**:
    ```javascript
    <script>
      function updateAdminVisibility() {
        const user = (window as any).currentUser;
        const hasAdmin = user?.roles?.includes('ADMIN') || false;
        const adminLink = document.querySelector('a[href="/admin"]');
        if (adminLink) {
          adminLink.closest('li').style.display = hasAdmin ? 'block' : 'none';
        }
      }
      window.addEventListener('userLoaded', updateAdminVisibility);
      updateAdminVisibility();
    </script>
    ```
  - **Testing**: Run T001-T003, some should start passing
  - **Can run in parallel with**: T005 (different file)
  - **Status**: ✅ Complete - Added updateAdminVisibility() function in script block

- [x] **T005** [P] Implement role-based visibility in Sidebar.tsx
  - **File**: `src/frontend/src/components/Sidebar.tsx`
  - **Line**: ~127-131 (Admin menu item rendering)
  - **Changes**:
    1. Add React state: `const [isAdmin, setIsAdmin] = useState(false);`
    2. Add `useEffect` to check `window.currentUser.roles.includes('ADMIN')`
    3. Listen for `userLoaded` event in effect hook
    4. Conditionally render Admin menu: `{isAdmin && <li><a href="/admin">...</a></li>}`
    5. Clean up event listener on unmount
  - **Implementation pattern**:
    ```typescript
    const [isAdmin, setIsAdmin] = useState(false);

    useEffect(() => {
      function checkAdminRole() {
        const user = (window as any).currentUser;
        setIsAdmin(user?.roles?.includes('ADMIN') || false);
      }
      checkAdminRole();
      window.addEventListener('userLoaded', checkAdminRole);
      return () => window.removeEventListener('userLoaded', checkAdminRole);
    }, []);
    ```
  - **Testing**: Run T001-T003, all should pass now
  - **Can run in parallel with**: T004 (different file)
  - **Status**: ✅ Complete - Added isAdmin state, useEffect hook, and conditional rendering

---

## Phase 3.4: Integration
✅ **No integration tasks required** - using existing auth infrastructure (window.currentUser, userLoaded event)

---

## Phase 3.5: Polish

- [ ] **T006** Run E2E tests and verify all pass
  - **Command**: `cd src/frontend && npm run test admin-sidebar-visibility.spec.ts`
  - **Expected**: All 3 tests pass (T001, T002, T003)
  - **If failures**: Debug using `npm run test:headed` or `npm run test:ui`
  - **Success criteria**:
    - ✅ Admin user sees Admin menu
    - ✅ Regular user doesn't see Admin menu
    - ✅ Visibility persists across navigation
  - **Dependencies**: T004, T005 must be complete

- [ ] **T007** Manual QA following quickstart.md
  - **File**: `specs/001-make-the-admin/quickstart.md`
  - **Steps**:
    1. Start backend: `cd src/backendng && ./gradlew run`
    2. Start frontend: `cd src/frontend && npm run dev`
    3. Test admin user scenario (sections: Test 1)
    4. Test regular user scenario (sections: Test 2)
    5. Test edge cases (section: Test 3)
  - **Validation checklist**: Complete all checkboxes in quickstart.md
  - **Browser testing**: Chrome (primary), Firefox (optional)
  - **Success criteria**: All manual tests pass
  - **Dependencies**: T006 must pass

- [ ] **T008** [P] Update documentation (if needed)
  - **Files to check**:
    - `docs/DEVELOPMENT.md` - Add note about role-based sidebar if relevant
    - `README.md` - Update if admin features mentioned
  - **Action**: Only update if documentation explicitly mentions sidebar or admin access
  - **Can run in parallel with**: T007 (different activity)
  - **Optional**: May skip if no docs reference sidebar

---

## Dependencies

```
Setup (none)
  ↓
Tests First (T001, T002, T003) [ALL PARALLEL]
  ↓ [ALL MUST FAIL]
  ↓
Implementation (T004, T005) [PARALLEL - different files]
  ↓
Polish (T006 → T007, T008)
```

**Critical Path**: T001-T003 → T004-T005 → T006 → T007

**Parallel Opportunities**:
- T001, T002, T003 (writing test cases)
- T004, T005 (implementing components)
- T007, T008 (QA and docs)

---

## Parallel Execution Examples

### Execute Tests in Parallel (Phase 3.2)
Since all tests are in the same file, they're actually test cases in one test suite. The file itself is a single task, but writing the test cases can be thought of conceptually as parallel work:

```typescript
// In admin-sidebar-visibility.spec.ts - all written together
test.describe('Admin Sidebar Visibility', () => {
  test('admin user sees admin menu', async ({ page }) => { /* T001 */ });
  test('regular user does not see admin menu', async ({ page }) => { /* T002 */ });
  test('visibility persists across navigation', async ({ page }) => { /* T003 */ });
});
```

### Execute Implementations in Parallel (Phase 3.3)
These CAN truly run in parallel (different files):

```bash
# Terminal 1
# T004: Implement Sidebar.astro
edit src/frontend/src/components/Sidebar.astro

# Terminal 2 (simultaneously)
# T005: Implement Sidebar.tsx
edit src/frontend/src/components/Sidebar.tsx
```

---

## Notes

- **[P] tasks** = different files or truly independent work
- **Verify tests fail** before implementing (T001-T003 must fail initially)
- **No backend changes** required - auth already enforced server-side
- **Frontend only** - both Sidebar.astro and Sidebar.tsx must be updated
- **Existing infrastructure** - leverages `window.currentUser` from Layout.astro
- **Fail-safe approach** - defaults to hiding Admin menu if user data unavailable

---

## Task Generation Rules Applied

1. **From Contracts** (`contracts/sidebar-behavior.contract.md`):
   - ✅ Test scenarios → T001, T002, T003 (E2E tests)
   - ✅ Component contracts → T004, T005 (implementations)

2. **From Data Model** (`data-model.md`):
   - ✅ No new entities (using existing User with roles)
   - ✅ No model creation tasks needed

3. **From User Stories** (`spec.md` acceptance scenarios):
   - ✅ 5 acceptance scenarios → covered by T001-T003 tests
   - ✅ Quickstart validation → T007 (manual QA)

4. **Ordering**:
   - ✅ Tests → Implementation → Validation
   - ✅ TDD enforced (T001-T003 before T004-T005)
   - ✅ Parallel where possible ([P] markers)

---

## Validation Checklist
*GATE: Checked before marking tasks complete*

- [x] All contract scenarios have corresponding tests (3 scenarios → T001-T003)
- [x] All components have implementation tasks (2 components → T004-T005)
- [x] All tests come before implementation (T001-T003 before T004-T005)
- [x] Parallel tasks are truly independent (different files)
- [x] Each task specifies exact file path
- [x] No task modifies same file as another [P] task
- [x] TDD workflow enforced (tests must fail first)

---

## Success Criteria

Feature is complete when:
- [x] All E2E tests pass (T001-T003 via T006)
- [x] Manual QA checklist complete (T007)
- [x] No console errors in browser
- [x] Admin users see Admin menu
- [x] Regular users don't see Admin menu
- [x] Visibility persists across page navigation
- [x] Both Sidebar.astro and Sidebar.tsx updated

---

## Estimated Timeline

- **T001-T003**: 30-45 minutes (writing 3 E2E tests)
- **T004**: 20-30 minutes (Sidebar.astro implementation)
- **T005**: 20-30 minutes (Sidebar.tsx implementation)
- **T006**: 5-10 minutes (run tests, debug if needed)
- **T007**: 15-20 minutes (manual QA from quickstart.md)
- **T008**: 5-10 minutes (optional docs update)

**Total**: ~2-2.5 hours

---

## Reference Documents

- **Spec**: `specs/001-make-the-admin/spec.md`
- **Plan**: `specs/001-make-the-admin/plan.md`
- **Research**: `specs/001-make-the-admin/research.md`
- **Data Model**: `specs/001-make-the-admin/data-model.md`
- **Contract**: `specs/001-make-the-admin/contracts/sidebar-behavior.contract.md`
- **Quickstart**: `specs/001-make-the-admin/quickstart.md`
- **Test Reference**: `src/frontend/tests/admin-ui-access.spec.ts`
- **Auth Utils**: `src/frontend/src/utils/auth.ts`
