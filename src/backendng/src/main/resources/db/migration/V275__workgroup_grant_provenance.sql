-- Existing relationships have unknown provenance: preserve as manual grants.
ALTER TABLE workgroup_aws_account
    ADD COLUMN manual_grant BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN owner_sync_grant BOOLEAN NOT NULL DEFAULT FALSE;
