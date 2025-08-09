-- Rollback script for Demand Classification Feature
-- Version: 1.0
-- Date: 2025-08-09
-- Description: Remove tables and columns for demand classification system

-- Drop stored procedure
DROP PROCEDURE IF EXISTS GetClassificationStatistics;

-- Drop classification columns from demand table
ALTER TABLE demand 
DROP COLUMN IF EXISTS classification,
DROP COLUMN IF EXISTS classification_hash,
DROP COLUMN IF EXISTS classification_confidence,
DROP INDEX IF EXISTS idx_demand_classification;

-- Drop demand_classification_result table
DROP TABLE IF EXISTS demand_classification_result;

-- Drop demand_classification_rule table
DROP TABLE IF EXISTS demand_classification_rule;

-- Note: This rollback will permanently delete all classification data
-- Make sure to backup data if needed before running this script