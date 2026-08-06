-- Feature 088 — AI-Assisted Risk Assessment Answers (admin UI toggle).
--
-- The feature is gated by a runtime boolean on app_settings so an ADMIN can
-- flip it from the UI without a redeploy. The env var
-- `AI_RISK_ASSESSMENT_ENABLED` becomes the default value when the row is
-- first created; once persisted, the DB row is authoritative.

ALTER TABLE app_settings
    ADD COLUMN ai_risk_assessment_enabled BOOLEAN NOT NULL DEFAULT FALSE;
