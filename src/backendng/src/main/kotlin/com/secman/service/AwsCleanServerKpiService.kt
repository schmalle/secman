package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
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
     * Both inputs are scalar COUNTs, so this is constant-heap regardless of fleet size. It
     * used to accept the overdue-vulnerability list that MaterializedViewRefreshService had
     * already loaded, to avoid a second full-table read — but that made the entire ~166k-entity
     * list stay reachable for the whole refresh cycle, which is what left no headroom during the
     * 2026-07-30 import. Two cheap aggregates beat one reused giant list.
     */
    fun recalculate() {
        val start = System.currentTimeMillis()
        try {
            val totalAwsServers = assetRepository.countAllAwsAssetsWithInstanceId()

            val dirtyAwsServers = if (totalAwsServers == 0L) {
                0L
            } else {
                vulnerabilityRepository.countDirtyAwsServers(
                    LocalDateTime.now().minusDays(VULN_AGE_THRESHOLD_DAYS)
                )
            }

            val cleanAwsServers = (totalAwsServers - dirtyAwsServers).coerceAtLeast(0)
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
        // Atomic native upsert on the cache_key unique index - see
        // VulnerabilityStatisticsCacheRepository.upsertByCacheKey for the race this closes.
        cacheRepository.upsertByCacheKey(CACHE_KEY, json, LocalDateTime.now(), durationMs)
    }
}
