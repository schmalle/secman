ALTER TABLE risk_assessment
    ADD COLUMN IF NOT EXISTS outstanding_reminder_sent_at DATETIME(6) NULL;
