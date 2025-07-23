-- Additional database setup for Secman
-- This file contains any additional database setup beyond basic schema creation

USE secman;

-- Set proper timezone
SET time_zone = '+00:00';

-- Optimize settings for application
SET GLOBAL innodb_buffer_pool_size = 134217728; -- 128MB
SET GLOBAL max_connections = 100;

-- Create indexes that might be needed for performance
-- These will be created by Hibernate, but we can add additional ones here if needed

-- Application-specific configurations can be added here