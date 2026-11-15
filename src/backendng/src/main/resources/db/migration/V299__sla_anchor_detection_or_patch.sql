-- SLA anchor = max(first detection, patch date).  docs/CROWDSTRIKE_IMPORT.md §SLA anchor
--
-- Before: with secman.vulnerability.use-patch-publication-date (default true since 2026-08-24)
-- the CrowdStrike import wrote the patch date into first_seen_at, so a finding was overdue
-- 30 days after a fix became available, even if CrowdStrike detected it on the host much later.
-- After: a finding is overdue 30 days after the LATER of "first detected on this host" and
-- "fix available". first_seen_at now means earliest detection only; the patch date enters the
-- anchor through the generated column below. The Kotlin twin is Vulnerability.slaAnchorOf and
-- Vulnerability.SLA_ANCHOR_SQL. Keep the expression identical in all three places.
--
-- Order: index and column go first, so the UPDATE does not maintain an index on a column whose
-- definition is about to change. Dropping and re-adding a VIRTUAL column is a metadata change;
-- only the CREATE INDEX at the end reads the table.

DROP INDEX IF EXISTS idx_vulnerability_excepted_sla ON vulnerability;
ALTER TABLE vulnerability DROP COLUMN IF EXISTS sla_anchor_at;

-- Undo the patch-date anchor on CrowdStrike rows. A row whose first_seen_at equals its own
-- patch_publication_date got that value from the old patch anchor. Its scan_timestamp is Falcon's
-- created_timestamp from the latest import, which is the best detection date still on record.
-- Rows anchored on detection (before 2026-08-24, or without a patch date) do not match and keep
-- their preserved earliest detection. A row whose patch anchor came from an earlier, since
-- revised patch date also does not match; it keeps that older date as its detection, so it
-- errs toward overdue.
UPDATE vulnerability
SET first_seen_at = scan_timestamp
WHERE source = 'CROWDSTRIKE'
  AND patch_publication_date IS NOT NULL
  AND first_seen_at = patch_publication_date
  AND first_seen_at <> scan_timestamp;

-- GREATEST returns NULL when any argument is NULL, hence the COALESCE around the patch date.
ALTER TABLE vulnerability ADD COLUMN sla_anchor_at DATETIME(6) GENERATED ALWAYS AS (
    GREATEST(COALESCE(first_seen_at, scan_timestamp),
             COALESCE(patch_publication_date, first_seen_at, scan_timestamp))
) VIRTUAL;
CREATE INDEX idx_vulnerability_excepted_sla ON vulnerability (excepted, sla_anchor_at);
