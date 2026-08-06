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
    fun `getKpi returns not available when nothing has been cached yet`() {
        every { cacheRepository.findByCacheKey(AwsCleanServerKpiService.CACHE_KEY) } returns Optional.empty()

        val result = service.getKpi()

        assertThat(result.available).isFalse()
        assertThat(result.percentage).isNull()
        assertThat(result.totalAwsServers).isNull()
    }

    @Test
    fun `recalculate computes 100 percent when no AWS server has an old vulnerability`() {
        every { assetRepository.countAllAwsAssetsWithInstanceId() } returns 3L
        every { vulnerabilityRepository.countDirtyAwsServers(any()) } returns 0L

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(AwsCleanServerKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        assertThat(jsonSlot.captured).contains("\"totalAwsServers\":3")
        assertThat(jsonSlot.captured).contains("\"cleanAwsServers\":3")
        assertThat(jsonSlot.captured).contains("\"percentage\":100.0")
    }

    @Test
    fun `recalculate derives clean count and rounds the percentage to one decimal`() {
        every { assetRepository.countAllAwsAssetsWithInstanceId() } returns 3L
        every { vulnerabilityRepository.countDirtyAwsServers(any()) } returns 1L

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
        every { assetRepository.countAllAwsAssetsWithInstanceId() } returns 1L
        every { vulnerabilityRepository.countDirtyAwsServers(any()) } returns 1L
        every { cacheRepository.upsertByCacheKey(any(), any(), any(), any()) } returns 1

        service.recalculate()

        verify(exactly = 1) { vulnerabilityRepository.countDirtyAwsServers(any()) }
    }

    @Test
    fun `recalculate never reports a negative clean count if the counts disagree`() {
        // Defensive: the two counts are separate queries, so a concurrent import could in
        // principle report more dirty servers than the total. coerceAtLeast(0) must hold.
        every { assetRepository.countAllAwsAssetsWithInstanceId() } returns 2L
        every { vulnerabilityRepository.countDirtyAwsServers(any()) } returns 5L

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(AwsCleanServerKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        assertThat(jsonSlot.captured).contains("\"cleanAwsServers\":0")
        assertThat(jsonSlot.captured).contains("\"percentage\":0.0")
    }

    @Test
    fun `recalculate skips the dirty-server query entirely when there are no AWS servers`() {
        every { assetRepository.countAllAwsAssetsWithInstanceId() } returns 0L

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(AwsCleanServerKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        assertThat(jsonSlot.captured).contains("\"totalAwsServers\":0")
        assertThat(jsonSlot.captured).contains("\"percentage\":0.0")
        verify(exactly = 0) { vulnerabilityRepository.countDirtyAwsServers(any()) }
    }

    @Test
    fun `recalculate never throws even when a dependency fails`() {
        every { assetRepository.countAllAwsAssetsWithInstanceId() } throws RuntimeException("boom")

        service.recalculate()
        // No exception propagates; nothing else to assert since nothing was cached
    }
}
