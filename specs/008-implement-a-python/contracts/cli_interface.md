# CLI Interface Contract

## Overview
Command-line interface specification for the Falcon vulnerability query tool.

## Command Name

`falcon-vulns` (or `python -m src.cli.main`)

## Usage Pattern

```
falcon-vulns --device-type TYPE --severity SEV [SEV...] --min-days-open N
             [--ad-domain DOMAIN] [--hostname NAME]
             [--output PATH] [--format {XLSX,CSV,TXT}]
             [--verbose] [--help]
```

---

## Required Arguments

### `--device-type {CLIENT,SERVER,BOTH}`
- **Purpose**: Filter vulnerabilities by device classification
- **Values**:
  - `CLIENT`: Workstation/endpoint devices only
  - `SERVER`: Server devices only
  - `BOTH`: No device type filtering
- **Validation**: Must be one of the three values (case-insensitive accepted, converted to uppercase)
- **Error**: Missing or invalid → Print usage, exit code 3

### `--severity {MEDIUM,HIGH,CRITICAL} [{MEDIUM,HIGH,CRITICAL} ...]`
- **Purpose**: Filter by vulnerability severity levels (OR logic)
- **Values**: One or more of MEDIUM, HIGH, CRITICAL
- **Examples**:
  - `--severity CRITICAL` - Only critical
  - `--severity HIGH CRITICAL` - High OR Critical
  - `--severity MEDIUM HIGH CRITICAL` - All three
- **Validation**: At least one value required, all must be valid severity levels
- **Error**: Missing or invalid → Print usage, exit code 3

### `--min-days-open N`
- **Purpose**: Filter vulnerabilities open for at least N days
- **Value**: Non-negative integer
- **Examples**:
  - `--min-days-open 0` - All vulnerabilities (no age filter)
  - `--min-days-open 30` - Vulnerabilities open 30+ days
  - `--min-days-open 90` - Vulnerabilities open 90+ days
- **Validation**: Must be integer >= 0
- **Error**: Missing, non-integer, or negative → Print usage, exit code 3

---

## Optional Arguments

### `--ad-domain DOMAIN`
- **Purpose**: Filter devices by Active Directory domain membership
- **Value**: Domain name string (e.g., "CORP.LOCAL", "PROD.EXAMPLE.COM")
- **Default**: None (no AD domain filtering)
- **Validation**: Non-empty string if provided
- **Behavior**: Case-insensitive match

### `--hostname NAME`
- **Purpose**: Filter to specific hostname(s)
- **Value**: Exact hostname or pattern
- **Default**: None (all hostnames)
- **Validation**: Non-empty string if provided
- **Behavior**: If multiple devices match, return all (per edge case clarification)

### `--output PATH`
- **Purpose**: Specify custom export file path
- **Value**: Absolute or relative file path
- **Default**: None (use auto-generated filename: `falcon_vulns_YYYYMMDD_HHMMSS.{ext}`)
- **Validation**: Directory must be writable
- **Error**: Unwritable path → Error message, exit code 5

### `--format {XLSX,CSV,TXT}`
- **Purpose**: Select export format
- **Values**:
  - `XLSX`: Excel workbook (default)
  - `CSV`: Comma-delimited
  - `TXT`: Tab-delimited
- **Default**: `XLSX`
- **Validation**: Must be one of three values (case-insensitive)

### `--verbose`
- **Purpose**: Enable extended logging (DEBUG level)
- **Default**: False (INFO level logging)
- **Behavior**: Includes API call details, request/response metadata (sanitized)
- **Constitutional**: Dual logging mode requirement

### `--help` / `-h`
- **Purpose**: Display usage help and exit
- **Behavior**: Print comprehensive help text, exit code 0
- **Output**: Includes all arguments, examples, environment variable requirements

---

## Output Behavior

### Standard Output (stdout)
- **Normal Mode**: Result count message, export confirmation
- **Verbose Mode**: Additional progress updates, API call logging
- **Examples**:
  ```
  Found 42 vulnerabilities matching criteria
  Exported to: falcon_vulns_20251005_143022.xlsx
  ```

### Standard Error (stderr)
- **Errors**: All error messages
- **Progress**: Long-running query progress (after 10 seconds)
- **Examples**:
  ```
  ERROR: Authentication failed - invalid credentials (exit code 1)
  Fetching page 3/10... (1247 records retrieved)
  ```

---

## Exit Codes

| Code | Meaning | Trigger Conditions |
|------|---------|-------------------|
| 0 | Success | Query completed, export successful (including 0 results) |
| 1 | Authentication Error | Missing/invalid FALCON_CLIENT_ID, FALCON_CLIENT_SECRET, FALCON_CLOUD_REGION, or API auth failure |
| 2 | Network Error | Connection timeout, DNS resolution failure, network unreachable |
| 3 | Invalid Arguments | Missing required arg, invalid value, parse error |
| 4 | API Error | Rate limit retries exhausted, malformed API response, server error after retries |
| 5 | Export Error | Cannot write file, insufficient disk space, permission denied |

---

## Environment Variables

**Required** (validated before execution):
- `FALCON_CLIENT_ID`: CrowdStrike Falcon API client ID
- `FALCON_CLIENT_SECRET`: CrowdStrike Falcon API client secret
- `FALCON_CLOUD_REGION`: Falcon cloud region (e.g., "us-1", "us-2", "eu-1", "us-gov-1")

**Validation**:
- Check presence before API calls
- Never log values (constitutional requirement)
- Error if missing → Exit code 1 with clear message

---

## Example Invocations

### Basic Query
```bash
export FALCON_CLIENT_ID=abc123
export FALCON_CLIENT_SECRET=xyz789
export FALCON_CLOUD_REGION=us-1

falcon-vulns --device-type SERVER --severity CRITICAL --min-days-open 30
```

### Filtered by Domain
```bash
falcon-vulns --device-type BOTH --severity HIGH CRITICAL \
             --min-days-open 90 --ad-domain CORP.LOCAL \
             --output /reports/vulns.xlsx
```

### Specific Host with Verbose Logging
```bash
falcon-vulns --device-type BOTH --severity MEDIUM HIGH CRITICAL \
             --min-days-open 0 --hostname WEB-SERVER-01 \
             --format CSV --verbose
```

### CSV Export with Custom Path
```bash
falcon-vulns --device-type CLIENT --severity CRITICAL \
             --min-days-open 7 --format CSV \
             --output ./weekly-client-critical.csv
```

---

## Help Text

```
usage: falcon-vulns [--help] --device-type {CLIENT,SERVER,BOTH}
                    --severity {MEDIUM,HIGH,CRITICAL} [{MEDIUM,HIGH,CRITICAL} ...]
                    --min-days-open N
                    [--ad-domain DOMAIN] [--hostname NAME]
                    [--output PATH] [--format {XLSX,CSV,TXT}]
                    [--verbose]

Query CrowdStrike Falcon for device vulnerabilities with filtering and export.

Required Arguments:
  --device-type {CLIENT,SERVER,BOTH}
                        Device type filter (CLIENT=workstations, SERVER=servers, BOTH=all)
  --severity {MEDIUM,HIGH,CRITICAL} [{MEDIUM,HIGH,CRITICAL} ...]
                        Vulnerability severity levels (space-separated for multiple)
  --min-days-open N     Minimum days vulnerability has been open (0=all ages)

Optional Arguments:
  --ad-domain DOMAIN    Filter by Active Directory domain
  --hostname NAME       Filter by specific hostname
  --output PATH         Custom export file path (default: auto-generated with timestamp)
  --format {XLSX,CSV,TXT}
                        Export format (default: XLSX)
  --verbose             Enable detailed logging (DEBUG level)
  --help, -h            Show this help message and exit

Required Environment Variables:
  FALCON_CLIENT_ID      CrowdStrike Falcon API client ID
  FALCON_CLIENT_SECRET  CrowdStrike Falcon API client secret
  FALCON_CLOUD_REGION   Falcon cloud region (us-1, us-2, eu-1, us-gov-1, etc.)

Exit Codes:
  0  Success
  1  Authentication error
  2  Network error
  3  Invalid command-line arguments
  4  API error (rate limit, server error)
  5  Export file write error

Examples:
  # Query critical vulnerabilities on servers open 30+ days
  falcon-vulns --device-type SERVER --severity CRITICAL --min-days-open 30

  # Export high/critical vulns in CORP.LOCAL domain to CSV
  falcon-vulns --device-type BOTH --severity HIGH CRITICAL \\
               --min-days-open 90 --ad-domain CORP.LOCAL --format CSV

For more information: https://www.falconpy.io
```

---

## Contract Test Coverage

**Tests to Implement** (must FAIL before implementation):

1. `test_missing_required_args_exits_3()` - Missing --device-type exits with code 3
2. `test_invalid_device_type_exits_3()` - Invalid value for --device-type exits 3
3. `test_invalid_severity_exits_3()` - Invalid severity value exits 3
4. `test_negative_min_days_exits_3()` - Negative --min-days-open exits 3
5. `test_help_flag_exits_0()` - --help prints usage and exits 0
6. `test_missing_env_vars_exits_1()` - Missing FALCON_CLIENT_ID exits 1
7. `test_unwritable_output_path_exits_5()` - Cannot write to --output path exits 5
8. `test_valid_args_parsed_correctly()` - All arguments parsed into FilterCriteria
9. `test_multiple_severities_parsed()` - Multiple --severity values parsed as list
10. `test_default_format_is_xlsx()` - No --format specified defaults to XLSX
