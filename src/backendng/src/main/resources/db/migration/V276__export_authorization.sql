-- Historical files without a stable actor and scope are not downloadable.
ALTER TABLE export_jobs ADD COLUMN actor_user_id BIGINT NULL, ADD COLUMN scope_digest VARCHAR(64) NULL;
