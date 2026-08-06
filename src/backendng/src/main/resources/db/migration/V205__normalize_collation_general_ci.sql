-- V205__normalize_collation_general_ci.sql
-- Holistic fix for "Illegal mix of collations" errors across the schema.
--
-- The schema has accumulated three collation islands:
--   1. utf8mb4_general_ci      - dominant, used by asset/users/user_mapping/vulnerability/...
--   2. utf8mb4_uca1400_ai_ci   - MariaDB 11.4 server default; stamped on Flyway-created tables
--                                that didn't specify CHARSET/COLLATE explicitly
--   3. utf8mb4_unicode_ci      - the secman database's own default; inherited by some tables
--
-- Every cross-island JOIN/IN/= on string columns blows up with
-- "Illegal mix of collations ... for operation '='". The first symptom found
-- (V204) was workgroup_aws_account vs asset; broader scan revealed asset_heatmap_entry,
-- asset_tag, aws_account_sharing, export_jobs, admin_summary_log, asset_compliance_history,
-- review_decision, user_mapping_statistics_log, vulnerability_age_snapshot, and
-- vulnerability_statistics_cache as additional landmines.
--
-- Strategy: converge on utf8mb4_general_ci (the dominant legacy collation) - lowest-risk
-- because the load-bearing tables are already general_ci. Also set the database default
-- to general_ci so future migrations and Hibernate auto-update don't re-introduce drift.
--
-- NOT converted (intentionally):
--   - flyway_schema_history          - Flyway-managed
--   - identity_providers             - has utf8mb4_bin columns for JSON storage
--   - assessment_content_snapshots   - has utf8mb4_bin columns for JSON storage
--   - *_seq                          - sequence objects, no string columns

-- 1. Make general_ci the database default so new tables inherit it
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- 2. Normalize every drifted base table. CONVERT TO is idempotent on tables already
--    at the target collation, and FK integrity is unaffected (every FK on these tables
--    references a numeric `id` column, never a string column).

ALTER TABLE admin_summary_log              CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE asset_compliance_history       CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE asset_heatmap_entry            CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE asset_tag                      CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE aws_account_sharing            CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE export_jobs                    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE review_decision                CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE user_mapping_statistics_log    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE vulnerability_age_snapshot     CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
ALTER TABLE vulnerability_statistics_cache CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
