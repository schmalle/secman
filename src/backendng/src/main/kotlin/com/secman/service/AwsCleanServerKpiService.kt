package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.dto.AwsCleanServerKpiCacheData
import com.secman.dto.AwsCleanServerKpiResponse
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.VulnerabilityStatisticsCacheRepository
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Cached fleet-wide detection age. Provider contact only affects the separate EDR metric. */
@Singleton
class AwsCleanServerKpiService(
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val cacheRepository: VulnerabilityStatisticsCacheRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val CACHE_KEY = "aws_clean_server_kpi_current_v4"
        const val VULN_AGE_THRESHOLD_DAYS = 30L
    }

    @Scheduled(fixedDelay = "15m", initialDelay = "30s")
    fun refresh() = recalculate()

    @Scheduled(fixedDelay = "1m", initialDelay = "1m")
    fun refreshExpiredInventory() {
        if (cacheRepository.findByCacheKey(CACHE_KEY).isEmpty) recalculate()
    }

    fun recalculate(requireSuccess: Boolean = false) {
        val start = System.nanoTime()
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        // Existing finding timestamps are persisted as local DATETIME.
        val storageZone = ZoneId.systemDefault()
        try {
            val counts = vulnerabilityRepository.countAwsServerDetectionAge(
                LocalDateTime.ofInstant(now.minus(Duration.ofDays(VULN_AGE_THRESHOLD_DAYS)), storageZone),
                LocalDateTime.ofInstant(now, storageZone)
            )
            val percentage = if (counts.total == 0L || counts.total == counts.unknownCount) null else
                BigDecimal.valueOf(counts.clean).movePointRight(2)
                    .divide(BigDecimal.valueOf(counts.total), 1, RoundingMode.HALF_UP).toDouble()
            val json = objectMapper.writeValueAsString(
                AwsCleanServerKpiCacheData(counts.total, counts.clean, percentage, counts.unknownCount, counts.overdue)
            )
            cacheRepository.upsertStoredSnapshot(
                CACHE_KEY, json, LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                Duration.ofNanos(System.nanoTime() - start).toMillis()
            )
        } catch (e: Exception) {
            log.error("AWS clean-server KPI refresh failed", e)
            if (requireSuccess) throw e
        }
    }

    fun getKpi(): AwsCleanServerKpiResponse {
        val entry = cacheRepository.findByCacheKey(CACHE_KEY).orElse(null)
            ?: return AwsCleanServerKpiResponse(available = false)
        val data = objectMapper.readValue(entry.cachedJson, AwsCleanServerKpiCacheData::class.java)
        return AwsCleanServerKpiResponse(
            available = true,
            percentage = data.percentage,
            totalAwsServers = data.totalAwsServers,
            cleanAwsServers = data.cleanAwsServers,
            lastCalculatedAt = entry.lastRefreshedAt,
            unknownAwsServers = data.unknownAwsServers,
            overdueAwsServers = data.overdueAwsServers
        )
    }
}
