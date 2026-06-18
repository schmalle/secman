-- Pool & schedule admin/secchampion exception-request emails.
-- admin_notified_at tracks whether a PENDING request has already been included in a
-- digest email. NULL = not yet announced; the hourly digest scheduler picks those up,
-- sends one consolidated email per reviewer, then stamps this column.
ALTER TABLE vulnerability_exception_request
    ADD COLUMN admin_notified_at DATETIME NULL AFTER updated_at;

-- Backfill: treat every pre-existing request as already announced so the first digest
-- run does not dump the entire historical backlog onto reviewers.
UPDATE vulnerability_exception_request
    SET admin_notified_at = COALESCE(created_at, NOW())
    WHERE admin_notified_at IS NULL;

-- Index supporting the digest query: find PENDING requests that are not yet notified.
CREATE INDEX idx_excreq_status_notified
    ON vulnerability_exception_request (status, admin_notified_at);
