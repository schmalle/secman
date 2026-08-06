# Quickstart: Vulnerability Query Tool

## Purpose
Manual validation steps to verify the Falcon vulnerability query tool meets all acceptance criteria from the feature specification.

## Prerequisites

1. **CrowdStrike Falcon Account**: Active API credentials with Spotlight Vulnerabilities permissions
2. **Python Environment**: Python 3.11+ installed
3. **Tool Installed**: `falcon-vulns` CLI available in PATH or via `python -m src.cli.main`

## Environment Setup

```bash
# Set required environment variables
export FALCON_CLIENT_ID="your_client_id_here"
export FALCON_CLIENT_SECRET="your_client_secret_here"
export FALCON_CLOUD_REGION="us-1"  # Adjust for your region

# Verify variables are set
echo "Client ID: ${FALCON_CLIENT_ID:0:8}..."  # Show first 8 chars only
echo "Region: $FALCON_CLOUD_REGION"
```

**Expected Output**: Environment variables configured without errors

---

## Validation Scenario 1: Basic Query (Acceptance Scenario 1)

**Test**: Query critical vulnerabilities open 30+ days on servers in specific AD domain

```bash
falcon-vulns \
  --device-type SERVER \
  --severity CRITICAL \
  --min-days-open 30 \
  --ad-domain CORP.LOCAL \
  --format XLSX \
  --verbose
```

**Expected Output**:
```
Found X vulnerabilities matching criteria
Exported to: falcon_vulns_20251005_143022.xlsx
```

**Validation Steps**:
1. ✅ Command completes without error (exit code 0)
2. ✅ Result count displayed before export
3. ✅ XLSX file created with timestamped name
4. ✅ Verbose logging shows API call details (sanitized)
5. ✅ If query takes >10 seconds, progress indication displayed on stderr

---

## Validation Scenario 2: Excel Export Format (Acceptance Scenario 2)

**Test**: Verify XLSX file matches required column structure

```bash
# Run query
falcon-vulns \
  --device-type BOTH \
  --severity HIGH CRITICAL \
  --min-days-open 0 \
  --output test_export.xlsx

# Inspect file (using Python or Excel)
python -c "
import openpyxl
wb = openpyxl.load_workbook('test_export.xlsx')
ws = wb.active
headers = [cell.value for cell in ws[1]]
print('Headers:', headers)
"
```

**Expected Column Order**:
1. Hostname
2. Local IP
3. Host groups
4. Cloud service account ID
5. Cloud service instance ID
6. OS version
7. Active Directory domain
8. Vulnerability ID
9. CVSS severity
10. Vulnerable product versions
11. Days open

**Validation Steps**:
1. ✅ All 11 columns present in exact order
2. ✅ Data types preserved (text for IDs, numbers for scores)
3. ✅ No header row corruption
4. ✅ Sample row values match expected device/vulnerability data

---

## Validation Scenario 3: CSV Export (Acceptance Scenario 3)

**Test**: Export to CSV format with specific hostname filter

```bash
falcon-vulns \
  --device-type BOTH \
  --severity MEDIUM HIGH CRITICAL \
  --min-days-open 0 \
  --hostname WEB-SERVER-01 \
  --format CSV \
  --output test_export.csv

# Inspect CSV
head -n 3 test_export.csv
```

**Expected Output**:
```
Hostname,Local IP,Host groups,Cloud service account ID,Cloud service instance ID,OS version,Active Directory domain,Vulnerability ID,CVSS severity,Vulnerable product versions,Days open
WEB-SERVER-01,10.100.200.1,SVR-WEB-DMZ,,,Windows Server 2019,CORP.LOCAL,CVE-2021-44228,Critical,Apache Log4j 2.14.1,58
...
```

**Validation Steps**:
1. ✅ CSV file created
2. ✅ Comma-delimited format
3. ✅ Proper quoting for fields containing commas
4. ✅ Only WEB-SERVER-01 hostname in results (or all matches if multiple)

---

## Validation Scenario 4: Multiple Severity Filters (Acceptance Scenario 4)

**Test**: OR logic for multiple severity levels

```bash
falcon-vulns \
  --device-type BOTH \
  --severity HIGH CRITICAL \
  --min-days-open 0 \
  --format TXT \
  --output multi_severity.txt
```

**Validation Steps**:
1. ✅ Results include BOTH high AND critical vulnerabilities
2. ✅ No MEDIUM severity vulnerabilities in output
3. ✅ TXT file uses tab-delimited format
4. ✅ Column structure matches CSV/XLSX

---

## Validation Scenario 5: Missing Credentials Error (Acceptance Scenario 5)

**Test**: Clear error message when credentials not configured

```bash
# Temporarily unset credentials
unset FALCON_CLIENT_ID

falcon-vulns \
  --device-type SERVER \
  --severity CRITICAL \
  --min-days-open 30

# Expected: Error message and exit code 1
echo "Exit code: $?"
```

**Expected Output**:
```
ERROR: Missing required environment variable: FALCON_CLIENT_ID
Please set FALCON_CLIENT_ID, FALCON_CLIENT_SECRET, and FALCON_CLOUD_REGION
Exit code: 1
```

**Validation Steps**:
1. ✅ Clear error message identifying missing variable
2. ✅ Exit code = 1 (authentication error)
3. ✅ No API call attempted
4. ✅ Help text suggests required environment variables

---

## Edge Case Validation

### Empty Result Set

```bash
falcon-vulns \
  --device-type SERVER \
  --severity CRITICAL \
  --min-days-open 999999 \
  --format XLSX
```

**Expected**:
- Message: "Found 0 vulnerabilities matching criteria"
- XLSX file created with headers only (no data rows)
- Exit code: 0

**Validation**: ✅ Empty results handled gracefully

---

### Invalid Command-Line Arguments

```bash
# Invalid device type
falcon-vulns --device-type LAPTOP --severity CRITICAL --min-days-open 30
```

**Expected**:
- Error message: "Invalid device type: LAPTOP (must be CLIENT, SERVER, or BOTH)"
- Usage help displayed
- Exit code: 3

**Validation**: ✅ Invalid arguments rejected with clear error

---

### Network Timeout Simulation

**Note**: Requires network manipulation (disconnect, firewall rule, etc.)

```bash
# Simulate network unavailability
# (Test environment specific - may use iptables, airplane mode, etc.)

falcon-vulns --device-type SERVER --severity CRITICAL --min-days-open 30
```

**Expected**:
- Error message: "Network error: Connection timeout"
- Exit code: 2

**Validation**: ✅ Network errors exit with code 2

---

### Unwritable Export Path

```bash
falcon-vulns \
  --device-type SERVER \
  --severity CRITICAL \
  --min-days-open 30 \
  --output /root/protected/vulns.xlsx
```

**Expected**:
- Error message: "Cannot write to /root/protected/vulns.xlsx: Permission denied"
- Exit code: 5

**Validation**: ✅ File write errors exit with code 5

---

## Performance Validation

### Progress Indication (>10 Second Query)

**Setup**: Query conditions expected to take >10 seconds (large dataset, slow network)

```bash
falcon-vulns \
  --device-type BOTH \
  --severity MEDIUM HIGH CRITICAL \
  --min-days-open 0 \
  --verbose
```

**Expected** (after 10 seconds):
```
Fetching page 1/? ... (0 records retrieved)
Fetching page 2/8 ... (500 records retrieved)
Fetching page 3/8 ... (1000 records retrieved)
...
```

**Validation Steps**:
1. ✅ No progress indication for queries <10 seconds
2. ✅ Progress updates display on stderr after 10 second threshold
3. ✅ Page numbers and record counts shown
4. ✅ Total pages estimated after first response

---

### Pagination (Unlimited Result Set)

**Test**: Query returning >5000 results (requires pagination)

```bash
falcon-vulns \
  --device-type BOTH \
  --severity MEDIUM HIGH CRITICAL \
  --min-days-open 0 \
  --output large_dataset.xlsx \
  --verbose
```

**Validation Steps**:
1. ✅ All results retrieved (no truncation)
2. ✅ Verbose log shows multiple API calls
3. ✅ Pagination offset increments correctly
4. ✅ Final record count matches API total

---

## Logging Validation

### Normal Mode (INFO Level)

```bash
falcon-vulns --device-type SERVER --severity CRITICAL --min-days-open 30 2>&1 | tee normal_log.txt
```

**Expected Log Content**:
- Query parameters summary
- Result count
- Export file confirmation
- NO API request/response details
- NO credentials in output

**Validation**: ✅ Minimal, user-friendly output

---

### Extended Mode (DEBUG Level)

```bash
falcon-vulns --device-type SERVER --severity CRITICAL --min-days-open 30 --verbose 2>&1 | tee debug_log.txt
```

**Expected Log Content**:
- All INFO level messages
- API call details (endpoint, filter FQL)
- Response metadata (status code, pagination info)
- Sanitized data (no credentials, sensitive fields removed)

**Validation**: ✅ Comprehensive logging for troubleshooting

---

## Final Validation Checklist

- [ ] ✅ All 5 acceptance scenarios pass
- [ ] ✅ All edge cases handled correctly
- [ ] ✅ Exit codes match specification (0-5)
- [ ] ✅ XLSX export matches required column order
- [ ] ✅ CSV format uses proper delimiters and quoting
- [ ] ✅ TXT format uses tab delimiters
- [ ] ✅ Progress indication after 10 seconds
- [ ] ✅ Unlimited result sets retrieved via pagination
- [ ] ✅ Retry logic handles rate limiting
- [ ] ✅ Credentials never logged at any level
- [ ] ✅ Help text displays correctly
- [ ] ✅ Dual logging modes work as expected

## Troubleshooting

**Issue**: "Authentication failed"
- **Solution**: Verify FALCON_CLIENT_ID, FALCON_CLIENT_SECRET, FALCON_CLOUD_REGION are correct
- **Check**: API credentials have Spotlight Vulnerabilities permission

**Issue**: "No vulnerabilities found" (unexpected)
- **Solution**: Relax filters (lower --min-days-open, use --severity MEDIUM HIGH CRITICAL)
- **Check**: --ad-domain matches devices in your environment

**Issue**: Progress indication not showing
- **Solution**: Query may complete in <10 seconds - reduce result set or test with --verbose
- **Check**: Ensure stderr is visible (not redirected)

**Issue**: Export file not created
- **Solution**: Check --output path is writable
- **Check**: Disk space available

---

## Success Criteria

All validation scenarios pass → Tool ready for production use ✅
