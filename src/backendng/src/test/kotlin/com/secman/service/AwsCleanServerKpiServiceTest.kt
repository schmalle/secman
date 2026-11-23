package com.secman.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.secman.domain.VulnerabilityStatisticsCache
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.VulnerabilityStatisticsCacheRepository
import com.secman.repository.projection.AwsServerDetectionCounts
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class AwsCleanServerKpiServiceTest {
    private val findings = mockk<VulnerabilityRepository>()
    private val cache = mockk<VulnerabilityStatisticsCacheRepository>()
    private val service = AwsCleanServerKpiService(findings, cache, jacksonObjectMapper())

    @Test
    fun `percentages use the full denominator and keep unavailable states distinct`() {
        val cases = listOf(
            AwsServerDetectionCounts(6, 3, 1, 2) to 50.0,
            AwsServerDetectionCounts(6, 2, 2, 2) to 33.3,
            AwsServerDetectionCounts(6, 0, 1, 5) to 0.0,
            AwsServerDetectionCounts(6, 0, 0, 6) to null,
            AwsServerDetectionCounts(0, 0, 0, 0) to null
        )
        val json = slot<String>()
        every { cache.upsertStoredSnapshot(AwsCleanServerKpiService.CACHE_KEY, capture(json), any(), any()) } returns 1
        every { cache.findByCacheKey(AwsCleanServerKpiService.CACHE_KEY) } answers {
            Optional.of(VulnerabilityStatisticsCache(cacheKey = AwsCleanServerKpiService.CACHE_KEY, cachedJson = json.captured))
        }
        for ((counts, percentage) in cases) {
            every { findings.countAwsServerDetectionAge(any(), any()) } returns counts
            service.recalculate(requireSuccess = true)
            val response = service.getKpi()
            assertThat(response.available).isTrue()
            assertThat(response.percentage).isEqualTo(percentage)
            assertThat(response.totalAwsServers).isEqualTo(counts.total)
            assertThat(response.cleanAwsServers).isEqualTo(counts.clean)
            assertThat(response.overdueAwsServers).isEqualTo(counts.overdue)
            assertThat(response.unknownAwsServers).isEqualTo(counts.unknownCount)
        }
    }

    @Test
    fun `a failed refresh leaves the previous cache intact`() {
        every { findings.countAwsServerDetectionAge(any(), any()) } throws IllegalStateException("test")
        service.recalculate()
        verify(exactly = 0) { cache.upsertStoredSnapshot(any(), any(), any(), any()) }
    }

    @Test
    fun `missing versioned cache is unavailable without querying the fleet`() {
        every { cache.findByCacheKey(AwsCleanServerKpiService.CACHE_KEY) } returns Optional.empty()
        assertThat(service.getKpi().available).isFalse()
        verify(exactly = 0) { findings.countAwsServerDetectionAge(any(), any()) }
    }
}
