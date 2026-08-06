-- Feature: clean-state (replace) import for installed products.
-- Stamps each row with the CLI import run id so a multi-batch run can replace
-- a server's products (delete rows not from the current run) without a later
-- batch wiping rows an earlier batch in the same run just inserted.
ALTER TABLE installed_product ADD COLUMN import_run_id VARCHAR(64) NULL;
CREATE INDEX idx_installed_product_run ON installed_product(asset_id, import_run_id);
