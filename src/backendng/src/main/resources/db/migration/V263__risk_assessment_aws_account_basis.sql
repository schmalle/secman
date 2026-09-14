-- Risk assessments can target an AWS account directly. The foreign key points
-- to the reference row while assessment_basis_id mirrors aws_account.id, just
-- as the existing DEMAND and ASSET bases mirror their entity primary keys.
ALTER TABLE risk_assessment
    ADD COLUMN aws_account_id BIGINT NULL AFTER asset_id,
    ADD CONSTRAINT fk_risk_assessment_aws_account
        FOREIGN KEY (aws_account_id) REFERENCES aws_account(id),
    ADD INDEX idx_risk_assessment_aws_account (aws_account_id);
