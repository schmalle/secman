ALTER TABLE crowdstrike_asset_identity ADD COLUMN identity_observed_at DATETIME(6) NULL;
ALTER TABLE crowdstrike_snapshot
    ADD COLUMN fetched_count INT NULL,
    ADD COLUMN accepted_count INT NULL,
    ADD COLUMN missing_cve_count INT NULL,
    ADD COLUMN duplicate_count INT NULL,
    ADD COLUMN coverage_kind VARCHAR(32) NULL;

-- Do not invent provider observations for legacy bindings. Audit survives asset deletion.
CREATE TABLE crowdstrike_instance_transition (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    crowdstrike_aid VARCHAR(64) NOT NULL,
    former_instance_id VARCHAR(255) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    cloud_account_id VARCHAR(255) NOT NULL,
    ad_domain VARCHAR(255) NULL,
    falcon_first_seen_at DATETIME(6) NOT NULL,
    falcon_last_seen_at DATETIME(6) NULL,
    observed_at DATETIME(6) NOT NULL,
    committed_at DATETIME(6) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    INDEX idx_cs_transition_asset (asset_id)
);
