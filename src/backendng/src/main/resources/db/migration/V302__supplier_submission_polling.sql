ALTER TABLE supplier_assessment_invitation
    ADD COLUMN poll_due_at DATETIME(6) NULL,
    ADD COLUMN poll_lease VARCHAR(36) NULL,
    ADD COLUMN poll_state VARCHAR(24) NULL,
    ADD COLUMN poll_checked_at DATETIME(6) NULL,
    ADD COLUMN poll_observed_at DATETIME(6) NULL,
    ADD COLUMN poll_observed_digest VARCHAR(64) NULL,
    ADD COLUMN poll_integrity_conflict BOOLEAN NOT NULL DEFAULT FALSE,
    ADD INDEX idx_supplier_poll_due (poll_due_at);

UPDATE supplier_assessment_invitation
SET poll_due_at = UTC_TIMESTAMP(6)
WHERE delivery_state IN ('MAIL_SENDING', 'SENT', 'UNCERTAIN')
  AND revoked_at IS NULL AND imported_at IS NULL;
