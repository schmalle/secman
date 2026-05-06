-- V207__aws_account_sharing_selected_accounts.sql
-- Per-account scoping for AWS account sharing rules.
--
-- Existing rules continue to share ALL of the source user's AWS accounts —
-- the new join table is empty for them, and resolution queries treat an
-- empty selection as "share all" so today's semantics are preserved.
--
-- Rules created or edited going forward MAY restrict to a subset by
-- inserting rows here. Resolution then matches only the listed account ids.

CREATE TABLE aws_account_sharing_account (
    sharing_id      BIGINT       NOT NULL,
    aws_account_id  VARCHAR(64)  NOT NULL,

    CONSTRAINT pk_aws_sharing_account PRIMARY KEY (sharing_id, aws_account_id),

    CONSTRAINT fk_aws_sharing_account_sharing
        FOREIGN KEY (sharing_id) REFERENCES aws_account_sharing(id)
        ON DELETE CASCADE,

    INDEX idx_aws_sharing_account_sharing (sharing_id),
    INDEX idx_aws_sharing_account_account (aws_account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
