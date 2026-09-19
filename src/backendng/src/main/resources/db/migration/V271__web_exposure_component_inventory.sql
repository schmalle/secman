-- Current web exposure and component inventory derived from WEB_SECURITY snapshots.
ALTER TABLE integration_run ADD COLUMN inventory_json LONGTEXT NULL;
UPDATE integration_run SET inventory_json = 'null' WHERE inventory_json IS NULL;
ALTER TABLE integration_run MODIFY COLUMN inventory_json LONGTEXT NOT NULL;

CREATE TABLE web_exposure (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subject_id BIGINT NOT NULL,
    last_run_id BIGINT NOT NULL,
    configured_url VARCHAR(2048) NOT NULL,
    effective_url VARCHAR(2048) NULL,
    reachability VARCHAR(16) NOT NULL,
    http_status SMALLINT UNSIGNED NULL,
    redirect_count INT NOT NULL,
    vantage_point VARCHAR(100) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_web_exposure_subject (subject_id),
    INDEX idx_web_exposure_reachability (reachability, observed_at),
    CONSTRAINT fk_web_exposure_subject FOREIGN KEY (subject_id) REFERENCES integration_subject(id),
    CONSTRAINT fk_web_exposure_run FOREIGN KEY (last_run_id) REFERENCES integration_run(id)
);

CREATE TABLE web_component (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subject_id BIGINT NOT NULL,
    component_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    category VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(100) NULL,
    confidence DOUBLE NOT NULL,
    evidence_type VARCHAR(32) NOT NULL,
    evidence VARCHAR(512) NOT NULL,
    source_url VARCHAR(2048) NULL,
    state VARCHAR(20) NOT NULL,
    first_seen_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    last_run_id BIGINT NOT NULL,
    UNIQUE KEY uk_web_component_identity (subject_id, component_key),
    INDEX idx_web_component_current (subject_id, state, category),
    INDEX idx_web_component_name (name),
    CONSTRAINT fk_web_component_subject FOREIGN KEY (subject_id) REFERENCES integration_subject(id),
    CONSTRAINT fk_web_component_run FOREIGN KEY (last_run_id) REFERENCES integration_run(id)
);
