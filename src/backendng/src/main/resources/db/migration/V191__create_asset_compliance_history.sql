-- Asset Compliance History: tracks status changes (COMPLIANT / NON_COMPLIANT) per asset.
-- Only status transitions are stored to minimize storage.
-- Feature: ec2-vulnerability-tracking
CREATE TABLE IF NOT EXISTS asset_compliance_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    status VARCHAR(15) NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    overdue_count INT NOT NULL DEFAULT 0,
    oldest_vuln_days INT DEFAULT NULL,
    source VARCHAR(30) NOT NULL,
    CONSTRAINT fk_ach_asset FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE CASCADE,
    INDEX idx_ach_asset_id (asset_id),
    INDEX idx_ach_changed_at (changed_at),
    INDEX idx_ach_status (status),
    INDEX idx_ach_asset_changed (asset_id, changed_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
