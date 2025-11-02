-- Migration: Add Amazon SES email provider support to email_configs
-- Feature: Amazon SES Email Integration
-- Date: 2025-11-02
-- Description: Extends email_configs table to support both SMTP and Amazon SES providers
-- Note: All sensitive credentials are encrypted at application layer using EncryptedStringConverter

-- Add name column for configuration identification
ALTER TABLE email_configs
ADD COLUMN IF NOT EXISTS name VARCHAR(100) NOT NULL DEFAULT 'Default SMTP Configuration';

-- Add provider column to distinguish between SMTP and Amazon SES
ALTER TABLE email_configs
ADD COLUMN IF NOT EXISTS provider VARCHAR(20) NOT NULL DEFAULT 'SMTP';

-- Add Amazon SES specific fields
ALTER TABLE email_configs
ADD COLUMN IF NOT EXISTS ses_access_key VARCHAR(255) NULL COMMENT 'Encrypted AWS Access Key ID';

ALTER TABLE email_configs
ADD COLUMN IF NOT EXISTS ses_secret_key TEXT NULL COMMENT 'Encrypted AWS Secret Access Key';

ALTER TABLE email_configs
ADD COLUMN IF NOT EXISTS ses_region VARCHAR(50) NULL COMMENT 'AWS Region (e.g., us-east-1, eu-west-1)';

-- Make SMTP fields nullable to support SES-only configurations
ALTER TABLE email_configs
MODIFY COLUMN smtp_host VARCHAR(255) NULL;

ALTER TABLE email_configs
MODIFY COLUMN smtp_port INT NULL;

ALTER TABLE email_configs
MODIFY COLUMN smtp_tls BOOLEAN NULL DEFAULT TRUE;

ALTER TABLE email_configs
MODIFY COLUMN smtp_ssl BOOLEAN NULL DEFAULT FALSE;

-- Add index for provider type for faster queries
CREATE INDEX IF NOT EXISTS idx_email_configs_provider ON email_configs(provider);

-- Add index for active configuration lookup
CREATE INDEX IF NOT EXISTS idx_email_configs_active ON email_configs(is_active);

-- Add constraint to ensure provider is valid
ALTER TABLE email_configs
ADD CONSTRAINT IF NOT EXISTS chk_provider_type CHECK (provider IN ('SMTP', 'AMAZON_SES'));

-- Add constraint to ensure SMTP configs have required SMTP fields
-- Note: This is enforced at application layer due to conditional requirements

-- Add table comment for documentation
ALTER TABLE email_configs
COMMENT = 'Email provider configurations supporting SMTP and Amazon SES. One configuration can be active at a time.';

-- Update existing records to have proper name if they have default
UPDATE email_configs
SET name = CONCAT('SMTP Configuration ', id)
WHERE name = 'Default SMTP Configuration';

-- Verify the migration
-- SELECT id, name, provider, smtp_host, ses_region, is_active FROM email_configs;
