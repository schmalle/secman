-- Feature 085: CLI manage-user-mappings --send-email option
-- Audit log for every invocation of POST /api/cli/user-mappings/send-statistics-email
-- One row per invocation, including dry-runs and zero-recipient failures.

CREATE TABLE IF NOT EXISTS user_mapping_statistics_log (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    executed_at            DATETIME(6)   NOT NULL,
    invoked_by             VARCHAR(255)  NOT NULL,
    filter_email           VARCHAR(255),
    filter_status          VARCHAR(20),
    total_users            INT           NOT NULL,
    total_mappings         INT           NOT NULL,
    active_mappings        INT           NOT NULL,
    pending_mappings       INT           NOT NULL,
    domain_mappings        INT           NOT NULL,
    aws_account_mappings   INT           NOT NULL,
    recipient_count        INT           NOT NULL,
    emails_sent            INT           NOT NULL DEFAULT 0,
    emails_failed          INT           NOT NULL DEFAULT 0,
    status                 VARCHAR(20)   NOT NULL,
    dry_run                BOOLEAN       NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id)
);

CREATE INDEX idx_ums_log_executed_at ON user_mapping_statistics_log (executed_at);
CREATE INDEX idx_ums_log_status      ON user_mapping_statistics_log (status);
