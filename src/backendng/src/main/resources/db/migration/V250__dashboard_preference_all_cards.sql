-- V250: Extend dashboard_preference to cover the remaining six home-dashboard cards, so
-- every card on the startpage is user-configurable rather than just the security KPIs.
--
-- Same NOT NULL DEFAULT TRUE reasoning as V249: hbm2ddl.auto=update would add these
-- NULLABLE and un-backfilled, and the DashboardPreference fields are non-null Kotlin
-- Booleans, so existing preference rows would fail to hydrate. TRUE preserves today's
-- behaviour — every card stays visible until a user explicitly turns it off.
--
-- No CREATE TABLE guard needed here (unlike V249): V249 already guarantees the table
-- exists by the time this migration runs.
ALTER TABLE dashboard_preference
    ADD COLUMN IF NOT EXISTS show_asset_inventory          BIT(1) NOT NULL DEFAULT b'1',
    ADD COLUMN IF NOT EXISTS show_users                    BIT(1) NOT NULL DEFAULT b'1',
    ADD COLUMN IF NOT EXISTS show_active_users             BIT(1) NOT NULL DEFAULT b'1',
    ADD COLUMN IF NOT EXISTS show_active_releases          BIT(1) NOT NULL DEFAULT b'1',
    ADD COLUMN IF NOT EXISTS show_running_risk_assessments BIT(1) NOT NULL DEFAULT b'1',
    ADD COLUMN IF NOT EXISTS show_last_crowdstrike_import  BIT(1) NOT NULL DEFAULT b'1';
