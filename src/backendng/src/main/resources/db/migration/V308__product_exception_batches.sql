CREATE TABLE IF NOT EXISTS product_exception_batch (
    id VARCHAR(36) PRIMARY KEY, owner_id BIGINT NOT NULL, manifest LONGTEXT NOT NULL,
    created DATETIME(6) NOT NULL, auto_approve BOOLEAN NOT NULL, status VARCHAR(20) NOT NULL,
    request_key VARCHAR(36), decided_by VARCHAR(255), decided_at DATETIME(6),
    UNIQUE KEY uq_batch_submission (owner_id, request_key), KEY idx_batch_queue (status, created)
);
ALTER TABLE vulnerability_exception ADD COLUMN IF NOT EXISTS product_mode VARCHAR(8),
    ADD COLUMN IF NOT EXISTS batch_id VARCHAR(36), ADD INDEX IF NOT EXISTS idx_exception_batch (batch_id);
CREATE TABLE IF NOT EXISTS product_exception_batch_account (
    batch_id VARCHAR(36) NOT NULL, account_id VARCHAR(12) NOT NULL,
    PRIMARY KEY (batch_id, account_id),
    FOREIGN KEY (batch_id) REFERENCES product_exception_batch(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS product_exception_batch_decision (
    batch_id VARCHAR(36) NOT NULL, sequence_no INT NOT NULL, actor VARCHAR(255) NOT NULL,
    outcome VARCHAR(20) NOT NULL, occurred_at DATETIME(6) NOT NULL, note VARCHAR(2048),
    PRIMARY KEY (batch_id, sequence_no),
    FOREIGN KEY (batch_id) REFERENCES product_exception_batch(id) ON DELETE CASCADE
);
ALTER TABLE vulnerability ADD COLUMN IF NOT EXISTS excepted_until DATETIME(6) NULL;
-- Binary digest preserves literal selector accents under a case-insensitive database collation.
ALTER TABLE vulnerability_exception ADD COLUMN IF NOT EXISTS batch_rule_key BINARY(32)
    GENERATED ALWAYS AS (CASE WHEN batch_id IS NOT NULL THEN
        UNHEX(SHA2(CONCAT(scope_value, ':', product_mode, ':', subject_value), 256)) END) VIRTUAL,
    ADD UNIQUE INDEX IF NOT EXISTS uq_batch_rule (batch_id, batch_rule_key);
