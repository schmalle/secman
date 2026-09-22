package com.secman.repository

/** Live grant predicates shared by asset queries; metadata and hierarchy grant no access. */
object AssetAccessSql {
    const val IDS = """
        SELECT DISTINCT a.id FROM asset a
        WHERE
                a.id IN (
                    SELECT aw.asset_id FROM asset_workgroups aw
                    JOIN user_workgroups uw ON aw.workgroup_id = uw.workgroup_id
                    JOIN workgroup w ON w.id = aw.workgroup_id
                    WHERE uw.user_id = :userId AND w.enabled = TRUE
                )
                OR a.cloud_account_id IN (
                    SELECT um.aws_account_id FROM user_mapping um
                    WHERE um.email = :userEmail AND um.aws_account_id IS NOT NULL
                )
                OR LOWER(a.ad_domain) IN (
                    SELECT LOWER(um.domain) FROM user_mapping um
                    WHERE um.email = :userEmail AND um.domain IS NOT NULL
                )
                OR a.cloud_account_id IN (
                    SELECT DISTINCT um2.aws_account_id
                    FROM aws_account_sharing acs
                    JOIN users u_source ON u_source.id = acs.source_user_id
                    JOIN user_mapping um2 ON um2.email = u_source.email AND um2.aws_account_id IS NOT NULL
                    WHERE acs.target_user_id = :userId
                      AND (
                        NOT EXISTS (
                            SELECT 1 FROM aws_account_sharing_account asa
                            WHERE asa.sharing_id = acs.id
                        )
                        OR EXISTS (
                            SELECT 1 FROM aws_account_sharing_account asa
                            WHERE asa.sharing_id = acs.id
                              AND asa.aws_account_id = um2.aws_account_id
                        )
                      )
                )
                OR a.cloud_account_id IN (
                    SELECT waa.aws_account_id FROM workgroup_aws_account waa
                    JOIN user_workgroups uw ON uw.workgroup_id = waa.workgroup_id
                    JOIN workgroup w ON w.id = waa.workgroup_id
                    WHERE uw.user_id = :userId AND w.enabled = TRUE
                )
                OR LOWER(a.ad_domain) COLLATE utf8mb4_general_ci IN (
                    SELECT wad.ad_domain COLLATE utf8mb4_general_ci FROM workgroup_ad_domain wad
                    JOIN user_workgroups uw ON uw.workgroup_id = wad.workgroup_id
                    JOIN workgroup w ON w.id = wad.workgroup_id
                    WHERE uw.user_id = :userId AND w.enabled = TRUE
                )
    """
}
