package com.secman.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.secman.domain.VulnerabilityStatisticsCache
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityStatisticsCacheRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

/**
 * Unit coverage for the EDR-coverage KPI's ARITHMETIC, exemption handling and failure modes.
 *
 * The SQL predicates that decide which instances are "EC2", "recently seen by CrowdStrike" and
 * "exempted by an active NO_EDR exception" live in AssetRepository and cannot be exercised
 * through a mock — transcribing them into SQL is exactly where a silent regression would hide,
 * so they are covered against a real database by EdrCoverageKpiCountIntegrationTest.
 */
@DisplayName("EdrCoverageKpiService")
class EdrCoverageKpiServiceTest {

    private lateinit var assetRepository: AssetRepository
    private lateinit var cacheRepository: VulnerabilityStatisticsCacheRepository
    private lateinit var service: EdrCoverageKpiService

    @BeforeEach
    fun setUp() {
        assetRepository = mockk()
        cacheRepository = mockk()
        service = EdrCoverageKpiService(assetRepository, cacheRepository, jacksonObjectMapper())
    }

    private fun cacheEntry(json: String) = VulnerabilityStatisticsCache(
        cacheKey = EdrCoverageKpiService.CACHE_KEY,
        cachedJson = json,
        lastRefreshedAt = LocalDateTime.now()
    )

    @Test
    fun `getKpi returns not available when nothing has been cached yet`() {
        every { cacheRepository.findByCacheKey(EdrCoverageKpiService.CACHE_KEY) } returns Optional.empty()

        val result = service.getKpi()

        assertThat(result.available).isFalse()
        assertThat(result.percentage).isNull()
        assertThat(result.totalEc2Instances).isNull()
    }

    /**
     * The fresh-deployment case. A cached row exists and the arithmetic legitimately produced
     * 0%, but no import has stamped an agent sighting yet — reporting 0% would read as a
     * fleet-wide EDR outage rather than as missing data.
     */
    @Test
    fun `getKpi returns not available while no asset has ever been seen by CrowdStrike`() {
        every { cacheRepository.findByCacheKey(EdrCoverageKpiService.CACHE_KEY) } returns
            Optional.of(cacheEntry("""{"totalEc2Instances":10,"eligibleEc2Instances":10,"coveredEc2Instances":0,"excludedByNoEdrException":0,"percentage":0.0,"agentSeenWithinDays":7}"""))
        every { assetRepository.countAssetsWithAnyCrowdStrikeAgentSighting() } returns 0L

        assertThat(service.getKpi().available).isFalse()
    }

    @Test
    fun `getKpi reports the cached figures once a sighting exists`() {
        every { cacheRepository.findByCacheKey(EdrCoverageKpiService.CACHE_KEY) } returns
            Optional.of(cacheEntry("""{"totalEc2Instances":10,"eligibleEc2Instances":8,"coveredEc2Instances":6,"excludedByNoEdrException":2,"percentage":75.0,"agentSeenWithinDays":7}"""))
        every { assetRepository.countAssetsWithAnyCrowdStrikeAgentSighting() } returns 6L

        val result = service.getKpi()

        assertThat(result.available).isTrue()
        assertThat(result.percentage).isEqualTo(75.0)
        assertThat(result.totalEc2Instances).isEqualTo(10L)
        assertThat(result.eligibleEc2Instances).isEqualTo(8L)
        assertThat(result.coveredEc2Instances).isEqualTo(6L)
        assertThat(result.excludedByNoEdrException).isEqualTo(2L)
        assertThat(result.agentSeenWithinDays).isEqualTo(7L)
    }

    @Test
    fun `recalculate computes 100 percent when every EC2 instance was seen recently`() {
        every { assetRepository.countAllAwsAssetsWithInstanceId() } returns 4L
        every { assetRepository.countEc2AssetsExcludedByNoEdrException() } returns 0L
        every { assetRepository.countEc2AssetsWithFreshCrowdStrikeAgent(any()) } returns 4L

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(EdrCoverageKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        assertThat(jsonSlot.captured).contains("\"totalEc2Instances\":4")
        assertThat(jsonSlot.captured).contains("\"eligibleEc2Instances\":4")
        assertThat(jsonSlot.captured).contains("\"coveredEc2Instances\":4")
        assertThat(jsonSlot.captured).contains("\"percentage\":100.0")
    }

    /**
     * The point of the whole exception feature: an exempted instance leaves the denominator, so
     * a fleet where every agent-capable box is covered reads 100% rather than being permanently
     * capped by hardware that cannot run a sensor.
     */
    @Test
    fun `NO_EDR exemptions leave the denominator and are reported separately`() {
        every { assetRepository.countAllAwsAssetsWithInstanceId() } returns 10L
        every { assetRepository.countEc2AssetsExcludedByNoEdrException() } returns 2L
        every { assetRepository.countEc2AssetsWithFreshCrowdStrikeAgent(any()) } returns 8L

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(EdrCoverageKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        assertThat(jsonSlot.captured).contains("\"totalEc2Instances\":10")
        assertThat(jsonSlot.captured).contains("\"excludedByNoEdrException\":2")
        assertThat(jsonSlot.captured).contains("\"eligibleEc2Instances\":8")
        assertThat(jsonSlot.captured).contains("\"coveredEc2Instances\":8")
        // 8/8, NOT 8/10 — the exempted boxes are out of scope, not failures.
        assertThat(jsonSlot.captured).contains("\"percentage\":100.0")
    }

    @Test
    fun `recalculate rounds the percentage to one decimal`() {
        every { assetRepository.countAllAwsAssetsWithInstanceId() } returns 3L
        every { assetRepository.countEc2AssetsExcludedByNoEdrException() } returns 0L
        every { assetRepository.countEc2AssetsWithFreshCrowdStrikeAgent(any()) } returns 2L

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(EdrCoverageKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        assertThat(jsonSlot.captured).contains("\"percentage\":66.7")
    }

    @Test
    fun `recalculate reports zero without dividing when there are no EC2 instances`() {
        every { assetRepository.countAllAwsAssetsWithInstanceId() } returns 0L

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(EdrCoverageKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        assertThat(jsonSlot.captured).contains("\"percentage\":0.0")
        // The follow-up counts are skipped entirely rather than run against an empty population.
        verify(exactly = 0) { assetRepository.countEc2AssetsExcludedByNoEdrException() }
        verify(exactly = 0) { assetRepository.countEc2AssetsWithFreshCrowdStrikeAgent(any()) }
    }

    /**
     * recalculate() is called from MaterializedViewRefreshService.refreshDerivedData(), where
     * every step is non-fatal by contract — one failing KPI must not abort the refresh or the
     * KPIs that run after it.
     */
    @Test
    fun `recalculate swallows repository failures and leaves the previous cache in place`() {
        every { assetRepository.countAllAwsAssetsWithInstanceId() } throws RuntimeException("database on fire")

        service.recalculate()

        verify(exactly = 0) { cacheRepository.upsertByCacheKey(any(), any(), any(), any()) }
    }

    /**
     * An exemption count that somehow exceeded the population must not produce a negative
     * denominator (and therefore a negative percentage) on a dashboard.
     */
    @Test
    fun `an exemption count larger than the population floors the denominator at zero`() {
        every { assetRepository.countAllAwsAssetsWithInstanceId() } returns 2L
        every { assetRepository.countEc2AssetsExcludedByNoEdrException() } returns 5L

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(EdrCoverageKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        assertThat(jsonSlot.captured).contains("\"eligibleEc2Instances\":0")
        assertThat(jsonSlot.captured).contains("\"percentage\":0.0")
    }
}
