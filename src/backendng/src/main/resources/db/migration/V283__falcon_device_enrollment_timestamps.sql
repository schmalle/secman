ALTER TABLE crowdstrike_asset_identity
    ADD COLUMN falcon_first_seen_at DATETIME(6) NULL,
    ADD COLUMN falcon_last_seen_at DATETIME(6) NULL;
