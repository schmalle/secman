-- V248: Track when CrowdStrike last reported an asset as a managed device.
--
-- Deliberately separate from crowdstrike_last_imported_at (V209). That column means
-- "this asset had a finding imported" and drives the CrowdStrike cleanup/stale-deletion
-- rules; overloading it would change deletion behaviour.
--
-- More importantly it is the wrong EDR-presence signal: scripts/import.sh runs
-- `query servers --severity CRITICAL,HIGH` and CrowdStrikeApiClientImpl forwards a batch
-- only when it is non-empty, so a fully-patched host WITH a healthy Falcon sensor is
-- never written. This column is instead stamped from the import's Stage-1 queried-host
-- population (the full device list, including hosts that returned zero vulnerabilities),
-- which is already transmitted to POST /api/crowdstrike/servers/reconcile-stale.
--
-- No backfill: crowdstrike_last_imported_at cannot seed it without importing the very
-- undercount this column exists to avoid. The EDR-coverage KPI self-heals after the
-- first import following deployment.
ALTER TABLE asset
    ADD COLUMN IF NOT EXISTS crowdstrike_agent_seen_at DATETIME NULL;

CREATE INDEX IF NOT EXISTS idx_asset_crowdstrike_agent_seen_at
    ON asset (crowdstrike_agent_seen_at);

-- Composite for the KPI numerator: EC2 population (cloud_instance_id) × agent freshness.
CREATE INDEX IF NOT EXISTS idx_asset_ec2_agent_seen
    ON asset (cloud_instance_id(64), crowdstrike_agent_seen_at);
