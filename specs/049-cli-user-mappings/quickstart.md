# Quickstart Guide: CLI User Mapping Management

**Feature**: 049-cli-user-mappings
**Audience**: System administrators with ADMIN role
**Estimated Time**: 10 minutes

## Prerequisites

1. **Access Requirements**:
   - ADMIN role in SecMan
   - Access to server where SecMan is deployed
   - Command-line access (SSH/terminal)

2. **Environment Setup**:
   - Java 21+ installed
   - SecMan repository cloned
   - Database connection configured

3. **Authentication**:
   - Set your admin email as environment variable:
     ```bash
     export SECMAN_ADMIN_EMAIL=admin@example.com
     ```
   - Or use `--admin-user` flag with each command

## 5-Minute Quick Start

### 1. View Current Mappings

See what mappings exist in the system:

```bash
./gradlew cli:run --args='manage-user-mappings list'
```

**Expected Output**:
```
User Mappings
================================================================================

Email: admin@example.com (Status: ACTIVE)
  Domains:
    - corp.local (ACTIVE, created: 2025-11-19 10:30:00)
  AWS Accounts:
    - 123456789012 (ACTIVE, created: 2025-11-19 10:31:00)

Total Users: 1
Total Mappings: 2 (2 active, 0 pending)
```

### 2. Add a Domain Mapping

Assign a domain to a user:

```bash
./gradlew cli:run --args='manage-user-mappings add-domain \
  --emails john@example.com \
  --domains example.com'
```

**Expected Output**:
```
Processing domain mappings...
✅ Created: john@example.com → example.com

Summary:
  Total: 1 mapping processed
  Created: 1 active
```

### 3. Add an AWS Account Mapping

Assign an AWS account to a user:

```bash
./gradlew cli:run --args='manage-user-mappings add-aws \
  --emails jane@example.com \
  --accounts 987654321098'
```

**Expected Output**:
```
Processing AWS account mappings...
✅ Created: jane@example.com → 987654321098

Summary:
  Total: 1 mapping processed
  Created: 1 active
```

### 4. List Mappings for Specific User

Filter to see one user's mappings:

```bash
./gradlew cli:run --args='manage-user-mappings list \
  --email john@example.com'
```

**Expected Output**:
```
User Mappings
================================================================================

Email: john@example.com (Status: ACTIVE)
  Domains:
    - example.com (ACTIVE, created: 2025-11-19 10:35:00)

Total Users: 1
Total Mappings: 1 (1 active, 0 pending)
```

### 5. Remove a Mapping

Remove a specific domain mapping:

```bash
./gradlew cli:run --args='manage-user-mappings remove \
  --email john@example.com \
  --domain example.com'
```

**Expected Output**:
```
Removing mappings for john@example.com...
✅ Removed: john@example.com → example.com (domain)

Summary:
  Mappings removed: 1
```

## Common Use Cases

### Use Case 1: Onboard New User with Multiple Domains

**Scenario**: New employee needs access to 3 domains

```bash
./gradlew cli:run --args='manage-user-mappings add-domain \
  --emails newuser@example.com \
  --domains corp.local,dev.local,prod.local'
```

**Result**: 3 mappings created

### Use Case 2: Grant AWS Access to Cloud Team

**Scenario**: Cloud team (3 users) needs access to 2 AWS accounts

```bash
./gradlew cli:run --args='manage-user-mappings add-aws \
  --emails user1@example.com,user2@example.com,user3@example.com \
  --accounts 123456789012,987654321098'
```

**Result**: 6 mappings created (3 users × 2 accounts)

### Use Case 3: Future User Mapping

**Scenario**: Set up access before user account is created

```bash
./gradlew cli:run --args='manage-user-mappings add-domain \
  --emails futurehire@example.com \
  --domains example.com'
```

**Expected Output**:
```
Processing domain mappings...
⚠️  Pending (user not found): futurehire@example.com → example.com

Summary:
  Total: 1 mapping processed
  Created: 1 pending
```

**What Happens Next**: When `futurehire@example.com` is created via web UI, the mapping automatically becomes ACTIVE

### Use Case 4: Bulk Import from CSV

**Scenario**: Import 50 mappings from Excel export

**Step 1: Create CSV file**
```bash
cat > bulk-mappings.csv <<EOF
email,type,value
team1@example.com,domain,corp.local
team1@example.com,aws,123456789012
team2@example.com,domain,corp.local
team3@example.com,aws,987654321098
EOF
```

**Step 2: Test with dry-run**
```bash
./gradlew cli:run --args='manage-user-mappings import \
  --file bulk-mappings.csv \
  --dry-run'
```

**Step 3: Run actual import**
```bash
./gradlew cli:run --args='manage-user-mappings import \
  --file bulk-mappings.csv'
```

**Expected Output**:
```
Importing mappings from file: bulk-mappings.csv
================================================================================

Processing 4 entries...
✅ Line 1: Created team1@example.com → corp.local
✅ Line 2: Created team1@example.com → 123456789012
✅ Line 3: Created team2@example.com → corp.local
✅ Line 4: Created team3@example.com → 987654321098

Summary:
  Total processed: 4
  Created: 4 (4 active, 0 pending)
  Skipped: 0 duplicates
  Errors: 0 validation failures
```

### Use Case 5: Audit Active vs Pending Mappings

**Scenario**: See which mappings are waiting for user creation

```bash
./gradlew cli:run --args='manage-user-mappings list \
  --status PENDING'
```

**Expected Output**:
```
User Mappings
================================================================================

Email: futurehire@example.com (Status: PENDING)
  Domains:
    - example.com (PENDING, created: 2025-11-19 09:00:00)

Total Users: 1
Total Mappings: 1 (0 active, 1 pending)
```

### Use Case 6: Export to JSON for Backup

**Scenario**: Export all mappings to JSON file for backup

```bash
./gradlew cli:run --args='manage-user-mappings list \
  --format JSON' > backup-$(date +%Y%m%d).json
```

**Result**: Creates `backup-20251119.json` with all mappings

### Use Case 7: Remove All Mappings for Departing User

**Scenario**: Employee leaving company, revoke all access

```bash
./gradlew cli:run --args='manage-user-mappings remove \
  --email departing@example.com \
  --all'
```

**Expected Output**:
```
Removing mappings for departing@example.com...
✅ Removed: departing@example.com → corp.local (domain)
✅ Removed: departing@example.com → dev.local (domain)
✅ Removed: departing@example.com → 123456789012 (AWS account)

Summary:
  Mappings removed: 3
```

## Advanced Usage

### Scripting & Automation

**Example: Daily Sync from HR System**

```bash
#!/bin/bash
# sync-user-mappings.sh

# Export ADMIN email
export SECMAN_ADMIN_EMAIL=automation@example.com

# Generate CSV from HR system (example)
# ... your HR export logic here ...

# Import to SecMan
./gradlew cli:run --args='manage-user-mappings import \
  --file hr-export.csv' > /var/log/secman/mapping-sync-$(date +%Y%m%d).log 2>&1

# Check exit code
if [ $? -eq 0 ]; then
    echo "Sync successful"
else
    echo "Sync failed - check logs"
    exit 1
fi
```

**Schedule with Cron**:
```bash
# Run daily at 2 AM
0 2 * * * /path/to/sync-user-mappings.sh
```

### Combining with grep/jq for Analysis

**Example: Find all AWS accounts for a user**

```bash
./gradlew cli:run --args='manage-user-mappings list \
  --email admin@example.com \
  --format JSON' | jq '.users[0].mappings.awsAccounts[].value'
```

**Output**:
```
"123456789012"
"987654321098"
```

**Example: Count pending mappings**

```bash
./gradlew cli:run --args='manage-user-mappings list \
  --status PENDING \
  --format JSON' | jq '.summary.pendingCount'
```

**Output**:
```
5
```

### Error Handling in Scripts

```bash
#!/bin/bash

# Try to add mapping
OUTPUT=$(./gradlew cli:run --args='manage-user-mappings add-domain \
  --emails user@example.com \
  --domains example.com' 2>&1)

EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    echo "Success: $OUTPUT"
else
    echo "Error occurred:"
    echo "$OUTPUT"
    # Send alert email
    echo "$OUTPUT" | mail -s "SecMan Mapping Error" admin@example.com
    exit 1
fi
```

## Troubleshooting

### Problem: "Admin user required" error

**Error Message**:
```
❌ Error: Admin user required (use --admin-user or set SECMAN_ADMIN_EMAIL)
```

**Solution**:
```bash
# Option 1: Set environment variable
export SECMAN_ADMIN_EMAIL=admin@example.com

# Option 2: Use flag
./gradlew cli:run --args='manage-user-mappings list \
  --admin-user admin@example.com'
```

### Problem: "Invalid email format" error

**Error Message**:
```
❌ Error: Invalid email format: 'user'
```

**Solution**: Ensure email has proper format `user@domain.com`

```bash
# Wrong
--emails user

# Correct
--emails user@example.com
```

### Problem: "Invalid AWS account ID" error

**Error Message**:
```
❌ Error: Invalid AWS account ID '12345' (must be 12 digits)
```

**Solution**: AWS account IDs must be exactly 12 numeric digits

```bash
# Wrong
--accounts 12345

# Correct
--accounts 123456789012
```

### Problem: Mapping shows as PENDING unexpectedly

**Scenario**: Created mapping for existing user but shows PENDING

**Check**:
```bash
# Verify user exists with exact email
./gradlew cli:run --args='manage-user-mappings list \
  --email user@example.com'
```

**Cause**: Email case mismatch (user exists as `User@Example.com` but you entered `user@example.com`)

**Solution**: Emails are normalized to lowercase - this should work automatically. If PENDING, user truly doesn't exist yet.

### Problem: File not found during import

**Error Message**:
```
❌ Error: File not found: mappings.csv
```

**Solution**: Use absolute path or verify current directory

```bash
# Use absolute path
./gradlew cli:run --args='manage-user-mappings import \
  --file /full/path/to/mappings.csv'

# Or use relative path from repo root
./gradlew cli:run --args='manage-user-mappings import \
  --file ./data/mappings.csv'
```

## Best Practices

### 1. Always Test with Dry-Run (Imports)

```bash
# Test first
./gradlew cli:run --args='manage-user-mappings import \
  --file large-file.csv \
  --dry-run'

# If validation passes, run for real
./gradlew cli:run --args='manage-user-mappings import \
  --file large-file.csv'
```

### 2. Use Environment Variable for Admin Email

Add to your `.bashrc` or `.zshrc`:
```bash
export SECMAN_ADMIN_EMAIL=your-admin@example.com
```

### 3. Create Aliases for Common Operations

```bash
# Add to .bashrc
alias secman-mappings='./gradlew cli:run --args="manage-user-mappings'
alias secman-list='secman-mappings list"'
alias secman-list-pending='secman-mappings list --status PENDING"'

# Usage
secman-list
secman-list-pending
```

### 4. Log All Operations

```bash
# Redirect to log file
./gradlew cli:run --args='manage-user-mappings import \
  --file mappings.csv' >> /var/log/secman/mappings.log 2>&1
```

### 5. Regular Audits

**Weekly Check for Pending Mappings**:
```bash
#!/bin/bash
# check-pending-mappings.sh

PENDING_COUNT=$(./gradlew cli:run --args='manage-user-mappings list \
  --status PENDING \
  --format JSON' | jq '.summary.pendingCount')

if [ "$PENDING_COUNT" -gt 10 ]; then
    echo "Warning: $PENDING_COUNT pending mappings found"
    # Send alert
fi
```

## Next Steps

- **Read Full Documentation**: See `src/cli/src/main/resources/cli-docs/USER_MAPPING_COMMANDS.md`
- **Understand Batch Formats**: Review `contracts/batch-formats.md` for CSV/JSON details
- **Command Reference**: See `contracts/commands.md` for complete command specifications
- **Integration**: Consider integrating with your HR/IAM system for automated provisioning

## Quick Reference Card

```bash
# List all mappings
./gradlew cli:run --args='manage-user-mappings list'

# Add domain mapping
./gradlew cli:run --args='manage-user-mappings add-domain \
  --emails USER@EXAMPLE.COM \
  --domains DOMAIN.COM'

# Add AWS account mapping
./gradlew cli:run --args='manage-user-mappings add-aws \
  --emails USER@EXAMPLE.COM \
  --accounts 123456789012'

# Remove specific mapping
./gradlew cli:run --args='manage-user-mappings remove \
  --email USER@EXAMPLE.COM \
  --domain DOMAIN.COM'

# Remove all mappings for user
./gradlew cli:run --args='manage-user-mappings remove \
  --email USER@EXAMPLE.COM \
  --all'

# Import from file
./gradlew cli:run --args='manage-user-mappings import \
  --file FILE.csv \
  --dry-run'  # Test first!

# List pending mappings
./gradlew cli:run --args='manage-user-mappings list \
  --status PENDING'

# Export to JSON
./gradlew cli:run --args='manage-user-mappings list \
  --format JSON' > backup.json
```

## Support

For issues or questions:
1. Check audit logs: `/var/log/secman/mappings.log`
2. Run command with `--help` flag
3. Review error messages carefully
4. Contact SecMan administrators

**Remember**: All operations require ADMIN role and are fully audited!
