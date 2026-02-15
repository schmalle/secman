-- =============================================================================
-- Secman Database Installation Script
-- =============================================================================
-- This script creates the database and application database user for Secman.
-- Run as MariaDB root user BEFORE starting the application for the first time.
--
-- Requirements: MariaDB 10.5+ (for CREATE USER IF NOT EXISTS support)
--
-- Database credentials (CHANGE the password in production!):
--   Database: secman
--   Username: secman
--   Password: CHANGEME
-- =============================================================================

-- Create database with proper character set for full Unicode support
CREATE DATABASE IF NOT EXISTS secman
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- Create application database user (localhost-only access)
-- IMPORTANT: Change 'CHANGEME' to a strong password in production!
CREATE USER IF NOT EXISTS 'secman'@'localhost' IDENTIFIED BY 'CHANGEME';

-- Grant only necessary privileges on the secman database
-- The application needs DDL rights for Hibernate schema auto-update and Flyway migrations
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
    ON secman.* TO 'secman'@'localhost';

-- Reload grant tables to ensure changes take effect
FLUSH PRIVILEGES;

-- Confirmation (no credentials displayed for security)
SELECT 'Database and user setup completed successfully!' AS Status;
SELECT 'Database: secman' AS Info;
SELECT 'User: secman@localhost' AS Info;
SELECT 'IMPORTANT: Change the default password in production!' AS Warning;
