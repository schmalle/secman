-- V201__add_workgroup_aws_account.sql
-- New workgroup_aws_account table for workgroup-level AWS account assignment
-- Spec: docs/superpowers/specs/2026-04-28-workgroup-aws-account-assignment-design.md
-- Adds access rule #9 to the Unified Access Control matrix.

CREATE TABLE IF NOT EXISTS workgroup_aws_account (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    workgroup_id    BIGINT       NOT NULL,
    aws_account_id  VARCHAR(12)  NOT NULL,
    created_by_id   BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NULL,
    CONSTRAINT uk_workgroup_aws_account UNIQUE (workgroup_id, aws_account_id),
    CONSTRAINT fk_wg_aws_workgroup
        FOREIGN KEY (workgroup_id) REFERENCES workgroup(id) ON DELETE CASCADE,
    CONSTRAINT fk_wg_aws_created_by
        FOREIGN KEY (created_by_id) REFERENCES users(id),
    INDEX idx_wg_aws_workgroup  (workgroup_id),
    INDEX idx_wg_aws_account_id (aws_account_id)
);
