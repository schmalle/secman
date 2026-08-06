-- V247: Add the `kind` discriminator to the exception model.
--
-- Until now every exception was a vulnerability-suppression rule. `kind` introduces a
-- third, orthogonal axis alongside subject × scope:
--
--   VULNERABILITY  the historical meaning — suppresses matching findings
--   NO_EDR         "this asset cannot run an EDR agent"; suppresses NOTHING and exists
--                  only to remove the asset from the EDR-coverage KPI denominator
--
-- NOT NULL DEFAULT + an explicit backfill is deliberate and load-bearing. Hibernate's
-- hbm2ddl.auto=update would otherwise add this column NULLABLE and UN-BACKFILLED, and
-- ExceptionMatchSql.EXCEPTION_MATCH now filters on it. A NULL kind on legacy rows would
-- therefore stop every pre-existing exception from matching, and the nightly
-- recomputeAllExceptedScheduled would clear `vulnerability.excepted` fleet-wide,
-- unattended. The SQL predicate additionally tolerates NULL as a second line of defence;
-- this migration is the first. Same shape as V196's enum backfill.

ALTER TABLE vulnerability_exception
    ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'VULNERABILITY';
UPDATE vulnerability_exception SET kind = 'VULNERABILITY' WHERE kind IS NULL OR kind = '';

ALTER TABLE vulnerability_exception_request
    ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'VULNERABILITY';
UPDATE vulnerability_exception_request SET kind = 'VULNERABILITY' WHERE kind IS NULL OR kind = '';

-- Rebuild the EXCEPTION_MATCH covering index with `kind` as the leading equality column.
--
-- V203 created idx_vuln_exception_covering specifically so the correlated EXCEPTION_MATCH
-- semi-join is served index-only — that predicate is evaluated once per outer row across
-- ~358k vulnerabilities, and the non-covering variant is what produced the original ~124s
-- query. EXCEPTION_MATCH now leads with `kind`, so leaving the index unchanged would force
-- MariaDB to read the row for `kind` on every evaluation and reintroduce that cost class.
ALTER TABLE vulnerability_exception DROP INDEX IF EXISTS idx_vuln_exception_covering;
ALTER TABLE vulnerability_exception
    ADD INDEX IF NOT EXISTS idx_vuln_exception_covering
        (kind, expiration_date, subject, scope, subject_value(64), scope_value(64), asset_id);

-- Supports the EDR-coverage KPI denominator (active NO_EDR exception per asset).
CREATE INDEX IF NOT EXISTS idx_vuln_exception_kind_asset
    ON vulnerability_exception (kind, asset_id, expiration_date);

-- Supports the NO_EDR duplicate-request check and supersede sweep, neither of which can
-- key on cve_id (a NO_EDR request has none).
CREATE INDEX IF NOT EXISTS idx_vuln_req_kind_asset
    ON vulnerability_exception_request (kind, asset_id, superseded);
