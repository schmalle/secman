# Fix: Asset Deletion Not Working

## Problem Summary

The Delete button in Asset Management executes but the asset remains in the database. The backend logs show "Cascade delete successful" but the transaction rolls back silently.

## Root Cause

The `asset_deletion_audit_log` table is missing from the database. When `AssetCascadeDeleteService` tries to save the audit log after deletion, it fails, causing the entire transaction to roll back.

## Solution

Create the missing `asset_deletion_audit_log` table.

## Steps to Fix

### Option A: Execute Manual SQL Script (Immediate Fix)

1. Open your MariaDB client or database tool (e.g., DBeaver, DataGrip, TablePlus)
2. Connect to the `secman` database
3. Execute the SQL script: `specs/033-cascade-asset-deletion/FIX-create-audit-table.sql`

```bash
# Using MySQL CLI
mysql -u secman -p secman < specs/033-cascade-asset-deletion/FIX-create-audit-table.sql
```

4. Verify table creation:
```sql
USE secman;
SHOW TABLES LIKE 'asset_deletion_audit_log';
DESC asset_deletion_audit_log;
```

### Option B: Restart Backend (Hibernate Auto-Create)

If Hibernate's `hbm2ddl.auto=update` is enabled (it is), restart the backend and Hibernate will create the missing table:

1. Stop your backend server
2. Start it again: `./gradlew :backendng:bootRun`
3. Check the logs for table creation messages
4. Verify the table exists in the database

### Option C: Use Flyway Migration (Production-Ready)

The migration script has been created at:
- `src/backendng/src/main/resources/db/migration/V003__add_asset_deletion_audit_log.sql`

If you have Flyway configured, it will automatically run on next startup.

## Verification

After applying the fix:

1. Refresh the Asset Management page
2. Click Delete on any asset
3. Confirm the deletion dialog
4. The asset should be deleted from the database
5. An audit record should appear in `asset_deletion_audit_log`

Check audit log:
```sql
SELECT * FROM asset_deletion_audit_log ORDER BY deletion_timestamp DESC LIMIT 5;
```

## Why This Happened

Feature 033 (cascade-asset-deletion) introduced the `AssetDeletionAuditLog` entity but the table was never created in your database, likely because:
- Hibernate DDL auto-generation was temporarily disabled
- The migration script wasn't run during initial setup
- The table was accidentally dropped

## Files Created/Modified

1. **Migration Script**: `src/backendng/src/main/resources/db/migration/V003__add_asset_deletion_audit_log.sql`
2. **Manual Fix Script**: `specs/033-cascade-asset-deletion/FIX-create-audit-table.sql`
3. **Frontend Fix**: `src/frontend/src/components/AssetManagement.tsx` (removed disabled attribute from Delete button)

## Related Feature

Feature 033: Cascade Asset Deletion
- FR-011: Audit logging for all cascade deletions
- Entity: `AssetDeletionAuditLog.kt`
- Service: `AssetCascadeDeleteService.kt`
