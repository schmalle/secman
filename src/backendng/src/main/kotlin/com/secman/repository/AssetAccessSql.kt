package com.secman.repository

/** Live grant predicates shared by asset queries; metadata and hierarchy grant no access. */
object AssetAccessSql {
    /**
     * One branch per grant route, combined with UNION. The former single SELECT joined the six
     * routes with OR, which no index can drive, so every evaluation scanned all of `asset`; each
     * branch here starts from the caller's grant rows and reaches `asset` through an index
     * (asset_workgroups PK, idx_asset_cloud_account, idx_asset_ad_domain).
     *
     * AD domains compare under the general_ci collation (V205), which is case-insensitive, so the
     * former LOWER() wrappers were redundant and only disabled idx_asset_ad_domain. V295 converged
     * the stored values to lower case.
     */
    const val IDS = """
        SELECT aw.asset_id AS id FROM asset_workgroups aw
        JOIN user_workgroups uw ON aw.workgroup_id = uw.workgroup_id
        JOIN workgroup w ON w.id = aw.workgroup_id
        WHERE uw.user_id = :userId AND w.enabled = TRUE
        UNION
        SELECT a.id FROM asset a
        JOIN user_mapping um ON a.cloud_account_id = um.aws_account_id
        WHERE um.email = :userEmail AND um.aws_account_id IS NOT NULL
        UNION
        SELECT a.id FROM asset a
        JOIN user_mapping um ON a.ad_domain = um.domain
        WHERE um.email = :userEmail AND um.domain IS NOT NULL
        UNION
        SELECT a.id FROM asset a
        WHERE a.cloud_account_id IN (
            SELECT um2.aws_account_id
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
        UNION
        SELECT a.id FROM asset a
        JOIN workgroup_aws_account waa ON a.cloud_account_id = waa.aws_account_id
        JOIN user_workgroups uw ON uw.workgroup_id = waa.workgroup_id
        JOIN workgroup w ON w.id = waa.workgroup_id
        WHERE uw.user_id = :userId AND w.enabled = TRUE
        UNION
        SELECT a.id FROM asset a
        JOIN workgroup_ad_domain wad ON a.ad_domain = wad.ad_domain COLLATE utf8mb4_general_ci
        JOIN user_workgroups uw ON uw.workgroup_id = wad.workgroup_id
        JOIN workgroup w ON w.id = wad.workgroup_id
        WHERE uw.user_id = :userId AND w.enabled = TRUE
    """

    /** Membership test for one asset; same routes as [IDS], without returning the whole grant set. */
    const val CONTAINS = """
        SELECT COUNT(*) FROM asset a WHERE a.id = :assetId AND a.id IN ($IDS)
    """

    const val PAGE = """
        SELECT a.* FROM asset a
        WHERE a.id IN ($IDS)
        ORDER BY a.id
    """

    const val COUNT = """
        SELECT COUNT(*) FROM asset a
        WHERE a.id IN ($IDS)
    """
}
