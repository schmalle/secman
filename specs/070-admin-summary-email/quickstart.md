# Quickstart: Admin Summary Email

**Feature**: 070-admin-summary-email
**Date**: 2026-01-27

## Overview

CLI command to send system statistics summary email to all ADMIN users.

## Usage

### Basic Execution

```bash
# Send admin summary email
./scriptpp/secman send-admin-summary

# Preview without sending (dry run)
./scriptpp/secman send-admin-summary --dry-run

# Detailed output
./scriptpp/secman send-admin-summary --verbose

# Combined
./scriptpp/secman send-admin-summary --dry-run --verbose
```

### Expected Output

```
============================================================
SecMan Admin Summary Email
============================================================

📊 Gathering statistics...
   Users: 145
   Vulnerabilities: 23,456
   Assets: 1,234

📧 Sending to 3 ADMIN users...
   ✓ admin@example.com
   ✓ security-lead@example.com
   ✓ ciso@example.com

============================================================
Summary
============================================================
Recipients: 3
Emails sent: 3
Failures: 0

✅ Admin summary email sent successfully
```

### Dry Run Output

```
============================================================
SecMan Admin Summary Email
============================================================

⚠️  DRY-RUN MODE: No emails will be sent

📊 Statistics to be sent:
   Users: 145
   Vulnerabilities: 23,456
   Assets: 1,234

📧 Would send to 3 ADMIN users:
   - admin@example.com
   - security-lead@example.com
   - ciso@example.com

✅ Dry run complete - no emails sent
```

## Cron Scheduling

Weekly summary (every Monday at 8 AM):

```cron
0 8 * * 1 /opt/secman/bin/secman send-admin-summary >> /var/log/secman/admin-summary.log 2>&1
```

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success - all emails sent |
| 1 | Failure - some or all emails failed |

## Prerequisites

1. Email configuration must be active in database
2. At least one user must have ADMIN role with valid email
3. CLI environment configured (see `./scriptpp/secman help`)

## Troubleshooting

| Issue | Solution |
|-------|----------|
| "No active email configuration" | Configure SMTP in Admin → Email Settings |
| "No ADMIN users found" | Assign ADMIN role to at least one user |
| "Email send failed" | Check SMTP settings, verify recipient addresses |
| "ADMIN user skipped" | User has no email address configured |
