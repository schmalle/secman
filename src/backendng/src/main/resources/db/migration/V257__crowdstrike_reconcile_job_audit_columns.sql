-- Reconcile-sweep audit statistics: make every sweep's scope and outcome
-- attributable from the DB alone (2026-08-21 incident: a skew-mangled cutoff
-- deleted 727,637 just-imported rows and the job row only recorded the count).
-- All columns nullable — rows written by older backends simply carry NULLs.
ALTER TABLE crowdstrike_reconcile_job
    ADD COLUMN IF NOT EXISTS queried_host_count INT NULL,
    ADD COLUMN IF NOT EXISTS resolved_asset_count INT NULL,
    ADD COLUMN IF NOT EXISTS excluded_failed_host_count INT NULL,
    ADD COLUMN IF NOT EXISTS stale_candidates BIGINT NULL,
    ADD COLUMN IF NOT EXISTS refreshed BIGINT NULL,
    ADD COLUMN IF NOT EXISTS dry_run BOOLEAN NULL;
