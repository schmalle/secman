# Quickstart: Admin Sidebar Visibility Control

**Feature**: Role-based visibility for Admin sidebar menu item
**Date**: 2025-10-02
**Estimated Time**: 15 minutes for manual testing

## Prerequisites

### Backend Running
```bash
cd /Users/flake/sources/misc/secman/src/backendng
./gradlew run
# Backend should be running at http://localhost:8080
```

### Frontend Running
```bash
cd /Users/flake/sources/misc/secman/src/frontend
npm run dev
# Frontend should be running at http://localhost:4321
```

### Test Users Available
- **Admin User**: `adminuser` / `password` (has ROLE_ADMIN)
- **Regular User**: `regularuser` / `password` (has ROLE_USER only)

## Quick Validation (5 minutes)

### Test 1: Admin User Sees Admin Menu ✓

1. **Navigate to login page**
   ```
   Open http://localhost:4321/login in browser
   ```

2. **Login as admin**
   - Username: `adminuser`
   - Password: `password`
   - Click "Login"

3. **Verify admin menu visible**
   - Should redirect to dashboard (`/dashboard` or `/`)
   - Look at sidebar on the left
   - Admin menu item should be visible (with gear icon ⚙️)
   - Should be near the bottom of the main menu items

4. **Verify admin menu works**
   - Click on "Admin" menu item
   - Should navigate to `/admin`
   - Should load admin page without errors

5. **Verify visibility persists**
   - Navigate to Requirements (`/requirements`)
   - Admin menu should still be visible
   - Navigate to Assets (`/assets`)
   - Admin menu should still be visible

**Expected Result**: ✅ Admin menu item visible and functional

---

### Test 2: Regular User Doesn't See Admin Menu ✓

1. **Logout from admin account**
   - Click "Logout" button in sidebar (bottom section)
   - Should redirect to `/login`

2. **Login as regular user**
   - Username: `regularuser`
   - Password: `password`
   - Click "Login"

3. **Verify admin menu NOT visible**
   - Should redirect to dashboard
   - Look at sidebar on the left
   - Admin menu item should NOT be present
   - Other menu items (Dashboard, Requirements, etc.) should be visible

4. **Verify across different pages**
   - Navigate to Requirements (`/requirements`)
   - Admin menu should NOT appear
   - Navigate to Standards (`/standards`)
   - Admin menu should NOT appear

5. **Attempt direct navigation** (optional security check)
   - Type `/admin` in URL bar
   - Backend should reject (401/403) or redirect to error page
   - Note: This tests backend security, not sidebar visibility

**Expected Result**: ✅ Admin menu item NOT visible for regular user

---

### Test 3: Edge Case - Fresh Load ✓

1. **While logged in as admin**
   - Open browser DevTools (F12)
   - Go to Console tab
   - Hard refresh page (Ctrl+Shift+R or Cmd+Shift+R)

2. **Verify no console errors**
   - Check console for JavaScript errors
   - Admin menu should appear after page loads
   - No "Cannot read property 'roles' of null" errors

3. **Clear localStorage** (simulate auth issues)
   ```javascript
   // In browser console
   localStorage.clear();
   location.reload();
   ```

4. **Verify graceful handling**
   - Should redirect to login page (no auth token)
   - No JavaScript errors during redirect
   - Sidebar may not render on login page (expected)

**Expected Result**: ✅ No errors, graceful handling of missing user data

---

## Automated Testing (10 minutes)

### Run E2E Tests

```bash
cd /Users/flake/sources/misc/secman/src/frontend

# Run all E2E tests including new admin sidebar tests
npm run test

# Or run only the admin sidebar visibility tests
npx playwright test admin-sidebar-visibility.spec.ts

# Run with UI for debugging
npx playwright test admin-sidebar-visibility.spec.ts --ui

# Run in headed mode (see browser)
npx playwright test admin-sidebar-visibility.spec.ts --headed
```

### Expected Test Output

```
Running 3 tests using 1 worker

  ✓ [chromium] › admin-sidebar-visibility.spec.ts:5:1 › Admin Sidebar Visibility › admin user sees admin menu item (1.2s)
  ✓ [chromium] › admin-sidebar-visibility.spec.ts:18:1 › Admin Sidebar Visibility › regular user does not see admin menu item (1.1s)
  ✓ [chromium] › admin-sidebar-visibility.spec.ts:31:1 › Admin Sidebar Visibility › admin menu visibility persists across navigation (1.5s)

  3 passed (4.8s)
```

### If Tests Fail

1. **Check backend is running**: `curl http://localhost:8080/api/health` (or similar)
2. **Check frontend is running**: `curl http://localhost:4321`
3. **Check test users exist**: Verify `adminuser` and `regularuser` in database
4. **Check browser console**: Run tests in headed mode to see errors
5. **Review test output**: Playwright shows screenshots/traces on failure

---

## Manual Testing Checklist

### Functional Requirements Validation

- [ ] **FR-001**: Admin user sees Admin menu item ✓
- [ ] **FR-002**: Regular user does NOT see Admin menu item ✓
- [ ] **FR-003**: Sidebar checks user roles correctly ✓
- [ ] **FR-004**: Menu visibility updates on navigation ✓
- [ ] **FR-005**: Other menu items visible for all users ✓
- [ ] **FR-006**: Graceful handling of missing user data ✓

### Acceptance Scenarios Validation

- [ ] **AS-001**: Admin viewing any page → Admin menu visible ✓
- [ ] **AS-002**: Regular user viewing any page → Admin menu hidden ✓
- [ ] **AS-003**: Unauthenticated user → No sidebar or redirects to login ✓
- [ ] **AS-004**: Admin clicks Admin menu → Navigates successfully ✓
- [ ] **AS-005**: Role change → Visibility updates on navigation ✓

### Edge Cases Validation

- [ ] **Edge-001**: Fresh page load → No errors, menu appears correctly ✓
- [ ] **Edge-002**: Missing user data → Menu hidden, no errors ✓
- [ ] **Edge-003**: Direct URL navigation → Backend enforces authorization ✓

---

## Browser Testing Matrix

### Recommended Browsers

| Browser | Version | Status |
|---------|---------|--------|
| Chrome | Latest | ✓ Primary |
| Firefox | Latest | ✓ Recommended |
| Safari | Latest | ✓ Optional |
| Edge | Latest | ✓ Optional |

### Responsive Testing

1. **Desktop** (1920x1080): Full sidebar visible
2. **Tablet** (768x1024): Sidebar may collapse (Bootstrap responsive)
3. **Mobile** (375x667): Sidebar hidden or hamburger menu

**Note**: Admin menu visibility logic should work regardless of screen size.

---

## Troubleshooting

### Issue: Admin menu not appearing for admin user

**Check**:
1. User actually has ROLE_ADMIN in database
2. `/api/auth/status` returns `roles: ["ADMIN", ...]`
3. `window.currentUser` is set in browser console
4. `userLoaded` event is firing (check in DevTools → Elements → Event Listeners)

**Debug**:
```javascript
// In browser console (after login)
console.log(window.currentUser);
console.log(window.currentUser?.roles);
console.log(window.currentUser?.roles?.includes('ADMIN'));
```

### Issue: Admin menu visible for regular user

**Check**:
1. User DOESN'T have ROLE_ADMIN in database
2. Clear browser cache and localStorage
3. Hard refresh (Ctrl+Shift+R)
4. Check implementation didn't accidentally invert logic

**Debug**:
```javascript
// Should return false for regular user
console.log(window.currentUser?.roles?.includes('ADMIN'));
```

### Issue: JavaScript errors on load

**Check**:
1. Syntax errors in Sidebar.astro or Sidebar.tsx
2. Missing null checks for `window.currentUser`
3. Event listener cleanup in React component

**Debug**:
Open DevTools → Console → Check for errors

### Issue: Tests failing

**Check**:
1. Backend running on port 8080
2. Frontend running on port 4321
3. Test database has correct users
4. Playwright installed (`npx playwright install`)

**Debug**:
```bash
# Run with debug mode
PWDEBUG=1 npx playwright test admin-sidebar-visibility.spec.ts
```

---

## Verification Checklist

Before marking feature as complete, verify:

- [x] All automated E2E tests passing
- [x] Manual testing checklist complete
- [x] No console errors in browser
- [x] Admin user sees Admin menu
- [x] Regular user doesn't see Admin menu
- [x] Menu visibility persists across navigation
- [x] Other menu items unaffected
- [x] Edge cases handled gracefully
- [x] Works in Chrome (primary browser)
- [x] Documentation updated (if needed)

---

## Success Criteria

✅ **Feature is complete when**:
1. All E2E tests pass (3/3)
2. Manual testing checklist passes (100%)
3. No console errors in browser
4. Admin menu behaves according to contract
5. Code reviewed and approved
6. Merged to main branch

---

## Next Steps

After quickstart validation:

1. **If all tests pass**: Feature ready for code review → merge
2. **If tests fail**: Debug using troubleshooting section → fix → re-test
3. **If edge cases found**: Document in contracts → add tests → fix → re-test

---

## Support

- **Feature Spec**: `specs/001-make-the-admin/spec.md`
- **Implementation Plan**: `specs/001-make-the-admin/plan.md`
- **Contracts**: `specs/001-make-the-admin/contracts/sidebar-behavior.contract.md`
- **Test File**: `src/frontend/tests/admin-sidebar-visibility.spec.ts`
- **Source Files**:
  - `src/frontend/src/components/Sidebar.astro`
  - `src/frontend/src/components/Sidebar.tsx`
