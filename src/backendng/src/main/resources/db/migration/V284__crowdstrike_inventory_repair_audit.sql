ALTER TABLE asset ADD COLUMN crowdstrike_product_type VARCHAR(64) NULL;

-- Enrollment evidence survives replacement of the current binding and asset deletion.
CREATE TABLE crowdstrike_enrollment_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    crowdstrike_aid VARCHAR(64) NOT NULL UNIQUE,
    asset_id BIGINT NOT NULL,
    hostname VARCHAR(255) NOT NULL,
    instance_id VARCHAR(255) NULL,
    cloud_account_id VARCHAR(255) NULL,
    product_type VARCHAR(64) NULL,
    falcon_first_seen_at DATETIME(6) NULL,
    falcon_last_seen_at DATETIME(6) NULL,
    superseded_by VARCHAR(64) NULL,
    observed_at DATETIME(6) NOT NULL,
    INDEX idx_cs_enrollment_asset (asset_id)
);

CREATE TABLE crowdstrike_contact_repair_audit (
    run_id CHAR(36) NOT NULL,
    asset_id BIGINT NOT NULL,
    before_seen_at DATETIME(6) NULL,
    after_seen_at DATETIME(6) NOT NULL,
    repaired_at DATETIME(6) NOT NULL,
    rolled_back_at DATETIME(6) NULL,
    PRIMARY KEY (run_id, asset_id)
);
