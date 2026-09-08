-- Additive storage for scoped, atomic extension snapshots. Legacy rows remain intact.
ALTER TABLE github_repository ADD COLUMN github_instance VARCHAR(255) NOT NULL DEFAULT 'github.com';
UPDATE github_repository
SET github_instance = LOWER(SUBSTRING_INDEX(SUBSTRING_INDEX(html_url, '/', 3), '://', -1))
WHERE html_url LIKE 'https://%' AND SUBSTRING_INDEX(SUBSTRING_INDEX(html_url, '/', 3), '://', -1) <> '';
UPDATE github_repository
SET github_instance = LEFT(github_instance, CHAR_LENGTH(github_instance) - 4)
WHERE github_instance LIKE '%:443';
UPDATE github_repository SET github_instance = 'github.com' WHERE github_instance = 'api.github.com';
UPDATE github_repository SET github_instance = SUBSTRING(github_instance, 5)
WHERE github_instance LIKE 'api.%.ghe.com';
ALTER TABLE github_repository
    DROP INDEX uk_github_repo_id,
    DROP INDEX uk_github_repo_full_name,
    ADD UNIQUE KEY uk_github_instance_repo_id (github_instance, github_repo_id),
    ADD UNIQUE KEY uk_github_instance_full_name (github_instance, full_name);

CREATE TABLE integration_scanner (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    source VARCHAR(20) NOT NULL,
    service_user_id BIGINT NOT NULL,
    stale_after_hours INT NOT NULL DEFAULT 24,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_stale_notified_at DATETIME(6) NULL,
    CONSTRAINT fk_integration_scanner_user FOREIGN KEY (service_user_id) REFERENCES users(id)
);

CREATE TABLE integration_subject (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scanner_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    github_repository_id BIGINT NULL,
    last_status VARCHAR(20) NULL,
    last_scan_at DATETIME(6) NULL,
    last_successful_scan_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_integration_subject_asset (scanner_id, asset_id),
    UNIQUE KEY uk_integration_subject_repo (scanner_id, github_repository_id),
    CONSTRAINT fk_integration_subject_scanner FOREIGN KEY (scanner_id) REFERENCES integration_scanner(id),
    CONSTRAINT fk_integration_subject_asset FOREIGN KEY (asset_id) REFERENCES asset(id),
    CONSTRAINT fk_integration_subject_repo FOREIGN KEY (github_repository_id) REFERENCES github_repository(id)
);

CREATE TABLE integration_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scanner_id BIGINT NOT NULL,
    subject_id BIGINT NOT NULL,
    run_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    content_digest VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    complete_coverage BOOLEAN NOT NULL,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    accepted INT NOT NULL,
    resolved INT NOT NULL,
    metadata_json TEXT NOT NULL,
    findings_json LONGTEXT NOT NULL,
    UNIQUE KEY uk_integration_run_retry (scanner_id, subject_id, run_key),
    INDEX idx_integration_run_subject (subject_id, completed_at),
    CONSTRAINT fk_integration_run_scanner FOREIGN KEY (scanner_id) REFERENCES integration_scanner(id),
    CONSTRAINT fk_integration_run_subject FOREIGN KEY (subject_id) REFERENCES integration_subject(id)
);

CREATE TABLE integration_finding (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subject_id BIGINT NOT NULL,
    external_id VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
    severity VARCHAR(20) NOT NULL,
    state VARCHAR(20) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT NULL,
    recommendation TEXT NULL,
    evidence TEXT NULL,
    file_path VARCHAR(1024) NULL,
    line_range VARCHAR(100) NULL,
    url VARCHAR(2048) NULL,
    confidence DOUBLE NULL,
    engine VARCHAR(100) NULL,
    model VARCHAR(200) NULL,
    commit_sha VARCHAR(64) NULL,
    issue_url VARCHAR(2048) NULL,
    fix_pr_url VARCHAR(2048) NULL,
    first_seen_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    vulnerability_id BIGINT NULL,
    projection_key VARCHAR(255) NOT NULL,
    projection_product VARCHAR(512) NULL,
    last_run_id BIGINT NOT NULL,
    UNIQUE KEY uk_integration_finding_identity (subject_id, external_id),
    UNIQUE KEY uk_integration_finding_projection (vulnerability_id),
    INDEX idx_integration_finding_state (subject_id, state),
    CONSTRAINT fk_integration_finding_subject FOREIGN KEY (subject_id) REFERENCES integration_subject(id),
    CONSTRAINT fk_integration_finding_run FOREIGN KEY (last_run_id) REFERENCES integration_run(id),
    CONSTRAINT fk_integration_finding_projection FOREIGN KEY (vulnerability_id) REFERENCES vulnerability(id) ON DELETE SET NULL
);

CREATE TABLE integration_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    finding_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    file_name VARCHAR(200) NOT NULL,
    content_type VARCHAR(50) NOT NULL,
    content MEDIUMBLOB NOT NULL,
    INDEX idx_integration_attachment_finding_run (finding_id, run_id),
    CONSTRAINT fk_integration_attachment_finding FOREIGN KEY (finding_id) REFERENCES integration_finding(id),
    CONSTRAINT fk_integration_attachment_run FOREIGN KEY (run_id) REFERENCES integration_run(id)
);
