-- Keep workgroup AD domain comparisons compatible with legacy asset.ad_domain
-- and user_mapping.domain columns, which use utf8mb4_general_ci.
ALTER TABLE workgroup_ad_domain
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
