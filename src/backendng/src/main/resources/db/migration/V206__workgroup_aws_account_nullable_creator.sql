-- V206__workgroup_aws_account_nullable_creator.sql
-- Make workgroup_aws_account.created_by_id nullable so user deletion can
-- detach the creator instead of failing on the fk_wg_aws_created_by FK.
--
-- Matches the "preserve history, detach the actor" pattern already used
-- on assets.manual_creator, releases.created_by, maintenance_banner.created_by,
-- etc. (see UserService.deleteUser).
--
-- Existing rows keep their current created_by_id value; only the column
-- nullability and the FK action change.

ALTER TABLE workgroup_aws_account
    DROP FOREIGN KEY fk_wg_aws_created_by;

ALTER TABLE workgroup_aws_account
    MODIFY COLUMN created_by_id BIGINT NULL;

ALTER TABLE workgroup_aws_account
    ADD CONSTRAINT fk_wg_aws_created_by
        FOREIGN KEY (created_by_id) REFERENCES users(id)
        ON DELETE SET NULL;
