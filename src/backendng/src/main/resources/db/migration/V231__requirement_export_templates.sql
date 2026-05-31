CREATE TABLE requirement_export_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT NULL,
    version_label VARCHAR(100) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    content LONGBLOB NOT NULL,
    validation_report_json TEXT NULL,
    uploaded_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMP NULL,
    deactivated_at TIMESTAMP NULL,
    last_used_at TIMESTAMP NULL,
    INDEX idx_req_export_template_status_created (status, created_at),
    INDEX idx_req_export_template_sha256 (sha256)
);

CREATE TABLE requirement_export_template_usage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id BIGINT NULL,
    template_sha256 CHAR(64) NULL,
    exported_by VARCHAR(255) NOT NULL,
    export_scope VARCHAR(20) NOT NULL,
    release_id BIGINT NULL,
    usecase_id BIGINT NULL,
    language VARCHAR(32) NULL,
    template_mode VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_req_export_template_usage_template (template_id),
    INDEX idx_req_export_template_usage_created (created_at),
    CONSTRAINT fk_req_export_template_usage_template FOREIGN KEY (template_id)
        REFERENCES requirement_export_template(id) ON DELETE SET NULL
);
