CREATE TABLE crowdstrike_repair_request (
    request_id VARCHAR(36) NOT NULL PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    dry_run BOOLEAN NOT NULL,
    actor VARCHAR(255) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL,
    http_status INT NOT NULL,
    error_code VARCHAR(64) NULL,
    message VARCHAR(512) NULL,
    aid VARCHAR(64) NULL,
    response_json LONGTEXT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    INDEX idx_cs_repair_request_asset (asset_id)
);
