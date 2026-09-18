-- Existing groups are enrolled only when reconciled by the AWS mapping importer.
ALTER TABLE workgroup ADD COLUMN aws_account_managed BOOLEAN NOT NULL DEFAULT FALSE;
