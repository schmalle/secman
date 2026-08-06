-- Track when an asset last appeared in a CrowdStrike import.
-- Existing rows stay NULL until a future CrowdStrike import or an explicit
-- admin backfill marks them as CrowdStrike-managed.
ALTER TABLE asset
    ADD COLUMN crowdstrike_last_imported_at DATETIME NULL;

CREATE INDEX idx_asset_crowdstrike_last_imported_at
    ON asset (crowdstrike_last_imported_at);
