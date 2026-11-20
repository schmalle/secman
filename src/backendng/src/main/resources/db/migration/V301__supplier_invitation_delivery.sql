ALTER TABLE supplier_assessment_invitation
    ADD COLUMN previous_external_id VARCHAR(36) NULL,
    ADD COLUMN retain_previous_draft BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN delivery_state VARCHAR(24) NULL,
    ADD COLUMN delivery_due_at DATETIME(6) NULL,
    ADD COLUMN delivery_expires_at DATETIME(6) NULL,
    ADD COLUMN delivery_lease VARCHAR(36) NULL,
    ADD COLUMN delivery_credential TEXT NULL,
    ADD COLUMN delivery_verifier VARCHAR(60) NULL,
    ADD INDEX idx_supplier_delivery_due (delivery_due_at);
