-- Feature: per-alert Dependabot detail for imported GitHub repositories
-- (consolidates the standalone Dependabot Alerts page into GitHub repo
-- vulnerability management). Rows are current-state only: deleted and
-- reinserted per repository on every import, mirroring the CrowdStrike
-- vulnerability import's delete-by-asset + reinsert pattern.
CREATE TABLE IF NOT EXISTS github_repo_dependabot_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    github_repository_id BIGINT NOT NULL,
    alert_number INT NOT NULL,
    package_name VARCHAR(255) NOT NULL,
    ecosystem VARCHAR(50) NOT NULL,
    manifest_path VARCHAR(1024) NULL DEFAULT NULL,
    severity VARCHAR(20) NOT NULL,
    ghsa_id VARCHAR(64) NULL DEFAULT NULL,
    cve_id VARCHAR(32) NULL DEFAULT NULL,
    summary VARCHAR(1024) NULL DEFAULT NULL,
    vulnerable_version_range VARCHAR(255) NULL DEFAULT NULL,
    first_patched_version VARCHAR(255) NULL DEFAULT NULL,
    html_url VARCHAR(1024) NULL DEFAULT NULL,
    alert_created_at DATETIME(6) NULL DEFAULT NULL,
    alert_updated_at DATETIME(6) NULL DEFAULT NULL,
    CONSTRAINT fk_ghalert_repo FOREIGN KEY (github_repository_id)
        REFERENCES github_repository (id) ON DELETE CASCADE,
    INDEX idx_ghalert_repo (github_repository_id),
    INDEX idx_ghalert_severity (severity)
);

-- Superseded by github_repo_dependabot_alert above (per-repo FK vs. loose
-- repository string, GitHub App auth vs. PAT). No production data depends
-- on it — safe to drop.
DROP TABLE IF EXISTS dependabot_alert;
