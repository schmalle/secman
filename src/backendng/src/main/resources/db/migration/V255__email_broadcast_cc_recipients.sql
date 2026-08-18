-- Feature: manually-added CC recipients for email broadcast jobs
-- ("Contact affected owners" on the EOL product drilldown page).
-- Comma-separated list of admin-entered email addresses, CC'd on every
-- message the job sends. NULL/empty means no CC.

ALTER TABLE email_broadcast_jobs
    ADD COLUMN cc_recipients VARCHAR(2000) NULL;
