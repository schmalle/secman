# Research: Admin Sidebar Visibility Control

**Feature**: Role-based visibility for Admin sidebar menu item
**Date**: 2025-10-02
**Status**: Complete

## Research Questions

### 1. How is user authentication currently handled?

**Decision**: Use existing JWT-based authentication with localStorage
**Rationale**:
- JWT tokens stored in `localStorage.authToken` (auth.ts:14)
- User data stored in `localStorage.user` as JSON (auth.ts:21)
- Global `window.currentUser` variable set by Layout.astro (Layout.astro:123)
- `userLoaded` event dispatched when auth check completes (Layout.astro:126)

**Alternatives considered**:
- ❌ Server-side rendering with cookies - Incompatible with existing client-side auth
- ❌ New API call per render - Unnecessary performance overhead
- ✅ Leverage existing `window.currentUser` - Zero overhead, already available

### 2. How are user roles structured and accessed?

**Decision**: Use `User.roles` array from `window.currentUser`
**Rationale**:
- Backend: `User.roles: MutableSet<Role>` with enum values `ADMIN` and `USER` (User.kt:36)
- Frontend: User interface defines `roles: string[]` (auth.ts:7)
- Helper functions available: `hasRole(role)` and `isAdmin()` (auth.ts:42-52)
- Roles sent as strings in JSON (e.g., `["ADMIN", "USER"]`)

**Alternatives considered**:
- ❌ Call `/api/auth/status` on every render - Performance issue
- ❌ Parse JWT token client-side - Security risk, unnecessary complexity
- ✅ Read from `window.currentUser.roles` - Already populated, instant access

### 3. What are the patterns for conditional rendering in Astro vs React?

**Decision**: Different approaches for each component type
**Rationale**:

**Astro (Sidebar.astro)**:
- Server-side: No user data available during SSR (auth is client-side)
- Client-side script block: Access `window.currentUser` after page load
- Pattern: Filter `sidebarItems` array based on role check
- Reference: Commented-out code at Sidebar.astro:28-33 shows intended pattern

**React (Sidebar.tsx)**:
- Use `useState` + `useEffect` to listen for `userLoaded` event
- Access `window.currentUser` in effect hook
- Conditionally render based on state
- Pattern: Similar to other React components accessing global user

**Alternatives considered**:
- ❌ Pass roles as props through component tree - Breaks existing structure
- ❌ Create React Context for auth - Over-engineering for single check
- ✅ Use global `window.currentUser` - Matches existing patterns

### 4. What testing approach should be used?

**Decision**: Playwright E2E tests following existing patterns
**Rationale**:
- Existing test: `admin-ui-access.spec.ts` shows admin user testing pattern
- Test helpers available in `test-helpers.ts`
- E2E tests verify full auth flow including JWT tokens, localStorage, and UI rendering
- Pattern: Login as different users → Navigate → Assert visibility

**Test scenarios from spec**:
1. Admin user sees Admin menu item
2. Non-admin user doesn't see Admin menu item
3. Role change updates visibility (navigate to trigger re-render)

**Alternatives considered**:
- ❌ Unit tests for components - Can't verify full auth integration
- ❌ Visual regression tests - Overkill for simple visibility check
- ✅ E2E tests with Playwright - Complete coverage, matches existing patterns

### 5. How to handle edge cases?

**Decision**: Fail-safe approach - hide Admin menu by default
**Rationale**:

**Edge cases identified**:
- User data not loaded yet → Hide (before `userLoaded` event)
- `window.currentUser` is null → Hide (auth failed or logged out)
- `roles` array is empty or undefined → Hide (malformed data)
- User role changed mid-session → Update on next navigation (re-render)

**Implementation approach**:
- Initialize visibility state to `false` (hidden by default)
- Only show when `window.currentUser?.roles?.includes('ADMIN')` is truthy
- Re-check on each render/navigation

**Alternatives considered**:
- ❌ Show admin menu until proven non-admin - Security risk (brief exposure)
- ❌ Show loading state - Unnecessary complexity for instant check
- ✅ Hide by default - Secure, simple, follows least-privilege principle

## Technology Stack Summary

### Frontend
- **Framework**: Astro 5.13.5 (SSG/SSR) + React 19.1.1 (islands)
- **Language**: TypeScript
- **Styling**: Bootstrap 5.3.8 + Bootstrap Icons
- **State**: Global `window.currentUser`, localStorage
- **Testing**: Playwright E2E tests

### Backend
- **Framework**: Micronaut 4.4.3
- **Language**: Kotlin 2.1.0
- **Auth**: Micronaut Security JWT
- **Database**: MariaDB (for user/roles)

### Auth Flow (existing)
1. User logs in → Backend validates → Returns JWT token
2. Frontend stores token in `localStorage.authToken`
3. Layout.astro calls `/api/auth/status` with token
4. Backend returns user object with roles
5. Layout.astro sets `window.currentUser` and dispatches `userLoaded` event
6. Components access `window.currentUser.roles` for authorization

## Implementation Patterns

### Pattern 1: Astro Component (Sidebar.astro)
```javascript
// Client-side script block
<script>
  // Wait for userLoaded event or check immediately
  function updateAdminVisibility() {
    const user = window.currentUser;
    const hasAdmin = user?.roles?.includes('ADMIN');
    // Filter sidebarItems or hide/show admin link
  }

  window.addEventListener('userLoaded', updateAdminVisibility);
  updateAdminVisibility(); // Check on load
</script>
```

### Pattern 2: React Component (Sidebar.tsx)
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

// Render: {isAdmin && <AdminMenuItem />}
```

### Pattern 3: E2E Test
```typescript
test('admin user sees admin menu', async ({ page }) => {
  await page.goto('http://localhost:4321/login');
  await page.fill('input[type="text"]', 'adminuser');
  await page.fill('input[type="password"]', 'password');
  await page.click('button[type="submit"]');

  await expect(page).toHaveURL(/.*\/dashboard/);
  await expect(page.locator('a[href="/admin"]')).toBeVisible();
});
```

## Dependencies & Files

### Files to Modify
- `src/frontend/src/components/Sidebar.astro` - Add role check (line 17, 44-59)
- `src/frontend/src/components/Sidebar.tsx` - Add role check with React hooks (line 127-131)

### Files to Create
- `src/frontend/tests/admin-sidebar-visibility.spec.ts` - E2E tests

### Files to Reference
- `src/frontend/src/utils/auth.ts` - Auth utility functions
- `src/frontend/src/layouts/Layout.astro` - User loading mechanism
- `src/frontend/tests/admin-ui-access.spec.ts` - Test pattern reference
- `src/backendng/src/main/kotlin/com/secman/domain/User.kt` - Role enum

## Performance Considerations

- **Zero API calls**: Role check uses cached `window.currentUser`
- **Instant rendering**: No async operations needed
- **Minimal re-renders**: Only when user loads or navigates
- **No layout shift**: Admin menu hidden from initial render (fail-safe)

## Security Considerations

- **Frontend visibility only**: This controls UI display, not access
- **Backend enforcement**: Admin routes already protected server-side
- **Fail-safe approach**: Defaults to hiding admin menu on any error
- **No sensitive data exposure**: Roles are already in client-side user object

## Open Questions

None - all research complete.

## References

- Astro docs: https://docs.astro.build/en/core-concepts/astro-components/
- React hooks: https://react.dev/reference/react/hooks
- Playwright testing: https://playwright.dev/docs/intro
- Existing codebase patterns in `src/frontend/`
