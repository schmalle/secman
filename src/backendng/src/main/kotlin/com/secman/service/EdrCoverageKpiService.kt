package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.dto.EdrCoverageKpiCacheData
import com.secman.dto.EdrCoverageKpiResponse
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityStatisticsCacheRepository
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/**
 * Computes and caches the "EC2 instances with a CrowdStrike sensor installed" KPI:
 * (EC2 instances CrowdStrike reported recently) / (all EC2 instances, minus those with an
 * approved "No EDR possible" exception), as a percentage.
 *
 * Structurally a sibling of [AwsCleanServerKpiService] — same precomputed-cache contract, so
 * a login only ever reads a pre-computed row and never issues a live query.
 *
 * Two definitions worth stating explicitly, because the naive versions are both wrong:
 *
 *  - **"EC2 instance"** = an asset with a non-blank `cloudInstanceId`, matching the established
 *    codebase definition (`AssetRepository.countAllAwsAssetsWithInstanceId`). This naturally
 *    excludes the synthetic "AWS Account <id>" placeholder assets, which get a cloudAccountId
 *    but never a cloudInstanceId.
 *
 *  - **"has CrowdStrike"** = `crowdStrikeAgentSeenAt` within [AGENT_SEEN_FRESHNESS_DAYS], NOT
 *    `crowdStrikeLastImportedAt`. The latter is only written for hosts that returned findings —
 *    the daily import filters `--severity CRITICAL,HIGH` and drops empty batches — so building
 *    the numerator on it would systematically undercount, reporting low coverage precisely for
 *    well-patched fleets. `crowdStrikeAgentSeenAt` is stamped from the import's full Stage-1
 *    queried-host population instead (see CrowdStrikeVulnerabilityImportService.stampAgentSeen).
 */
@Singleton
open class EdrCoverageKpiService(
    private val assetRepository: AssetRepository,
    private val cacheRepository: VulnerabilityStatisticsCacheRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(EdrCoverageKpiService::class.java)

    companion object {
        const val CACHE_KEY = "edr_coverage_kpi"

        /**
         * How recently CrowdStrike must have reported a host for it to count as covered.
         *
         * Fixed rather than admin-tunable, for the same reason
         * [AwsCleanServerKpiService.VULN_AGE_THRESHOLD_DAYS] is fixed: the KPI's meaning must
         * not silently drift when an unrelated setting changes.
         *
         * Requiring recency at all is what makes this measure CURRENT coverage — a
         * decommissioned or de-instrumented host stops counting as protected instead of
         * ratcheting the number upward forever. The trade-off is the other direction: a run of
         * failed imports longer than this window will depress the KPI. If that turns out to
         * produce false alarms in practice, widening this constant is the whole fix.
         */
        const val AGENT_SEEN_FRESHNESS_DAYS = 7L
    }

    /**
     * Recalculate the KPI and upsert it into the cache. Never throws — failures are logged and
     * the previous cached value (if any) is left in place, matching the non-fatal contract of
     * every other step in MaterializedViewRefreshService.refreshDerivedData().
     *
     * All three inputs are scalar COUNTs, so this is constant-heap regardless of fleet size.
     */
    fun recalculate() {
        val start = System.currentTimeMillis()
        try {
            val totalEc2 = assetRepository.countAllAwsAssetsWithInstanceId()

            // Skip the remaining queries entirely when there is no EC2 population at all.
            val excluded = if (totalEc2 == 0L) 0L else assetRepository.countEc2AssetsExcludedByNoEdrException()
            val eligible = (totalEc2 - excluded).coerceAtLeast(0)
            val covered = if (eligible == 0L) {
                0L
            } else {
                assetRepository.countEc2AssetsWithFreshCrowdStrikeAgent(
                    LocalDateTime.now().minusDays(AGENT_SEEN_FRESHNESS_DAYS)
                )
            }

            val percentage = computePercentage(covered, eligible)

            val json = objectMapper.writeValueAsString(
                EdrCoverageKpiCacheData(
                    totalEc2Instances = totalEc2,
                    eligibleEc2Instances = eligible,
                    coveredEc2Instances = covered,
                    excludedByNoEdrException = excluded,
                    percentage = percentage,
                    agentSeenWithinDays = AGENT_SEEN_FRESHNESS_DAYS
                )
            )
            upsertCache(json, System.currentTimeMillis() - start)

            log.info(
                "EDR coverage KPI recalculated: totalEc2={}, excludedByNoEdr={}, eligible={}, covered={}, percentage={}",
                totalEc2, excluded, eligible, covered, percentage
            )
        } catch (e: Exception) {
            log.error("EDR coverage KPI recalculation failed (non-fatal): {}", e.message, e)
        }
    }

    /**
     * Recalculate hourly, in addition to the post-import refresh.
     *
     * The denominator changes when a NO_EDR exception EXPIRES, and an expiry fires no CRUD
     * event — the same gap ExceptionMaterializationService's hourly sweep exists to cover. The
     * numerator likewise decays as agent sightings age past the freshness window. Three scalar
     * counts is cheap enough that a fixed hourly tick is simpler than event-chasing.
     */
    @Scheduled(fixedDelay = "1h", initialDelay = "20m")
    open fun recalculateScheduled() {
        recalculate()
    }

    /**
     * Read the last-computed KPI value.
     *
     * `available = false` when no calculation has ever completed OR when no asset has ever been
     * stamped with an agent sighting. The second case matters on a fresh deployment: the
     * arithmetic would legitimately produce 0%, which reads as "we lost EDR everywhere" rather
     * than "no import has run yet". It self-heals after the first CrowdStrike import.
     */
    fun getKpi(): EdrCoverageKpiResponse {
        val entry = cacheRepository.findByCacheKey(CACHE_KEY).orElse(null)
            ?: return EdrCoverageKpiResponse(available = false)

        if (assetRepository.countAssetsWithAnyCrowdStrikeAgentSighting() == 0L) {
            return EdrCoverageKpiResponse(available = false)
        }

        val data = objectMapper.readValue(entry.cachedJson, EdrCoverageKpiCacheData::class.java)
        return EdrCoverageKpiResponse(
            available = true,
            percentage = data.percentage,
            totalEc2Instances = data.totalEc2Instances,
            eligibleEc2Instances = data.eligibleEc2Instances,
            coveredEc2Instances = data.coveredEc2Instances,
            excludedByNoEdrException = data.excludedByNoEdrException,
            agentSeenWithinDays = data.agentSeenWithinDays,
            lastCalculatedAt = entry.lastRefreshedAt
        )
    }

    private fun computePercentage(covered: Long, eligible: Long): Double {
        if (eligible <= 0) return 0.0
        return BigDecimal(covered)
            .multiply(BigDecimal(100))
            .divide(BigDecimal(eligible), 1, RoundingMode.HALF_UP)
            .toDouble()
    }

    @Transactional
    open fun upsertCache(json: String, durationMs: Long) {
        // Atomic native upsert on the cache_key unique index — see
        // VulnerabilityStatisticsCacheRepository.upsertByCacheKey for the race this closes.
        cacheRepository.upsertByCacheKey(CACHE_KEY, json, LocalDateTime.now(), durationMs)
    }
}
