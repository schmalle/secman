-- Migration script for extended risk assessment model
-- This script migrates existing RiskAssessment records to use the new unified basis approach

-- Add new columns if they don't exist (Hibernate should handle this, but just in case)
ALTER TABLE risk_assessment 
ADD COLUMN IF NOT EXISTS assessment_basis_type VARCHAR(10),
ADD COLUMN IF NOT EXISTS assessment_basis_id BIGINT;

-- Create indexes for the new fields
CREATE INDEX IF NOT EXISTS idx_risk_assessment_basis ON risk_assessment(assessment_basis_type, assessment_basis_id);
CREATE INDEX IF NOT EXISTS idx_risk_assessment_assessor ON risk_assessment(assessor_id);
CREATE INDEX IF NOT EXISTS idx_risk_assessment_requestor ON risk_assessment(requestor_id);
CREATE INDEX IF NOT EXISTS idx_risk_assessment_status ON risk_assessment(status);
CREATE INDEX IF NOT EXISTS idx_risk_assessment_dates ON risk_assessment(start_date, end_date);

-- Update existing records to populate the new basis fields
-- For records with demand_id (should be most/all records)
UPDATE risk_assessment 
SET 
    assessment_basis_type = 'DEMAND',
    assessment_basis_id = demand_id
WHERE demand_id IS NOT NULL 
  AND assessment_basis_type IS NULL;

-- For records with only asset_id (legacy records from before demand implementation)
UPDATE risk_assessment 
SET 
    assessment_basis_type = 'ASSET',
    assessment_basis_id = asset_id
WHERE demand_id IS NULL 
  AND asset_id IS NOT NULL
  AND assessment_basis_type IS NULL;

-- Make the new columns NOT NULL after populating them
ALTER TABLE risk_assessment 
ALTER COLUMN assessment_basis_type SET NOT NULL,
ALTER COLUMN assessment_basis_id SET NOT NULL;

-- Verification queries to check the migration
SELECT 
    'Migration Summary' as category,
    COUNT(*) as total_assessments,
    SUM(CASE WHEN assessment_basis_type = 'DEMAND' THEN 1 ELSE 0 END) as demand_based,
    SUM(CASE WHEN assessment_basis_type = 'ASSET' THEN 1 ELSE 0 END) as asset_based
FROM risk_assessment;

-- Check for any records that couldn't be migrated
SELECT 
    id, 
    demand_id, 
    asset_id, 
    assessment_basis_type, 
    assessment_basis_id,
    'MIGRATION_ISSUE' as issue
FROM risk_assessment 
WHERE assessment_basis_type IS NULL 
   OR assessment_basis_id IS NULL;

-- Validate consistency between old and new fields
SELECT 
    id,
    demand_id,
    asset_id,
    assessment_basis_type,
    assessment_basis_id,
    CASE 
        WHEN assessment_basis_type = 'DEMAND' AND assessment_basis_id = demand_id THEN 'CONSISTENT'
        WHEN assessment_basis_type = 'ASSET' AND assessment_basis_id = asset_id THEN 'CONSISTENT'
        ELSE 'INCONSISTENT'
    END as consistency_check
FROM risk_assessment
WHERE assessment_basis_type IS NOT NULL
  AND assessment_basis_id IS NOT NULL;