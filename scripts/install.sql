-- Install Database Script for Secman
-- This script will create the database and user for Secman application
-- Database: secman
-- Username: secman
-- Password: CHANGEME

-- Create database if it doesn't exist
CREATE DATABASE IF NOT EXISTS secman;

-- Create user if it doesn't exist
-- Note: Using CREATE USER IF NOT EXISTS requires MySQL 5.7+ or MariaDB 10.1+
CREATE USER IF NOT EXISTS 'secman'@'localhost' IDENTIFIED BY 'CHANGEME';

-- Grant full privileges to secman user on secman database
GRANT ALL PRIVILEGES ON secman.* TO 'secman'@'localhost';

-- Reload the grant tables to ensure changes take effect
FLUSH PRIVILEGES;

-- Display confirmation message
SELECT 'Database and user setup completed successfully!' AS Status;
SELECT 'Database: secman' AS Database_Name;
SELECT 'User: secman@localhost' AS Database_User;
SELECT 'Password: CHANGEME' AS Database_Password;