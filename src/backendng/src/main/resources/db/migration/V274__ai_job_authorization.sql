ALTER TABLE ai_suggestion_job
    ADD COLUMN assignment_version BIGINT NOT NULL DEFAULT -1,
    ADD COLUMN api_key_id BIGINT NULL,
    ADD COLUMN initiating_user_id BIGINT NULL;
