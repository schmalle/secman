package com.secman.repository

/** Legacy rows stay visible during reconciliation; a proven snapshot must match the current AID. */
object CrowdStrikeSnapshotSql {
    const val VISIBLE = """(crowdstrike_snapshot_id IS NULL OR crowdstrike_snapshot_id IN (
        SELECT s.id FROM crowdstrike_snapshot s
        JOIN crowdstrike_asset_identity i ON i.asset_id = s.asset_id AND i.crowdstrike_aid = s.crowdstrike_aid
        WHERE s.active = 1
    )) AND (source <> 'CROWDSTRIKE' OR UPPER(COALESCE(cvss_severity, '')) IN ('HIGH', 'CRITICAL'))"""
    const val CURRENT_FINDINGS = "(SELECT * FROM vulnerability WHERE $VISIBLE)"
    const val IDS_AFTER = """
        SELECT v.id
        FROM $CURRENT_FINDINGS v
        WHERE v.id > :afterId ORDER BY v.id ASC
    """
    const val MAX_ID = """
        SELECT COALESCE(MAX(v.id), 0)
        FROM $CURRENT_FINDINGS v
    """
    const val INSTALLER_COUNT = """
        SELECT COUNT(*)
        FROM $CURRENT_FINDINGS v
        WHERE v.product_class = 'INSTALLER_ARTIFACT'
    """
}
