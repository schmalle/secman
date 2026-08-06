-- Track when the 7-day expiration reminder was sent so it is sent exactly once,
-- surviving application restarts. NULL means no reminder has been sent yet.
ALTER TABLE vulnerability_exception_request
    ADD COLUMN reminder_sent_at DATETIME NULL DEFAULT NULL;
