-- Asset identity lookups used by every CrowdStrike import batch.
--
-- The hostname match compared LOWER(COALESCE(crowdstrike_hostname, name)), so no index could serve
-- it and each server in an import scanned the whole asset table. The asset table is
-- utf8mb4_general_ci (V205), which already compares case-insensitively, so the LOWER() wrappers
-- changed nothing but the plan. The COALESCE now lives in one generated column with its own index,
-- following the V295 pattern; queries compare against that column with plain = and a prefix LIKE,
-- both of which range-scan the index.
--
-- VIRTUAL for the same reason as V295: adding it is an instant metadata change, and the index
-- materializes the value without rebuilding the table.
ALTER TABLE asset ADD COLUMN IF NOT EXISTS crowdstrike_match_name VARCHAR(255) GENERATED ALWAYS AS (
    COALESCE(crowdstrike_hostname, name)
) VIRTUAL;
CREATE INDEX IF NOT EXISTS idx_asset_crowdstrike_match_name ON asset (crowdstrike_match_name);
