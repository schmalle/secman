-- Feature 088 follow-up: make AI risk-assessment model runtime-configurable
-- from Admin UI by persisting the OpenRouter model id in app_settings.
ALTER TABLE app_settings
    ADD COLUMN ai_risk_assessment_model VARCHAR(255) NOT NULL DEFAULT 'anthropic/claude-sonnet-4.6:online';
