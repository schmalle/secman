ALTER TABLE asset
    ADD COLUMN crowdstrike_hostname VARCHAR(255) NULL AFTER name,
    ADD COLUMN name_overridden_at DATETIME(6) NULL AFTER crowdstrike_hostname,
    ADD COLUMN name_overridden_by VARCHAR(255) NULL AFTER name_overridden_at;

CREATE INDEX idx_asset_crowdstrike_hostname ON asset (crowdstrike_hostname);

CREATE TABLE crowdstrike_asset_identity (
    id BIGINT NOT NULL AUTO_INCREMENT,
    asset_id BIGINT NOT NULL,
    crowdstrike_aid VARCHAR(64) NOT NULL,
    source_hostname VARCHAR(255) NOT NULL,
    first_seen_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_crowdstrike_asset_identity_aid UNIQUE (crowdstrike_aid),
    CONSTRAINT fk_crowdstrike_asset_identity_asset
        FOREIGN KEY (asset_id) REFERENCES asset (id) ON DELETE CASCADE
);

CREATE INDEX idx_crowdstrike_asset_identity_asset
    ON crowdstrike_asset_identity (asset_id);
