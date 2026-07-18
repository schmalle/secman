package com.secman.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.secman.domain.Asset
import com.secman.domain.Vulnerability
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

    private fun awsAsset(id: Long, instanceId: String = "i-$id") = Asset(
        id = id,
        name = "server-$id",
        type = "SERVER",
        owner = "owner",
        cloudAccountId = "111111111111",
        cloudInstanceId = instanceId
    )

    private fun overdueVuln(asset: Asset, daysOld: Long = 45) = Vulnerability(
        asset = asset,
        vulnerabilityId = "CVE-2020-0001",
        cvssSeverity = "HIGH",
        scanTimestamp = LocalDateTime.now().minusDays(daysOld)
    )

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
        every { vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(any()) } returns emptyList()

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(AwsCleanServerKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        assertThat(jsonSlot.captured).contains("\"totalAwsServers\":3")
        assertThat(jsonSlot.captured).contains("\"cleanAwsServers\":3")
        assertThat(jsonSlot.captured).contains("\"percentage\":100.0")
    }

    @Test
    fun `recalculate excludes AWS servers whose only overdue vulnerability is excepted`() {
        val dirty = awsAsset(1)
        val excepted = awsAsset(2)
        val nonAwsAsset = Asset(id = 3, name = "on-prem", type = "SERVER", owner = "owner")

        val dirtyVuln = overdueVuln(dirty)
        val exceptedVuln = overdueVuln(excepted).apply { this.excepted = true }
        val nonAwsVuln = overdueVuln(nonAwsAsset)

        every { assetRepository.countAllAwsAssetsWithInstanceId() } returns 3L
        every { vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(any()) } returns
            listOf(dirtyVuln, exceptedVuln, nonAwsVuln)

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(AwsCleanServerKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        // 3 total AWS servers, 1 genuinely dirty (asset 1) -> 2 clean -> 66.7%
        assertThat(jsonSlot.captured).contains("\"totalAwsServers\":3")
        assertThat(jsonSlot.captured).contains("\"cleanAwsServers\":2")
        assertThat(jsonSlot.captured).contains("\"percentage\":66.7")
    }

    @Test
    fun `recalculate reuses a preloaded vulnerability list instead of querying`() {
        val dirty = awsAsset(1)
        val dirtyVuln = overdueVuln(dirty)

        every { assetRepository.countAllAwsAssetsWithInstanceId() } returns 1L

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(AwsCleanServerKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate(preloadedVulnerabilities = listOf(dirtyVuln))

        assertThat(jsonSlot.captured).contains("\"cleanAwsServers\":0")
        verify(exactly = 0) { vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(any()) }
    }

    @Test
    fun `recalculate is a no-op safe when total AWS servers is zero`() {
        every { assetRepository.countAllAwsAssetsWithInstanceId() } returns 0L

        val jsonSlot = slot<String>()
        every { cacheRepository.upsertByCacheKey(AwsCleanServerKpiService.CACHE_KEY, capture(jsonSlot), any(), any()) } returns 1

        service.recalculate()

        assertThat(jsonSlot.captured).contains("\"totalAwsServers\":0")
        assertThat(jsonSlot.captured).contains("\"percentage\":0.0")
        verify(exactly = 0) { vulnerabilityRepository.findOverdueVulnerabilitiesWithAssets(any()) }
    }

    @Test
    fun `recalculate never throws even when a dependency fails`() {
        every { assetRepository.countAllAwsAssetsWithInstanceId() } throws RuntimeException("boom")

        service.recalculate()
        // No exception propagates; nothing else to assert since nothing was cached
    }
}
