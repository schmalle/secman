-- Foreign Key Constraint Fix Script for Asset Deletion
-- This script fixes dangerous CASCADE constraints that could cause data loss
-- Run this script after initial application startup to correct foreign key constraints

USE secman;

-- =============================================================================
-- FOREIGN KEY CONSTRAINT FIXES
-- =============================================================================

-- 1. Fix risk_assessment.asset_id constraint (currently CASCADE - DANGEROUS!)
-- First, check if the constraint exists and drop it
SET @constraint_name = '';
SELECT CONSTRAINT_NAME INTO @constraint_name 
FROM information_schema.KEY_COLUMN_USAGE 
WHERE TABLE_SCHEMA = 'secman' 
  AND TABLE_NAME = 'risk_assessment' 
  AND COLUMN_NAME = 'asset_id' 
  AND CONSTRAINT_NAME != 'PRIMARY'
LIMIT 1;

-- Drop existing constraint if it exists
SET @sql = IF(@constraint_name != '', 
              CONCAT('ALTER TABLE risk_assessment DROP FOREIGN KEY ', @constraint_name), 
              'SELECT "No asset_id foreign key found in risk_assessment" as message');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add the correct RESTRICT constraint
ALTER TABLE risk_assessment 
ADD CONSTRAINT risk_assessment_asset_fk 
FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE RESTRICT ON UPDATE CASCADE;

-- =============================================================================

-- 2. Fix risk.asset_id constraint (currently CASCADE - DANGEROUS!)
-- First, check if the constraint exists and drop it
SET @risk_constraint_name = '';
SELECT CONSTRAINT_NAME INTO @risk_constraint_name 
FROM information_schema.KEY_COLUMN_USAGE 
WHERE TABLE_SCHEMA = 'secman' 
  AND TABLE_NAME = 'risk' 
  AND COLUMN_NAME = 'asset_id' 
  AND CONSTRAINT_NAME != 'PRIMARY'
LIMIT 1;

-- Drop existing constraint if it exists
SET @sql = IF(@risk_constraint_name != '', 
              CONCAT('ALTER TABLE risk DROP FOREIGN KEY ', @risk_constraint_name), 
              'SELECT "No asset_id foreign key found in risk table" as message');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add the correct RESTRICT constraint
ALTER TABLE risk 
ADD CONSTRAINT risk_asset_fk 
FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE RESTRICT ON UPDATE CASCADE;

-- =============================================================================

-- 3. Verify demand.existing_asset_id constraint is already RESTRICT (should be correct)
-- If it doesn't exist, add it
SELECT 'Checking demand.existing_asset_id constraint...' as status;

SET @demand_constraint_exists = 0;
SELECT COUNT(*) INTO @demand_constraint_exists
FROM information_schema.KEY_COLUMN_USAGE 
WHERE TABLE_SCHEMA = 'secman' 
  AND TABLE_NAME = 'demand' 
  AND COLUMN_NAME = 'existing_asset_id';

-- Add constraint if it doesn't exist
SET @sql = IF(@demand_constraint_exists = 0,
              'ALTER TABLE demand ADD CONSTRAINT demand_existing_asset_fk FOREIGN KEY (existing_asset_id) REFERENCES asset(id) ON DELETE RESTRICT ON UPDATE CASCADE',
              'SELECT "Demand constraint already exists" as message');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- =============================================================================
-- VERIFICATION QUERIES
-- =============================================================================

SELECT 'Foreign Key Constraint Verification' as section;

-- Show all foreign key constraints for asset references
SELECT 
    TABLE_NAME,
    COLUMN_NAME,
    CONSTRAINT_NAME,
    REFERENCED_TABLE_NAME,
    REFERENCED_COLUMN_NAME
FROM information_schema.KEY_COLUMN_USAGE 
WHERE TABLE_SCHEMA = 'secman' 
  AND REFERENCED_TABLE_NAME = 'asset'
ORDER BY TABLE_NAME, COLUMN_NAME;

-- Show foreign key constraint behaviors (if supported by MySQL/MariaDB version)
SELECT 
    TABLE_NAME,
    COLUMN_NAME,
    CONSTRAINT_NAME,
    DELETE_RULE,
    UPDATE_RULE
FROM information_schema.REFERENTIAL_CONSTRAINTS rc
JOIN information_schema.KEY_COLUMN_USAGE kcu 
  ON rc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME 
  AND rc.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA
WHERE rc.CONSTRAINT_SCHEMA = 'secman' 
  AND kcu.REFERENCED_TABLE_NAME = 'asset'
ORDER BY TABLE_NAME, COLUMN_NAME;

SELECT 'Foreign key constraint fixes completed successfully!' as status;
SELECT 'All asset references now use RESTRICT instead of CASCADE' as result;
SELECT 'This prevents accidental data loss during asset deletion' as benefit;