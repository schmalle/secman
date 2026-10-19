-- Bound equality lookup avoids scanning and locking unrelated assets during Falcon imports.
CREATE INDEX idx_asset_cloud_instance_account ON asset (cloud_instance_id, cloud_account_id);
