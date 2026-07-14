package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.Vulnerability
import com.secman.domain.VulnerabilityStatisticsCache
import com.secman.dto.AwsCleanServerKpiCacheData
import com.secman.dto.AwsCleanServerKpiResponse
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.VulnerabilityStatisticsCacheRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/**
 * Computes and caches the "AWS servers with no vulnerability older than 30 days"
 * security KPI: (AWS servers with zero vulnerabilities scanned >30 days ago) /
 * (all AWS servers), as a percentage.
 *
 * Deliberately uses a fixed 30-day window rather than the admin-tunable
 * `VulnerabilityConfig.reminderOneDays` threshold, so the KPI's meaning never
 * silently drifts if that unrelated setting is changed.
 *
 * Recalculated as part of MaterializedViewRefreshService.refreshDerivedData()
 * (i.e. after every CrowdStrike import and every manual admin refresh), so a
 * login only ever reads a pre-computed cache row — never a live query.
 * Piggybacks on the existing `vulnerability_statistics_cache` table so no new
 * schema is needed.
 */
@Singleton
open class AwsCleanServerKpiService(
    private val assetRepository: AssetRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val cacheRepository: VulnerabilityStatisticsCacheRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(AwsCleanServerKpiService::class.java)

    companion object {
        const val CACHE_KEY = "aws_clean_server_kpi"
        const val VULN_AGE_THRESHOLD_DAYS = 30L

        /**
         * "AWS server" = an asset with an EC2 instance ID, matching the
         * established codebase definition (AssetRepository.countAllAwsAssetsWithInstanceId,
         * used by asset-match-clear). This naturally excludes the synthetic
         * "AWS Account <id>" placeholder assets (AwsAccountRiskAssessmentService),
         * which are given a cloudAccountId but never a cloudInstanceId.
         */
    }

    /**
     * Recalculate the KPI and upsert it into the cache. Never throws — failures
     * are logged and the previous cached value (if any) is left in place.
     *
     * @param preloadedVulnerabilities when the caller (MaterializedViewRefreshService) already
     *   loaded a superset of this KPI's 30-day window in the same refresh cycle, it passes that
     *   list here to avoid a second full-table load. The 30-day filter below is always applied
     *   in memory, whether the source is this preloaded (wider) set or a freshly queried one.
     */
    fun recalculate(preloadedVulnerabilities: List<Vulnerability>? = null) {
        val start = System.currentTimeMillis()
        try {
            val totalAwsServers = assetRepository.countAllAwsAssetsWithInstanceId()

            val dirtyAwsServerIds: Set<Long> = if (totalAwsServers == 0L) {
                emptySet()
            } else {
                val thresholdDate = LocalDateTime.now().minusDays(VULN_AGE_THRESHOLD_DAYS)
                val source = preloadedVulnerabilities
                    ?: vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(thresholdDate)
                source.asSequence()
                    .filter { (it.firstSeenAt ?: it.scanTimestamp) < thresholdDate }
                    .filter { !it.asset.cloudInstanceId.isNullOrBlank() }
                    .filter { !it.excepted }
                    .mapNotNull { it.asset.id }
                    .toSet()
            }

            val cleanAwsServers = (totalAwsServers - dirtyAwsServerIds.size).coerceAtLeast(0)
            val percentage = computePercentage(cleanAwsServers, totalAwsServers)

            val json = objectMapper.writeValueAsString(
                AwsCleanServerKpiCacheData(
                    totalAwsServers = totalAwsServers,
                    cleanAwsServers = cleanAwsServers,
                    percentage = percentage
                )
            )
            upsertCache(json, System.currentTimeMillis() - start)

            log.info(
                "AWS clean-server KPI recalculated: totalAwsServers={}, cleanAwsServers={}, percentage={}",
                totalAwsServers, cleanAwsServers, percentage
            )
        } catch (e: Exception) {
            log.error("AWS clean-server KPI recalculation failed (non-fatal): {}", e.message, e)
        }
    }

    /**
     * Read the last-computed KPI value. `available = false` when no
     * calculation has ever completed (fresh install, or before the first
     * CrowdStrike import that triggers a refresh).
     */
    fun getKpi(): AwsCleanServerKpiResponse {
        val entry = cacheRepository.findByCacheKey(CACHE_KEY).orElse(null)
            ?: return AwsCleanServerKpiResponse(available = false)

        val data = objectMapper.readValue(entry.cachedJson, AwsCleanServerKpiCacheData::class.java)
        return AwsCleanServerKpiResponse(
            available = true,
            percentage = data.percentage,
            totalAwsServers = data.totalAwsServers,
            cleanAwsServers = data.cleanAwsServers,
            lastCalculatedAt = entry.lastRefreshedAt
        )
    }

    private fun computePercentage(clean: Long, total: Long): Double {
        if (total <= 0) return 0.0
        return BigDecimal(clean)
            .multiply(BigDecimal(100))
            .divide(BigDecimal(total), 1, RoundingMode.HALF_UP)
            .toDouble()
    }

    @Transactional
    open fun upsertCache(json: String, durationMs: Long) {
        val existing = cacheRepository.findByCacheKey(CACHE_KEY)
        if (existing.isPresent) {
            val entry = existing.get()
            entry.cachedJson = json
            entry.lastRefreshedAt = LocalDateTime.now()
            entry.refreshDurationMs = durationMs
            cacheRepository.update(entry)
        } else {
            cacheRepository.save(
                VulnerabilityStatisticsCache(
                    cacheKey = CACHE_KEY,
                    cachedJson = json,
                    lastRefreshedAt = LocalDateTime.now(),
                    refreshDurationMs = durationMs
                )
            )
        }
    }
}
