-- Migration: Make aws_account_id and domain columns nullable in user_mapping table
-- Feature: 017-user-mapping-management
-- Date: 2025-10-13
-- Reason: Business rules allow either aws_account_id OR domain to be null (at least one must be provided)
--
-- This aligns the database schema with the business logic:
-- - Email is always required (NOT NULL)
-- - At least one of aws_account_id OR domain must be provided
-- - Either field can be null individually, but not both
--
-- The application enforces the "at least one field" rule at the service layer.

USE secman_dev;

-- Make aws_account_id nullable
ALTER TABLE user_mapping 
MODIFY COLUMN aws_account_id VARCHAR(12) NULL;

-- Make domain nullable (if it isn't already)
ALTER TABLE user_mapping 
MODIFY COLUMN domain VARCHAR(255) NULL;

-- Verify the changes
DESCRIBE user_mapping;

-- Test data to verify (optional - can be run separately)
-- INSERT INTO user_mapping (email, aws_account_id, domain, created_at, updated_at) 
-- VALUES ('test@example.com', NULL, 'example.com', NOW(), NOW());
-- 
-- SELECT * FROM user_mapping WHERE email = 'test@example.com';
-- 
-- DELETE FROM user_mapping WHERE email = 'test@example.com';
