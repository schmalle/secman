package com.secman.service

import com.secman.dto.AssetInterventionStatus
import com.secman.repository.ExceptionMatchSql
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

/**
 * Per-asset vulnerability aggregation shared by the Account Vulns and WG Vulns views.
 *
 * This existed as two copy-pasted `countVulnerabilitiesBySeverity` private methods, one in
 * AccountVulnsService and one in WorkgroupVulnsService, and the copies had already drifted:
 * the workgroup copy omitted the `JOIN asset a`, and therefore could not carry the excepted /
 * non-excepted columns at all — ExceptionMatchSql resolves `a.ip`, `a.cloud_account_id` and
 * `a.os_version`. That is why /wg-vulns showed no exception breakdown while /account-vulns did.
 * Adding the intervention-status column to two diverging copies would have produced a third
 * divergence, so the query lives here once.
 *
 * One `GROUP BY` over the asset's vulnerabilities produces every count, including the
 * intervention status inputs — no extra round trip and no N+1.
 */
@Singleton
class AssetVulnCountsQuery(
    private val entityManager: EntityManager,
    private val vulnerabilityConfigService: VulnerabilityConfigService
) {

    private val logger = LoggerFactory.getLogger(AssetVulnCountsQuery::class.java)

    /**
     * Vulnerability counts for one asset, broken down by severity and by exception coverage.
     *
     * @property total Total vulnerability count
     * @property critical Count of CRITICAL severity vulnerabilities
     * @property high Count of HIGH severity vulnerabilities
     * @property medium Count of MEDIUM severity vulnerabilities
     * @property low Count of LOW severity vulnerabilities
     * @property unknown Count of vulnerabilities with NULL or non-standard severity
     * @property excepted Count of vulnerabilities covered by an active exception
     * @property nonExcepted Count of vulnerabilities not covered by an active exception
     * @property nonExceptedOverdue Non-excepted vulnerabilities older than the threshold
     */
    data class AssetVulnCounts(
        val total: Int,
        val critical: Int,
        val high: Int,
        val medium: Int,
        val low: Int,
        val unknown: Int,
        val excepted: Int,
        val nonExcepted: Int,
        val nonExceptedOverdue: Int
    ) {
        /**
         * Traffic-light status for this asset. See AssetInterventionStatus.
         *
         * Note the ordering: overdue is checked first, so an asset carrying both overdue and
         * recent findings is RED, not YELLOW.
         */
        val status: AssetInterventionStatus
            get() = when {
                nonExceptedOverdue > 0 -> AssetInterventionStatus.RED
                nonExcepted > 0 -> AssetInterventionStatus.YELLOW
                else -> AssetInterventionStatus.GREEN
            }

        /**
         * Check that the breakdowns reconcile against the total.
         *
         * @return true if validation passed, false if a mismatch was detected
         */
        fun isValid(): Boolean {
            val sum = critical + high + medium + low + unknown
            return sum == total &&
                excepted + nonExcepted == total &&
                nonExceptedOverdue <= nonExcepted
        }
    }

    /**
     * Days after which a non-excepted vulnerability counts as overdue.
     *
     * Reuses the threshold that already defines "overdue" for the Outdated Assets view and the
     * reminder mails, so all three move together when an admin changes it. Defaults to 30.
     */
    fun thresholdDays(): Int = vulnerabilityConfigService.getReminderOneDays()

    /**
     * Count vulnerabilities per asset, grouped by severity and exception coverage.
     *
     * @param assetIds Asset IDs to count for; an empty list short-circuits without querying
     * @param thresholdDate Vulnerabilities whose SLA anchor predates this instant are overdue.
     *                      Callers pass one instant for the whole request so that every asset in
     *                      a response is measured from the same moment.
     * @return Map of asset ID to counts; assets with no vulnerabilities are absent from the map
     */
    fun countByAsset(assetIds: List<Long>, thresholdDate: LocalDateTime): Map<Long, AssetVulnCounts> {
        if (assetIds.isEmpty()) {
            return emptyMap()
        }

        logger.debug("Counting vulnerabilities for {} assets (overdue before {})", assetIds.size, thresholdDate)

        // Conditional aggregation: one pass over the asset's vulnerabilities yields every column.
        // COALESCE maps NULL severity to '' so it lands in unknown_count rather than vanishing.
        // The SLA anchor is COALESCE(first_seen_at, scan_timestamp) — scan_timestamp alone is
        // refreshed on every re-import and would understate true age.
        val sql = """
            SELECT
                v.asset_id,
                COUNT(*) as total_count,
                SUM(CASE WHEN UPPER(COALESCE(v.cvss_severity, '')) = 'CRITICAL' THEN 1 ELSE 0 END) as critical_count,
                SUM(CASE WHEN UPPER(COALESCE(v.cvss_severity, '')) = 'HIGH' THEN 1 ELSE 0 END) as high_count,
                SUM(CASE WHEN UPPER(COALESCE(v.cvss_severity, '')) = 'MEDIUM' THEN 1 ELSE 0 END) as medium_count,
                SUM(CASE WHEN UPPER(COALESCE(v.cvss_severity, '')) = 'LOW' THEN 1 ELSE 0 END) as low_count,
                SUM(CASE WHEN COALESCE(v.cvss_severity, '') = ''
                         OR UPPER(v.cvss_severity) NOT IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')
                    THEN 1 ELSE 0 END) as unknown_count,
                SUM(CASE WHEN EXISTS (
                    SELECT 1 FROM vulnerability_exception e WHERE ${ExceptionMatchSql.EXCEPTION_MATCH}
                ) THEN 1 ELSE 0 END) as excepted_count,
                SUM(CASE WHEN NOT EXISTS (
                    SELECT 1 FROM vulnerability_exception e WHERE ${ExceptionMatchSql.EXCEPTION_MATCH}
                ) THEN 1 ELSE 0 END) as non_excepted_count,
                SUM(CASE WHEN NOT EXISTS (
                    SELECT 1 FROM vulnerability_exception e WHERE ${ExceptionMatchSql.EXCEPTION_MATCH}
                ) AND COALESCE(v.first_seen_at, v.scan_timestamp) < :thresholdDate
                    THEN 1 ELSE 0 END) as non_excepted_overdue_count
            FROM vulnerability v
            JOIN asset a ON v.asset_id = a.id
            WHERE v.asset_id IN (:assetIds)
            GROUP BY v.asset_id
        """.trimIndent()

        try {
            val query = entityManager.createNativeQuery(sql)
            query.setParameter("assetIds", assetIds)
            query.setParameter("thresholdDate", thresholdDate)

            @Suppress("UNCHECKED_CAST")
            val results = query.resultList as List<Array<Any>>

            val countsMap = results.associate { row ->
                val assetId = (row[0] as Number).toLong()
                val counts = AssetVulnCounts(
                    total = (row[1] as Number).toInt(),
                    critical = (row[2] as Number).toInt(),
                    high = (row[3] as Number).toInt(),
                    medium = (row[4] as Number).toInt(),
                    low = (row[5] as Number).toInt(),
                    unknown = (row[6] as Number).toInt(),
                    excepted = (row[7] as Number).toInt(),
                    nonExcepted = (row[8] as Number).toInt(),
                    nonExceptedOverdue = (row[9] as Number).toInt()
                )

                if (!counts.isValid()) {
                    val sum = counts.critical + counts.high + counts.medium + counts.low + counts.unknown
                    logger.error(
                        "Vulnerability count mismatch for asset {}: sum={}, exceptionSum={}, total={} " +
                        "(critical={}, high={}, medium={}, low={}, unknown={}, excepted={}, nonExcepted={}, overdue={})",
                        assetId, sum, counts.excepted + counts.nonExcepted, counts.total,
                        counts.critical, counts.high, counts.medium, counts.low, counts.unknown,
                        counts.excepted, counts.nonExcepted, counts.nonExceptedOverdue
                    )
                }

                assetId to counts
            }

            logger.debug("Counting complete: {} assets with vulnerability data", countsMap.size)
            return countsMap

        } catch (e: Exception) {
            logger.error("Error counting vulnerabilities by asset", e)
            // Return empty map on error - count fields stay null, matching the pre-existing
            // backward-compatible behaviour of both callers.
            return emptyMap()
        }
    }
}
