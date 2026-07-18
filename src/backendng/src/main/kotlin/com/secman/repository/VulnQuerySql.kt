package com.secman.repository

/**
 * Shared native-SQL fragments for the statistics query families in
 * [VulnerabilityRepository] that exist in two access scopes:
 *
 *   ForAll    = SELECT + TAIL                          (ADMIN view)
 *   ForAssets = SELECT + SCOPE_* + TAIL                (unified access control)
 *
 * Each family's SELECT ends with `WHERE ` and its TAIL starts with the first
 * shared condition, so the only difference between the two scopes is the
 * interpolated `... IN :assetIds AND ` fragment. This removes the risk of the
 * two variants drifting apart (they are the same query with one extra filter).
 *
 * Same compile-time-interpolation pattern as [ExceptionMatchSql]; annotation
 * values stay compile-time constants.
 */
object VulnQuerySql {

    /** Access-scope fragments (note the trailing `AND `). */
    const val SCOPE_VULN_ASSETS = "v.asset_id IN :assetIds AND "
    const val SCOPE_ASSET_IDS = "a.id IN :assetIds AND "

    const val NOT_EXCEPTED = """NOT EXISTS (
            SELECT 1 FROM vulnerability_exception e WHERE ${ExceptionMatchSql.EXCEPTION_MATCH}
        )"""

    // --- Most common vulnerabilities (top 10 by occurrence) ---
    const val MOST_COMMON_SELECT = """
        SELECT v.vulnerability_id as vulnerabilityId,
               COALESCE(v.cvss_severity, 'UNKNOWN') as cvssSeverity,
               COUNT(*) as occurrenceCount,
               COUNT(DISTINCT v.asset_id) as affectedAssetCount
        FROM vulnerability v
        JOIN asset a ON v.asset_id = a.id
        WHERE """
    const val MOST_COMMON_TAIL = """$NOT_EXCEPTED
        GROUP BY v.vulnerability_id, v.cvss_severity
        ORDER BY COUNT(*) DESC
        LIMIT 10
        """

    // --- Most vulnerable products (top 10 by distinct CVEs) ---
    const val MOST_VULNERABLE_PRODUCTS_SELECT = """
        SELECT v.vulnerable_product_versions as product,
               COUNT(DISTINCT v.vulnerability_id) as vulnerabilityCount,
               COUNT(DISTINCT v.asset_id) as affectedAssetCount,
               COUNT(DISTINCT CASE WHEN v.cvss_severity = 'CRITICAL' THEN v.vulnerability_id END) as criticalCount,
               COUNT(DISTINCT CASE WHEN v.cvss_severity = 'HIGH' THEN v.vulnerability_id END) as highCount
        FROM vulnerability v
        JOIN asset a ON v.asset_id = a.id
        WHERE """
    const val MOST_VULNERABLE_PRODUCTS_TAIL = """v.vulnerable_product_versions IS NOT NULL
          AND v.vulnerable_product_versions != ''
          AND $NOT_EXCEPTED
        GROUP BY v.vulnerable_product_versions
        ORDER BY COUNT(DISTINCT v.vulnerability_id) DESC
        LIMIT 10
        """

    // --- Severity distribution ---
    const val SEVERITY_DISTRIBUTION_SELECT = """
        SELECT COALESCE(v.cvss_severity, 'UNKNOWN') as severity, COUNT(*) as count
        FROM vulnerability v
        JOIN asset a ON v.asset_id = a.id
        WHERE """
    const val SEVERITY_DISTRIBUTION_TAIL = """$NOT_EXCEPTED
        GROUP BY COALESCE(v.cvss_severity, 'UNKNOWN')
        """

    // --- Top assets by vulnerability count (top 50) ---
    const val TOP_ASSETS_SELECT = """
        SELECT a.id as assetId, a.name as assetName, a.type as assetType, a.ip as assetIp,
               COUNT(*) as totalVulnerabilityCount,
               SUM(CASE WHEN v.cvss_severity = 'CRITICAL' THEN 1 ELSE 0 END) as criticalCount,
               SUM(CASE WHEN v.cvss_severity = 'HIGH' THEN 1 ELSE 0 END) as highCount,
               SUM(CASE WHEN v.cvss_severity = 'MEDIUM' THEN 1 ELSE 0 END) as mediumCount,
               SUM(CASE WHEN v.cvss_severity = 'LOW' THEN 1 ELSE 0 END) as lowCount
        FROM vulnerability v
        JOIN asset a ON v.asset_id = a.id
        WHERE """
    const val TOP_ASSETS_TAIL = """$NOT_EXCEPTED
        GROUP BY a.id, a.name, a.type, a.ip
        ORDER BY COUNT(*) DESC
        LIMIT 50
        """

    // --- Vulnerabilities grouped by asset type ---
    const val BY_ASSET_TYPE_SELECT = """
        SELECT COALESCE(a.type, 'Unknown') as assetType,
               COUNT(DISTINCT a.id) as assetCount,
               COUNT(*) as totalVulnerabilityCount,
               SUM(CASE WHEN v.cvss_severity = 'CRITICAL' THEN 1 ELSE 0 END) as criticalCount,
               SUM(CASE WHEN v.cvss_severity = 'HIGH' THEN 1 ELSE 0 END) as highCount,
               SUM(CASE WHEN v.cvss_severity = 'MEDIUM' THEN 1 ELSE 0 END) as mediumCount,
               SUM(CASE WHEN v.cvss_severity = 'LOW' THEN 1 ELSE 0 END) as lowCount,
               CAST(COUNT(*) AS DOUBLE) / COUNT(DISTINCT a.id) as avgVulnsPerAsset
        FROM vulnerability v
        JOIN asset a ON v.asset_id = a.id
        WHERE """
    const val BY_ASSET_TYPE_TAIL = """$NOT_EXCEPTED
        GROUP BY COALESCE(a.type, 'Unknown')
        ORDER BY COUNT(*) DESC
        """

    // --- Temporal trends (daily counts since :startDate) ---
    const val TEMPORAL_TRENDS_SELECT = """
        SELECT DATE(v.scan_timestamp) as date,
               COUNT(*) as totalCount,
               SUM(CASE WHEN v.cvss_severity = 'CRITICAL' THEN 1 ELSE 0 END) as criticalCount,
               SUM(CASE WHEN v.cvss_severity = 'HIGH' THEN 1 ELSE 0 END) as highCount,
               SUM(CASE WHEN v.cvss_severity = 'MEDIUM' THEN 1 ELSE 0 END) as mediumCount,
               SUM(CASE WHEN v.cvss_severity = 'LOW' THEN 1 ELSE 0 END) as lowCount
        FROM vulnerability v
        JOIN asset a ON v.asset_id = a.id
        WHERE """
    const val TEMPORAL_TRENDS_TAIL = """v.scan_timestamp >= :startDate
        AND $NOT_EXCEPTED
        GROUP BY DATE(v.scan_timestamp)
        ORDER BY DATE(v.scan_timestamp) ASC
        """
}
