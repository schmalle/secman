CREATE TABLE IF NOT EXISTS installed_product (
    id BIGINT NOT NULL AUTO_INCREMENT,
    asset_id BIGINT NOT NULL,
    external_id VARCHAR(255) NULL,
    crowdstrike_aid VARCHAR(64) NULL,
    name VARCHAR(512) NOT NULL,
    vendor VARCHAR(255) NULL,
    version VARCHAR(255) NULL,
    category VARCHAR(255) NULL,
    installation_path VARCHAR(1024) NULL,
    installed_at DATETIME(6) NULL,
    last_used_at DATETIME(6) NULL,
    last_updated_at DATETIME(6) NULL,
    imported_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_installed_product_asset FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_installed_product_asset ON installed_product(asset_id);
CREATE INDEX IF NOT EXISTS idx_installed_product_name ON installed_product(name);
CREATE INDEX IF NOT EXISTS idx_installed_product_vendor ON installed_product(vendor);
CREATE INDEX IF NOT EXISTS idx_installed_product_external ON installed_product(external_id);
CREATE INDEX IF NOT EXISTS idx_installed_product_logical ON installed_product(asset_id, name(255), vendor(191), version(191));
