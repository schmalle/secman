-- Existing keys retain self-delegation. Review explicit delegates before granting others.
ALTER TABLE mcp_api_keys ADD COLUMN allowed_delegate_user_ids VARCHAR(4000) NOT NULL DEFAULT '';
