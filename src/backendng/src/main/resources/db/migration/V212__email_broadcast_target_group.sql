-- Feature: Admin email broadcast target group selection
-- Adds the audience the admin chose at create time so historical jobs remain self-describing.

ALTER TABLE email_broadcast_jobs
    ADD COLUMN target_group VARCHAR(40) NOT NULL DEFAULT 'ALL_USERS';
