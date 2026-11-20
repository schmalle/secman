CREATE TABLE supplier_assessment_invitation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id VARCHAR(36) NOT NULL,
    assessment_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    recipient_user_id BIGINT NULL,
    requester_label VARCHAR(255) NOT NULL,
    service_scope VARCHAR(1000) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    deadline DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    submission_id VARCHAR(36) NULL,
    submission_digest VARCHAR(64) NULL,
    submitted_at DATETIME(6) NULL,
    imported_at DATETIME(6) NULL,
    imported_revision BIGINT NULL,
    CONSTRAINT uk_supplier_invite_external UNIQUE (external_id),
    CONSTRAINT uk_supplier_invite_generation UNIQUE (assessment_id, generation),
    CONSTRAINT fk_supplier_invite_assessment FOREIGN KEY (assessment_id)
        REFERENCES risk_assessment (id) ON DELETE CASCADE,
    CONSTRAINT fk_supplier_invite_release FOREIGN KEY (release_id)
        REFERENCES releases (id)
);
