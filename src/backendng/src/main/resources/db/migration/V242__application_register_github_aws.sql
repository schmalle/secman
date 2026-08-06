ALTER TABLE application_register
    ADD COLUMN github_repository_url TEXT NULL,
    ADD COLUMN aws_account_ids       TEXT NULL;
