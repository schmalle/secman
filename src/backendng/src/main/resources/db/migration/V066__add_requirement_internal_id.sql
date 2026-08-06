-- Feature 066: Requirement ID.Revision Versioning
-- Add internal ID tracking for requirements with unique identifiers

-- NOTE: On fresh databases, tables are created by Hibernate auto-DDL after Flyway runs.
-- All ALTER/UPDATE statements are conditional to handle both fresh and existing databases.

-- 1. Create sequence table for atomic ID generation
CREATE TABLE IF NOT EXISTS requirement_id_sequence (
    id BIGINT PRIMARY KEY DEFAULT 1,
    next_value INT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 2. Add internal_id column to requirement (if table exists)
SET @table_exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'requirement');

SET @sql = IF(@table_exists > 0,
    'ALTER TABLE requirement ADD COLUMN IF NOT EXISTS internal_id VARCHAR(20) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. Migrate existing requirements: assign IDs by database ID order (if table exists and has rows without internal_id)
SET @needs_migration = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'requirement');

SET @sql = IF(@needs_migration > 0,
    'UPDATE requirement SET internal_id = CONCAT(''REQ-'', LPAD(id, 3, ''0'')) WHERE internal_id IS NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. Initialize sequence to next available value (if requirement table exists)
SET @sql = IF(@table_exists > 0,
    'INSERT INTO requirement_id_sequence (id, next_value, updated_at) SELECT 1, COALESCE(MAX(CAST(SUBSTRING(internal_id, 5) AS UNSIGNED)), 0) + 1, NOW() FROM requirement ON DUPLICATE KEY UPDATE next_value = VALUES(next_value), updated_at = NOW()',
    'INSERT INTO requirement_id_sequence (id, next_value, updated_at) VALUES (1, 1, NOW()) ON DUPLICATE KEY UPDATE id = id');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. Make internal_id NOT NULL and add unique constraint (if table and column exist)
SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'requirement' AND column_name = 'internal_id');

SET @has_null = 0;
SET @sql = IF(@col_exists > 0,
    'SELECT COUNT(*) INTO @has_null FROM requirement WHERE internal_id IS NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(@col_exists > 0 AND @has_null = 0,
    'ALTER TABLE requirement MODIFY COLUMN internal_id VARCHAR(20) NOT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Add unique constraint if not already present
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE() AND table_name = 'requirement' AND constraint_name = 'uk_requirement_internal_id');

SET @sql = IF(@col_exists > 0 AND @idx_exists = 0,
    'ALTER TABLE requirement ADD CONSTRAINT uk_requirement_internal_id UNIQUE (internal_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6. Add columns to requirement_snapshot (if table exists)
SET @snapshot_exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'requirement_snapshot');

SET @sql = IF(@snapshot_exists > 0,
    'ALTER TABLE requirement_snapshot ADD COLUMN IF NOT EXISTS internal_id VARCHAR(20) NULL, ADD COLUMN IF NOT EXISTS revision INT NOT NULL DEFAULT 1',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 7. Backfill snapshot data from original requirements (if both tables exist)
SET @sql = IF(@snapshot_exists > 0 AND @table_exists > 0,
    'UPDATE requirement_snapshot rs JOIN requirement r ON rs.original_requirement_id = r.id SET rs.internal_id = r.internal_id, rs.revision = r.version_number WHERE rs.internal_id IS NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 8. Make snapshot internal_id NOT NULL (if column exists and no nulls remain)
SET @snap_col_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'requirement_snapshot' AND column_name = 'internal_id');

SET @snap_has_null = 0;
SET @sql = IF(@snap_col_exists > 0,
    'SELECT COUNT(*) INTO @snap_has_null FROM requirement_snapshot WHERE internal_id IS NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(@snap_col_exists > 0 AND @snap_has_null = 0,
    'ALTER TABLE requirement_snapshot MODIFY COLUMN internal_id VARCHAR(20) NOT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 9. Add index for snapshot lookups by internal_id (if table exists)
SET @sql = IF(@snapshot_exists > 0,
    'CREATE INDEX IF NOT EXISTS idx_snapshot_internal_id ON requirement_snapshot(internal_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
