-- Do not infer old findings' AID from today's binding. Legacy rows remain unverified.
CREATE TABLE IF NOT EXISTS crowdstrike_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    crowdstrike_aid VARCHAR(64) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    observed_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    complete BIT NOT NULL DEFAULT 1,
    active BIT NOT NULL DEFAULT 1,
    INDEX idx_cs_snapshot_scope (asset_id, severity, active)
);
-- CREATE TABLE skips existing tables, including their index definitions.
CREATE INDEX IF NOT EXISTS idx_cs_snapshot_scope
    ON crowdstrike_snapshot (asset_id, severity, active);

CREATE TABLE IF NOT EXISTS crowdstrike_identity_lock (
    identity_key VARCHAR(64) NOT NULL PRIMARY KEY
);
ALTER TABLE vulnerability
    ADD COLUMN IF NOT EXISTS crowdstrike_snapshot_id BIGINT NULL,
    ADD INDEX IF NOT EXISTS idx_vulnerability_cs_snapshot (crowdstrike_snapshot_id);
-- Audit snapshots deliberately have no asset FK: asset deletion preserves enrollment evidence.
DELETE FROM vulnerability_statistics_cache WHERE cache_key = 'aws_clean_server_kpi';
