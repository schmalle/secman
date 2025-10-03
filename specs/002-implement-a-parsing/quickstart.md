# Quickstart: Nmap Scan Import Feature

**Feature**: 002-implement-a-parsing
**Purpose**: Manual testing and validation guide
**Prerequisite**: Implementation complete, Docker environment running

## Overview

This guide walks through the complete nmap scan import workflow from upload to viewing port history. Follow these steps after implementing all tasks to verify the feature works end-to-end.

---

## Setup

### 1. Start Development Environment

```bash
cd /Users/flake/sources/misc/secman
./docker/scripts/dev.sh up -d
```

**Verify**:
- Frontend: http://localhost:4321
- Backend: http://localhost:8080/health
- Database: `mysql -h localhost -u secman -pCHANGEME secman`

### 2. Prepare Test Data

Use the existing nmap test file:
```bash
ls -lh testdata/nmap.xml
# Should show: testdata/nmap.xml (single host: www.heise.de with ports 80, 443)
```

### 3. Login as Admin

- Navigate to http://localhost:4321
- Login: `adminuser` / `password`
- Verify ADMIN role: Check sidebar shows "Scans" entry

---

## Test Scenario 1: Upload Nmap Scan

### Steps

1. **Navigate to Import page**
   - Click "Import" in sidebar
   - Verify page loads with file upload interface

2. **Upload nmap file**
   - Click "Browse Files" or drag testdata/nmap.xml
   - Verify file name appears: "nmap.xml"
   - Click "Upload and Process Requirements" button
   - Wait for processing (should complete <5s for single host)

3. **Verify upload success**
   - Success message appears: "Scan uploaded and processed successfully"
   - Summary shows:
     - Hosts discovered: 1
     - Assets created/updated: 1
     - Total ports: 2 (or 3 depending on closed ports)

**Expected Database State**:
```sql
SELECT * FROM scan ORDER BY id DESC LIMIT 1;
-- Should show: scan_type='nmap', host_count=1, uploaded_by='adminuser'

SELECT * FROM scan_result WHERE scan_id = <scan_id>;
-- Should show: ip_address='193.99.144.85', hostname='www.heise.de'

SELECT * FROM scan_port WHERE scan_result_id = <result_id>;
-- Should show: port 80 (state=open), port 443 (state=open)

SELECT * FROM asset WHERE ip = '193.99.144.85';
-- Should show: name='www.heise.de', type='Network Host', owner='adminuser'
```

---

## Test Scenario 2: View Scan History (Admin)

### Steps

1. **Navigate to Scans page**
   - Click "Scans" in sidebar
   - Verify page loads (admin-only access)

2. **Verify scan appears in list**
   - Table shows uploaded scan
   - Columns display:
     - Scan date: 2025-10-03 08:33:50 (from XML)
     - Filename: nmap.xml
     - Type: nmap
     - Hosts: 1
     - Duration: ~159 seconds
     - Uploaded by: adminuser

3. **View scan details**
   - Click scan row or "View Details" button
   - Detail view shows:
     - Scan metadata (same as list)
     - Host list table:
       - IP: 193.99.144.85
       - Hostname: www.heise.de
       - Asset link: clickable to asset page
       - Open ports: 2 (or 3)

**Expected API Response** (`GET /api/scans`):
```json
{
  "scans": [{
    "id": 1,
    "scanType": "nmap",
    "filename": "nmap.xml",
    "scanDate": "2025-10-03T08:33:50Z",
    "uploadedBy": "adminuser",
    "hostCount": 1,
    "duration": 159,
    "createdAt": "2025-10-03T..."
  }],
  "pagination": {
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

---

## Test Scenario 3: View Port History (All Users)

### Steps

1. **Navigate to Asset Management**
   - Click "Asset Management" in sidebar
   - Verify asset list loads

2. **Find scanned asset**
   - Locate asset "www.heise.de" (or 193.99.144.85)
   - Verify "Show open ports" button appears (enabled)

3. **View port history**
   - Click "Show open ports" button
   - Modal/panel opens showing port scan timeline
   - Displays:
     - Scan date: 2025-10-03 08:33:50
     - Scan type: nmap
     - Port list:
       - Port 80/tcp - open - http
       - Port 443/tcp - open - https

**Expected API Response** (`GET /api/assets/{id}/ports`):
```json
{
  "assetId": 1,
  "assetName": "www.heise.de",
  "ipAddress": "193.99.144.85",
  "scanHistory": [{
    "scanId": 1,
    "scanDate": "2025-10-03T08:33:50Z",
    "scanType": "nmap",
    "hostname": "www.heise.de",
    "ports": [
      {
        "portNumber": 80,
        "protocol": "tcp",
        "state": "open",
        "service": "http",
        "version": "nginx"
      },
      {
        "portNumber": 443,
        "protocol": "tcp",
        "state": "open",
        "service": "https",
        "version": null
      }
    ]
  }]
}
```

---

## Test Scenario 4: Multiple Scans Over Time

### Steps

1. **Create second nmap scan**
   ```bash
   # Simulate scanning same host later (manually edit nmap.xml scan date if needed)
   # Or use a different nmap file from your archives
   ```

2. **Upload second scan**
   - Navigate to Import page
   - Upload modified/new nmap.xml
   - Verify upload success

3. **Verify scan history accumulates**
   - Go to Scans page (admin)
   - Verify 2 scans listed
   - Go to asset page
   - Click "Show open ports"
   - Verify 2 scan results in timeline (chronological order)

**Expected Behavior**:
- Asset count stays at 1 (same IP reused)
- ScanResult count increases to 2
- Port history shows timeline of changes

---

## Test Scenario 5: RBAC Enforcement

### Steps

1. **Logout and login as normal user**
   - Logout adminuser
   - Login as `normaluser` / `password`

2. **Verify Scans page hidden**
   - Check sidebar: "Scans" entry should NOT appear
   - Direct navigation to /scans should redirect or show 403

3. **Verify port view still accessible**
   - Navigate to Asset Management
   - Find scanned asset
   - Click "Show open ports"
   - Verify port history loads (authenticated users allowed)

**Expected Behavior**:
- Admin-only pages return 403 for normal users
- Asset port view works for all authenticated users

---

## Test Scenario 6: Error Handling

### Steps

1. **Test invalid file upload**
   - Try uploading non-XML file (e.g., .txt)
   - Verify error: "File is not valid XML"

2. **Test malformed XML**
   - Create file with invalid XML syntax
   - Verify error: "XML parsing failed"

3. **Test empty scan**
   - Upload nmap XML with no hosts
   - Verify success with hostCount=0

4. **Test large file**
   - Upload 50MB+ file
   - Verify error: "File too large"

---

## Validation Checklist

After running all scenarios, verify:

- [x] Nmap XML upload works
- [x] Assets created with correct name/type/IP
- [x] Scan metadata stored correctly
- [x] Port data parsed and stored
- [x] Scans page shows scan history (admin-only)
- [x] Asset page shows "Show open ports" button
- [x] Port history displays chronologically
- [x] Multiple scans of same host tracked separately
- [x] RBAC enforced (admin vs normal user)
- [x] Error handling works (invalid files, large files)
- [x] Database foreign keys prevent orphaned data
- [x] API responses match contract schemas

---

## Database Cleanup (After Testing)

```sql
-- Remove test data
DELETE FROM scan_port WHERE scan_result_id IN (SELECT id FROM scan_result);
DELETE FROM scan_result WHERE scan_id IN (SELECT id FROM scan);
DELETE FROM scan;
DELETE FROM asset WHERE owner = 'adminuser' AND type = 'Network Host';
```

---

## Performance Benchmarks

Test with larger files to verify performance goals:

```bash
# Generate large nmap file (1000 hosts)
nmap -sS -oX /tmp/large-scan.xml 192.168.1.0/24

# Upload and time
time curl -X POST http://localhost:8080/api/scan/upload-nmap \
  -H "Authorization: Bearer <token>" \
  -F "file=@/tmp/large-scan.xml"

# Verify: Should complete in <30s for 1000 hosts
```

---

## Acceptance Criteria

✅ Feature is **DONE** when:
1. All 6 test scenarios pass
2. All validation checklist items checked
3. Performance benchmarks met
4. No errors in backend logs
5. Frontend renders without console errors
6. Database constraints prevent invalid data

**Sign-off**: [Developer name] - [Date]
