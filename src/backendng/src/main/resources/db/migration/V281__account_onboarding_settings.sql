CREATE TABLE IF NOT EXISTS account_onboarding_settings (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    singleton_key INT NOT NULL DEFAULT 1 UNIQUE,
    mode VARCHAR(20) NOT NULL DEFAULT 'WELCOME_ONLY',
    risk_assessment_use_case VARCHAR(255) NULL,
    risk_assessment_deadline_days INT NOT NULL DEFAULT 7,
    welcome_subject VARCHAR(255) NOT NULL,
    welcome_body_html TEXT NOT NULL
);

ALTER TABLE aws_account_risk_assessment ADD COLUMN IF NOT EXISTS simulated BOOLEAN NOT NULL DEFAULT FALSE;
