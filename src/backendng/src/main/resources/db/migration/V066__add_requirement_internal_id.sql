-- Feature 066: Requirement ID.Revision Versioning
-- Add internal ID tracking for requirements with unique identifiers

-- 1. Create sequence table for atomic ID generation
CREATE TABLE requirement_id_sequence (
    id BIGINT PRIMARY KEY DEFAULT 1,
    next_value INT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 2. Add internal_id column to requirement (nullable initially for migration)
ALTER TABLE requirement
ADD COLUMN internal_id VARCHAR(20) NULL;

-- 3. Migrate existing requirements: assign IDs by database ID order
SET @rank := 0;
UPDATE requirement r
JOIN (
    SELECT id, @rank := @rank + 1 AS rn
    FROM requirement
    ORDER BY id ASC
) ranked ON r.id = ranked.id
SET r.internal_id = CONCAT('REQ-', LPAD(ranked.rn, 3, '0'));

-- 4. Initialize sequence to next available value
INSERT INTO requirement_id_sequence (id, next_value, updated_at)
SELECT 1, COALESCE(MAX(CAST(SUBSTRING(internal_id, 5) AS UNSIGNED)), 0) + 1, NOW()
FROM requirement;

-- 5. Make internal_id NOT NULL and add unique constraint
ALTER TABLE requirement
MODIFY COLUMN internal_id VARCHAR(20) NOT NULL,
ADD CONSTRAINT uk_requirement_internal_id UNIQUE (internal_id);

-- 6. Add columns to requirement_snapshot
ALTER TABLE requirement_snapshot
ADD COLUMN internal_id VARCHAR(20) NULL,
ADD COLUMN revision INT NOT NULL DEFAULT 1;

-- 7. Backfill snapshot data from original requirements
UPDATE requirement_snapshot rs
JOIN requirement r ON rs.original_requirement_id = r.id
SET rs.internal_id = r.internal_id,
    rs.revision = r.version_number;

-- 8. Make snapshot internal_id NOT NULL
ALTER TABLE requirement_snapshot
MODIFY COLUMN internal_id VARCHAR(20) NOT NULL;

-- 9. Add index for snapshot lookups by internal_id
CREATE INDEX idx_snapshot_internal_id ON requirement_snapshot(internal_id);
