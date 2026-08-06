-- Feature: CrowdStrike stale-vulnerability cleanup hardening
--
-- Tracks the union of severities ever queried by a CrowdStrike import. The
-- reconcile sweep uses this union (not just the current run's severity flag)
-- so a `--severity CRITICAL` run today still cleans up yesterday's stale HIGHs
-- without forcing operators to remember consistent severity flags across runs.
--
-- The table is small (one row per severity ever imported, expected ≤ 5 rows
-- in practice) so a primary key on `severity` is enough — no composite index
-- needed.

CREATE TABLE crowdstrike_severity_history (
    severity      VARCHAR(20) NOT NULL,
    first_seen_at DATETIME    NOT NULL,
    last_seen_at  DATETIME    NOT NULL,
    PRIMARY KEY (severity)
);
