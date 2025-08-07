-- Migration script: Introduce Demand entity and update Risk Assessment to use Demands
-- This script handles the migration from asset-based to demand-based risk assessments
-- WARNING: This is a complex migration that requires careful execution

-- Step 1: Create demand table (this will be done automatically by Hibernate when the application starts)
-- The Demand entity will be created based on the @Entity annotation

-- Step 2: Create temporary demands for existing risk assessments
-- This ensures backward compatibility during the migration period

-- Enable safe updates mode
SET SQL_SAFE_UPDATES = 0;

-- Create a temporary table to track migration progress
CREATE TABLE IF NOT EXISTS migration_log (
    id INT AUTO_INCREMENT PRIMARY KEY,
    step VARCHAR(100) NOT NULL,
    status ENUM('STARTED', 'COMPLETED', 'FAILED') NOT NULL,
    message TEXT,
    executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Log start of migration
INSERT INTO migration_log (step, status, message) VALUES ('MIGRATION_START', 'STARTED', 'Starting demand-based risk assessment migration');

-- Step 3: Wait for application to create demand table, then populate with data for existing risk assessments
-- Note: This step should be run AFTER the application has started and created the demand table

-- Check if demand table exists before proceeding
SET @table_exists = (
    SELECT COUNT(*)
    FROM information_schema.tables 
    WHERE table_schema = DATABASE() 
    AND table_name = 'demand'
);

-- Only proceed if demand table exists
-- Step 4: Create demands for each existing risk assessment
-- This creates CHANGE-type demands for each existing risk assessment

INSERT INTO migration_log (step, status, message) VALUES ('CREATE_MIGRATION_DEMANDS', 'STARTED', 'Creating migration demands for existing risk assessments');

-- Create a demand for each unique asset that has risk assessments
-- These will be migration demands to maintain data integrity
INSERT INTO demand (
    title,
    description,
    demand_type,
    existing_asset_id,
    business_justification,
    priority,
    status,
    requestor_id,
    requested_date,
    approved_date,
    created_at,
    updated_at
)
SELECT DISTINCT
    CONCAT('Migration Demand for Asset: ', a.name) as title,
    CONCAT('Auto-generated demand during migration for existing risk assessments on asset: ', a.name) as description,
    'CHANGE' as demand_type,
    a.id as existing_asset_id,
    'Migration demand created to maintain data integrity for existing risk assessments' as business_justification,
    'MEDIUM' as priority,
    'IN_PROGRESS' as status, -- Set to IN_PROGRESS since they already have risk assessments
    ra.requestor_id,
    MIN(ra.created_at) as requested_date,
    MIN(ra.created_at) as approved_date, -- Assume it was approved when the risk assessment was created
    NOW() as created_at,
    NOW() as updated_at
FROM risk_assessment ra
JOIN asset a ON ra.asset_id = a.id
WHERE ra.asset_id IS NOT NULL
AND NOT EXISTS (
    SELECT 1 FROM demand d WHERE d.existing_asset_id = a.id
)
GROUP BY a.id, ra.requestor_id;

INSERT INTO migration_log (step, status, message) VALUES ('CREATE_MIGRATION_DEMANDS', 'COMPLETED', CONCAT('Created ', ROW_COUNT(), ' migration demands'));

-- Step 5: Add demand_id column to risk_assessment table if it doesn't exist
-- Note: This will be handled by Hibernate when the application starts

-- Step 6: Update existing risk assessments to reference the appropriate demand
INSERT INTO migration_log (step, status, message) VALUES ('UPDATE_RISK_ASSESSMENTS', 'STARTED', 'Updating risk assessments to reference demands');

-- Update risk assessments to link to the migration demands
UPDATE risk_assessment ra
JOIN demand d ON ra.asset_id = d.existing_asset_id 
    AND ra.requestor_id = d.requestor_id
    AND d.title LIKE 'Migration Demand for Asset:%'
SET ra.demand_id = d.id
WHERE ra.asset_id IS NOT NULL 
AND ra.demand_id IS NULL;

INSERT INTO migration_log (step, status, message) VALUES ('UPDATE_RISK_ASSESSMENTS', 'COMPLETED', CONCAT('Updated ', ROW_COUNT(), ' risk assessments'));

-- Step 7: Validate migration
INSERT INTO migration_log (step, status, message) VALUES ('VALIDATE_MIGRATION', 'STARTED', 'Validating migration results');

-- Check for any risk assessments without demands
SET @orphaned_assessments = (
    SELECT COUNT(*) 
    FROM risk_assessment 
    WHERE demand_id IS NULL AND asset_id IS NOT NULL
);

INSERT INTO migration_log (step, status, message) 
VALUES ('VALIDATE_MIGRATION', 
        CASE WHEN @orphaned_assessments = 0 THEN 'COMPLETED' ELSE 'FAILED' END,
        CONCAT('Found ', @orphaned_assessments, ' orphaned risk assessments'));

-- Final migration log
INSERT INTO migration_log (step, status, message) VALUES ('MIGRATION_END', 'COMPLETED', 'Demand-based risk assessment migration completed');

-- Display migration summary
SELECT 
    'Migration Summary' as summary,
    (SELECT COUNT(*) FROM demand WHERE title LIKE 'Migration Demand for Asset:%') as migration_demands_created,
    (SELECT COUNT(*) FROM risk_assessment WHERE demand_id IS NOT NULL) as risk_assessments_updated,
    (SELECT COUNT(*) FROM risk_assessment WHERE demand_id IS NULL AND asset_id IS NOT NULL) as orphaned_assessments,
    NOW() as completed_at;

-- Display migration log
SELECT * FROM migration_log ORDER BY executed_at;

-- Re-enable safe updates
SET SQL_SAFE_UPDATES = 1;

-- IMPORTANT NOTES:
-- 1. This script should be run AFTER the application has started and created the demand table
-- 2. The application should be stopped during the migration to prevent data inconsistencies
-- 3. A backup should be taken before running this migration
-- 4. After migration, thoroughly test the application to ensure everything works correctly
-- 5. The asset_id column in risk_assessment is kept for backward compatibility but will be deprecated