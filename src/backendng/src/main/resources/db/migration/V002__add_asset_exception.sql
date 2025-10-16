-- Migration: Add asset_id column to vulnerability_exception
-- Feature: 021-vulnerability-overdue-exception-logic (Phase 2)
-- Date: 2025-10-16
-- Description: Adds support for ASSET-type exceptions
-- Note: With hibernate.hbm2ddl.auto=update, this will be created automatically.
--       This script is provided for reference and manual execution if needed.

-- Add asset_id column to vulnerability_exception table
ALTER TABLE vulnerability_exception 
ADD COLUMN asset_id BIGINT NULL;

-- Add index on asset_id for performance
CREATE INDEX IF NOT EXISTS idx_vuln_exception_asset 
ON vulnerability_exception(asset_id);

-- Add foreign key constraint (optional - CASCADE delete when asset is deleted)
-- This ensures exceptions are automatically cleaned up when assets are removed
ALTER TABLE vulnerability_exception
ADD CONSTRAINT fk_exception_asset
FOREIGN KEY (asset_id) REFERENCES asset(id)
ON DELETE CASCADE;

-- Add comment for documentation
ALTER TABLE vulnerability_exception 
COMMENT = 'Vulnerability exceptions (IP, PRODUCT, ASSET types). Feature 021 added ASSET support.';

-- Verify the changes
-- SELECT COUNT(*) as asset_exceptions 
-- FROM vulnerability_exception 
-- WHERE exception_type = 'ASSET';
