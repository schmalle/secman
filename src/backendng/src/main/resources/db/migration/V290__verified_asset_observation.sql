-- Visibility uses evidence of observation, never the time inventory was imported.
ALTER TABLE asset ADD COLUMN IF NOT EXISTS verified_observed_at DATETIME(6) NULL;
ALTER TABLE asset ADD COLUMN IF NOT EXISTS crowdstrike_managed BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE asset a SET a.crowdstrike_managed = TRUE
WHERE a.crowdstrike_last_imported_at IS NOT NULL OR a.crowdstrike_hostname IS NOT NULL
   OR a.crowdstrike_agent_seen_at IS NOT NULL OR a.owner = 'CrowdStrike Import'
   OR EXISTS (SELECT 1 FROM crowdstrike_asset_identity i WHERE i.asset_id = a.id);
CREATE INDEX IF NOT EXISTS idx_asset_verified_observed_at ON asset (verified_observed_at);

-- Legacy scan times can be server-local and can fall back to upload time.
-- They have no provenance flag, so they are not safe observation evidence.
-- Require a new timestamped scan rather than backfilling last_seen or scan_result.

-- Successful non-web runs are observation evidence, including runs without findings.
-- Legacy web runs require fresh reachable inventory because SUCCESS alone can mean unreachable.
UPDATE asset a JOIN (
    SELECT s.asset_id, MAX(r.completed_at) AS observed_at
    FROM integration_run r JOIN integration_subject s ON s.id = r.subject_id
    JOIN integration_scanner c ON c.id = r.scanner_id
    WHERE r.status = 'SUCCESS' AND c.source <> 'WEB_SECURITY'
      AND r.completed_at <= UTC_TIMESTAMP(6) GROUP BY s.asset_id
) i ON i.asset_id = a.id
SET a.verified_observed_at = i.observed_at
WHERE a.verified_observed_at IS NULL OR a.verified_observed_at < i.observed_at;

ALTER TABLE vulnerability_statistics_cache ADD COLUMN IF NOT EXISTS valid_until DATETIME(6) NULL;
-- Legacy cached populations must be recalculated before they are served again.
UPDATE vulnerability_statistics_cache SET valid_until = NULL;
