package com.secman.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.VulnerabilityStatisticsCacheRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

/**
 * Unit coverage for the KPI's ARITHMETIC and failure handling.
 *
 * The three predicates that decide which servers are "dirty" (non-excepted, has an EC2
 * instance id, older than the 30-day SLA anchor) now live in
 * VulnerabilityRepository.countDirtyAwsServers and cannot be exercised with a mock. They are
 * covered against a real database by AwsCleanServerKpiCountIntegrationTest — which is the right
 * place, since transcribing those predicates from Kotlin into SQL is exactly the step where a
 * silent regression could hide.
 */
class AwsCleanServerKpiServiceTest {

    private lateinit var assetRepository: AssetRepository
    private lateinit var vulnerabilityRepository: VulnerabilityRepository
    private lateinit var cacheRepository: VulnerabilityStatisticsCacheRepository
    private lateinit var service: AwsCleanServerKpiService

    @BeforeEach
    fun setUp() {
        assetRepository = mockk()
        vulnerabilityRepository = mockk()
        cacheRepository = mockk()
        service = AwsCleanServerKpiService(
            assetRepository,
            vulnerabilityRepository,
            cacheRepository,
            jacksonObjectMapper()
        )
    }


    @Test
    fun `old scope and yesterday caches are unavailable and scheduled for refresh`() {
        for (day in listOf<String?>(null, FalconSeenTodayWindow.today().startUtc.minusDays(1).toString())) {
            val json = jacksonObjectMapper().writeValueAsString(com.secman.dto.AwsCleanServerKpiCacheData(
                totalAwsServers = 10, cleanAwsServers = 9, percentage = 90.0, seenTodayStartUtc = day))
            every { cacheRepository.findByCacheKey(AwsCleanServerKpiService.CACHE_KEY) } returns Optional.of(
                com.secman.domain.VulnerabilityStatisticsCache(cacheKey = AwsCleanServerKpiService.CACHE_KEY, cachedJson = json))
            assertThat(service.getKpi().available).isFalse()
        }
        every { assetRepository.countAwsAssetsSeenToday(any(), any()) } returns 0L
        every { cacheRepository.upsertByCacheKey(any(), any(), any(), any()) } returns 1
        service.refreshForNewDay()
        verify(exactly = 1) { assetRepository.countAwsAssetsSeenToday(any(), any()) }
    }

    @Test
    fun `today cache is served without recalculation`() {
        val json = jacksonObjectMapper().writeValueAsString(com.secman.dto.AwsCleanServerKpiCacheData(
            totalAwsServers = 10, cleanAwsServers = 9, percentage = 90.0,
            seenTodayStartUtc = FalconSeenTodayWindow.today().startUtc.toString()))
        every { cacheRepository.findByCacheKey(AwsCleanServerKpiService.CACHE_KEY) } returns Optional.of(
            com.secman.domain.VulnerabilityStatisticsCache(cacheKey = AwsCleanServerKpiService.CACHE_KEY, cachedJson = json))
        assertThat(service.getKpi().percentage).isEqualTo(90.0)
        service.refreshForNewDay()
        verify(exactly = 0) { assetRepository.countAwsAssetsSeenToday(any(), any()) }
    }

    @Test
    fun `getKpi returns not available when nothing has been cached yet`() {
        every { cacheRepository.findByCacheKey(AwsCleanServerKpiService.CACHE_KEY) } returns Optional.empty()

        val result = service.getKpi()

        assertThat(result.available).isFalse()
        assertThat(result.percentage).isNull()
        assertThat(result.totalAwsServers).isNull()
    }

    @Test
    fun `recalculate computes 100 percent when no AWS server has an old vulnerability`() {
        every { assetRepository.countAwsAssetsSeenToday(any(), any()) } returns 3L
        every { vulnerabilityRepository.countDirtyAwsServers(any(), any(), any()) } returns 0L

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(AwsCleanServerKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        assertThat(jsonSlot.captured).contains("\"totalAwsServers\":3")
        assertThat(jsonSlot.captured).contains("\"cleanAwsServers\":3")
        assertThat(jsonSlot.captured).contains("\"percentage\":100.0")
    }

    @Test
    fun `recalculate derives clean count and rounds the percentage to one decimal`() {
        every { assetRepository.countAwsAssetsSeenToday(any(), any()) } returns 3L
        every { vulnerabilityRepository.countDirtyAwsServers(any(), any(), any()) } returns 1L

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(AwsCleanServerKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        // 3 total AWS servers, 1 dirty -> 2 clean -> 66.7% (HALF_UP at 1dp)
        assertThat(jsonSlot.captured).contains("\"totalAwsServers\":3")
        assertThat(jsonSlot.captured).contains("\"cleanAwsServers\":2")
        assertThat(jsonSlot.captured).contains("\"percentage\":66.7")
    }

    /**
     * The KPI must never pull the overdue-vulnerability entity graph into heap. It used to receive
     * that list as a parameter, which forced MaterializedViewRefreshService to keep ~166k entities
     * reachable for the whole refresh cycle.
     *
     * The stronger half of this guarantee is now structural rather than asserted:
     * `findOverdueVulnerabilitiesWithAssets` has been deleted outright, so no code can call it.
     * What remains worth pinning is that the KPI derives its answer from a scalar count.
     */
    @Test
    fun `recalculate derives the dirty count from a scalar aggregate`() {
        every { assetRepository.countAwsAssetsSeenToday(any(), any()) } returns 1L
        every { vulnerabilityRepository.countDirtyAwsServers(any(), any(), any()) } returns 1L
        every { cacheRepository.upsertByCacheKey(any(), any(), any(), any()) } returns 1

        service.recalculate()

        verify(exactly = 1) { vulnerabilityRepository.countDirtyAwsServers(any(), any(), any()) }
    }

    @Test
    fun `recalculate never reports a negative clean count if the counts disagree`() {
        // Defensive: the two counts are separate queries, so a concurrent import could in
        // principle report more dirty servers than the total. coerceAtLeast(0) must hold.
        every { assetRepository.countAwsAssetsSeenToday(any(), any()) } returns 2L
        every { vulnerabilityRepository.countDirtyAwsServers(any(), any(), any()) } returns 5L

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(AwsCleanServerKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        assertThat(jsonSlot.captured).contains("\"cleanAwsServers\":0")
        assertThat(jsonSlot.captured).contains("\"percentage\":0.0")
    }

    @Test
    fun `recalculate skips the dirty-server query entirely when there are no AWS servers`() {
        every { assetRepository.countAwsAssetsSeenToday(any(), any()) } returns 0L

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(AwsCleanServerKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        assertThat(jsonSlot.captured).contains("\"totalAwsServers\":0")
        assertThat(jsonSlot.captured).contains("\"percentage\":0.0")
        verify(exactly = 0) { vulnerabilityRepository.countDirtyAwsServers(any(), any(), any()) }
    }

    @Test
    fun `recalculate never throws even when a dependency fails`() {
        every { assetRepository.countAwsAssetsSeenToday(any(), any()) } throws RuntimeException("boom")

        service.recalculate()
        // No exception propagates; nothing else to assert since nothing was cached
    }
}
