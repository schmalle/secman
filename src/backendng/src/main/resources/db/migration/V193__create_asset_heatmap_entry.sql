-- V193: Create asset_heatmap_entry table for vulnerability heatmap
-- Pre-calculated severity counts per asset for instant heatmap rendering

CREATE TABLE IF NOT EXISTS asset_heatmap_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    asset_name VARCHAR(255) NOT NULL,
    asset_type VARCHAR(50) NOT NULL,
    critical_count INT NOT NULL DEFAULT 0,
    high_count INT NOT NULL DEFAULT 0,
    medium_count INT NOT NULL DEFAULT 0,
    low_count INT NOT NULL DEFAULT 0,
    total_count INT NOT NULL DEFAULT 0,
    heat_level VARCHAR(10) NOT NULL DEFAULT 'GREEN',
    cloud_account_id VARCHAR(255),
    ad_domain VARCHAR(255),
    owner VARCHAR(255),
    workgroup_ids VARCHAR(500),
    manual_creator_id BIGINT,
    scan_uploader_id BIGINT,
    last_calculated_at DATETIME NOT NULL,
    CONSTRAINT uk_heatmap_asset_id UNIQUE (asset_id),
    INDEX idx_heatmap_heat_level (heat_level),
    INDEX idx_heatmap_asset_name (asset_name)
);
