-- V204__fix_workgroup_aws_account_collation.sql
-- Fix collation mismatch on workgroup_aws_account.
--
-- V201 created the table without specifying a charset/collation, so MariaDB 11.4
-- stamped it with its server default (utf8mb4_uca1400_ai_ci) while every other
-- legacy table (asset, user_mapping, users, ...) is utf8mb4_general_ci from older
-- Hibernate auto-DDL. Joining workgroup_aws_account.aws_account_id against
-- asset.cloud_account_id then fails with:
--   Illegal mix of collations (utf8mb4_general_ci,IMPLICIT)
--   and (utf8mb4_uca1400_ai_ci,IMPLICIT) for operation '='
-- breaking AssetRepository.findAccessibleAssets and the vulnerability/exceptions UI.
--
-- This migration normalises the table to utf8mb4_general_ci to match the rest of
-- the schema. CONVERT TO ... is idempotent and safe for the 12-char ASCII AWS
-- account IDs stored here.

ALTER TABLE workgroup_aws_account
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
