-- V245: AWS account reference table.
--
-- Supplies a human-readable name for a 12-digit AWS account ID. Rows are created
-- lazily — only when an admin names an account. Accounts with no row are still
-- reported everywhere, they simply display their bare ID.
--
-- Related: docs/superpowers/specs/2026-07-26-account-finding-age-design.md

CREATE TABLE IF NOT EXISTS aws_account (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    aws_account_id VARCHAR(12)  NOT NULL,
    name           VARCHAR(255) NULL,
    updated_at     DATETIME     NULL,
    updated_by     VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_aws_account_account_id UNIQUE (aws_account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
