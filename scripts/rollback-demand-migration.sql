-- Rollback Script for Demand-Based Risk Assessment Migration
-- WARNING: This script will remove demand functionality and revert to asset-based risk assessments
-- Use this only if the migration needs to be rolled back due to critical issues

-- Enable safe updates mode
SET SQL_SAFE_UPDATES = 0;

-- Create rollback log
INSERT INTO migration_log (step, status, message) VALUES ('ROLLBACK_START', 'STARTED', 'Starting rollback of demand-based risk assessment migration');

-- Step 1: Ensure all risk assessments have asset_id populated
-- For risk assessments linked to CHANGE demands, copy the existing_asset_id
UPDATE risk_assessment ra
JOIN demand d ON ra.demand_id = d.id
SET ra.asset_id = d.existing_asset_id
WHERE ra.asset_id IS NULL 
AND d.demand_type = 'CHANGE' 
AND d.existing_asset_id IS NOT NULL;

INSERT INTO migration_log (step, status, message) VALUES ('POPULATE_ASSET_IDS', 'COMPLETED', CONCAT('Updated ', ROW_COUNT(), ' risk assessments with asset IDs'));

-- Step 2: Handle risk assessments for CREATE_NEW demands
-- These are more complex as the assets might not exist yet
-- Option 1: Create placeholder assets for CREATE_NEW demands that have risk assessments
INSERT INTO asset (name, type, owner, description, created_at, updated_at)
SELECT DISTINCT
    COALESCE(d.new_asset_name, CONCAT('Placeholder Asset for Demand ', d.id)) as name,
    COALESCE(d.new_asset_type, 'Unknown') as type,
    COALESCE(d.new_asset_owner, 'Unknown') as owner,
    CONCAT('Placeholder asset created during rollback for demand: ', d.title) as description,
    NOW() as created_at,
    NOW() as updated_at
FROM demand d
JOIN risk_assessment ra ON d.id = ra.demand_id
WHERE d.demand_type = 'CREATE_NEW'
AND ra.asset_id IS NULL;

-- Update risk assessments to use the newly created placeholder assets
UPDATE risk_assessment ra
JOIN demand d ON ra.demand_id = d.id
JOIN asset a ON a.name = COALESCE(d.new_asset_name, CONCAT('Placeholder Asset for Demand ', d.id))
SET ra.asset_id = a.id
WHERE d.demand_type = 'CREATE_NEW'
AND ra.asset_id IS NULL;

INSERT INTO migration_log (step, status, message) VALUES ('CREATE_PLACEHOLDER_ASSETS', 'COMPLETED', CONCAT('Created placeholder assets and updated risk assessments'));

-- Step 3: Verify all risk assessments have asset_id
SET @assessments_without_assets = (
    SELECT COUNT(*) 
    FROM risk_assessment 
    WHERE asset_id IS NULL
);

IF @assessments_without_assets > 0 THEN
    INSERT INTO migration_log (step, status, message) VALUES ('ROLLBACK_VALIDATION', 'FAILED', CONCAT('Still have ', @assessments_without_assets, ' risk assessments without assets'));
ELSE
    INSERT INTO migration_log (step, status, message) VALUES ('ROLLBACK_VALIDATION', 'COMPLETED', 'All risk assessments have asset references');
END IF;

-- Step 4: Clear demand_id references from risk_assessment
-- This step should only be done if you're completely removing demand functionality
-- UPDATE risk_assessment SET demand_id = NULL;
-- INSERT INTO migration_log (step, status, message) VALUES ('CLEAR_DEMAND_REFERENCES', 'COMPLETED', 'Cleared demand references from risk assessments');

-- Step 5: Drop demand-related tables (DANGER ZONE - only if completely removing demand functionality)
-- WARNING: This will permanently delete all demand data
-- DROP TABLE IF EXISTS demand;
-- INSERT INTO migration_log (step, status, message) VALUES ('DROP_DEMAND_TABLE', 'COMPLETED', 'Dropped demand table');

-- Final rollback log
INSERT INTO migration_log (step, status, message) VALUES ('ROLLBACK_END', 'COMPLETED', 'Rollback completed - risk assessments restored to asset-based model');

-- Display rollback summary
SELECT 
    'Rollback Summary' as summary,
    (SELECT COUNT(*) FROM risk_assessment WHERE asset_id IS NOT NULL) as risk_assessments_with_assets,
    (SELECT COUNT(*) FROM risk_assessment WHERE asset_id IS NULL) as risk_assessments_without_assets,
    (SELECT COUNT(*) FROM asset WHERE description LIKE 'Placeholder asset created during rollback%') as placeholder_assets_created,
    NOW() as completed_at;

-- Display recent migration log entries
SELECT * FROM migration_log WHERE executed_at >= DATE_SUB(NOW(), INTERVAL 1 HOUR) ORDER BY executed_at;

-- Re-enable safe updates
SET SQL_SAFE_UPDATES = 1;

-- IMPORTANT ROLLBACK NOTES:
-- 1. This rollback creates placeholder assets for CREATE_NEW demands
-- 2. The placeholder assets may need manual cleanup/proper configuration
-- 3. Demand data is preserved unless explicitly dropped
-- 4. The application code needs to be reverted to the asset-based version
-- 5. Test thoroughly after rollback to ensure functionality is restored
-- 6. Consider the business impact of losing demand-related functionality