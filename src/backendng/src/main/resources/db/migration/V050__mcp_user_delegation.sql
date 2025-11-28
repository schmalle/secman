-- MCP User Delegation Feature Migration
-- Feature: 050-mcp-user-delegation
-- Date: 2025-11-28

-- Extend mcp_api_keys table with delegation fields
ALTER TABLE mcp_api_keys
    ADD COLUMN delegation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN allowed_delegation_domains VARCHAR(500) NULL;

-- Extend mcp_audit_logs table with delegation tracking fields
ALTER TABLE mcp_audit_logs
    ADD COLUMN delegated_user_email VARCHAR(255) NULL,
    ADD COLUMN delegated_user_id BIGINT NULL;

-- Create index for filtering audit logs by delegated user
CREATE INDEX idx_mcp_audit_delegated_user ON mcp_audit_logs(delegated_user_email, timestamp);
