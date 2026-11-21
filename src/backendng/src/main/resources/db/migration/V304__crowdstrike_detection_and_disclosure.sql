-- V299 repaired legacy first_seen_at values derived from patch dates.
UPDATE vulnerability SET first_seen_at = scan_timestamp
WHERE source = 'CROWDSTRIKE' AND first_seen_at IS NULL AND scan_timestamp <> import_timestamp;

UPDATE vulnerability v
JOIN (SELECT vulnerability_id, MIN(cve_published_date) AS published
      FROM vulnerability WHERE source = 'CROWDSTRIKE' AND cve_published_date IS NOT NULL
      GROUP BY vulnerability_id HAVING MIN(cve_published_date) = MAX(cve_published_date)) known
  ON known.vulnerability_id = v.vulnerability_id
SET v.cve_published_date = known.published
WHERE v.source = 'CROWDSTRIKE' AND v.cve_published_date IS NULL;

ALTER TABLE vulnerability DROP INDEX idx_vulnerability_excepted_sla,
    MODIFY sla_anchor_at DATETIME(6) GENERATED ALWAYS AS (
        CASE WHEN source = 'CROWDSTRIKE' THEN first_seen_at
        ELSE GREATEST(COALESCE(first_seen_at, scan_timestamp),
                      COALESCE(patch_publication_date, first_seen_at, scan_timestamp)) END
    ) VIRTUAL,
    ADD INDEX idx_vulnerability_excepted_sla (excepted, sla_anchor_at);

ALTER TABLE vulnerability ADD COLUMN IF NOT EXISTS no_vendor_fix BOOLEAN NULL;
