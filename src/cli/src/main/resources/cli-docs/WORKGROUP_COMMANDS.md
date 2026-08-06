# Workgroup Management Commands

**Feature**: Workgroup Asset Management CLI
**Version**: 1.0.0
**Last Updated**: 2025-12-01

## Overview

The `manage-workgroups` command suite provides CLI tools for ADMIN users to manage workgroup asset assignments in SecMan. These commands enable bulk assignment and removal of assets using pattern-based selection with wildcard support.

**Key Features**:
- List workgroups and their assets
- Search assets by name pattern with wildcard support (`*` and `?`)
- Assign assets to workgroups by pattern or specific IDs
- Remove assets from workgroups by pattern or bulk removal
- Filter assets by type (SERVER, WORKSTATION, etc.)
- Dry-run mode to preview changes before applying
- Multiple output formats (TABLE, JSON, CSV)
- Audit logging for all operations

## Prerequisites

### Authentication
All write operations require **ADMIN role** access. Specify admin credentials via:
- `--admin-user <email>` flag on each command, OR
- `SECMAN_ADMIN_EMAIL` environment variable

### Database Connection
Commands connect to the backend database via Micronaut Data JPA. Ensure:
- Database is running and accessible
- Connection details in `src/backendng/src/main/resources/application.yml`

## Pattern Matching

All commands support wildcard patterns for asset name matching:

| Pattern | Description | Example Matches |
|---------|-------------|-----------------|
| `*` | Matches zero or more characters | `ip-10-*` matches `ip-10-255-75-85`, `ip-10-0-0-1` |
| `?` | Matches exactly one character | `server?` matches `server1`, `serverA`, `server9` |
| `*text*` | Contains text anywhere | `*prod*` matches `web-prod-01`, `production-db` |
| `prefix*suffix` | Starts with prefix, ends with suffix | `ip-*internal` matches `ip-172-internal` |

**Pattern matching is case-insensitive.**

---

## Commands

### 1. List Workgroups and Assets

**Command**: `list`

**Purpose**: View workgroups, assets in a workgroup, or search all assets by pattern.

**Syntax**:
```bash
./gradlew cli:run --args='manage-workgroups list \
  [--workgroup <name-or-id>] \
  [--name <pattern>] \
  [--search-assets <pattern>] \
  [--type <asset-type>] \
  [--format <TABLE|JSON|CSV>]'
```

**Options**:
- `--workgroup` or `-w`: Workgroup name or ID to list assets for
- `--name` or `-n`: Filter workgroups by name pattern (wildcards supported)
- `--search-assets` or `-s`: Search all assets by name pattern (for preview before assigning)
- `--type` or `-t`: Filter assets by type (e.g., SERVER, WORKSTATION)
- `--format` or `-f`: Output format (default: TABLE)

**Use Cases**:

#### List All Workgroups
```bash
./gradlew cli:run --args='manage-workgroups list'
```

**Output**:
```
================================================================================
Workgroups
================================================================================

ID      Name                            Assets      Description
--------------------------------------------------------------------------------
1       Production                      45          Production servers
2       Development                     23          Dev environment
3       Test                            12          Testing workgroup

Total: 3 workgroup(s)
```

#### List Assets in a Specific Workgroup
```bash
./gradlew cli:run --args='manage-workgroups list --workgroup Production'
```

**Output**:
```
==========================================================================================
Assets in workgroup: Production
==========================================================================================

ID      Name                                      Type          IP
------------------------------------------------------------------------------------------
101     ip-10-255-75-85                          SERVER        10.255.75.85
102     web-prod-01                              SERVER        10.100.1.10
103     db-prod-primary                          SERVER        10.100.2.1

Total: 3 asset(s)
```

#### Search Assets by Pattern (Preview Before Assigning)
```bash
./gradlew cli:run --args='manage-workgroups list --search-assets "ip-10-*"'
```

**Output**:
```
==========================================================================================
Assets matching pattern: ip-10-*
==========================================================================================

ID      Name                                      Type          IP
------------------------------------------------------------------------------------------
101     ip-10-255-75-85                          SERVER        10.255.75.85
105     ip-10-0-0-1                              SERVER        10.0.0.1
108     ip-10-172-31-1                           SERVER        10.172.31.1

Total: 3 asset(s)
```

#### Filter by Type
```bash
./gradlew cli:run --args='manage-workgroups list --search-assets "*" --type SERVER'
```

#### Export to JSON
```bash
./gradlew cli:run --args='manage-workgroups list --format JSON' > workgroups.json
```

**JSON Output**:
```json
[
  {
    "id": 1,
    "name": "Production",
    "description": "Production servers",
    "criticality": "HIGH",
    "assetCount": 45,
    "parentId": null,
    "createdAt": "2025-01-15T10:00:00Z"
  }
]
```

#### Export to CSV
```bash
./gradlew cli:run --args='manage-workgroups list --format CSV' > workgroups.csv
```

---

### 2. Assign Assets to Workgroup

**Command**: `assign-assets`

**Purpose**: Add assets to a workgroup using pattern matching or specific IDs.

**Syntax**:
```bash
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup <name-or-id> \
  [--pattern <pattern> | --ids <id1,id2,...>] \
  [--type <asset-type>] \
  [--dry-run] \
  [--verbose] \
  [--admin-user <email>]'
```

**Options**:
- `--workgroup` or `-w` (required): Target workgroup name or ID
- `--pattern` or `-p`: Asset name pattern with wildcards
- `--ids` or `-i`: Comma-separated list of asset IDs
- `--type` or `-t`: Filter assets by type
- `--dry-run` or `-d`: Preview without making changes
- `--verbose` or `-v`: Show detailed output including asset names
- `--admin-user` or `-u`: Admin user email

**IMPORTANT**: Must specify either `--pattern` or `--ids`.

**Examples**:

#### Assign by Pattern
```bash
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "ip-10-*" \
  --admin-user admin@example.com'
```

**Output**:
```
SUCCESS: Assigned 15 assets to workgroup 'Production' (skipped 3 already assigned)

Summary:
  - Assigned: 15
  - Skipped (already assigned): 3
```

#### Assign by Pattern with Type Filter
```bash
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "*prod*" \
  --type SERVER \
  --admin-user admin@example.com'
```

#### Assign Specific Assets by ID
```bash
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup Production \
  --ids 101,102,103,104 \
  --admin-user admin@example.com'
```

#### Dry Run (Preview Changes)
```bash
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "ip-172-*" \
  --dry-run'
```

**Dry Run Output**:
```
DRY RUN - No changes will be made

Assets that would be assigned:
  - ip-172-31-18-9.compute.internal (SERVER)
  - ip-172-16-0-1 (SERVER)
  - ip-172-20-100-50 (SERVER)

Assets already assigned (would be skipped):
  - ip-172-31-1-1 (SERVER)

Summary: 3 would be assigned, 1 already assigned
```

#### Verbose Output (Show Asset Names)
```bash
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "web-*" \
  --verbose \
  --admin-user admin@example.com'
```

**Verbose Output**:
```
SUCCESS: Assigned 5 assets to workgroup 'Production' (skipped 0 already assigned)

Summary:
  - Assigned: 5
  - Skipped (already assigned): 0

Assigned assets:
  - web-prod-01
  - web-prod-02
  - web-staging-01
  - web-dev-01
  - web-test-01
```

---

### 3. Remove Assets from Workgroup

**Command**: `remove-assets`

**Purpose**: Remove assets from a workgroup using pattern matching, specific IDs, or remove all.

**Syntax**:
```bash
./gradlew cli:run --args='manage-workgroups remove-assets \
  --workgroup <name-or-id> \
  [--pattern <pattern> | --ids <id1,id2,...> | --all] \
  [--type <asset-type>] \
  [--dry-run] \
  [--verbose] \
  [--force] \
  [--admin-user <email>]'
```

**Options**:
- `--workgroup` or `-w` (required): Target workgroup name or ID
- `--pattern` or `-p`: Asset name pattern with wildcards
- `--ids` or `-i`: Comma-separated list of asset IDs
- `--all` or `-a`: Remove ALL assets from the workgroup
- `--type` or `-t`: Filter assets by type
- `--dry-run` or `-d`: Preview without making changes
- `--verbose` or `-v`: Show detailed output including asset names
- `--force` or `-f`: Skip confirmation prompt for `--all`
- `--admin-user` or `-u`: Admin user email

**IMPORTANT**: Must specify exactly one of `--pattern`, `--ids`, or `--all`.

**Examples**:

#### Remove by Pattern
```bash
./gradlew cli:run --args='manage-workgroups remove-assets \
  --workgroup Test \
  --pattern "*test*" \
  --admin-user admin@example.com'
```

**Output**:
```
SUCCESS: Removed 8 assets from workgroup 'Test' (skipped 0 not assigned)

Summary:
  - Removed: 8
  - Skipped (not in workgroup): 0
```

#### Remove Specific Assets by ID
```bash
./gradlew cli:run --args='manage-workgroups remove-assets \
  --workgroup Development \
  --ids 201,202,203 \
  --admin-user admin@example.com'
```

#### Remove All Assets (with confirmation)
```bash
./gradlew cli:run --args='manage-workgroups remove-assets \
  --workgroup OldProject \
  --all \
  --admin-user admin@example.com'
```

**Prompt**:
```
Are you sure you want to remove all 25 assets from 'OldProject'? [y/N]: y

SUCCESS: Removed 25 assets from workgroup 'OldProject' (skipped 0 not assigned)
```

#### Remove All Assets (skip confirmation)
```bash
./gradlew cli:run --args='manage-workgroups remove-assets \
  --workgroup OldProject \
  --all \
  --force \
  --admin-user admin@example.com'
```

#### Dry Run (Preview Removal)
```bash
./gradlew cli:run --args='manage-workgroups remove-assets \
  --workgroup Production \
  --pattern "*staging*" \
  --dry-run'
```

**Dry Run Output**:
```
DRY RUN - No changes will be made

Assets that would be removed:
  - web-staging-01 (SERVER)
  - db-staging-01 (SERVER)
  - cache-staging-01 (SERVER)

Summary: 3 would be removed, 42 would remain
```

---

## Common Workflows

### 1. Organize Assets into Workgroups by Naming Convention

```bash
# Preview what would be assigned
./gradlew cli:run --args='manage-workgroups list --search-assets "ip-10-255-*"'

# Assign all matching assets
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup "AWS-Production" \
  --pattern "ip-10-255-*" \
  --admin-user admin@example.com'

# Verify assignment
./gradlew cli:run --args='manage-workgroups list --workgroup "AWS-Production"'
```

### 2. Migrate Assets Between Workgroups

```bash
# Remove from old workgroup
./gradlew cli:run --args='manage-workgroups remove-assets \
  --workgroup OldTeam \
  --pattern "*project-x*" \
  --admin-user admin@example.com'

# Add to new workgroup
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup NewTeam \
  --pattern "*project-x*" \
  --admin-user admin@example.com'
```

### 3. Clean Up Test Workgroup

```bash
# Preview what will be removed
./gradlew cli:run --args='manage-workgroups remove-assets \
  --workgroup Test \
  --all \
  --dry-run'

# Remove all with confirmation
./gradlew cli:run --args='manage-workgroups remove-assets \
  --workgroup Test \
  --all \
  --admin-user admin@example.com'
```

### 4. Bulk Assignment from Multiple Patterns

```bash
# Assign production servers
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "*-prod-*" \
  --admin-user admin@example.com'

# Also add servers with different naming
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "prod*" \
  --admin-user admin@example.com'

# And specific IP range
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "ip-10-100-*" \
  --admin-user admin@example.com'
```

### 5. Export Workgroup Assets for Audit

```bash
# Export workgroup list to JSON
./gradlew cli:run --args='manage-workgroups list --format JSON' > workgroups_audit.json

# Export specific workgroup assets to CSV
./gradlew cli:run --args='manage-workgroups list \
  --workgroup Production \
  --format CSV' > production_assets.csv
```

---

## Troubleshooting

### Issue: "Admin user required" Error
**Cause**: No admin user specified for write operations
**Solution**: Set `SECMAN_ADMIN_EMAIL` environment variable or use `--admin-user` flag
```bash
export SECMAN_ADMIN_EMAIL=admin@example.com
# OR
./gradlew cli:run --args='manage-workgroups assign-assets ... --admin-user admin@example.com'
```

### Issue: "Workgroup not found" Error
**Cause**: Workgroup name or ID doesn't exist
**Solution**: List workgroups to verify the name
```bash
./gradlew cli:run --args='manage-workgroups list'
```

### Issue: No Assets Match Pattern
**Cause**: Pattern doesn't match any asset names
**Solution**: Use `list --search-assets` to test pattern
```bash
# Test your pattern first
./gradlew cli:run --args='manage-workgroups list --search-assets "your-pattern*"'
```

### Issue: All Assets Already Assigned
**Cause**: Assets matching pattern are already in the workgroup
**Solution**: Use `--verbose` to see details, or check current workgroup assets
```bash
./gradlew cli:run --args='manage-workgroups list --workgroup YourWorkgroup'
```

### Issue: Pattern Matching Unexpected Results
**Cause**: Pattern syntax may not be as expected
**Solution**: Remember that patterns are case-insensitive and use glob-style wildcards
- `*` matches zero or more characters
- `?` matches exactly one character
- Patterns match the entire name (implicit `^...$` anchoring)

---

## Best Practices

### 1. Always Preview with Dry-Run for Bulk Operations
```bash
# Preview before assigning
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "ip-*" \
  --dry-run'

# If satisfied, execute
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "ip-*" \
  --admin-user admin@example.com'
```

### 2. Use Search to Test Patterns Before Assigning
```bash
# Search first
./gradlew cli:run --args='manage-workgroups list --search-assets "*prod*" --type SERVER'

# Then assign matching assets
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "*prod*" \
  --type SERVER \
  --admin-user admin@example.com'
```

### 3. Use Environment Variable for Admin User
```bash
export SECMAN_ADMIN_EMAIL=admin@example.com

# Then omit --admin-user from all commands
./gradlew cli:run --args='manage-workgroups assign-assets ...'
```

### 4. Use Verbose Mode to Track Changes
```bash
./gradlew cli:run --args='manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "*" \
  --verbose \
  --admin-user admin@example.com'
```

### 5. Export Before Bulk Changes
```bash
# Backup current state
./gradlew cli:run --args='manage-workgroups list \
  --workgroup Production \
  --format JSON' > backup_$(date +%Y%m%d).json

# Then make changes
./gradlew cli:run --args='manage-workgroups remove-assets ...'
```

---

## Security Considerations

1. **ADMIN Role Required**: All write operations (assign/remove) enforce ADMIN role
2. **Audit Logging**: All operations logged with actor, timestamp, and affected entities
3. **Read Operations**: List commands are available to authenticated users
4. **No Cascading Deletes**: Removing assets from workgroup doesn't delete the assets
5. **Confirmation for Bulk Remove**: `--all` requires confirmation unless `--force` is used

---

## Related Documentation

- **Main CLI Reference**: `docs/CLI.md`
- **User Mapping Commands**: `cli-docs/USER_MAPPING_COMMANDS.md`
- **Access Control**: CLAUDE.md - "Unified Access Control" section
- **Workgroup API**: `docs/API.md` - Workgroup endpoints

---

## Support

For issues or questions:
1. Check troubleshooting guide above
2. Review audit logs in application logs
3. Verify database connectivity: `./gradlew backendng:run`
4. Report bugs: https://github.com/schmalle/secman/issues

---

**End of Workgroup Commands Documentation**
