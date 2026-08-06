-- Add an optional URI field so assets can represent addressable endpoints
-- such as web applications, APIs, SaaS tenants, and URNs.
ALTER TABLE asset
    ADD COLUMN uri VARCHAR(2048) NULL AFTER ip;
