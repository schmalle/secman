# Quickstart Guide: Release-Based Requirement Version Management

**Feature**: 011-i-want-to
**Date**: 2025-10-05
**Purpose**: Validate complete feature functionality from user perspective

## Prerequisites

- secman backend running on localhost:8080
- secman frontend running on localhost:4321
- Test user with RELEASE_MANAGER or ADMIN role
- At least 10 test requirements in the system

## Scenario 1: Create Release and Freeze Requirements

**Goal**: Create a new release and verify all requirements are frozen

### Steps

1. **Login** as user with RELEASE_MANAGER role
   ```bash
   # Via UI: Navigate to http://localhost:4321/login
   # Or via API:
   curl -X POST http://localhost:8080/api/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username": "testuser", "password": "testpass"}'
   # Save JWT token from response
   ```

2. **Navigate to Release Management**
   ```
   UI: Click "Releases" in navigation menu
   URL: http://localhost:4321/releases
   ```

3. **Create new release**
   ```
   UI Actions:
   - Click "Create Release" button
   - Enter version: "1.0.0"
   - Enter name: "Q4 2024 Baseline"
   - Enter description: "Initial requirement baseline for compliance audit"
   - Click "Create"

   Expected:
   - Success message: "Release 1.0.0 created with X requirements frozen"
   - Release appears in list with status "DRAFT"
   - Requirement count matches current requirement total
   ```

   Or via API:
   ```bash
   curl -X POST http://localhost:8080/api/releases \
     -H "Authorization: Bearer $JWT_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
       "version": "1.0.0",
       "name": "Q4 2024 Baseline",
       "description": "Initial requirement baseline for compliance audit"
     }'
   ```

### Validation

- ✅ Release created with unique ID
- ✅ Release version is "1.0.0" (exact match)
- ✅ Requirement count = total current requirements
- ✅ Release status = DRAFT
- ✅ CreatedBy = logged-in username
- ✅ CreatedAt = current timestamp

### Test Database State

```sql
-- Verify release created
SELECT * FROM releases WHERE version = '1.0.0';
-- Expected: 1 row

-- Verify snapshots created
SELECT COUNT(*) FROM requirement_snapshot WHERE release_id = <release_id_from_above>;
-- Expected: Count matches requirement total

-- Verify snapshot data integrity
SELECT rs.shortreq, r.shortreq
FROM requirement_snapshot rs
JOIN requirements r ON rs.original_requirement_id = r.id
WHERE rs.release_id = <release_id>
LIMIT 5;
-- Expected: Shortreq values match between snapshot and current
```

---

## Scenario 2: Update Requirement After Release

**Goal**: Verify that updating a requirement does not affect frozen snapshots

### Steps

1. **Identify a requirement** frozen in release "1.0.0"
   ```
   UI: Navigate to Releases → "1.0.0" → View Requirements
   Note the shortreq of first requirement (e.g., "SEC-001: Authentication required")
   ```

2. **Update the requirement**
   ```
   UI:
   - Navigate to Requirements page
   - Find the same requirement (SEC-001)
   - Click "Edit"
   - Change shortreq to "SEC-001: Multi-factor authentication required"
   - Click "Save"

   Expected:
   - Success message: "Requirement updated"
   ```

3. **Verify snapshot unchanged**
   ```
   UI:
   - Navigate back to Releases → "1.0.0" → View Requirements
   - Find the same requirement

   Expected:
   - Snapshot still shows original: "SEC-001: Authentication required"
   - Current requirement shows new: "SEC-001: Multi-factor authentication required"
   ```

### Validation

- ✅ Current requirement updated
- ✅ Snapshot in release "1.0.0" unchanged
- ✅ No error messages
- ✅ Snapshot timestamp unchanged

### Test Database State

```sql
-- Verify current requirement updated
SELECT shortreq FROM requirements WHERE id = <req_id>;
-- Expected: "SEC-001: Multi-factor authentication required"

-- Verify snapshot unchanged
SELECT shortreq FROM requirement_snapshot
WHERE original_requirement_id = <req_id> AND release_id = <release_id>;
-- Expected: "SEC-001: Authentication required"
```

---

## Scenario 3: Export from Release vs Current

**Goal**: Verify export functionality works for both current and historical states

### Steps

1. **Export current requirements**
   ```
   UI:
   - Navigate to Requirements → Export
   - Ensure "Release" dropdown shows "Current Version"
   - Select format: Excel
   - Click "Export"

   Expected:
   - File downloads: "requirements_current_2024-10-05.xlsx"
   - File contains updated requirement: "SEC-001: Multi-factor authentication required"
   ```

2. **Export from release "1.0.0"**
   ```
   UI:
   - Navigate to Requirements → Export
   - Select "Release" dropdown → "1.0.0 - Q4 2024 Baseline"
   - Select format: Excel
   - Click "Export"

   Expected:
   - File downloads: "requirements_v1.0.0_2024-10-05.xlsx"
   - File contains original requirement: "SEC-001: Authentication required"
   - File header includes release metadata: "Release 1.0.0 - Q4 2024 Baseline"
   ```

### Validation

- ✅ Both exports complete without errors
- ✅ Filenames differ (current vs v1.0.0)
- ✅ Content differs (updated vs frozen)
- ✅ Release export includes metadata header
- ✅ Current export excludes release metadata

---

## Scenario 4: Deletion Prevention

**Goal**: Verify that requirements frozen in releases cannot be deleted

### Steps

1. **Attempt to delete frozen requirement**
   ```
   UI:
   - Navigate to Requirements page
   - Find requirement "SEC-001" (frozen in release "1.0.0")
   - Click "Delete" button

   Expected:
   - Error modal appears
   - Message: "Cannot delete requirement: frozen in releases 1.0.0"
   - Requirement NOT deleted
   ```

   Or via API:
   ```bash
   curl -X DELETE http://localhost:8080/api/requirements/<req_id> \
     -H "Authorization: Bearer $JWT_TOKEN"

   # Expected response: 400 Bad Request
   # Body: {"error": "Cannot delete requirement: frozen in releases 1.0.0"}
   ```

### Validation

- ✅ Deletion blocked with clear error message
- ✅ Error message lists all releases containing the requirement
- ✅ Requirement still exists in database
- ✅ No database changes

---

## Scenario 5: Create Second Release and Compare

**Goal**: Verify comparison functionality highlights differences

### Steps

1. **Create second release**
   ```
   UI:
   - Navigate to Releases → Create Release
   - Version: "1.1.0"
   - Name: "Q4 2024 Update"
   - Description: "Updated requirements after SEC-001 enhancement"
   - Click "Create"

   Expected:
   - Release "1.1.0" created
   - Requirement count matches current total
   - Frozen snapshots include updated "SEC-001: Multi-factor authentication required"
   ```

2. **Compare releases**
   ```
   UI:
   - Navigate to Releases → Compare
   - From: "1.0.0 - Q4 2024 Baseline"
   - To: "1.1.0 - Q4 2024 Update"
   - Click "Compare"

   Expected:
   - Comparison view appears
   - Modified section shows SEC-001 with field changes:
     - Field: shortreq
     - Old: "SEC-001: Authentication required"
     - New: "SEC-001: Multi-factor authentication required"
   - Color coding: Yellow highlight for modified
   ```

   Or via API:
   ```bash
   curl "http://localhost:8080/api/releases/compare?fromReleaseId=1&toReleaseId=2" \
     -H "Authorization: Bearer $JWT_TOKEN"

   # Expected: ComparisonResult with modified array containing SEC-001 changes
   ```

### Validation

- ✅ Comparison displays three categories: Added, Deleted, Modified
- ✅ SEC-001 appears in Modified with correct old/new values
- ✅ Field-level changes shown (shortreq field)
- ✅ Color coding: Green (added), Red (deleted), Yellow (modified)
- ✅ Unchanged count displayed

### Test Database State

```sql
-- Verify both releases have different snapshots for SEC-001
SELECT r.version, rs.shortreq
FROM requirement_snapshot rs
JOIN releases r ON rs.release_id = r.id
WHERE rs.original_requirement_id = <req_id>
ORDER BY r.version;

-- Expected:
-- 1.0.0 | SEC-001: Authentication required
-- 1.1.0 | SEC-001: Multi-factor authentication required
```

---

## Scenario 6: Delete Release

**Goal**: Verify release deletion cascades to snapshots

### Steps

1. **Delete release "1.0.0"**
   ```
   UI:
   - Navigate to Releases
   - Find "1.0.0 - Q4 2024 Baseline"
   - Click "Delete" button
   - Confirm deletion in modal

   Expected:
   - Success message: "Release 1.0.0 deleted"
   - Release removed from list
   ```

   Or via API:
   ```bash
   curl -X DELETE http://localhost:8080/api/releases/1 \
     -H "Authorization: Bearer $JWT_TOKEN"

   # Expected: 204 No Content
   ```

2. **Verify snapshots deleted**
   ```
   UI:
   - Navigate to Releases → Compare
   - Attempt to select "1.0.0" in dropdown

   Expected:
   - "1.0.0" no longer appears in dropdown
   - Only "1.1.0" available
   ```

3. **Verify requirement can now be deleted**
   ```
   UI:
   - Navigate to Requirements
   - Find "SEC-001" (only in release "1.1.0" now)
   - Click "Delete"

   Expected:
   - Still blocked: "Cannot delete requirement: frozen in releases 1.1.0"
   ```

### Validation

- ✅ Release deleted successfully
- ✅ Snapshots cascade deleted
- ✅ Comparison no longer offers deleted release
- ✅ Export no longer offers deleted release
- ✅ Current requirements unaffected
- ✅ Requirement deletion still blocked if in other releases

### Test Database State

```sql
-- Verify release deleted
SELECT COUNT(*) FROM releases WHERE version = '1.0.0';
-- Expected: 0

-- Verify snapshots deleted
SELECT COUNT(*) FROM requirement_snapshot WHERE release_id = 1;
-- Expected: 0

-- Verify current requirements intact
SELECT COUNT(*) FROM requirements;
-- Expected: Original count (no deletions)
```

---

## Scenario 7: Permission Checks

**Goal**: Verify RBAC enforcement for release operations

### Steps

1. **Logout and login as USER (not RELEASE_MANAGER)**
   ```
   UI: Logout → Login with user role = USER
   ```

2. **Verify UI restrictions**
   ```
   UI:
   - Navigate to Releases page

   Expected:
   - "Create Release" button NOT visible
   - Release list visible (read-only)
   - "Delete" button NOT visible on releases
   - Export still available
   - Compare still available
   ```

3. **Attempt API call without permission**
   ```bash
   # Login as USER and get JWT
   curl -X POST http://localhost:8080/api/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username": "normaluser", "password": "testpass"}'

   # Attempt to create release
   curl -X POST http://localhost:8080/api/releases \
     -H "Authorization: Bearer $USER_JWT_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"version": "2.0.0", "name": "Test"}'

   # Expected: 403 Forbidden
   # Body: {"error": "ADMIN or RELEASE_MANAGER role required"}
   ```

### Validation

- ✅ USER role cannot create releases
- ✅ USER role cannot delete releases
- ✅ USER role CAN view releases
- ✅ USER role CAN export from releases
- ✅ USER role CAN compare releases
- ✅ UI hides unauthorized actions
- ✅ API returns 403 for unauthorized actions

---

## Performance Validation

### Release Creation Performance

```bash
# Measure release creation time with 1000 requirements
time curl -X POST http://localhost:8080/api/releases \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"version": "3.0.0", "name": "Performance Test"}'

# Expected: < 2 seconds for 1000 requirements
```

### Export Performance

```bash
# Measure export time from release
time curl "http://localhost:8080/api/requirements/export/xlsx?releaseId=2" \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -o test.xlsx

# Expected: < 3 seconds for 1000 requirements
```

### Comparison Performance

```bash
# Measure comparison time
time curl "http://localhost:8080/api/releases/compare?fromReleaseId=2&toReleaseId=3" \
  -H "Authorization: Bearer $JWT_TOKEN"

# Expected: < 1 second for 1000 vs 1000 requirements
```

---

## Edge Cases

### Empty Release
```
Create release when no requirements exist
Expected: Release created with requirementCount = 0
Warning: "No requirements to freeze"
```

### Duplicate Version
```
Attempt to create release with existing version "1.1.0"
Expected: 400 Bad Request, "Version 1.1.0 already exists"
```

### Invalid Version Format
```
Attempt to create release with version "v1.0" or "Q4-2024"
Expected: 400 Bad Request, "Version must follow semantic versioning format (MAJOR.MINOR.PATCH)"
```

### Compare Same Release
```
Compare release with itself (fromReleaseId=2, toReleaseId=2)
Expected: 400 Bad Request, "fromReleaseId and toReleaseId must be different"
```

---

## Success Criteria

All scenarios pass when:
- ✅ Releases create successfully with all requirements frozen
- ✅ Snapshots are immutable (requirement updates don't affect them)
- ✅ Exports work correctly for both current and historical states
- ✅ Deletion prevention works for requirements in releases
- ✅ Comparison shows correct differences with field-level detail
- ✅ Release deletion cascades to snapshots
- ✅ Permission checks enforce RBAC rules
- ✅ Performance meets constitutional requirements (<200ms API, <3s export)
- ✅ All edge cases handled gracefully with clear error messages

---

**Validation Status**: ⬜ Not Started | ⬜ In Progress | ⬜ Passed | ⬜ Failed

**Notes**:
- Run this quickstart after all implementation tasks complete
- Use fresh test database or reset to clean state before starting
- Document any deviations or failures for investigation
- Performance results may vary based on hardware/database configuration
