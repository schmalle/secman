CREATE TABLE crowdstrike_identity_repair (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    operation_id VARCHAR(36) NOT NULL,
    source_asset_id BIGINT NOT NULL,
    target_asset_id BIGINT NOT NULL,
    crowdstrike_aid VARCHAR(64) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    falcon_first_seen_at DATETIME(6) NOT NULL,
    falcon_last_seen_at DATETIME(6) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    committed_at DATETIME(6) NOT NULL,
    INDEX idx_cs_repair_operation (operation_id),
    INDEX idx_cs_repair_source (source_asset_id)
);
