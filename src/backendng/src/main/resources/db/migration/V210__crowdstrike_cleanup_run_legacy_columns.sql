-- Feature 087: CrowdStrike legacy stale-asset cleanup.
-- Adds per-run accounting of how many candidates / deletions came from rule B
-- (the legacy fence: owner='CrowdStrike Import' AND crowdstrike_last_imported_at
-- IS NULL AND no manualCreator AND no scanUploader AND COALESCE(...) < cutoff).
-- Existing runs (before this migration) wrote no rule-B contribution; default 0
-- is the correct historical value.
ALTER TABLE crowdstrike_cleanup_run
    ADD COLUMN legacy_candidate_count INT NOT NULL DEFAULT 0,
    ADD COLUMN legacy_deleted_count   INT NOT NULL DEFAULT 0;
