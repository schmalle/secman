# Regression Testing Guide - Feature 037: Last Admin Protection

## Purpose
Verify that the Last Admin Protection feature does not break existing user management functionality for non-protected scenarios.

## Test Environment Setup

### Prerequisites
- Backend server running on localhost:8080
- Frontend server running on localhost:4321
- Test database with multiple users
- Admin credentials for testing

### Test Data Requirements
Create the following test users:

1. **admin1** - User with ADMIN role (will be the "last admin" in some tests)
2. **admin2** - User with ADMIN role (for multi-admin scenarios)
3. **user1** - User with USER role only
4. **user2** - User with USER and VULN roles
5. **user3** - User with RELEASE_MANAGER role

## Regression Test Cases

### RT-001: Normal User Deletion (Non-Admin User)
**Objective**: Verify that users without ADMIN role can be deleted normally

**Preconditions**:
- At least 2 ADMIN users exist in system
- User to delete has NO blocking references (no demands, risk assessments, etc.)
- User to delete does NOT have ADMIN role

**Test Steps**:
1. Log in as admin user
2. Navigate to User Management page
3. Locate user1 (USER role only)
4. Click "Delete" button
5. Confirm deletion in confirmation dialog

**Expected Results**:
- ✅ User deletion succeeds
- ✅ HTTP 200 OK response
- ✅ User removed from user list
- ✅ Success message displayed
- ✅ No error alerts shown

**Actual Results**: _[To be filled during testing]_

---

### RT-002: Normal Role Update (Non-Admin to Admin)
**Objective**: Verify that adding ADMIN role to a user works normally

**Preconditions**:
- At least 1 ADMIN user exists in system
- User to update currently has USER role

**Test Steps**:
1. Log in as admin user
2. Navigate to User Management page
3. Click "Edit" on user1 (USER role)
4. Add "ADMIN" to roles (e.g., roles: ["USER", "ADMIN"])
5. Click "Save"

**Expected Results**:
- ✅ Role update succeeds
- ✅ HTTP 200 OK response
- ✅ User now shows "USER, ADMIN" roles in list
- ✅ Success message displayed

**Actual Results**: _[To be filled during testing]_

---

### RT-003: Normal Role Update (Admin to User - Multiple Admins)
**Objective**: Verify that removing ADMIN role works when multiple admins exist

**Preconditions**:
- At least 2 users with ADMIN role exist (admin1 and admin2)

**Test Steps**:
1. Log in as admin user
2. Navigate to User Management page
3. Click "Edit" on admin2
4. Remove "ADMIN" from roles (e.g., change from ["ADMIN"] to ["USER"])
5. Click "Save"

**Expected Results**:
- ✅ Role update succeeds
- ✅ HTTP 200 OK response
- ✅ admin2 now shows "USER" role only
- ✅ admin1 still has ADMIN role
- ✅ Success message displayed

**Actual Results**: _[To be filled during testing]_

---

### RT-004: User Deletion with Non-Admin Blocking References
**Objective**: Verify that existing validation for other blocking references still works

**Preconditions**:
- User has blocking references (e.g., created demands, risk assessments)
- User does NOT have ADMIN role

**Test Steps**:
1. Log in as admin user
2. Navigate to User Management page
3. Try to delete user who created demands
4. Observe error response

**Expected Results**:
- ✅ Deletion blocked
- ✅ HTTP 400 Bad Request (NOT 409 - this is not a system constraint)
- ✅ Error message shows specific blocking references (Demand, RiskAssessment, etc.)
- ✅ Error message includes count and details

**Actual Results**: _[To be filled during testing]_

---

### RT-005: Multi-Role User Update
**Objective**: Verify that updating users with multiple roles works correctly

**Preconditions**:
- user2 has roles: ["USER", "VULN"]

**Test Steps**:
1. Log in as admin user
2. Navigate to User Management page
3. Click "Edit" on user2
4. Change roles to ["USER", "VULN", "RELEASE_MANAGER"]
5. Click "Save"

**Expected Results**:
- ✅ Role update succeeds
- ✅ HTTP 200 OK response
- ✅ user2 now shows all three roles
- ✅ Success message displayed

**Actual Results**: _[To be filled during testing]_

---

### RT-006: Username and Email Update
**Objective**: Verify that non-role fields can still be updated normally

**Preconditions**:
- user3 exists with email "user3@example.com"

**Test Steps**:
1. Log in as admin user
2. Navigate to User Management page
3. Click "Edit" on user3
4. Change email to "user3-updated@example.com"
5. Change username to "user3-updated"
6. Click "Save"

**Expected Results**:
- ✅ Update succeeds
- ✅ HTTP 200 OK response
- ✅ User shows new username and email
- ✅ Roles remain unchanged
- ✅ Success message displayed

**Actual Results**: _[To be filled during testing]_

---

## Feature-Specific Protection Tests

### FT-001: Last Admin Deletion Blocked
**Objective**: Verify that last admin cannot be deleted

**Preconditions**:
- Only 1 user with ADMIN role exists (admin1)

**Test Steps**:
1. Log in as admin1
2. Navigate to User Management page
3. Try to delete admin1 (or have another admin try)
4. Observe error response

**Expected Results**:
- ✅ Deletion blocked
- ✅ HTTP 409 Conflict response
- ✅ Error message: "Cannot delete the last administrator. At least one ADMIN user must remain in the system."
- ✅ Alert shows actionable guidance
- ✅ User remains in system

**Actual Results**: _[To be filled during testing]_

---

### FT-002: Last Admin Role Removal Blocked
**Objective**: Verify that ADMIN role cannot be removed from last admin

**Preconditions**:
- Only 1 user with ADMIN role exists (admin1)

**Test Steps**:
1. Log in as admin user
2. Navigate to User Management page
3. Click "Edit" on admin1
4. Try to change roles from ["ADMIN"] to ["USER"]
5. Click "Save"

**Expected Results**:
- ✅ Role update blocked
- ✅ HTTP 409 Conflict response
- ✅ Error message: "Cannot remove ADMIN role from the last administrator. At least one ADMIN user must remain in the system."
- ✅ Error shows in edit modal
- ✅ User retains ADMIN role

**Actual Results**: _[To be filled during testing]_

---

### FT-003: Admin Deletion Allowed with Multiple Admins
**Objective**: Verify that admin can be deleted when others exist

**Preconditions**:
- At least 2 users with ADMIN role exist (admin1 and admin2)
- admin2 has no blocking references

**Test Steps**:
1. Log in as admin1
2. Navigate to User Management page
3. Delete admin2
4. Confirm deletion

**Expected Results**:
- ✅ Deletion succeeds
- ✅ HTTP 200 OK response
- ✅ admin2 removed from list
- ✅ admin1 still exists and has ADMIN role
- ✅ Success message displayed

**Actual Results**: _[To be filled during testing]_

---

## API-Level Regression Tests

### API-001: DELETE /api/users/{id} - Non-Admin User
```bash
# Setup: Ensure user ID 5 exists and is NOT an admin
curl -X DELETE http://localhost:8080/api/users/5 \
  -H "Authorization: Bearer ${JWT_TOKEN}"

# Expected: 200 OK with {"message": "User deleted successfully"}
```

### API-002: DELETE /api/users/{id} - Last Admin
```bash
# Setup: Ensure user ID 1 is the only admin
curl -X DELETE http://localhost:8080/api/users/1 \
  -H "Authorization: Bearer ${JWT_TOKEN}"

# Expected: 409 Conflict with structured error response
# Response body should include:
# {
#   "error": "Cannot delete user",
#   "message": "Cannot delete the last administrator...",
#   "blockingReferences": [
#     {
#       "entityType": "SystemConstraint",
#       "count": 1,
#       "role": "last_admin",
#       "details": "Cannot delete the last administrator..."
#     }
#   ]
# }
```

### API-003: PUT /api/users/{id} - Remove Admin from Last Admin
```bash
# Setup: Ensure user ID 1 is the only admin
curl -X PUT http://localhost:8080/api/users/1 \
  -H "Authorization: Bearer ${JWT_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"roles": ["USER"]}'

# Expected: 409 Conflict with structured error response
```

### API-004: PUT /api/users/{id} - Normal Role Update
```bash
# Setup: Ensure multiple admins exist
curl -X PUT http://localhost:8080/api/users/5 \
  -H "Authorization: Bearer ${JWT_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"roles": ["USER", "VULN"]}'

# Expected: 200 OK with updated user data
```

---

## Test Summary Template

### Test Execution Date: _[Date]_
### Tester: _[Name]_
### Environment: _[Dev/Staging/Prod]_

| Test ID | Test Name | Status | Notes |
|---------|-----------|--------|-------|
| RT-001  | Normal User Deletion | ⬜ Pass ⬜ Fail | |
| RT-002  | Role Update (Add Admin) | ⬜ Pass ⬜ Fail | |
| RT-003  | Role Update (Remove Admin) | ⬜ Pass ⬜ Fail | |
| RT-004  | Blocking References | ⬜ Pass ⬜ Fail | |
| RT-005  | Multi-Role Update | ⬜ Pass ⬜ Fail | |
| RT-006  | Username/Email Update | ⬜ Pass ⬜ Fail | |
| FT-001  | Last Admin Delete Block | ⬜ Pass ⬜ Fail | |
| FT-002  | Last Admin Role Block | ⬜ Pass ⬜ Fail | |
| FT-003  | Multi-Admin Delete OK | ⬜ Pass ⬜ Fail | |
| API-001 | API Delete Non-Admin | ⬜ Pass ⬜ Fail | |
| API-002 | API Delete Last Admin | ⬜ Pass ⬜ Fail | |
| API-003 | API Update Last Admin | ⬜ Pass ⬜ Fail | |
| API-004 | API Normal Update | ⬜ Pass ⬜ Fail | |

### Overall Result: ⬜ All Pass ⬜ Some Failures

### Issues Found:
_[List any bugs, unexpected behavior, or regression issues]_

---

## Code Verification Checklist

Based on implementation review:

- ✅ **@Transactional annotation** present on UserController.delete() (line 283)
- ✅ **@Transactional annotation** present on UserController.update() (line 191)
- ✅ **HTTP 409 Conflict** used for SystemConstraint violations (UserController.kt:252, 312)
- ✅ **HTTP 400 Bad Request** used for other blocking references (UserController.kt:314)
- ✅ **Error message format** includes error, message, and blockingReferences fields
- ✅ **BlockingReference structure** includes entityType, count, role, details
- ✅ **Frontend 409 detection** in handleDeleteUser (UserManagement.tsx:266)
- ✅ **Frontend 409 detection** in handleEditUserSubmit (UserManagement.tsx:370)
- ✅ **Actionable error messages** in both API and UI
- ✅ **Admin count query** implemented in UserService.countAdminUsers()
- ✅ **Validation methods** in UserDeletionValidator (validateUserDeletion, validateAdminRoleRemoval)

## Notes

### Regression Safety
The implementation extends existing validation rather than replacing it:
- Existing blocking reference checks for demands, risk assessments, etc. remain unchanged
- New SystemConstraint check is additive, not destructive
- Transaction isolation ensures data consistency
- HTTP status codes properly distinguish between constraint types (409 vs 400)

### Known Limitations
- Concurrent deletion of last 2 admins: Theoretical edge case, accepted risk with transaction isolation
- Admin count query: O(n) performance acceptable for expected user scale (<1000)

### Manual Testing Priority
1. **High Priority**: RT-001, RT-003, FT-001, FT-002 (core functionality)
2. **Medium Priority**: RT-002, FT-003, API-001, API-002 (feature-specific)
3. **Low Priority**: RT-004, RT-005, RT-006 (regression coverage)

---

**Last Updated**: 2025-10-31
**Feature**: 037-last-admin-protection
**Status**: Implementation complete, manual testing pending
