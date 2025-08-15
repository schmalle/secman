# Database Constraint Fix for Asset Deletion

## Problem Summary

The Secman application has a critical database constraint issue where some foreign key relationships use `CASCADE` deletion rules. This is dangerous because:

1. **Risk Assessment → Asset**: If an asset is deleted at the database level, all risk assessments are automatically deleted
2. **Risk → Asset**: If an asset is deleted at the database level, all risks are automatically deleted
3. **Data Loss Risk**: This can cause accidental data loss and referential integrity issues

## The Fix

Our asset deletion fix in the application code prevents deletion when references exist, but the underlying database constraints need to be corrected to use `RESTRICT` instead of `CASCADE`.

## Installation Steps

### 1. Initial Database Setup
```bash
mysql -u root -p < scripts/install/install-with-constraints.sql
```

### 2. Start the Application
Let Micronaut/Hibernate create the database tables automatically:
```bash
cd src/backendng
./gradlew run
```

### 3. Fix Foreign Key Constraints
After the application has created all tables, apply the constraint fixes:
```bash
mysql -u secman -pCHANGEME < scripts/fix-foreign-key-constraints.sql
```

### 4. Verification
Check that constraints are properly set:
```sql
-- Show all foreign key constraints referencing assets
SELECT 
    TABLE_NAME,
    COLUMN_NAME,
    CONSTRAINT_NAME,
    DELETE_RULE,
    UPDATE_RULE
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
JOIN information_schema.KEY_COLUMN_USAGE kcu 
  ON rc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME 
WHERE rc.CONSTRAINT_SCHEMA = 'secman' 
  AND kcu.REFERENCED_TABLE_NAME = 'asset';
```

All `DELETE_RULE` values should be `RESTRICT`, not `CASCADE`.

## What This Fixes

### Before the Fix
- Database-level asset deletion would cascade and delete related data
- Risk of accidental data loss
- Inconsistent behavior between application-level and database-level operations

### After the Fix
- ✅ Asset deletion properly blocked when references exist
- ✅ Detailed error messages showing exactly what references prevent deletion
- ✅ Database constraints prevent accidental cascade deletions
- ✅ Data integrity maintained at both application and database levels

## Testing the Fix

The application now includes comprehensive reference checking for:
- **Demands** that reference the asset
- **Risk Assessments** that reference the asset (both direct and via demands)
- **Risks** that reference the asset

When attempting to delete an asset with references, you'll see a detailed error message like:
```
Cannot delete asset 'Production Database' - it is referenced by: 1 demand(s) reference this asset and 2 risk assessment(s) reference this asset. Please handle these references first.
```

## Files Modified

### Backend Code
- `AssetController.kt`: Enhanced deletion logic with comprehensive reference checking
- Added `RiskRepository` injection for checking risk references

### Database Scripts
- `fix-foreign-key-constraints.sql`: Fixes dangerous CASCADE constraints
- `install-with-constraints.sql`: Updated installation script with instructions

### Application Logic
The asset deletion now:
1. Checks for referencing demands
2. Checks for referencing risk assessments (both legacy and new patterns)
3. Checks for referencing risks
4. Provides detailed error messages
5. Only allows deletion when no references exist

This ensures data integrity and prevents accidental data loss while providing clear guidance to users.