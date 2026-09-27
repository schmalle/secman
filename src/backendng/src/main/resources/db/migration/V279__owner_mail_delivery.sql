CREATE TABLE owner_mail_notification (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_key VARCHAR(64) NOT NULL,
    aws_account_id VARCHAR(12) NULL,
    owner_email VARCHAR(255) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    error_code VARCHAR(64) NULL,
    provider_message_id VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    claimed_at DATETIME(6) NULL,
    CONSTRAINT uk_owner_mail_event UNIQUE (event_key),
    INDEX idx_owner_mail_created (created_at)
);
