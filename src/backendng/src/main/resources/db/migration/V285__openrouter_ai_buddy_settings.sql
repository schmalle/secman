-- Existing records remain disabled for beta use. Existing API keys are encrypted on their next save.
ALTER TABLE translation_config MODIFY COLUMN api_key VARCHAR(1536) NOT NULL;
ALTER TABLE translation_config
    ADD COLUMN IF NOT EXISTS ai_buddy_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS ai_buddy_single_instance BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS ai_buddy_providers VARCHAR(512) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS ai_buddy_max_cost_usd DOUBLE NOT NULL DEFAULT 0.50,
    ADD COLUMN IF NOT EXISTS ai_buddy_daily_budget_usd DOUBLE NOT NULL DEFAULT 10.0,
    ADD COLUMN IF NOT EXISTS ai_buddy_input_price DOUBLE NOT NULL DEFAULT 5.0,
    ADD COLUMN IF NOT EXISTS ai_buddy_output_price DOUBLE NOT NULL DEFAULT 20.0,
    ADD COLUMN IF NOT EXISTS ai_buddy_microsoft_evidence BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS ai_buddy_crowdstrike_evidence BOOLEAN NOT NULL DEFAULT FALSE;
