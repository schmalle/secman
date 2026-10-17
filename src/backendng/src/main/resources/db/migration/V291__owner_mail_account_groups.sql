-- Retain the complete account list for grouped welcome delivery and manual retries.
ALTER TABLE owner_mail_notification ADD COLUMN aws_account_ids TEXT NULL;
