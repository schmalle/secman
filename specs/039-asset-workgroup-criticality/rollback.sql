-- Rollback Script for Feature 039: Asset and Workgroup Criticality
-- Created: 2025-11-01
-- Purpose: Restore database schema to pre-implementation state

-- Drop indexes (if they exist)
DROP INDEX IF EXISTS idx_workgroup_criticality ON workgroup;
DROP INDEX IF EXISTS idx_workgroup_criticality_name ON workgroup;
DROP INDEX IF EXISTS idx_asset_criticality ON asset;
DROP INDEX IF EXISTS idx_asset_criticality_name ON asset;

-- Drop criticality columns
ALTER TABLE workgroup DROP COLUMN IF EXISTS criticality;
ALTER TABLE asset DROP COLUMN IF EXISTS criticality;

-- Verification queries (run after rollback)
-- DESCRIBE workgroup; -- Should NOT show criticality column
-- DESCRIBE asset;     -- Should NOT show criticality column
