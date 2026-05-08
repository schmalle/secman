-- Feature 087: CrowdStrike legacy stale-asset cleanup.
--
-- Adds per-run accounting of how many candidates / deletions came from rule B
-- (the legacy fence: owner='CrowdStrike Import' AND crowdstrike_last_imported_at
-- IS NULL AND no manualCreator AND no scanUploader AND COALESCE(...) < cutoff).
-- Existing runs (before this migration) wrote no rule-B contribution; default 0
-- is the correct historical value.
--
-- IDEMPOTENCY NOTE
-- The crowdstrike_cleanup_run table was originally created by Hibernate's
-- hbm2ddl.auto=update at runtime — there is no prior Flyway CREATE for it.
-- On databases where Hibernate had already created the table (dev / test),
-- only the two ADD COLUMN IF NOT EXISTS statements do work; the CREATE TABLE
-- IF NOT EXISTS is a no-op. On databases where the table never existed
-- (production with hardened hbm2ddl, or fresh installs), the CREATE TABLE
-- builds the full schema and the ADD COLUMNs become no-ops. The CREATE TABLE
-- definition matches what Hibernate generates from the CrowdStrikeCleanupRun
-- entity, so subsequent hbm2ddl.auto=update runs see no schema drift.

CREATE TABLE IF NOT EXISTS crowdstrike_cleanup_run (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    status                    VARCHAR(30)  NOT NULL,
    triggered_by              VARCHAR(100) NOT NULL,
    stale_days                INT          NOT NULL,
    cutoff                    DATETIME(6)  NOT NULL,
    candidate_count           INT          NOT NULL DEFAULT 0,
    deleted_count             INT          NOT NULL DEFAULT 0,
    error_count               INT          NOT NULL DEFAULT 0,
    legacy_candidate_count    INT          NOT NULL DEFAULT 0,
    legacy_deleted_count      INT          NOT NULL DEFAULT 0,
    total_crowdstrike_tracked BIGINT       NOT NULL DEFAULT 0,
    started_at                DATETIME(6)  NOT NULL,
    completed_at              DATETIME(6)  NULL,
    duration_ms               BIGINT       NULL,
    error_message             VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    INDEX idx_cs_cleanup_started (started_at),
    INDEX idx_cs_cleanup_status  (status)
);

ALTER TABLE crowdstrike_cleanup_run
    ADD COLUMN IF NOT EXISTS legacy_candidate_count INT NOT NULL DEFAULT 0;

ALTER TABLE crowdstrike_cleanup_run
    ADD COLUMN IF NOT EXISTS legacy_deleted_count   INT NOT NULL DEFAULT 0;
