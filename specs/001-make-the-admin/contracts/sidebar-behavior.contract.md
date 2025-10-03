# UI Behavior Contract: Sidebar Admin Menu Visibility

**Component**: Sidebar (Astro and React implementations)
**Date**: 2025-10-02
**Version**: 1.0.0

## Contract Purpose

This contract defines the expected behavior of the Sidebar component regarding the visibility of the Admin menu item based on the authenticated user's roles.

## Input Contract

### Required Global State
```typescript
interface WindowWithUser extends Window {
  currentUser: User | null;
}

interface User {
  id: number;
  username: string;
  email: string;
  roles: string[];  // Array of role strings, e.g., ["ADMIN", "USER"]
}
```

### Input Sources
1. **Primary**: `window.currentUser` - Set by Layout.astro after successful authentication
2. **Event**: `userLoaded` - Custom event dispatched when user data is loaded
3. **Storage**: `localStorage.user` - Backup source (JSON string)

### Input Validation Rules
- `window.currentUser` may be `null` (unauthenticated or auth pending)
- `window.currentUser.roles` may be `undefined` or `[]` (malformed data)
- Role strings are case-sensitive: `"ADMIN"` not `"admin"`
- Multiple roles possible: `["ADMIN", "USER"]`

## Output Contract

### Sidebar Menu Items

**All Users (MUST be visible)**:
- Dashboard (`/`)
- Requirement Management (`/requirements`)
- Standard Management (`/standards`)
- Norm Management (`/norms`)
- Asset Management (`/assets`)
- UseCase Management (`/usecases`)
- Risk Management (collapsible section)
  - Risk Management Overview (`/risks`)
  - Risk Assessment (`/riskassessment`)
  - Reports (`/reports`)
- Import/Export (`/import-export`)
- About (`/about`)

**Admin Users Only (conditional)**:
- Admin (`/admin`) - **MUST be visible ONLY if user has ROLE_ADMIN**

### Visibility Rules

#### Rule 1: Show Admin Menu (FR-001)
```
GIVEN window.currentUser is not null
  AND window.currentUser.roles is defined
  AND window.currentUser.roles.includes('ADMIN') === true
WHEN Sidebar renders
THEN Admin menu item MUST be visible
  AND Admin menu item MUST be clickable
  AND Admin menu item MUST navigate to /admin on click
```

#### Rule 2: Hide Admin Menu (FR-002)
```
GIVEN one or more of the following:
  - window.currentUser is null, OR
  - window.currentUser.roles is undefined, OR
  - window.currentUser.roles is empty array [], OR
  - window.currentUser.roles.includes('ADMIN') === false
WHEN Sidebar renders
THEN Admin menu item MUST NOT be visible
  AND Admin menu item MUST NOT be in the DOM
  AND Admin menu item MUST NOT be accessible via keyboard navigation
```

#### Rule 3: Dynamic Updates (FR-004)
```
GIVEN Sidebar is already rendered
WHEN 'userLoaded' event is dispatched
  OR navigation occurs (component re-mount)
THEN Sidebar MUST re-evaluate visibility rules
  AND Admin menu visibility MUST update accordingly
```

## Behavior Contracts by Component

### Sidebar.astro Contract

**Implementation Pattern**:
```astro
---
const sidebarItems = [
  { text: "Dashboard", href: "/", icon: "bi-house-door-fill" },
  // ... other items ...
  { text: "Admin", href: "/admin", icon: "bi-gear-fill" },
];
---

<script>
  function updateSidebarVisibility() {
    const user = (window as any).currentUser;
    const hasAdminRole = user?.roles?.includes('ADMIN') || false;

    // Filter or toggle visibility
    const adminLink = document.querySelector('a[href="/admin"]');
    if (adminLink) {
      adminLink.closest('li').style.display = hasAdminRole ? 'block' : 'none';
    }
  }

  // Run on load and on user data updates
  window.addEventListener('userLoaded', updateSidebarVisibility);
  updateSidebarVisibility();
</script>
```

**Guaranteed Behavior**:
- Executes on initial page load
- Re-executes on `userLoaded` event
- Admin menu item hidden by default if user data not available
- No console errors if `window.currentUser` is null

### Sidebar.tsx Contract

**Implementation Pattern**:
```typescript
import React, { useState, useEffect } from 'react';

const Sidebar = () => {
  const [isAdmin, setIsAdmin] = useState(false);

  useEffect(() => {
    function checkAdminRole() {
      const user = (window as any).currentUser;
      const hasAdmin = user?.roles?.includes('ADMIN') || false;
      setIsAdmin(hasAdmin);
    }

    checkAdminRole();
    window.addEventListener('userLoaded', checkAdminRole);

    return () => window.removeEventListener('userLoaded', checkAdminRole);
  }, []);

  return (
    <nav id="sidebar">
      <ul className="list-unstyled components p-2">
        {/* ... other menu items ... */}

        {isAdmin && (
          <li>
            <a href="/admin">
              <i className="bi bi-speedometer2 me-2"></i> Admin
            </a>
          </li>
        )}
      </ul>
    </nav>
  );
};
```

**Guaranteed Behavior**:
- `isAdmin` state initializes to `false` (hidden by default)
- Checks role on component mount
- Re-checks role on `userLoaded` event
- Cleans up event listener on unmount
- No rendering errors if `window.currentUser` is null

## Edge Cases & Error Handling

### Edge Case 1: User Data Not Loaded Yet
**Scenario**: Page loads before `/api/auth/status` completes
**Expected**: Admin menu hidden (default state)
**Actual**: Shows when `userLoaded` event fires (if admin)

### Edge Case 2: User Object Missing Roles
**Scenario**: `window.currentUser` exists but `roles` is undefined
**Expected**: Admin menu hidden (fail-safe)
**Validation**: `user?.roles?.includes('ADMIN') || false` → returns `false`

### Edge Case 3: Role Change Mid-Session
**Scenario**: Admin role revoked while user is active
**Expected**: Admin menu hidden on next navigation
**Implementation**: Each navigation re-mounts Sidebar → re-checks roles

### Edge Case 4: Malformed Role Data
**Scenario**: Roles is not an array or contains non-strings
**Expected**: Admin menu hidden (fail-safe)
**Validation**: `.includes()` on non-array returns false, optional chaining protects

### Edge Case 5: Multiple Roles Including Admin
**Scenario**: User has `["ADMIN", "USER"]`
**Expected**: Admin menu visible
**Validation**: `includes('ADMIN')` returns `true`

### Edge Case 6: Case Sensitivity
**Scenario**: Backend sends `"admin"` instead of `"ADMIN"`
**Expected**: Admin menu hidden (case mismatch)
**Note**: Backend contract guarantees uppercase (User.kt enum serialization)

## Non-Functional Contracts

### Performance
- **Rendering**: Admin menu visibility check MUST complete in <1ms
- **No API Calls**: Visibility logic MUST NOT make HTTP requests
- **Memory**: Event listeners MUST be cleaned up on component unmount (React)

### Accessibility
- **Keyboard Navigation**: Hidden admin menu MUST NOT be reachable via Tab key
- **Screen Readers**: Hidden admin menu MUST have `display: none` or not be in DOM
- **Focus Management**: No focus traps or unexpected focus jumps

### Security
- **Frontend Only**: This is UI visibility control, NOT authorization
- **Backend Enforcement**: Backend MUST enforce authorization on `/admin/*` routes
- **No Token Exposure**: Contract MUST NOT expose JWT tokens or password hashes

## Testing Contract

### Required Test Scenarios

#### Test 1: Admin User Sees Admin Menu
```typescript
test('admin user sees admin menu item', async ({ page }) => {
  // Preconditions
  await loginAsAdmin(page);
  await navigateToDashboard(page);

  // Assertions
  await expect(page.locator('a[href="/admin"]')).toBeVisible();
  await expect(page.locator('a[href="/admin"]')).toHaveText(/Admin/);
});
```

#### Test 2: Non-Admin User Doesn't See Admin Menu
```typescript
test('regular user does not see admin menu item', async ({ page }) => {
  // Preconditions
  await loginAsRegularUser(page);
  await navigateToDashboard(page);

  // Assertions
  await expect(page.locator('a[href="/admin"]')).not.toBeVisible();
  // OR
  await expect(page.locator('a[href="/admin"]')).toHaveCount(0);
});
```

#### Test 3: Visibility Updates on Navigation
```typescript
test('admin menu visibility persists across navigation', async ({ page }) => {
  // Preconditions
  await loginAsAdmin(page);

  // Navigate to different pages
  await page.goto('http://localhost:4321/requirements');
  await expect(page.locator('a[href="/admin"]')).toBeVisible();

  await page.goto('http://localhost:4321/assets');
  await expect(page.locator('a[href="/admin"]')).toBeVisible();
});
```

#### Test 4: Unauthenticated State
```typescript
test('unauthenticated user sees no admin menu', async ({ page }) => {
  // Clear auth state
  await page.evaluate(() => {
    localStorage.removeItem('authToken');
    localStorage.removeItem('user');
  });

  // Navigate (will redirect to login, but test sidebar if shown)
  await page.goto('http://localhost:4321/login');

  // Assertions - sidebar not shown on login page, so skip or verify redirect
  await expect(page).toHaveURL(/.*\/login/);
});
```

### Test Data Requirements
- **Admin User**: Username `adminuser`, password `password`, roles `["ADMIN", "USER"]`
- **Regular User**: Username `regularuser`, password `password`, roles `["USER"]`

### Test Environment
- **Backend**: Running at `http://localhost:8080`
- **Frontend**: Running at `http://localhost:4321`
- **Database**: Test database with seeded users

## Acceptance Criteria

✅ **AC-001**: Admin user with ROLE_ADMIN sees Admin menu item in both Sidebar.astro and Sidebar.tsx
✅ **AC-002**: Regular user without ROLE_ADMIN does NOT see Admin menu item
✅ **AC-003**: Admin menu visibility persists across page navigation
✅ **AC-004**: All other menu items remain visible for all users
✅ **AC-005**: No JavaScript errors in console when user data is null/undefined
✅ **AC-006**: Admin menu item is fully functional (clickable, navigates to /admin)
✅ **AC-007**: E2E tests pass for all scenarios
✅ **AC-008**: Manual testing with quickstart.md passes

## Breaking Changes

None - this is a new feature with no existing behavior to break.

## Version History

- **1.0.0** (2025-10-02): Initial contract for admin sidebar visibility control
