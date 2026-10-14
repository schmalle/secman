-- Preserve historical runs as import-inactivity; their local cutoff has no reliable zone.
ALTER TABLE crowdstrike_cleanup_run
    ADD COLUMN IF NOT EXISTS basis VARCHAR(32) NOT NULL DEFAULT 'import-inactivity',
    ADD COLUMN IF NOT EXISTS cutoff_utc DATETIME(6) NULL,
    ADD COLUMN IF NOT EXISTS skipped_count INT NOT NULL DEFAULT 0;
