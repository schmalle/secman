-- Reset Database Script for Secman
-- This script will completely delete all tables and reset the database
-- Database: secman
-- Username: secman
-- Password: CHANGEME

-- Connect to MySQL and switch to secman database
USE secman;

-- Disable foreign key checks to allow dropping tables in any order
SET FOREIGN_KEY_CHECKS = 0;

-- Drop all user tables first (in reverse dependency order to avoid FK constraints)

-- Drop junction/relationship tables first
DROP TABLE IF EXISTS assessment_content_snapshots;
DROP TABLE IF EXISTS standard_requirement_changes;
DROP TABLE IF EXISTS requirement_standard_versions;
DROP TABLE IF EXISTS standard_usecase_versions;
DROP TABLE IF EXISTS requirement_usecase_versions;
DROP TABLE IF EXISTS requirement_norm;
DROP TABLE IF EXISTS requirement_usecase;
DROP TABLE IF EXISTS requirement_standard;
DROP TABLE IF EXISTS standard_usecase;
DROP TABLE IF EXISTS risk_assessment_usecase;
DROP TABLE IF EXISTS response;

-- Drop history tables
DROP TABLE IF EXISTS usecases_history;
DROP TABLE IF EXISTS norms_history;
DROP TABLE IF EXISTS standards_history;
DROP TABLE IF EXISTS requirements_history;

-- Drop main entity tables
DROP TABLE IF EXISTS risk_assessment;
DROP TABLE IF EXISTS risk;
DROP TABLE IF EXISTS asset;
DROP TABLE IF EXISTS requirement;
DROP TABLE IF EXISTS norm;
DROP TABLE IF EXISTS usecase;
DROP TABLE IF EXISTS standard;
DROP TABLE IF EXISTS releases;
DROP TABLE IF EXISTS assessment_token;
DROP TABLE IF EXISTS email_config;
DROP TABLE IF EXISTS translation_config;
DROP TABLE IF EXISTS user_roles;
DROP TABLE IF EXISTS users;

-- Drop Play Framework evolution tracking table
DROP TABLE IF EXISTS play_evolutions;

-- Re-enable foreign key checks
SET FOREIGN_KEY_CHECKS = 1;

-- Verify all tables are dropped
SHOW TABLES;

-- Optional: Reset auto-increment counters if recreating tables
-- (This will be handled automatically when tables are recreated)

-- Display confirmation message
SELECT 'Database secman has been completely reset. All tables have been dropped.' AS Status;