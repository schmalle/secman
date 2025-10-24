# Quickstart: Servers Query Import

**Feature**: 032-servers-query-import
**Date**: 2025-10-21

## Overview

The `secman query servers` command queries CrowdStrike Falcon API for HIGH and CRITICAL severity vulnerabilities on servers that have been open for more than 30 days, then automatically imports the discovered servers and vulnerabilities into the secman database.

## Prerequisites

1. **CrowdStrike API credentials configured**:
   ```bash
   secman config --client-id YOUR_CLIENT_ID --client-secret YOUR_CLIENT_SECRET
   ```

2. **Backend API running**: Ensure secman backend is accessible (default: `http://localhost:8080`)

3. **Authentication**: CLI must have backend API credentials configured

## Basic Usage

### Query All Servers

Query all servers in your CrowdStrike tenant:

```bash
secman query servers
```

**Output**:
```
=== CrowdStrike Servers Query ===
Querying vulnerabilities...
- Device type: SERVER
- Severity: HIGH, CRITICAL
- Days open: >= 30 days

Query complete:
- Servers found: 15
- Vulnerabilities found: 127
- Vulnerabilities skipped (no CVE): 3

Importing to secman database...

=== Import Statistics ===
Servers processed: 15
  - New servers: 8
  - Existing servers: 7
Vulnerabilities imported: 124
Vulnerabilities skipped: 3
Errors: 0

Import completed successfully!
```

---

### Query Specific Servers

Query specific servers by hostname:

```bash
secman query servers --hostnames prod-web-01,prod-web-02,prod-db-01
```

**Output**:
```
=== CrowdStrike Servers Query ===
Filtering by hostnames: prod-web-01, prod-web-02, prod-db-01

Querying vulnerabilities...

Query complete:
- Servers found: 3
- Vulnerabilities found: 18

Importing to secman database...

=== Import Statistics ===
Servers processed: 3
  - New servers: 1
  - Existing servers: 2
Vulnerabilities imported: 18
Vulnerabilities skipped: 0
Errors: 0

Import completed successfully!
```

---

### Dry Run (Preview Only)

Preview what would be imported without actually storing data:

```bash
secman query servers --dry-run
```

**Output**:
```
=== CrowdStrike Servers Query (DRY RUN) ===
Querying vulnerabilities...

Query complete:
- Servers found: 15
- Vulnerabilities found: 127

DRY RUN - No data imported to database.

Servers that would be processed:
  1. prod-web-01 (5 vulnerabilities)
  2. prod-web-02 (8 vulnerabilities)
  3. prod-db-01 (12 vulnerabilities)
  4. prod-app-01 (3 vulnerabilities)
  ...

Use --verbose for detailed vulnerability list.
```

---

### Verbose Output

Display detailed logging during query and import:

```bash
secman query servers --verbose
```

**Output**:
```
[2025-10-21 10:15:00] INFO: CrowdStrike API configuration loaded
[2025-10-21 10:15:00] INFO: Authenticating with CrowdStrike...
[2025-10-21 10:15:01] INFO: Authentication successful
[2025-10-21 10:15:01] INFO: Building query filter:
  - device_type:SERVER
  - severity:HIGH OR severity:CRITICAL
  - days_open:>=30
[2025-10-21 10:15:02] INFO: Querying CrowdStrike API (page 1)...
[2025-10-21 10:15:05] INFO: Found 100 vulnerabilities (100 total)
[2025-10-21 10:15:05] INFO: Querying CrowdStrike API (page 2)...
[2025-10-21 10:15:08] INFO: Found 27 vulnerabilities (127 total)
[2025-10-21 10:15:08] INFO: Query complete - 127 vulnerabilities across 15 servers
[2025-10-21 10:15:08] WARN: Skipping vulnerability without CVE ID for server prod-web-03
[2025-10-21 10:15:08] WARN: Skipping vulnerability without CVE ID for server prod-app-02
[2025-10-21 10:15:08] WARN: Skipping vulnerability without CVE ID for server prod-db-05
[2025-10-21 10:15:08] INFO: Sending import request to backend API...
[2025-10-21 10:15:10] INFO: Import successful - 124 vulnerabilities imported

=== Import Statistics ===
Servers processed: 15
  - New servers: 8
  - Existing servers: 7
Vulnerabilities imported: 124
Vulnerabilities skipped: 3
Errors: 0
```

---

## Command Options

| Option | Description | Default |
|--------|-------------|---------|
| `--hostnames <list>` | Comma-separated server hostnames to filter (e.g., `server01,server02`) | All servers |
| `--backend-url <url>` | Backend API URL | `http://localhost:8080` |
| `--dry-run` | Query CrowdStrike but don't import to database | false |
| `--verbose` | Display detailed logging | false |
| `--client-id <id>` | Override CrowdStrike client ID from config | (from config) |
| `--client-secret <secret>` | Override CrowdStrike client secret from config | (from config) |

---

## Examples

### Production Servers Only

```bash
secman query servers --hostnames $(cat production-servers.txt | tr '\n' ',')
```

### Custom Backend URL

```bash
secman query servers --backend-url https://secman.company.com:8080
```

### Temporary Credentials Override

```bash
secman query servers \
  --client-id YOUR_CLIENT_ID \
  --client-secret YOUR_CLIENT_SECRET \
  --verbose
```

### Dry Run with Verbose Logging

```bash
secman query servers --dry-run --verbose --hostnames prod-web-01
```

---

## Expected Behavior

### First Run (New Servers)

When running for the first time on a server:
1. Server asset is created with type="SERVER", owner="CrowdStrike Import"
2. All metadata fields populated from CrowdStrike (groups, cloudAccountId, etc.)
3. All HIGH/CRITICAL vulnerabilities (>30 days) are imported
4. Statistics show server as "new"

### Subsequent Runs (Existing Servers)

When running again on a previously imported server:
1. Existing server asset is found by hostname
2. Metadata fields are UPDATED from CrowdStrike
3. ALL old vulnerabilities are DELETED
4. ALL current vulnerabilities are IMPORTED (full replacement)
5. Statistics show server as "existing"

### Partial Failures

If some servers fail to import:
1. Successful servers complete their imports
2. Failed servers rollback (old vulnerabilities retained)
3. Statistics show counts for successful imports
4. Errors array contains failure messages

**Example**:
```
=== Import Statistics ===
Servers processed: 10
  - New servers: 4
  - Existing servers: 4
Vulnerabilities imported: 67
Vulnerabilities skipped: 2
Errors: 2
  - Failed to import vulnerabilities for server 'prod-db-02': Database constraint violation
  - Failed to import vulnerabilities for server 'prod-app-01': Transaction timeout
```

---

## Rate Limiting

CrowdStrike API has rate limits (429 responses). The CLI automatically retries with exponential backoff:

**Retry sequence**: 1s → 2s → 4s → 8s (max 3 retries)

**Example output**:
```
[2025-10-21 10:15:02] WARN: Rate limit exceeded (HTTP 429), retrying in 1 second...
[2025-10-21 10:15:03] INFO: Retry 1/3 - Querying CrowdStrike API...
[2025-10-21 10:15:06] INFO: Query successful
```

If rate limit persists after all retries:
```
ERROR: CrowdStrike API rate limit exceeded after 3 retries. Please wait and try again later.
```

---

## Troubleshooting

### "Authentication failed"

**Cause**: Invalid CrowdStrike API credentials

**Solution**:
```bash
# Re-configure credentials
secman config --client-id YOUR_CLIENT_ID --client-secret YOUR_CLIENT_SECRET

# Verify configuration
secman config --show
```

---

### "Backend API unreachable"

**Cause**: secman backend service not running or incorrect URL

**Solution**:
```bash
# Check backend status
curl http://localhost:8080/api/health

# Or specify custom backend URL
secman query servers --backend-url http://different-host:8080
```

---

### "No vulnerabilities found"

**Cause**: No servers have HIGH/CRITICAL vulnerabilities open >30 days

**Solution**:
```bash
# Verify with dry run
secman query servers --dry-run --verbose

# Check CrowdStrike console to confirm query results match
```

---

### "Vulnerabilities skipped (no CVE)"

**Cause**: CrowdStrike returned vulnerabilities without CVE identifiers

**Behavior**: These are logged as warnings and excluded from import

**Action**: Review warnings in verbose output, verify data quality in CrowdStrike console

```bash
secman query servers --verbose
```

---

## Integration with Existing Workflows

### Daily Automated Import

```bash
#!/bin/bash
# cron job: 0 2 * * * /opt/secman/scripts/daily-import.sh

cd /opt/secman
./secman query servers --verbose > /var/log/secman/import-$(date +\%Y\%m\%d).log 2>&1
```

### Alert on High Vulnerability Count

```bash
#!/bin/bash
OUTPUT=$(./secman query servers)
VULN_COUNT=$(echo "$OUTPUT" | grep "Vulnerabilities imported:" | awk '{print $3}')

if [ "$VULN_COUNT" -gt 100 ]; then
    echo "High vulnerability count: $VULN_COUNT" | mail -s "Secman Alert" security@company.com
fi
```

---

## Related Documentation

- [Feature Specification](spec.md) - Complete requirements and user stories
- [Implementation Plan](plan.md) - Technical design and architecture
- [Data Model](data-model.md) - Entity structure and relationships
- [API Contract](contracts/crowdstrike-vulnerabilities-save.openapi.yaml) - Backend endpoint specification
- [CrowdStrike CLI Monitor](../../src/cli/MONITOR.md) - Related monitoring feature (Feature 026)
