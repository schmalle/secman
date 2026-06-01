-- Feature: Product-targeted email broadcast
-- Stores the selected product for PRODUCT_USERS broadcast jobs.

ALTER TABLE email_broadcast_jobs
    ADD COLUMN target_product VARCHAR(255) NULL;
