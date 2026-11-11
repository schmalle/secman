-- MCP API keys reference their owner by a plain user_id with no foreign key, and until
-- V297's release deleting a user left the keys active. Authentication never checks that
-- the owner still exists, so such a key kept working. Deactivate them (rows stay for audit).
UPDATE mcp_api_keys
SET is_active = FALSE
WHERE is_active = TRUE
  AND user_id NOT IN (SELECT id FROM users);
