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
All commands — including the read-only `list` command — require **ADMIN role** access on the backend account used to run them. Specify backend credentials via:
- `--username <name>` flag (or the `SECMAN_ADMIN_NAME` environment variable), AND
- `--password <pass>` flag (or the `SECMAN_ADMIN_PASS` environment variable)

Both are required. Omitting `--username`/`SECMAN_ADMIN_NAME` fails fast with
`Backend username required. Use --username flag or set SECMAN_ADMIN_NAME environment variable`;
omitting `--password`/`SECMAN_ADMIN_PASS` fails the same way with the password equivalent.

> **Note**: `--admin-user` / `-u` (and the `SECMAN_ADMIN_EMAIL` environment variable)
> are also defined on the parent `manage-workgroups` command and still accepted, but
> they are **not read by any subcommand** — `list`, `assign-assets`, and
> `remove-assets` all authenticate using only the `--username`/`--password` backend
> credentials above. Passing only `--admin-user` without `--username`/`--password`
> will fail at runtime.

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
./scripts/secman manage-workgroups list \
  [--workgroup <name-or-id>] \
  [--name <pattern>] \
  [--search-assets <pattern>] \
  [--type <asset-type>] \
  [--format <TABLE|JSON|CSV>] \
  --username <backend-user> --password <backend-pass>
```

**Options**:
- `--workgroup` or `-w`: Workgroup name or ID to list assets for
- `--name` or `-n`: Filter workgroups by name pattern (wildcards supported)
- `--search-assets` or `-s`: Search all assets by name pattern (for preview before assigning)
- `--type` or `-t`: Filter assets by type (e.g., SERVER, WORKSTATION)
- `--format` or `-f`: Output format (default: TABLE)
- `--username` (required, or `SECMAN_ADMIN_NAME` env var): Backend username
- `--password` (required, or `SECMAN_ADMIN_PASS` env var): Backend password
- `--admin-user` or `-u`: **(No effect)** identity is derived from `--username`/`--password`

**Use Cases**:

#### List All Workgroups
```bash
./scripts/secman manage-workgroups list \
  --username admin --password '<password>'
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
./scripts/secman manage-workgroups list --workgroup Production \
  --username admin --password '<password>'
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
./scripts/secman manage-workgroups list --search-assets "ip-10-*" \
  --username admin --password '<password>'
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
./scripts/secman manage-workgroups list --search-assets "*" --type SERVER \
  --username admin --password '<password>'
```

#### Export to JSON
```bash
./scripts/secman manage-workgroups list --format JSON \
  --username admin --password '<password>' > workgroups.json
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
./scripts/secman manage-workgroups list --format CSV \
  --username admin --password '<password>' > workgroups.csv
```

---

### 2. Assign Assets to Workgroup

**Command**: `assign-assets`

**Purpose**: Add assets to a workgroup using pattern matching or specific IDs.

**Syntax**:
```bash
./scripts/secman manage-workgroups assign-assets \
  --workgroup <name-or-id> \
  [--pattern <pattern> | --ids <id1,id2,...>] \
  [--type <asset-type>] \
  [--dry-run] \
  [--verbose] \
  --username <backend-user> --password <backend-pass>
```

**Options**:
- `--workgroup` or `-w` (required): Target workgroup name or ID
- `--pattern` or `-p`: Asset name pattern with wildcards
- `--ids` or `-i`: Comma-separated list of asset IDs
- `--type` or `-t`: Filter assets by type
- `--dry-run` or `-d`: Preview without making changes
- `--verbose` or `-v`: Show detailed output including asset names
- `--username` (required, or `SECMAN_ADMIN_NAME` env var): Backend username
- `--password` (required, or `SECMAN_ADMIN_PASS` env var): Backend password
- `--admin-user` or `-u`: **(No effect)** identity is derived from `--username`/`--password`

**IMPORTANT**: Must specify either `--pattern` or `--ids`.

**Examples**:

#### Assign by Pattern
```bash
./scripts/secman manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "ip-10-*" \
  --username admin --password '<password>'
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
./scripts/secman manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "*prod*" \
  --type SERVER \
  --username admin --password '<password>'
```

#### Assign Specific Assets by ID
```bash
./scripts/secman manage-workgroups assign-assets \
  --workgroup Production \
  --ids 101,102,103,104 \
  --username admin --password '<password>'
```

#### Dry Run (Preview Changes)
```bash
./scripts/secman manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "ip-172-*" \
  --dry-run \
  --username admin --password '<password>'
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
./scripts/secman manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "web-*" \
  --verbose \
  --username admin --password '<password>'
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
./scripts/secman manage-workgroups remove-assets \
  --workgroup <name-or-id> \
  [--pattern <pattern> | --ids <id1,id2,...> | --all] \
  [--type <asset-type>] \
  [--dry-run] \
  [--verbose] \
  [--force] \
  --username <backend-user> --password <backend-pass>
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
- `--username` (required, or `SECMAN_ADMIN_NAME` env var): Backend username
- `--password` (required, or `SECMAN_ADMIN_PASS` env var): Backend password
- `--admin-user` or `-u`: **(No effect)** identity is derived from `--username`/`--password`

**IMPORTANT**: Must specify exactly one of `--pattern`, `--ids`, or `--all`.

**Examples**:

#### Remove by Pattern
```bash
./scripts/secman manage-workgroups remove-assets \
  --workgroup Test \
  --pattern "*test*" \
  --username admin --password '<password>'
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
./scripts/secman manage-workgroups remove-assets \
  --workgroup Development \
  --ids 201,202,203 \
  --username admin --password '<password>'
```

#### Remove All Assets (with confirmation)
```bash
./scripts/secman manage-workgroups remove-assets \
  --workgroup OldProject \
  --all \
  --username admin --password '<password>'
```

**Prompt**:
```
Are you sure you want to remove all 25 assets from 'OldProject'? [y/N]: y

SUCCESS: Removed 25 assets from workgroup 'OldProject' (skipped 0 not assigned)
```

#### Remove All Assets (skip confirmation)
```bash
./scripts/secman manage-workgroups remove-assets \
  --workgroup OldProject \
  --all \
  --force \
  --username admin --password '<password>'
```

#### Dry Run (Preview Removal)
```bash
./scripts/secman manage-workgroups remove-assets \
  --workgroup Production \
  --pattern "*staging*" \
  --dry-run \
  --username admin --password '<password>'
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
./scripts/secman manage-workgroups list --search-assets "ip-10-255-*" \
  --username admin --password '<password>'

# Assign all matching assets
./scripts/secman manage-workgroups assign-assets \
  --workgroup "AWS-Production" \
  --pattern "ip-10-255-*" \
  --username admin --password '<password>'

# Verify assignment
./scripts/secman manage-workgroups list --workgroup "AWS-Production" \
  --username admin --password '<password>'
```

### 2. Migrate Assets Between Workgroups

```bash
# Remove from old workgroup
./scripts/secman manage-workgroups remove-assets \
  --workgroup OldTeam \
  --pattern "*project-x*" \
  --username admin --password '<password>'

# Add to new workgroup
./scripts/secman manage-workgroups assign-assets \
  --workgroup NewTeam \
  --pattern "*project-x*" \
  --username admin --password '<password>'
```

### 3. Clean Up Test Workgroup

```bash
# Preview what will be removed
./scripts/secman manage-workgroups remove-assets \
  --workgroup Test \
  --all \
  --dry-run \
  --username admin --password '<password>'

# Remove all with confirmation
./scripts/secman manage-workgroups remove-assets \
  --workgroup Test \
  --all \
  --username admin --password '<password>'
```

### 4. Bulk Assignment from Multiple Patterns

```bash
# Assign production servers
./scripts/secman manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "*-prod-*" \
  --username admin --password '<password>'

# Also add servers with different naming
./scripts/secman manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "prod*" \
  --username admin --password '<password>'

# And specific IP range
./scripts/secman manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "ip-10-100-*" \
  --username admin --password '<password>'
```

### 5. Export Workgroup Assets for Audit

```bash
# Export workgroup list to JSON
./scripts/secman manage-workgroups list --format JSON \
  --username admin --password '<password>' > workgroups_audit.json

# Export specific workgroup assets to CSV
./scripts/secman manage-workgroups list \
  --workgroup Production \
  --format CSV \
  --username admin --password '<password>' > production_assets.csv
```

---

## Troubleshooting

### Issue: "Backend username required" / "Backend password required" Error
**Cause**: No `--username`/`--password` (or `SECMAN_ADMIN_NAME`/`SECMAN_ADMIN_PASS`) specified. Note that `--admin-user`/`SECMAN_ADMIN_EMAIL` do **not** satisfy this — they have no effect on any subcommand.
**Solution**: Set `SECMAN_ADMIN_NAME`/`SECMAN_ADMIN_PASS` environment variables or use `--username`/`--password` flags
```bash
export SECMAN_ADMIN_NAME=admin
export SECMAN_ADMIN_PASS='<password>'
# OR
./scripts/secman manage-workgroups assign-assets ... --username admin --password '<password>'
```

### Issue: "Workgroup not found" Error
**Cause**: Workgroup name or ID doesn't exist
**Solution**: List workgroups to verify the name
```bash
./scripts/secman manage-workgroups list --username admin --password '<password>'
```

### Issue: No Assets Match Pattern
**Cause**: Pattern doesn't match any asset names
**Solution**: Use `list --search-assets` to test pattern
```bash
# Test your pattern first
./scripts/secman manage-workgroups list --search-assets "your-pattern*" \
  --username admin --password '<password>'
```

### Issue: All Assets Already Assigned
**Cause**: Assets matching pattern are already in the workgroup
**Solution**: Use `--verbose` to see details, or check current workgroup assets
```bash
./scripts/secman manage-workgroups list --workgroup YourWorkgroup \
  --username admin --password '<password>'
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
./scripts/secman manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "ip-*" \
  --dry-run \
  --username admin --password '<password>'

# If satisfied, execute
./scripts/secman manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "ip-*" \
  --username admin --password '<password>'
```

### 2. Use Search to Test Patterns Before Assigning
```bash
# Search first
./scripts/secman manage-workgroups list --search-assets "*prod*" --type SERVER \
  --username admin --password '<password>'

# Then assign matching assets
./scripts/secman manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "*prod*" \
  --type SERVER \
  --username admin --password '<password>'
```

### 3. Use Environment Variables for Backend Credentials
```bash
export SECMAN_ADMIN_NAME=admin
export SECMAN_ADMIN_PASS='<password>'

# Then omit --username/--password from all commands
./scripts/secman manage-workgroups assign-assets ...
```

### 4. Use Verbose Mode to Track Changes
```bash
./scripts/secman manage-workgroups assign-assets \
  --workgroup Production \
  --pattern "*" \
  --verbose \
  --username admin --password '<password>'
```

### 5. Export Before Bulk Changes
```bash
# Backup current state
./scripts/secman manage-workgroups list \
  --workgroup Production \
  --format JSON \
  --username admin --password '<password>' > backup_$(date +%Y%m%d).json

# Then make changes
./scripts/secman manage-workgroups remove-assets ... --username admin --password '<password>'
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
- **Access Control**: CLAUDE.md - "Roles (RBAC)" and "Unified Asset Access" sections
- **Workgroup API**: CLAUDE.md - "API Endpoints" table (Workgroups row)

---

## Support

For issues or questions:
1. Check troubleshooting guide above
2. Review audit logs in application logs
3. Verify database connectivity: `./scripts/startbackenddev.sh`
4. Report bugs: https://github.com/schmalle/secman/issues

---

**End of Workgroup Commands Documentation**
