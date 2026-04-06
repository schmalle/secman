-- MCP User Delegation Feature Migration
-- Feature: 050-mcp-user-delegation
-- Date: 2025-11-28

-- NOTE: mcp_api_keys and mcp_audit_logs were originally created by Hibernate auto-DDL.
-- On fresh databases these tables may not exist yet when Flyway runs.
-- Use procedures to make ALTER TABLE conditional.

-- Extend mcp_api_keys table with delegation fields (if the table exists)
SET @table_exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'mcp_api_keys');

SET @sql = IF(@table_exists > 0,
    'ALTER TABLE mcp_api_keys ADD COLUMN IF NOT EXISTS delegation_enabled BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN IF NOT EXISTS allowed_delegation_domains VARCHAR(500) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Extend mcp_audit_logs table with delegation tracking fields (if the table exists)
SET @table_exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'mcp_audit_logs');

SET @sql = IF(@table_exists > 0,
    'ALTER TABLE mcp_audit_logs ADD COLUMN IF NOT EXISTS delegated_user_email VARCHAR(255) NULL, ADD COLUMN IF NOT EXISTS delegated_user_id BIGINT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Create index for filtering audit logs by delegated user (if the table exists)
SET @table_exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'mcp_audit_logs');

SET @sql = IF(@table_exists > 0,
    'CREATE INDEX IF NOT EXISTS idx_mcp_audit_delegated_user ON mcp_audit_logs(delegated_user_email, timestamp)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
