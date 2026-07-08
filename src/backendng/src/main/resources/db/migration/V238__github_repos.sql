-- Feature: GitHub repository vulnerability management.
-- GitHub App credentials (private key encrypted at rest by the application),
-- the repository inventory imported via the App, per-import finding-count
-- snapshots (history for the 30-day non-decrease alert), and per-repo
-- alerting exceptions.
CREATE TABLE IF NOT EXISTS github_app_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_id VARCHAR(64) NOT NULL,
    private_key_pem TEXT NOT NULL,
    installation_id VARCHAR(64) NULL DEFAULT NULL,
    organization VARCHAR(255) NULL DEFAULT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NULL DEFAULT NULL,
    updated_at DATETIME(6) NULL DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS github_repository (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    github_repo_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    owner VARCHAR(255) NOT NULL,
    full_name VARCHAR(512) NOT NULL,
    html_url VARCHAR(1024) NULL DEFAULT NULL,
    owner_email VARCHAR(255) NULL DEFAULT NULL,
    critical_count INT NOT NULL DEFAULT 0,
    high_count INT NOT NULL DEFAULT 0,
    last_import_at DATETIME(6) NULL DEFAULT NULL,
    last_high_critical_finding_at DATETIME(6) NULL DEFAULT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NULL DEFAULT NULL,
    updated_at DATETIME(6) NULL DEFAULT NULL,
    CONSTRAINT uk_github_repo_id UNIQUE (github_repo_id),
    CONSTRAINT uk_github_repo_full_name UNIQUE (full_name),
    INDEX idx_github_repo_owner (owner)
);

CREATE TABLE IF NOT EXISTS github_repo_finding_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    github_repository_id BIGINT NOT NULL,
    snapshot_at DATETIME(6) NOT NULL,
    critical_count INT NOT NULL,
    high_count INT NOT NULL,
    CONSTRAINT fk_ghsnap_repo FOREIGN KEY (github_repository_id)
        REFERENCES github_repository (id) ON DELETE CASCADE,
    INDEX idx_ghsnap_repo_at (github_repository_id, snapshot_at)
);

CREATE TABLE IF NOT EXISTS github_repo_alert_exception (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    github_repository_id BIGINT NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    expiration_date DATETIME(6) NULL DEFAULT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_ghexc_repo FOREIGN KEY (github_repository_id)
        REFERENCES github_repository (id) ON DELETE CASCADE,
    INDEX idx_ghexc_repo (github_repository_id)
);
