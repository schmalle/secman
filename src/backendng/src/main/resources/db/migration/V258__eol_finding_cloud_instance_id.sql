-- Denormalized cloud instance id on EOL findings.
--
-- Carried at scan time from `asset.cloud_instance_id`, exactly as asset_name /
-- cloud_account_id / asset_owner already are, so the EOL read queries and the
-- owner notification mails stay single-table (see EolFinding KDoc).
--
-- Nullable and not backfilled on purpose: EolScanService replaces every row on
-- each run (delete-then-insert), so existing rows acquire the value on the next
-- scan. Repository-component findings have no asset and keep it null.
ALTER TABLE eol_finding
    ADD COLUMN cloud_instance_id VARCHAR(255) NULL;
