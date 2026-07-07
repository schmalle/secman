-- Feature: auto-start risk assessments for owners of brand-new AWS accounts
-- detected during a user-mapping import (CLI --start-risk-assessment).
-- Tracks which import-detected account/owner pair produced which assessment
-- and stamps the two deadline reminders (2 days / 1 day before end_date) so
-- each is sent exactly once, surviving application restarts.
CREATE TABLE IF NOT EXISTS aws_account_risk_assessment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    aws_account_id VARCHAR(12) NOT NULL,
    owner_email VARCHAR(255) NOT NULL,
    risk_assessment_id BIGINT NOT NULL,
    use_case_name VARCHAR(255) NOT NULL,
    reminder_two_days_sent_at DATETIME NULL DEFAULT NULL,
    reminder_one_day_sent_at DATETIME NULL DEFAULT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_aws_acct_ra_assessment FOREIGN KEY (risk_assessment_id)
        REFERENCES risk_assessment (id) ON DELETE CASCADE,
    INDEX idx_aws_acct_ra_account (aws_account_id),
    INDEX idx_aws_acct_ra_assessment (risk_assessment_id)
);
