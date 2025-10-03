# Quickstart: Vulnerability Management

**Feature**: Vulnerability Management System
**Date**: 2025-10-03

## Overview
This quickstart guide validates the vulnerability management feature through manual testing. It covers importing vulnerability data, viewing vulnerabilities in the asset inventory, and verifying data accuracy.

---

## Prerequisites
1. **Running Application**: Docker Compose stack running (backend + frontend + MariaDB)
2. **Authenticated User**: Valid JWT token from login
3. **Test Data**: Sample vulnerability Excel file ready

---

## Setup

### 1. Prepare Test Excel File
Create `test-vulnerabilities.xlsx` with the following structure:

| Hostname | Local IP | Host groups | Cloud service account ID | Cloud service instance ID | OS version | Active Directory domain | Vulnerability ID | CVSS severity | Vulnerable product versions | Days open |
|----------|----------|-------------|--------------------------|---------------------------|------------|------------------------|------------------|---------------|----------------------------|-----------|
| MSHome | 10.100.200.1 | SVR-MS-DMZ | | | Windows Server 2030 | MS.HOME | CVE-2016-2183 | High | Windows 19 29h2 | 58 days |
| WebServer01 | 192.168.1.10 | Production,WebServers | AWS-123456 | i-0abc123 | Ubuntu 22.04 | | CVE-2024-1234 | Critical | Apache 2.4.50 | 12 days |
| WebServer01 | 192.168.1.10 | Production,WebServers | AWS-123456 | i-0abc123 | Ubuntu 22.04 | | CVE-2024-5678 | Medium | OpenSSL 3.0.1 | 5 days |
| NewAsset | 10.50.30.20 | DMZ | | | | | CVE-2023-9999 | Low | | 180 days |

**Key scenarios covered**:
- Row 1: Existing asset (if MSHome exists) with all fields populated
- Row 2-3: Same asset with multiple vulnerabilities (duplicate hostname, different CVEs)
- Row 4: New asset (doesn't exist) with minimal data, empty fields

### 2. Start Application
```bash
docker-compose up -d
# Wait for services to be healthy
docker-compose ps
```

### 3. Obtain Authentication Token
```bash
# Login as admin user
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' \
  | jq -r '.access_token'

# Save token for later use
export TOKEN="<your-jwt-token>"
```

---

## Test Scenarios

### Scenario 1: Import Vulnerability File

**Step 1**: Navigate to Import Page
1. Open browser: http://localhost:4321/import
2. Click on "Vulnerabilities" tab (new tab added by this feature)
3. Verify date/time picker is displayed with current date pre-filled

**Step 2**: Upload File
1. Click "Choose File" and select `test-vulnerabilities.xlsx`
2. Set scan date to: `2025-10-03T14:30` (today at 2:30 PM)
3. Click "Import Vulnerabilities" button
4. Verify loading indicator appears

**Expected Result**:
```json
{
  "message": "3 imported, 0 skipped (invalid), 1 assets created",
  "imported": 3,
  "skipped": 0,
  "assetsCreated": 1
}
```
- Success message displayed in green alert
- 3 vulnerabilities imported (4 rows - 1 duplicate hostname = 3 unique vulnerabilities)
- 0 rows skipped (all valid)
- 1 new asset created (NewAsset)

**Step 3**: Verify API Response (via cURL)
```bash
curl -X POST http://localhost:8080/api/import/upload-vulnerability-xlsx \
  -H "Authorization: Bearer $TOKEN" \
  -F "xlsxFile=@test-vulnerabilities.xlsx" \
  -F "scanDate=2025-10-03T14:30:00" \
  | jq .
```

**Expected API Response**:
```json
{
  "message": "3 imported, 0 skipped (invalid), 1 assets created",
  "imported": 3,
  "skipped": 0,
  "assetsCreated": 1,
  "skippedDetails": []
}
```

---

### Scenario 2: View Vulnerabilities in Asset Inventory

**Step 1**: Navigate to Assets Page
1. Open browser: http://localhost:4321/inventory
2. Find "MSHome" asset (or existing asset from test file)
3. Click on asset name to view details

**Step 2**: Verify Vulnerabilities Displayed
**Expected**:
- Vulnerabilities section visible on asset detail page
- Table with columns: CVE ID, Severity, Product Versions, Days Open, Scan Date
- Row 1: CVE-2016-2183, High, Windows 19 29h2, 58 days, 2025-10-03 14:30

**Step 3**: Verify Asset Extended Fields
**Expected** (for MSHome):
- Groups: "SVR-MS-DMZ" (displayed as badges/tags)
- OS Version: "Windows Server 2030"
- AD Domain: "MS.HOME"
- Cloud fields: Empty (no data in Excel)

**Step 4**: Check WebServer01 Asset
1. Navigate to WebServer01 asset
2. Verify 2 vulnerabilities listed (CVE-2024-1234 and CVE-2024-5678)
3. Verify groups: "Production, WebServers" (both groups merged if existed, or created)
4. Verify cloud fields: Account ID "AWS-123456", Instance ID "i-0abc123"

---

### Scenario 3: Verify New Asset Auto-Creation

**Step 1**: Find NewAsset
1. Navigate to Assets inventory page
2. Search for "NewAsset"
3. Click to view details

**Expected**:
- Asset exists (auto-created during import)
- **Owner**: "Security Team" (default)
- **Type**: "Server" (default)
- **Description**: "Auto-created from vulnerability scan" (default)
- **IP**: 10.50.30.20 (from Excel)
- **Groups**: "DMZ" (from Excel)
- OS Version: Empty (no data in Excel)
- Cloud/AD fields: Empty

**Step 2**: Verify Vulnerability
**Expected**:
- 1 vulnerability: CVE-2023-9999, Low, empty product versions, 180 days
- Scan timestamp: 2025-10-03 14:30

---

### Scenario 4: Asset Conflict Resolution (Merge)

**Step 1**: Create Test Asset Manually
1. Navigate to Assets page
2. Create new asset: Name="ConflictTest", IP="192.168.1.100", Groups="Group1", Owner="John Doe"

**Step 2**: Prepare Conflict Excel
Create `conflict-test.xlsx`:
| Hostname | Local IP | Host groups | ... | Vulnerability ID | CVSS severity | ... |
|----------|----------|-------------|-----|------------------|---------------|-----|
| ConflictTest | 192.168.1.200 | Group2,Group3 | ... | CVE-2024-0001 | High | ... |

**Step 3**: Import Conflict File
```bash
curl -X POST http://localhost:8080/api/import/upload-vulnerability-xlsx \
  -H "Authorization: Bearer $TOKEN" \
  -F "xlsxFile=@conflict-test.xlsx" \
  -F "scanDate=2025-10-03T16:00:00" \
  | jq .
```

**Expected**:
```json
{
  "message": "1 imported, 0 skipped (invalid), 0 assets created",
  "imported": 1,
  "skipped": 0,
  "assetsCreated": 0
}
```

**Step 4**: Verify Merge Behavior
1. Navigate to ConflictTest asset
2. **Expected merge result**:
   - IP: 192.168.1.200 (updated to new value)
   - Groups: "Group1, Group2, Group3" (appended, deduplicated)
   - Owner: "John Doe" (preserved, not overwritten)
   - Vulnerability: CVE-2024-0001 added

---

### Scenario 5: Duplicate Vulnerability Handling

**Step 1**: Re-import Same File
```bash
# Import the same file again with different scan date
curl -X POST http://localhost:8080/api/import/upload-vulnerability-xlsx \
  -H "Authorization: Bearer $TOKEN" \
  -F "xlsxFile=@test-vulnerabilities.xlsx" \
  -F "scanDate=2025-10-04T10:00:00" \
  | jq .
```

**Expected**:
```json
{
  "message": "3 imported, 0 skipped (invalid), 0 assets created",
  "imported": 3,
  "skipped": 0,
  "assetsCreated": 0
}
```

**Step 2**: Verify Duplicate Records Kept
1. Navigate to MSHome asset
2. **Expected**: 2 vulnerability records for CVE-2016-2183
   - Record 1: Scan timestamp 2025-10-03 14:30
   - Record 2: Scan timestamp 2025-10-04 10:00 (duplicate, kept for history)

---

### Scenario 6: Error Handling - Invalid File

**Step 1**: Test Missing Scan Date
```bash
curl -X POST http://localhost:8080/api/import/upload-vulnerability-xlsx \
  -H "Authorization: Bearer $TOKEN" \
  -F "xlsxFile=@test-vulnerabilities.xlsx" \
  | jq .
```

**Expected**: 400 Bad Request
```json
{
  "error": "Missing required field: scanDate"
}
```

**Step 2**: Test Invalid File Format
```bash
curl -X POST http://localhost:8080/api/import/upload-vulnerability-xlsx \
  -H "Authorization: Bearer $TOKEN" \
  -F "xlsxFile=@test.txt" \
  -F "scanDate=2025-10-03T14:30:00" \
  | jq .
```

**Expected**: 400 Bad Request
```json
{
  "error": "Only .xlsx files are supported"
}
```

**Step 3**: Test Invalid Row (via Excel)
Create `invalid-rows.xlsx`:
| Hostname | Local IP | ... | Vulnerability ID | CVSS severity | ... |
|----------|----------|-----|------------------|---------------|-----|
| | 10.1.1.1 | ... | CVE-2024-0001 | High | ... |
| ValidHost | 10.1.1.2 | ... | CVE-2024-0002 | Medium | ... |

Import file, **Expected**:
```json
{
  "message": "1 imported, 1 skipped (invalid), 0 assets created",
  "imported": 1,
  "skipped": 1,
  "assetsCreated": 0,
  "skippedDetails": [
    {
      "row": 2,
      "reason": "Missing required hostname"
    }
  ]
}
```

---

### Scenario 7: Unauthenticated Access

**Step 1**: Test Without Token
```bash
curl -X POST http://localhost:8080/api/import/upload-vulnerability-xlsx \
  -F "xlsxFile=@test-vulnerabilities.xlsx" \
  -F "scanDate=2025-10-03T14:30:00" \
  | jq .
```

**Expected**: 401 Unauthorized
```json
{
  "message": "Unauthorized"
}
```

---

## Validation Checklist

### Backend API
- [ ] POST /api/import/upload-vulnerability-xlsx accepts multipart/form-data (file + scanDate)
- [ ] Valid file with scan date returns 200 OK with import counts
- [ ] Invalid file format returns 400 Bad Request
- [ ] Missing scan date returns 400 Bad Request
- [ ] Unauthenticated request returns 401 Unauthorized
- [ ] File >10MB returns 413 Payload Too Large
- [ ] GET /api/assets/{id}/vulnerabilities returns vulnerability array
- [ ] Invalid asset ID returns 404 Not Found

### Data Integrity
- [ ] Vulnerabilities linked to correct assets (asset_id FK)
- [ ] Scan timestamp stored correctly (user-specified date, not import date)
- [ ] Empty Excel cells become null in database (not default values)
- [ ] Duplicate CVEs kept as separate records (historical tracking)
- [ ] Assets auto-created with default owner="Security Team", type="Server"
- [ ] Asset merge: groups appended, IP updated, owner/type/description preserved

### Frontend UI
- [ ] "Vulnerabilities" tab visible on Import page
- [ ] Date/time picker displayed, pre-filled with current datetime
- [ ] File upload button functional
- [ ] Success message shows counts: "X imported, Y skipped, Z assets created"
- [ ] Error messages displayed for validation failures
- [ ] Vulnerabilities displayed on asset detail page
- [ ] Vulnerability table shows: CVE ID, Severity, Product Versions, Days Open, Scan Date
- [ ] Asset detail shows new fields: Groups, OS Version, AD Domain, Cloud Account/Instance

### Error Handling
- [ ] Invalid rows skipped, valid rows imported (partial success)
- [ ] Skipped row details returned in API response
- [ ] User-friendly error messages (not stack traces)
- [ ] Transaction rollback on critical errors (DB constraint violations)

### Performance
- [ ] Import of 1000 rows completes in <5 seconds
- [ ] API response time <200ms for GET vulnerabilities (excluding file upload)
- [ ] UI responsive during import (loading indicator, no freeze)

---

## Cleanup
```bash
# Stop application
docker-compose down

# Remove test data (optional)
docker-compose down -v  # Remove volumes to clear database
```

---

## Troubleshooting

### Import Returns 500 Error
- Check backend logs: `docker-compose logs backendng`
- Verify database connection: `docker-compose ps db`
- Check Excel file format: Must be .xlsx (not .xls or .csv)

### Vulnerabilities Not Displayed
- Verify asset ID in URL matches imported hostname
- Check browser console for API errors
- Verify GET /api/assets/{id}/vulnerabilities returns 200

### Asset Not Auto-Created
- Check import response: `assetsCreated` should be >0
- Verify hostname in Excel is unique (not conflicting with existing asset)
- Check backend logs for asset creation errors

### Groups Not Merged Correctly
- Verify asset already existed before import
- Check Excel "Host groups" column format (comma-separated)
- Inspect database: `SELECT groups FROM asset WHERE name = 'AssetName'`

---

## Success Criteria
✅ All validation checklist items pass
✅ Test scenarios 1-7 complete successfully
✅ No errors in backend/frontend logs
✅ Database constraints enforced (FK, NOT NULL)
✅ UI displays data correctly (vulnerabilities on asset page)
✅ Performance targets met (<5s import, <200ms API)
