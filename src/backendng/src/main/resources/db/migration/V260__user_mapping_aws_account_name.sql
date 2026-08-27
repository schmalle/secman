-- Human-readable AWS account display name on user mappings.
--
-- Source is the `display_name` field of the account mapping file (Cloud
-- Custodian JSON `accounts[]`, or the `display_name` CSV column). It is an
-- attribute of the ACCOUNT, not part of the mapping's identity, which is why it
-- is deliberately NOT added to uk_user_mapping_composite: a renamed account must
-- update the existing row, never fork a second one.
--
-- Its purpose is workgroup linking — an account whose display name is
-- "DevOps-x" belongs to the workgroup "aws-DevOps-x" (see
-- WorkgroupAccountLinkService). Persisting it is what lets the correction path
-- (`manage-user-mappings link-workgroups`, MCP `link_workgroup_aws_accounts`)
-- re-link from the database without the operator re-supplying the source file.
--
-- Nullable and not backfilled: rows imported before this column existed acquire
-- the name on the next import that carries one.
ALTER TABLE user_mapping
    ADD COLUMN aws_account_name VARCHAR(255) NULL;

-- Supports the correction path's scan for "mappings that have both an account id
-- and a display name" without a full table scan.
CREATE INDEX idx_user_mapping_aws_account_name
    ON user_mapping (aws_account_name);
