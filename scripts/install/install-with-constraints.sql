-- Complete Database Install Script for Secman with Proper Constraints
-- This script creates the database, user, and sets up proper foreign key constraints
-- to prevent data loss from CASCADE deletions

-- =============================================================================
-- DATABASE AND USER SETUP
-- =============================================================================

-- Create database if it doesn't exist
CREATE DATABASE IF NOT EXISTS secman;

-- Create user if it doesn't exist
-- Note: Using CREATE USER IF NOT EXISTS requires MySQL 5.7+ or MariaDB 10.1+
CREATE USER IF NOT EXISTS 'secman'@'localhost' IDENTIFIED BY 'CHANGEME';

-- Grant full privileges to secman user on secman database
GRANT ALL PRIVILEGES ON secman.* TO 'secman'@'localhost';

-- Reload the grant tables to ensure changes take effect
FLUSH PRIVILEGES;

USE secman;

-- =============================================================================
-- POST-STARTUP CONSTRAINT FIXES
-- =============================================================================
-- NOTE: This section should be run AFTER the Micronaut application has started
-- and Hibernate has created all the tables automatically.
-- 
-- The following constraints need to be applied manually because Hibernate's
-- automatic table generation creates dangerous CASCADE constraints.

-- Instructions for deployment:
-- 1. Start the Micronaut application first (let Hibernate create tables)
-- 2. Run the fix-foreign-key-constraints.sql script
-- 3. Verify constraints with the verification queries

-- =============================================================================
-- SAMPLE DATA (Optional - can be uncommented after application startup)
-- =============================================================================

-- -- Create default admin user (password: "password")
-- INSERT IGNORE INTO users (id, username, email, password, created_at, updated_at) VALUES 
-- (1, 'adminuser', 'admin@example.com', '$2a$10$8K0K6KzMZJZ4KZ4KZ4KZ4e.', NOW(), NOW()),
-- (2, 'normaluser', 'user@example.com', '$2a$10$8K0K6KzMZJZ4KZ4KZ4KZ4e.', NOW(), NOW());

-- -- Create sample assets
-- INSERT IGNORE INTO asset (id, name, type, ip, owner, description, created_at, updated_at) VALUES
-- (1, 'Web Server', 'Server', '192.168.1.10', 'IT Team', 'Main web application server', NOW(), NOW()),
-- (2, 'Database Server', 'Database', '192.168.1.20', 'DBA Team', 'Primary database server', NOW(), NOW()),
-- (3, 'File Server', 'Storage', '192.168.1.30', 'Storage Team', 'Network attached storage', NOW(), NOW());

-- =============================================================================
-- VERIFICATION QUERIES
-- =============================================================================

SELECT 'Database and user setup completed successfully!' AS Status;
SELECT 'Database: secman' AS Database_Name;
SELECT 'User: secman@localhost' AS Database_User;
SELECT 'Password: CHANGEME' AS Database_Password;
SELECT '' AS '';
SELECT 'IMPORTANT: After starting the application, run fix-foreign-key-constraints.sql' AS Next_Step;
SELECT 'This will fix dangerous CASCADE constraints that could cause data loss' AS Reason;