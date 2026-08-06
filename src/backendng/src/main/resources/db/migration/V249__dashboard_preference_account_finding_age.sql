-- V249: Make the "longest-open findings by account" home-dashboard card user-configurable,
-- alongside the two KPI cards already covered by dashboard_preference.
--
-- CREATE TABLE IF NOT EXISTS comes first on purpose. dashboard_preference has never been
-- in Flyway — it was introduced as a Hibernate-managed entity only, so hbm2ddl.auto=update
-- creates it. Flyway runs BEFORE Hibernate, so on a fresh database a bare ALTER TABLE here
-- would fail against a table that does not exist yet. The shape below mirrors the entity;
-- on an existing database the IF NOT EXISTS makes it a no-op and the ALTER does the work.
--
-- NOT NULL DEFAULT TRUE rather than letting hbm2ddl.auto=update add the column: it would
-- add it NULLABLE and un-backfilled, and DashboardPreference.showAccountFindingAge is a
-- non-null Kotlin Boolean, so every pre-existing preference row would fail to hydrate.
-- Defaulting to TRUE also preserves today's behaviour — the card is visible until a user
-- explicitly turns it off. Same reasoning as V247.
CREATE TABLE IF NOT EXISTS dashboard_preference (
    id                          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id                     BIGINT       NOT NULL UNIQUE,
    show_aws_clean_server_kpi   BIT(1)       NOT NULL DEFAULT b'1',
    show_edr_coverage_kpi       BIT(1)       NOT NULL DEFAULT b'1',
    show_account_finding_age    BIT(1)       NOT NULL DEFAULT b'1',
    created_at                  DATETIME(6)  NOT NULL,
    updated_at                  DATETIME(6)  NOT NULL
);

ALTER TABLE dashboard_preference
    ADD COLUMN IF NOT EXISTS show_account_finding_age BIT(1) NOT NULL DEFAULT b'1';

UPDATE dashboard_preference SET show_account_finding_age = b'1' WHERE show_account_finding_age IS NULL;
