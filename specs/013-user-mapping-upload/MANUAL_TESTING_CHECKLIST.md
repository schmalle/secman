# Manual Testing Checklist
## Feature 013: User Mapping Upload
**Version**: 1.0  
**Date**: 2025-10-08  
**Tester**: ________________  
**Environment**: ________________

---

## Prerequisites

- [ ] Docker daemon is running
- [ ] Backend server is running (`docker compose up backend` or `./gradlew run`)
- [ ] Frontend server is running (`npm run dev`)
- [ ] Database is accessible
- [ ] Admin user account created
- [ ] Regular user account created (non-admin)

---

## Test Scenario 1: Access Control

### TC01.1: Non-Admin User Cannot Access
- [ ] Login as non-admin user (USER role)
- [ ] Navigate to `/admin`
- [ ] **VERIFY**: "User Mappings" card is NOT visible
- [ ] Navigate directly to `/admin/user-mappings`
- [ ] **VERIFY**: Redirected to login or 403 page

### TC01.2: Admin User Can Access
- [ ] Login as admin user
- [ ] Navigate to `/admin`
- [ ] **VERIFY**: "User Mappings" card is visible
- [ ] Click "Manage Mappings" button
- [ ] **VERIFY**: Redirected to `/admin/user-mappings`
- [ ] **VERIFY**: Page loads successfully with upload form

**Status**: ⬜ PASS ⬜ FAIL ⬜ SKIP  
**Notes**: _______________________________________________

---

## Test Scenario 2: UI Components

### TC02.1: Page Layout
- [ ] **VERIFY**: Page title "User Mapping Upload" is displayed
- [ ] **VERIFY**: Breadcrumb shows: Home > Admin > User Mappings
- [ ] **VERIFY**: File Requirements card is displayed
- [ ] **VERIFY**: Upload form card is displayed

### TC02.2: File Requirements Card
- [ ] **VERIFY**: Shows "Format: Excel (.xlsx)"
- [ ] **VERIFY**: Shows "Max Size: 10 MB"
- [ ] **VERIFY**: Lists required columns: Email, AWS Account ID, Domain
- [ ] **VERIFY**: "Download Sample" button is present
- [ ] Click "Download Sample" button
- [ ] **VERIFY**: Sample template file downloads

### TC02.3: Upload Form
- [ ] **VERIFY**: File input accepts only `.xlsx` files
- [ ] **VERIFY**: Upload button is disabled when no file selected
- [ ] Select a valid file
- [ ] **VERIFY**: File name and size are displayed
- [ ] **VERIFY**: Upload button becomes enabled

**Status**: ⬜ PASS ⬜ FAIL ⬜ SKIP  
**Notes**: _______________________________________________

---

## Test Scenario 3: Valid File Upload

### TC03.1: Upload Valid Mappings
**Test File**: `testdata/user-mappings/valid-mappings.xlsx` (3 rows)
- [ ] Select valid-mappings.xlsx file
- [ ] Click Upload button
- [ ] **VERIFY**: Upload button shows "Uploading..." with spinner
- [ ] **VERIFY**: Success alert appears
- [ ] **VERIFY**: Message shows "Import Complete"
- [ ] **VERIFY**: Shows "Imported: 3 mappings"
- [ ] **VERIFY**: Shows "Skipped: 0 mappings"
- [ ] **VERIFY**: File input is cleared

### TC03.2: Verify Database Records
- [ ] Open database client
- [ ] Query: `SELECT * FROM user_mapping WHERE email IN ('user1@example.com', 'user2@example.com', 'admin@company.org')`
- [ ] **VERIFY**: 3 records exist
- [ ] **VERIFY**: Emails are lowercase
- [ ] **VERIFY**: Domains are lowercase
- [ ] **VERIFY**: AWS account IDs are correct (12 digits)
- [ ] **VERIFY**: `created_at` and `updated_at` are set

### TC03.3: Upload Large File
**Test File**: `testdata/user-mappings/large-file.xlsx` (150 rows)
- [ ] Select large-file.xlsx
- [ ] Click Upload
- [ ] **VERIFY**: Upload completes within reasonable time (<10 seconds)
- [ ] **VERIFY**: Shows "Imported: 150 mappings"
- [ ] Query database: `SELECT COUNT(*) FROM user_mapping`
- [ ] **VERIFY**: Count includes all 150 new records

### TC03.4: Upload Special Characters
**Test File**: `testdata/user-mappings/special-characters.xlsx`
- [ ] Select special-characters.xlsx
- [ ] Click Upload
- [ ] **VERIFY**: Shows "Imported: 4 mappings"
- [ ] **VERIFY**: No errors
- [ ] Query: `SELECT * FROM user_mapping WHERE email LIKE '%+%' OR email LIKE '%_%'`
- [ ] **VERIFY**: Special characters in emails preserved (e.g., user+tag@, user_name@)

**Status**: ⬜ PASS ⬜ FAIL ⬜ SKIP  
**Notes**: _______________________________________________

---

## Test Scenario 4: Invalid File Handling

### TC04.1: Invalid Email Formats
**Test File**: `testdata/user-mappings/invalid-emails.xlsx` (4 invalid)
- [ ] Select invalid-emails.xlsx
- [ ] Click Upload
- [ ] **VERIFY**: Success alert appears (partial success)
- [ ] **VERIFY**: Shows "Imported: 0 mappings"
- [ ] **VERIFY**: Shows "Skipped: 4 mappings"
- [ ] **VERIFY**: "Details:" section is visible
- [ ] **VERIFY**: Error list shows 4 errors mentioning email validation
- [ ] Example error: "Row 2: Email must be valid (contain @)"

### TC04.2: Invalid AWS Account IDs
**Test File**: `testdata/user-mappings/invalid-aws-accounts.xlsx` (5 invalid)
- [ ] Select invalid-aws-accounts.xlsx
- [ ] Click Upload
- [ ] **VERIFY**: Shows "Imported: 0, Skipped: 5"
- [ ] **VERIFY**: Errors mention "AWS Account ID must be exactly 12 digits"

### TC04.3: Invalid Domains
**Test File**: `testdata/user-mappings/invalid-domains.xlsx`
- [ ] Select invalid-domains.xlsx
- [ ] Click Upload
- [ ] **VERIFY**: Shows "Imported: 1, Skipped: 3"
- [ ] **VERIFY**: Uppercase domain "UPPERCASE.COM" was normalized to lowercase
- [ ] **VERIFY**: Errors mention "Domain must contain only lowercase"

### TC04.4: Missing Required Column
**Test File**: `testdata/user-mappings/missing-columns.xlsx`
- [ ] Select missing-columns.xlsx
- [ ] Click Upload
- [ ] **VERIFY**: Error alert appears (not success)
- [ ] **VERIFY**: Message mentions "missing required column" or "Domain"

### TC04.5: Empty File
**Test File**: `testdata/user-mappings/empty-file.xlsx`
- [ ] Select empty-file.xlsx
- [ ] Click Upload
- [ ] **VERIFY**: Success alert appears
- [ ] **VERIFY**: Shows "Imported: 0, Skipped: 0"

### TC04.6: Wrong File Format
**Test File**: `testdata/user-mappings/wrong-format.txt`
- [ ] Try to select wrong-format.txt
- [ ] **VERIFY**: File input may reject (depends on browser)
- [ ] If file is selected, click Upload
- [ ] **VERIFY**: Error alert appears
- [ ] **VERIFY**: Message mentions "Only .xlsx files are supported"

**Status**: ⬜ PASS ⬜ FAIL ⬜ SKIP  
**Notes**: _______________________________________________

---

## Test Scenario 5: Mixed and Duplicate Data

### TC05.1: Mixed Valid/Invalid Data
**Test File**: `testdata/user-mappings/mixed-valid-invalid.xlsx` (3 valid, 2 invalid)
- [ ] Select mixed-valid-invalid.xlsx
- [ ] Click Upload
- [ ] **VERIFY**: Success alert appears
- [ ] **VERIFY**: Shows "Imported: 3, Skipped: 2"
- [ ] **VERIFY**: Details section shows 2 errors
- [ ] Query database for the 3 valid emails
- [ ] **VERIFY**: 3 records exist

### TC05.2: Duplicate Detection (Same File Twice)
**Test File**: `testdata/user-mappings/duplicates.xlsx`
- [ ] **First Upload**: Select duplicates.xlsx and upload
- [ ] **VERIFY**: Shows "Imported: 2, Skipped: 2" (duplicates within file)
- [ ] Dismiss alert
- [ ] **Second Upload**: Select same file and upload again
- [ ] **VERIFY**: Shows "Imported: 0, Skipped: 2"
- [ ] **VERIFY**: Message indicates "already exists"

### TC05.3: Empty Cells
**Test File**: `testdata/user-mappings/empty-cells.xlsx`
- [ ] Select empty-cells.xlsx
- [ ] Click Upload
- [ ] **VERIFY**: Shows "Imported: 1, Skipped: 4"
- [ ] **VERIFY**: Errors mention missing required fields

**Status**: ⬜ PASS ⬜ FAIL ⬜ SKIP  
**Notes**: _______________________________________________

---

## Test Scenario 6: UI Interactions

### TC06.1: Loading States
- [ ] Select valid-mappings.xlsx
- [ ] Click Upload button
- [ ] **VERIFY**: Button text changes to "Uploading..."
- [ ] **VERIFY**: Spinner icon appears
- [ ] **VERIFY**: Upload button is disabled during upload
- [ ] **VERIFY**: File input is disabled during upload
- [ ] Wait for completion
- [ ] **VERIFY**: Button returns to "Upload" state
- [ ] **VERIFY**: Controls are re-enabled

### TC06.2: Alert Dismissal
- [ ] Upload valid file to trigger success alert
- [ ] **VERIFY**: Success alert has close button (X)
- [ ] Click close button
- [ ] **VERIFY**: Alert disappears
- [ ] Upload invalid file to trigger error alert
- [ ] **VERIFY**: Error alert has close button
- [ ] Click close button
- [ ] **VERIFY**: Alert disappears

### TC06.3: Multiple Sequential Uploads
- [ ] Upload valid-mappings.xlsx
- [ ] Wait for success
- [ ] Dismiss alert
- [ ] **VERIFY**: Form is ready for next upload
- [ ] Upload special-characters.xlsx
- [ ] **VERIFY**: Second upload works correctly
- [ ] **VERIFY**: Shows correct counts for second file

### TC06.4: Navigation
- [ ] On user-mappings page, click "Admin" in breadcrumb
- [ ] **VERIFY**: Navigate to /admin page
- [ ] **VERIFY**: Admin page loads correctly
- [ ] Click "Home" in breadcrumb
- [ ] **VERIFY**: Navigate to homepage

**Status**: ⬜ PASS ⬜ FAIL ⬜ SKIP  
**Notes**: _______________________________________________

---

## Test Scenario 7: Data Validation and Normalization

### TC07.1: Email Normalization
- [ ] Create test file with emails: `User1@Example.COM`, `USER2@EXAMPLE.COM`
- [ ] Upload file
- [ ] Query database: `SELECT email FROM user_mapping WHERE email LIKE '%example.com'`
- [ ] **VERIFY**: All emails stored in lowercase (`user1@example.com`, `user2@example.com`)

### TC07.2: Domain Normalization
- [ ] Create test file with domains: `DOMAIN.COM`, `SubDomain.EXAMPLE.ORG`
- [ ] Upload file
- [ ] Query database: `SELECT domain FROM user_mapping WHERE domain LIKE '%domain%'`
- [ ] **VERIFY**: All domains stored in lowercase

### TC07.3: AWS Account ID Validation
- [ ] Create test file with AWS IDs: `123456789012`, `  123456789012  ` (with spaces)
- [ ] Upload file
- [ ] Query database: `SELECT aws_account_id FROM user_mapping WHERE aws_account_id = '123456789012'`
- [ ] **VERIFY**: Spaces are trimmed, stored as `123456789012`

### TC07.4: Excel Formula Handling
- [ ] Create test file with Excel formula in AWS Account ID cell: `=CONCATENATE("1234567","89012")`
- [ ] Upload file
- [ ] **VERIFY**: Formula result is evaluated (not formula text)
- [ ] **VERIFY**: Stored as `123456789012`

### TC07.5: Numeric Cell Type Handling (CRITICAL)
- [ ] Open Excel and create file
- [ ] Enter AWS Account ID `123456789012` as plain number (Excel will format as `1.23E+11`)
- [ ] Upload file
- [ ] Query database
- [ ] **VERIFY**: Stored correctly as `123456789012` (not scientific notation)
- [ ] **NOTE**: This tests the DataFormatter fix in the code

**Status**: ⬜ PASS ⬜ FAIL ⬜ SKIP  
**Notes**: _______________________________________________

---

## Test Scenario 8: Edge Cases

### TC08.1: File Size Limit
- [ ] Create Excel file > 10 MB (add many rows or images)
- [ ] Try to upload
- [ ] **VERIFY**: Error message about file size limit

### TC08.2: Very Long Values
- [ ] Create file with email length = 255 characters
- [ ] Create file with email length = 256 characters (exceeds limit)
- [ ] Upload both
- [ ] **VERIFY**: 255-char email is accepted
- [ ] **VERIFY**: 256-char email is rejected

### TC08.3: Unicode Characters
- [ ] Create file with unicode in domain: `user@例え.com`
- [ ] Upload file
- [ ] **VERIFY**: Handled appropriately (likely rejected due to validation)

### TC08.4: Concurrent Uploads (Multi-User)
- [ ] Open two browser windows (Admin User 1 and Admin User 2)
- [ ] Both upload different files at same time
- [ ] **VERIFY**: Both uploads succeed
- [ ] **VERIFY**: No race conditions or duplicate key errors

**Status**: ⬜ PASS ⬜ FAIL ⬜ SKIP  
**Notes**: _______________________________________________

---

## Test Scenario 9: Security

### TC09.1: Authentication Required
- [ ] Logout from application
- [ ] Navigate directly to `/admin/user-mappings`
- [ ] **VERIFY**: Redirected to login page

### TC09.2: Authorization Enforced
- [ ] Login as non-admin user
- [ ] Use browser DevTools or curl to POST to `/api/import/upload-user-mappings`
- [ ] **VERIFY**: 403 Forbidden response

### TC09.3: CSRF Protection (if implemented)
- [ ] Check if CSRF token is required for POST requests
- [ ] **VERIFY**: Upload without token fails (if CSRF enabled)

### TC09.4: SQL Injection Prevention
- [ ] Create file with SQL injection in email: `user'; DROP TABLE user_mapping; --@example.com`
- [ ] Upload file
- [ ] **VERIFY**: Treated as invalid email, not executed
- [ ] **VERIFY**: Database tables intact

### TC09.5: Path Traversal Prevention
- [ ] Create file with path traversal in domain: `../../../etc/passwd`
- [ ] Upload file
- [ ] **VERIFY**: Treated as invalid domain

### TC09.6: XSS Prevention
- [ ] Create file with XSS in email: `<script>alert('XSS')</script>@example.com`
- [ ] Upload file
- [ ] **VERIFY**: Displayed safely in error message (HTML escaped)

**Status**: ⬜ PASS ⬜ FAIL ⬜ SKIP  
**Notes**: _______________________________________________

---

## Test Scenario 10: Performance

### TC10.1: Upload Time (100 rows)
- [ ] Prepare file with 100 valid rows
- [ ] Start timer
- [ ] Upload file
- [ ] Stop timer when success alert appears
- [ ] **VERIFY**: Upload completes in < 5 seconds
- [ ] **Actual Time**: _______ seconds

### TC10.2: Upload Time (1000 rows)
- [ ] Prepare file with 1000 valid rows
- [ ] Start timer
- [ ] Upload file
- [ ] Stop timer
- [ ] **VERIFY**: Upload completes in < 30 seconds
- [ ] **Actual Time**: _______ seconds

### TC10.3: Database Query Performance
- [ ] After uploading 1000 rows
- [ ] Execute: `SELECT * FROM user_mapping WHERE email = 'user500@example.com'`
- [ ] Measure query time
- [ ] **VERIFY**: Query returns in < 100ms
- [ ] **Actual Time**: _______ ms

### TC10.4: Memory Usage
- [ ] Monitor backend memory before upload
- [ ] Upload large-file.xlsx (150 rows)
- [ ] Monitor memory during and after upload
- [ ] **VERIFY**: No significant memory leak
- [ ] **Memory Before**: _______ MB
- [ ] **Memory After**: _______ MB

**Status**: ⬜ PASS ⬜ FAIL ⬜ SKIP  
**Notes**: _______________________________________________

---

## Test Scenario 11: Database Integrity

### TC11.1: Unique Constraint
- [ ] Upload same mapping twice (same email + AWS ID + domain)
- [ ] **VERIFY**: Second upload skips duplicate
- [ ] **VERIFY**: No database error

### TC11.2: Index Usage
- [ ] Check database indexes exist:
  - `idx_user_mapping_email`
  - `idx_user_mapping_aws_account_id`
  - `idx_user_mapping_domain`
  - `idx_user_mapping_email_aws_account`
  - `idx_user_mapping_unique` (composite unique)
- [ ] **VERIFY**: All indexes created

### TC11.3: Timestamp Accuracy
- [ ] Note current time: _______
- [ ] Upload valid-mappings.xlsx
- [ ] Query: `SELECT created_at, updated_at FROM user_mapping ORDER BY created_at DESC LIMIT 1`
- [ ] **VERIFY**: Timestamps are within 1 second of upload time

**Status**: ⬜ PASS ⬜ FAIL ⬜ SKIP  
**Notes**: _______________________________________________

---

## Test Scenario 12: Error Handling and User Experience

### TC12.1: Network Failure Simulation
- [ ] Start upload
- [ ] Disconnect network (airplane mode or kill backend)
- [ ] **VERIFY**: Error alert appears with appropriate message
- [ ] **VERIFY**: UI remains responsive

### TC12.2: Backend Timeout
- [ ] Upload large file (if timeout configured)
- [ ] **VERIFY**: Appropriate timeout message if upload takes too long

### TC12.3: Error Message Clarity
- [ ] Upload file with multiple errors
- [ ] **VERIFY**: Error messages are clear and actionable
- [ ] **VERIFY**: Error messages indicate row number
- [ ] **VERIFY**: Error messages indicate the specific issue

### TC12.4: Success Message Clarity
- [ ] Upload file with partial success (3 imported, 2 skipped)
- [ ] **VERIFY**: Success message clearly shows both counts
- [ ] **VERIFY**: Details section shows which rows failed

**Status**: ⬜ PASS ⬜ FAIL ⬜ SKIP  
**Notes**: _______________________________________________

---

## Test Scenario 13: Regression Testing

### TC13.1: Other Import Functions Still Work
- [ ] Upload requirements Excel file via `/api/import/upload-xlsx`
- [ ] **VERIFY**: Requirements import still functions correctly

### TC13.2: Admin Page Functionality
- [ ] Navigate to /admin
- [ ] **VERIFY**: All other admin cards are still functional
- [ ] Test user management, workgroups, etc.

### TC13.3: Authentication/Authorization
- [ ] Test login/logout
- [ ] **VERIFY**: Session management works correctly
- [ ] **VERIFY**: Role-based access to other features still works

**Status**: ⬜ PASS ⬜ FAIL ⬜ SKIP  
**Notes**: _______________________________________________

---

## Summary

### Test Results
- **Total Scenarios**: 13
- **Scenarios Passed**: _______
- **Scenarios Failed**: _______
- **Scenarios Skipped**: _______

### Critical Issues Found
1. _______________________________________________________
2. _______________________________________________________
3. _______________________________________________________

### Minor Issues Found
1. _______________________________________________________
2. _______________________________________________________
3. _______________________________________________________

### Recommendations
1. _______________________________________________________
2. _______________________________________________________
3. _______________________________________________________

### Sign-Off
- **Tester Name**: ____________________
- **Date**: ____________________
- **Signature**: ____________________

**Overall Status**: ⬜ APPROVED FOR DEPLOYMENT ⬜ NEEDS FIXES ⬜ BLOCKED

---

**End of Manual Testing Checklist**
