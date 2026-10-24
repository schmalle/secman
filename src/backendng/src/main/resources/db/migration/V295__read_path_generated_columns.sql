-- Read-path optimization (docs/plans/DB_READ_OPTIMIZATION_PLAN.md, Phase 2).
--
-- The current-asset and SLA predicates appear in nearly every operational read, but both were
-- written as expressions over several columns (a CASE over five Falcon markers; a COALESCE over
-- two timestamps), so no index could serve them and every request scanned `asset` and the
-- 1M+ row `vulnerability` table. Each expression now has exactly one definition: a generated
-- column that MariaDB keeps in step with its sources on every write.
--
-- VIRTUAL, not STORED: adding a virtual column is an instant metadata change, while a stored
-- column would rebuild the whole vulnerability table under a copy lock on deploy. An index on a
-- virtual column materializes the value in the index only, which is all the reads need.
--
-- The JPA mappings (Asset.observedAt, Vulnerability.slaAnchorAt) carry the same expression in
-- columnDefinition so Hibernate-created test schemas match; they are read-only on the entity.

-- 1. Current-asset evidence. Falcon-managed assets use actual sensor contact; every other asset
--    uses verified scanner/integration observation (docs/CURRENT_ASSETS.md). The CASE is the
--    former AssetCurrentnessSql.OBSERVED_AT, unchanged, so a NULL owner still falls to ELSE.
ALTER TABLE asset ADD COLUMN IF NOT EXISTS observed_at DATETIME(6) GENERATED ALWAYS AS (
    CASE WHEN crowdstrike_managed = TRUE OR crowdstrike_last_imported_at IS NOT NULL
        OR crowdstrike_hostname IS NOT NULL OR crowdstrike_agent_seen_at IS NOT NULL
        OR owner = 'CrowdStrike Import'
    THEN crowdstrike_agent_seen_at ELSE verified_observed_at END
) VIRTUAL;
CREATE INDEX IF NOT EXISTS idx_asset_observed_at ON asset (observed_at, id);

-- 2. SLA anchor. first_seen_at survives re-imports; scan_timestamp is the legacy fallback.
--    Leading `excepted` lets the overdue filter (excepted = 0 AND anchor < threshold) range-scan.
--    This replaces idx_vulnerability_first_seen_at, which no query could use once every caller
--    wrapped the column in COALESCE; the index count on this table stays the same, which matters
--    for import deadlock contention (see V217).
ALTER TABLE vulnerability ADD COLUMN IF NOT EXISTS sla_anchor_at DATETIME(6) GENERATED ALWAYS AS (
    COALESCE(first_seen_at, scan_timestamp)
) VIRTUAL;
CREATE INDEX IF NOT EXISTS idx_vulnerability_excepted_sla ON vulnerability (excepted, sla_anchor_at);
DROP INDEX IF EXISTS idx_vulnerability_first_seen_at ON vulnerability;

-- 3. Workgroup -> asset direction of the membership join (the primary key leads with asset_id).
CREATE INDEX IF NOT EXISTS idx_asset_workgroups_workgroup_asset ON asset_workgroups (workgroup_id, asset_id);

-- 4. AD domains are compared case-insensitively by the general_ci collation (V205), so the
--    LOWER() wrappers in the access SQL only defeated idx_asset_ad_domain. Entity callbacks already
--    lower-case on write; converge rows written by native SQL before the wrappers went away.
UPDATE asset SET ad_domain = LOWER(TRIM(ad_domain))
WHERE ad_domain IS NOT NULL AND BINARY ad_domain <> BINARY LOWER(TRIM(ad_domain));
UPDATE user_mapping SET domain = LOWER(TRIM(domain))
WHERE domain IS NOT NULL AND BINARY domain <> BINARY LOWER(TRIM(domain));
UPDATE workgroup_ad_domain SET ad_domain = LOWER(TRIM(ad_domain))
WHERE BINARY ad_domain <> BINARY LOWER(TRIM(ad_domain));

-- 5. EOL findings are always read per asset set and status, ordered by end-of-life date.
CREATE INDEX IF NOT EXISTS idx_eol_finding_asset_status_date ON eol_finding (asset_id, status, eol_date);
