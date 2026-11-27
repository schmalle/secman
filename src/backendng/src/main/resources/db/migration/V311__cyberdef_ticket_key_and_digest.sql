-- CYBERDEF tickets, follow-up to V310 (docs/CYBERDEF_TICKETS.md):
-- 1. The human ticket key (CD-<year>-<id>) is derived from id and created_at when read, so
--    the stored copy and the second write that filled it go away.
-- 2. Outbox rows of one submission share a batch key, so a recipient gets one digest mail
--    for a batch instead of one mail per ticket.

ALTER TABLE cyberdef_ticket
    DROP INDEX IF EXISTS uk_cyberdef_ticket_key,
    DROP COLUMN IF EXISTS ticket_key;

ALTER TABLE cyberdef_ticket_notification
    ADD COLUMN IF NOT EXISTS batch_key VARCHAR(36) NULL AFTER event_id,
    ADD INDEX IF NOT EXISTS idx_cyberdef_notification_batch (batch_key, recipient_email);
