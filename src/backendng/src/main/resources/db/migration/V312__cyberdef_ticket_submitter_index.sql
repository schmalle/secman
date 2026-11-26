-- CYBERDEF tickets: the hourly submission limit counts a submitter's tickets since a point in
-- time. Without this index MariaDB reads every ticket the submitter ever created and filters
-- by created_at, a cost that grows forever. See docs/CYBERDEF_TICKETS.md.
ALTER TABLE cyberdef_ticket
    ADD INDEX IF NOT EXISTS idx_cyberdef_ticket_submitter_created (created_by_user_id, created_at);
