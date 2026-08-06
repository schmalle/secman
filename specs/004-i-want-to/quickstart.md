# Quickstart: VULN Role & Vulnerability Management UI

**Feature**: 004-i-want-to
**Date**: 2025-10-03
**Purpose**: Manual validation steps for Feature 004 implementation

## Prerequisites

- Backend running on http://localhost:8080
- Frontend running on http://localhost:4321
- MariaDB running with test data
- At least one asset with vulnerabilities imported (from Feature 003)

---

## Test Scenarios

### Scenario 1: Create User with VULN Role

**Objective**: Verify new VULN role can be assigned to users

**Steps**:
1. Login as admin user (username: `admin`, password: configured in .env)
2. Navigate to User Management page: http://localhost:4321/admin/user-management
3. Click "Create New User"
4. Fill in form:
   - Username: `vuln-user-test`
   - Email: `vuln@test.com`
   - Password: `Test1234!`
   - Roles: Check **VULN** checkbox
5. Click "Create User"

**Expected Result**:
- ✅ User created successfully
- ✅ New user appears in user list with "VULN" badge
- ✅ No errors in browser console

**Actual Result**: ________________

---

### Scenario 2: Access Vuln Management as VULN User

**Objective**: Verify VULN role grants access to vulnerability pages

**Steps**:
1. Logout (if logged in as admin)
2. Login with new VULN user credentials (`vuln-user-test` / `Test1234!`)
3. Check sidebar navigation for "Vuln Management" menu item
4. Click "Vuln Management" to expand submenu
5. Verify submenu shows "Vulns" and "Exceptions" links
6. Click "Vulns" link

**Expected Result**:
- ✅ "Vuln Management" menu visible in sidebar
- ✅ Submenu expands with "Vulns" and "Exceptions" options
- ✅ Navigates to http://localhost:4321/vulnerabilities/current
- ✅ Page loads without 403 Forbidden error
- ✅ Vulnerability table displays with data

**Actual Result**: ________________

---

### Scenario 3: Normal User Cannot Access Vuln Pages

**Objective**: Verify RBAC enforcement - normal users are denied access

**Steps**:
1. Create a normal user (if not exists):
   - Login as admin
   - Create user with username `normal-user`, only USER role checked
2. Logout, login as `normal-user`
3. Check sidebar navigation
4. Attempt to access http://localhost:4321/vulnerabilities/current directly via URL

**Expected Result**:
- ✅ "Vuln Management" menu NOT visible in sidebar
- ✅ Direct URL access shows 403 Forbidden error or redirects to unauthorized page
- ✅ Backend returns 403 response (check Network tab in DevTools)

**Actual Result**: ________________

---

### Scenario 4: View Current Vulnerabilities

**Objective**: Verify current vulnerabilities display logic (latest scan per asset)

**Steps**:
1. Login as VULN user or admin
2. Navigate to http://localhost:4321/vulnerabilities/current
3. Observe vulnerability table columns:
   - System (asset name)
   - IP Address
   - Vulnerability ID (CVE)
   - Severity
   - Product/Version
   - Days Open
   - Scan Date
   - Exception Status
4. Verify filtering controls present:
   - Severity dropdown (All, Critical, High, Medium, Low)
   - System dropdown (All systems + list of asset names)
   - Exception Status dropdown (All, Excepted, Not Excepted)
5. Verify sorting works by clicking column headers

**Expected Result**:
- ✅ Table displays vulnerabilities
- ✅ Only latest scan per asset shown (verify by checking scan dates for same asset)
- ✅ Filtering dropdowns functional
- ✅ Sorting by columns works
- ✅ No historical duplicates from old scans

**Test Data Verification** (via backend API):
```bash
# Get all vulnerabilities for asset ID 1
curl -H "Authorization: Bearer <JWT>" \
  http://localhost:8080/api/assets/1/vulnerabilities

# Should see multiple scan dates - frontend should only show latest
```

**Actual Result**: ________________

---

### Scenario 5: Create IP-Based Exception

**Objective**: Verify IP-based exception creation and vulnerability matching

**Steps**:
1. Navigate to http://localhost:4321/vulnerabilities/exceptions
2. Click "Create Exception" button
3. Fill in form:
   - Type: **IP**
   - Target Value: `192.168.1.10` (use an IP from vulnerabilities list)
   - Expiration Date: *Leave empty for permanent*
   - Reason: `Test system - accepted risk`
4. Click "Save"
5. Verify exception appears in exceptions table
6. Navigate back to http://localhost:4321/vulnerabilities/current
7. Find vulnerabilities for IP `192.168.1.10`
8. Verify "Exception" badge/indicator appears on those rows

**Expected Result**:
- ✅ Exception created successfully
- ✅ Exception appears in exceptions table with "Active" status
- ✅ Expiration Date shows "Never" or "Permanent"
- ✅ Vulnerabilities for target IP show exception indicator
- ✅ Exception reason visible in tooltip or modal when clicking indicator

**Actual Result**: ________________

---

### Scenario 6: Create Product-Based Exception with Expiration

**Objective**: Verify product-based exception with time limit

**Steps**:
1. Navigate to http://localhost:4321/vulnerabilities/exceptions
2. Click "Create Exception"
3. Fill in form:
   - Type: **PRODUCT**
   - Target Value: `OpenSSH 7.4` (use a product from vulnerabilities list)
   - Expiration Date: `2025-12-31 23:59:59`
   - Reason: `Upgrade scheduled for December 2025`
4. Click "Save"
5. Verify exception appears with expiration date
6. Navigate to current vulnerabilities page
7. Find vulnerabilities with product containing "OpenSSH 7.4"
8. Verify exception indicator appears

**Expected Result**:
- ✅ Exception created with future expiration date
- ✅ Exception shows as "Active" (expiration not yet reached)
- ✅ Vulnerabilities matching product pattern show exception indicator
- ✅ Multiple vulnerabilities across different IPs matched if they have same product

**Actual Result**: ________________

---

### Scenario 7: Edit Existing Exception

**Objective**: Verify all exception fields can be edited

**Steps**:
1. Navigate to http://localhost:4321/vulnerabilities/exceptions
2. Find the IP-based exception created in Scenario 5
3. Click "Edit" button
4. Modify fields:
   - Type: Change to **PRODUCT**
   - Target Value: `Apache 2.4`
   - Expiration Date: Set to `2026-06-30 23:59:59`
   - Reason: `Updated reason - migrating to new server`
5. Click "Save"
6. Verify changes reflected in table

**Expected Result**:
- ✅ All fields successfully updated
- ✅ Exception still shows as "Active"
- ✅ Old IP-based vulnerabilities no longer show exception indicator
- ✅ New product-based vulnerabilities now show exception indicator
- ✅ Updated reason and expiration date visible

**Actual Result**: ________________

---

### Scenario 8: Delete Exception

**Objective**: Verify exception deletion

**Steps**:
1. Navigate to http://localhost:4321/vulnerabilities/exceptions
2. Find an exception to delete
3. Click "Delete" button
4. Confirm deletion in modal dialog
5. Verify exception removed from table
6. Navigate to current vulnerabilities page
7. Verify vulnerabilities previously covered now show NO exception indicator

**Expected Result**:
- ✅ Confirmation dialog appears before deletion
- ✅ Exception deleted successfully
- ✅ No longer appears in exceptions table
- ✅ Vulnerabilities no longer show exception indicator
- ✅ Backend DELETE API returns 204 No Content

**Actual Result**: ________________

---

### Scenario 9: Filter Current Vulnerabilities

**Objective**: Verify filtering functionality on Vulns page

**Steps**:
1. Navigate to http://localhost:4321/vulnerabilities/current
2. Test Severity Filter:
   - Select "Critical" from severity dropdown
   - Verify only Critical vulnerabilities displayed
   - Select "All Severities" to reset
3. Test System Filter:
   - Select a specific asset name from system dropdown
   - Verify only vulnerabilities for that system displayed
   - Select "All Systems" to reset
4. Test Exception Status Filter:
   - Select "Excepted" from exception status dropdown
   - Verify only vulnerabilities with exception indicator displayed
   - Select "Not Excepted"
   - Verify only vulnerabilities WITHOUT exception indicator displayed
   - Select "All" to reset
5. Test Combined Filters:
   - Select "High" severity + specific system + "Not Excepted"
   - Verify results match ALL criteria

**Expected Result**:
- ✅ All filters work independently
- ✅ Combined filters work with AND logic
- ✅ Resetting filters shows all vulnerabilities again
- ✅ No JavaScript errors in console

**Actual Result**: ________________

---

### Scenario 10: Verify Admin Access

**Objective**: Confirm ADMIN role also has full access

**Steps**:
1. Login as admin user
2. Verify "Vuln Management" menu visible
3. Navigate to http://localhost:4321/vulnerabilities/current
4. Verify full access (no 403 error)
5. Navigate to http://localhost:4321/vulnerabilities/exceptions
6. Verify can create, edit, delete exceptions

**Expected Result**:
- ✅ Admin has identical access to VULN role
- ✅ No differences in functionality between ADMIN and VULN users
- ✅ Backend accepts both roles on all endpoints

**Actual Result**: ________________

---

## API Testing (Optional - Backend Contract Verification)

### Test GET /api/vulnerabilities/current

```bash
# Login first to get JWT
TOKEN=$(curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"vuln-user-test","password":"Test1234!"}' \
  | jq -r '.access_token')

# Get current vulnerabilities
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/vulnerabilities/current | jq

# Expected: Array of vulnerabilities with hasException field
```

### Test POST /api/vulnerability-exceptions

```bash
curl -X POST http://localhost:8080/api/vulnerability-exceptions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "exceptionType": "IP",
    "targetValue": "192.168.1.20",
    "expirationDate": null,
    "reason": "API test exception"
  }' | jq

# Expected: 201 Created with exception DTO
```

### Test Authorization (403 for Normal User)

```bash
# Login as normal user
TOKEN_NORMAL=$(curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"normal-user","password":"Test1234!"}' \
  | jq -r '.access_token')

# Attempt access
curl -H "Authorization: Bearer $TOKEN_NORMAL" \
  http://localhost:8080/api/vulnerabilities/current

# Expected: 403 Forbidden
```

---

## Success Criteria

All scenarios must pass with ✅ marks in "Actual Result" fields.

**Feature is considered complete when**:
1. All test scenarios pass without errors
2. No console errors in browser DevTools
3. No 500 errors in backend logs
4. RBAC correctly enforces ADMIN/VULN-only access
5. Exception matching logic works for both IP and PRODUCT types
6. Current vulnerability query excludes historical scans

---

## Cleanup

After testing, optionally clean up test data:

```sql
-- Remove test exceptions
DELETE FROM vulnerability_exception WHERE reason LIKE '%test%';

-- Remove test user
DELETE FROM user_roles WHERE user_id = (SELECT id FROM users WHERE username = 'vuln-user-test');
DELETE FROM users WHERE username = 'vuln-user-test';
```

---

**Quickstart Status**: Ready for manual testing after implementation
**Last Updated**: 2025-10-03
