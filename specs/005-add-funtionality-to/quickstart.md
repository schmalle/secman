# Quickstart: Masscan XML Import

**Feature**: 005-add-funtionality-to
**Date**: 2025-10-04
**Purpose**: Validate that Masscan XML import feature works end-to-end

## Prerequisites

Before starting, ensure:
- [x] Docker Compose services running (`docker-compose up -d`)
- [x] Backend accessible at http://localhost:8080
- [x] Frontend accessible at http://localhost:4321
- [x] Test database initialized
- [x] User account created (for authentication)
- [x] Test file available: `testdata/masscan.xml`

## Quick Validation (5 minutes)

### Step 1: Authenticate
```bash
# Get JWT token
TOKEN=$(curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"testpass"}' \
  | jq -r '.access_token')

echo "Token: $TOKEN"
```

**Expected**: JWT token returned (not null)

### Step 2: Upload Masscan XML
```bash
# Upload testdata/masscan.xml
curl -X POST http://localhost:8080/api/import/upload-masscan-xml \
  -H "Authorization: Bearer $TOKEN" \
  -F "xmlFile=@testdata/masscan.xml" \
  | jq .
```

**Expected Output**:
```json
{
  "message": "Imported 2 ports across 1 new assets",
  "assetsCreated": 1,
  "assetsUpdated": 0,
  "portsImported": 2
}
```

### Step 3: Verify Asset Created
```bash
# Query assets to find newly created asset
curl -X GET "http://localhost:8080/api/assets" \
  -H "Authorization: Bearer $TOKEN" \
  | jq '.[] | select(.ip == "193.99.144.85")'
```

**Expected Output**:
```json
{
  "id": 123,
  "name": null,
  "ip": "193.99.144.85",
  "type": "Scanned Host",
  "owner": "Security Team",
  "description": "",
  "lastSeen": "2025-10-04T08:49:32"
}
```

**Verification**:
- [x] Asset exists with IP 193.99.144.85
- [x] name is null (Masscan doesn't provide hostname)
- [x] type is "Scanned Host" (default)
- [x] owner is "Security Team" (default)
- [x] description is empty string (default)
- [x] lastSeen matches timestamp from XML (endtime: 1759560572)

### Step 4: Verify Scan Results Created
```bash
# Get asset ID from previous step
ASSET_ID=123

# Query scan results for this asset
curl -X GET "http://localhost:8080/api/assets/$ASSET_ID/scan-results" \
  -H "Authorization: Bearer $TOKEN" \
  | jq .
```

**Expected Output**:
```json
[
  {
    "id": 456,
    "port": 80,
    "protocol": "tcp",
    "state": "open",
    "service": null,
    "product": null,
    "version": null,
    "discoveredAt": "2025-10-04T08:49:32"
  },
  {
    "id": 457,
    "port": 443,
    "protocol": "tcp",
    "state": "open",
    "service": null,
    "product": null,
    "version": null,
    "discoveredAt": "2025-10-04T08:49:32"
  }
]
```

**Verification**:
- [x] 2 scan results created (ports 80 and 443)
- [x] Both have state="open" (filtered correctly)
- [x] service, product, version are null (Masscan doesn't provide)
- [x] discoveredAt matches endtime from XML

### Step 5: Re-upload Same File (Test Update Behavior)
```bash
# Upload same file again
curl -X POST http://localhost:8080/api/import/upload-masscan-xml \
  -H "Authorization: Bearer $TOKEN" \
  -F "xmlFile=@testdata/masscan.xml" \
  | jq .
```

**Expected Output**:
```json
{
  "message": "Imported 2 ports, updated 1 existing asset",
  "assetsCreated": 0,
  "assetsUpdated": 1,
  "portsImported": 2
}
```

**Verification**:
- [x] assetsCreated is 0 (asset already exists)
- [x] assetsUpdated is 1 (lastSeen timestamp updated)
- [x] portsImported is 2 (new scan results created, duplicates allowed)

### Step 6: Verify Duplicate Scan Results Created
```bash
# Query scan results again
curl -X GET "http://localhost:8080/api/assets/$ASSET_ID/scan-results" \
  -H "Authorization: Bearer $TOKEN" \
  | jq 'length'
```

**Expected Output**: `4` (2 original + 2 new from re-import)

**Verification**:
- [x] Total scan results is 4 (historical tracking, no deduplication)
- [x] Each import creates separate ScanResult entries

## Frontend Validation (Manual)

### Step 7: Access Import UI
1. Navigate to http://localhost:4321/import
2. Verify "Masscan XML" option appears in import type selector
3. Select "Masscan XML" from dropdown
4. Click "Choose File" and select `testdata/masscan.xml`
5. Click "Upload" button

**Expected**:
- Success message: "Imported 2 ports across 0 new assets, updated 1 existing asset"
- Import statistics displayed
- No errors in browser console

### Step 8: View Asset Details
1. Navigate to http://localhost:4321/assets
2. Find asset with IP 193.99.144.85
3. Click on asset to view details page

**Expected**:
- Asset details show:
  - Name: (empty)
  - IP: 193.99.144.85
  - Type: Scanned Host
  - Owner: Security Team
  - Last Seen: (timestamp from XML)
- Scan Results section shows ports 80 and 443
- Each port has multiple historical entries (if re-imported)

## Error Handling Validation

### Test 9: Invalid File Extension
```bash
# Try to upload .txt file
echo "not xml" > /tmp/test.txt
curl -X POST http://localhost:8080/api/import/upload-masscan-xml \
  -H "Authorization: Bearer $TOKEN" \
  -F "xmlFile=@/tmp/test.txt" \
  | jq .
```

**Expected Output**:
```json
{
  "error": "Only .xml files are supported"
}
```

**Expected HTTP Status**: 400 Bad Request

### Test 10: Oversized File
```bash
# Create 11MB file (exceeds 10MB limit)
dd if=/dev/zero of=/tmp/large.xml bs=1M count=11
curl -X POST http://localhost:8080/api/import/upload-masscan-xml \
  -H "Authorization: Bearer $TOKEN" \
  -F "xmlFile=@/tmp/large.xml" \
  | jq .
```

**Expected Output**:
```json
{
  "error": "File size exceeds maximum limit of 10MB"
}
```

**Expected HTTP Status**: 400 Bad Request

### Test 11: Malformed XML
```bash
# Create invalid XML file
cat > /tmp/invalid.xml <<EOF
<?xml version="1.0"?>
<nmaprun scanner="masscan" start="123">
  <host endtime="123">
    <address addr="192.168.1.1"
  </host>
EOF

curl -X POST http://localhost:8080/api/import/upload-masscan-xml \
  -H "Authorization: Bearer $TOKEN" \
  -F "xmlFile=@/tmp/invalid.xml" \
  | jq .
```

**Expected Output**:
```json
{
  "error": "Invalid Masscan XML format: ..."
}
```

**Expected HTTP Status**: 400 Bad Request

### Test 12: Wrong Scanner Type (Nmap XML)
```bash
# Create Nmap XML (not Masscan)
cat > /tmp/nmap.xml <<EOF
<?xml version="1.0"?>
<nmaprun scanner="nmap" start="1234567890">
  <host>
    <address addr="192.168.1.1" addrtype="ipv4"/>
  </host>
</nmaprun>
EOF

curl -X POST http://localhost:8080/api/import/upload-masscan-xml \
  -H "Authorization: Bearer $TOKEN" \
  -F "xmlFile=@/tmp/nmap.xml" \
  | jq .
```

**Expected Output**:
```json
{
  "error": "Not a Masscan XML file (scanner=nmap)"
}
```

**Expected HTTP Status**: 400 Bad Request

### Test 13: Unauthenticated Request
```bash
# Try to upload without token
curl -X POST http://localhost:8080/api/import/upload-masscan-xml \
  -F "xmlFile=@testdata/masscan.xml" \
  | jq .
```

**Expected HTTP Status**: 401 Unauthorized

## Performance Validation

### Test 14: Large File Performance
```bash
# Generate large Masscan XML with 1000 hosts
python3 scripts/generate-large-masscan.xml.py --hosts 1000 --output /tmp/large-scan.xml

# Time the upload
time curl -X POST http://localhost:8080/api/import/upload-masscan-xml \
  -H "Authorization: Bearer $TOKEN" \
  -F "xmlFile=@/tmp/large-scan.xml" \
  | jq .
```

**Expected**:
- Processing time < 10 seconds
- All 1000 hosts imported successfully
- API response < 200ms p95 (check logs for timing)
- No memory errors or timeouts

## Database Validation

### Test 15: Verify Database State
```bash
# Connect to MariaDB
docker exec -it secman-db mysql -u secman -psecman secman

# Count assets created
SELECT COUNT(*) FROM asset WHERE type = 'Scanned Host';

# Count scan results
SELECT COUNT(*) FROM scan_result WHERE state = 'open';

# Verify no service/product/version data (should be NULL)
SELECT COUNT(*) FROM scan_result
WHERE service IS NOT NULL OR product IS NOT NULL OR version IS NOT NULL;

# Expected: 0 (Masscan doesn't provide service detection)

# Verify timestamps are preserved
SELECT ip, last_seen FROM asset WHERE ip = '193.99.144.85';
# Expected: last_seen = 2025-10-04 08:49:32 (from endtime: 1759560572)
```

## Cleanup

```bash
# Delete test assets
curl -X DELETE "http://localhost:8080/api/assets/$ASSET_ID" \
  -H "Authorization: Bearer $TOKEN"

# Remove temporary files
rm /tmp/test.txt /tmp/large.xml /tmp/invalid.xml /tmp/nmap.xml /tmp/large-scan.xml
```

## Success Criteria

Feature is considered working if:
- [x] Valid Masscan XML uploads successfully
- [x] Assets created with correct default values
- [x] Only open ports imported (filtering works)
- [x] Duplicate ports create separate scan results (historical tracking)
- [x] Existing assets updated (lastSeen timestamp only)
- [x] Error handling works (validation, malformed XML, wrong scanner)
- [x] Authentication enforced
- [x] Frontend UI integration works
- [x] Performance acceptable (<10s for 1000 hosts)
- [x] Database state correct (nulls where expected, timestamps preserved)

## Troubleshooting

**Issue**: Import fails with "Database constraint violation"
- **Solution**: Verify Asset entity allows null name field

**Issue**: All scan results show service/product/version as null
- **Solution**: This is expected - Masscan doesn't provide service detection

**Issue**: Import succeeds but 0 ports imported
- **Solution**: Check if Masscan XML contains only closed/filtered ports (should be skipped)

**Issue**: Frontend shows "Unknown error"
- **Solution**: Check browser console and backend logs for detailed error

## Next Steps

After validating quickstart:
1. Run full test suite: `./gradlew test`
2. Run E2E tests: `npm run test:e2e`
3. Review code coverage report: `./gradlew jacocoTestReport`
4. Check for security vulnerabilities: `./gradlew dependencyCheckAnalyze`

---
*Quickstart validation complete - ready for task generation*
