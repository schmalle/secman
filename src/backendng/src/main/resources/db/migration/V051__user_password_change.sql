-- Feature 051: User Password Change
-- Add auth_source column to track authentication method

-- NOTE: On fresh databases, the users table is created by Hibernate auto-DDL
-- after Flyway runs, so this ALTER may not find the table. Use conditional logic.

SET @table_exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'users');

SET @sql = IF(@table_exists > 0,
    'ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_source VARCHAR(20) NOT NULL DEFAULT ''LOCAL''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Index for filtering users by auth source (admin queries)
SET @sql = IF(@table_exists > 0,
    'CREATE INDEX IF NOT EXISTS idx_user_auth_source ON users(auth_source)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
