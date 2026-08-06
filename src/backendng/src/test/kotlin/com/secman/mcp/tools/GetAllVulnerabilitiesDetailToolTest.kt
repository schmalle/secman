package com.secman.mcp.tools

import com.secman.domain.Asset
import com.secman.domain.Vulnerability
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.service.VulnerabilityExceptionService
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Regression tests for the OOM described on VulnerabilityRepository.findByAssetIdIn.
 *
 * This tool had two unbounded reads: findAll() for restricted callers with no filter, and
 * findByCvssSeverity(..., Pageable.UNPAGED) for the severity filter — the latter unbounded for
 * admins too. Both materialised most of the `vulnerability` table before paging in Kotlin.
 */
class GetAllVulnerabilitiesDetailToolTest {

    private val vulnerabilityRepository = mockk<VulnerabilityRepository>()
    private val exceptionService = mockk<VulnerabilityExceptionService>()
    private val assetRepository = mockk<AssetRepository>()

    private val tool = GetAllVulnerabilitiesDetailTool(
        vulnerabilityRepository, exceptionService, assetRepository
    )

    private val accessibleAsset = Asset(
        id = 1L, name = "web-01", type = "SERVER", ip = "10.0.0.1", owner = "alice"
    )

    /**
     * Stands in for the lazy Hibernate proxy hanging off Vulnerability.asset: the id is readable,
     * every other field is not what the response should report.
     */
    private val proxyAsset = Asset(id = 1L, name = "", type = "", owner = "")

    private val accessibleHigh = Vulnerability(
        id = 1L,
        asset = proxyAsset,
        vulnerabilityId = "CVE-2026-1",
        cvssSeverity = "High",
        scanTimestamp = LocalDateTime.now()
    )

    private fun ctx(accessibleIds: Set<Long>?) = mockk<McpExecutionContext>().also {
        every { it.getFilterableAssetIds() } returns accessibleIds
        every { it.canAccessAsset(any()) } returns true
    }

    /** Trap: stub the unbounded reads so buggy code still "works" and the verify below is what fails. */
    private fun stubUnboundedReads() {
        every { vulnerabilityRepository.findAll() } returns listOf(accessibleHigh)
        every { vulnerabilityRepository.findByCvssSeverity("HIGH", Pageable.UNPAGED) } returns
            Page.of(listOf(accessibleHigh), Pageable.UNPAGED, 1L)
        every { exceptionService.getActiveExceptions() } returns emptyList()
        every { assetRepository.findByIdIn(any()) } returns listOf(accessibleAsset)
    }

    @Test
    fun `unfiltered query for a restricted caller is paged in SQL`() = runBlocking {
        stubUnboundedReads()
        every { vulnerabilityRepository.findByAssetIdIn(setOf(1L), any()) } returns
            Page.of(listOf(accessibleHigh), Pageable.from(0, 100), 1L)

        val result = tool.execute(mapOf("pageSize" to 100), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        verify(exactly = 0) { vulnerabilityRepository.findAll() }
        verify { vulnerabilityRepository.findByAssetIdIn(setOf(1L), match { it.size == 100 }) }
    }

    @Test
    fun `severity query for a restricted caller is paged in SQL`() = runBlocking {
        stubUnboundedReads()
        every {
            vulnerabilityRepository.findByAssetIdInAndCvssSeverityIn(setOf(1L), listOf("HIGH"), any())
        } returns Page.of(listOf(accessibleHigh), Pageable.from(0, 100), 1L)

        val result = tool.execute(mapOf("severity" to "HIGH", "pageSize" to 100), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        verify(exactly = 0) { vulnerabilityRepository.findByCvssSeverity(any(), Pageable.UNPAGED) }
        verify {
            vulnerabilityRepository.findByAssetIdInAndCvssSeverityIn(
                setOf(1L), listOf("HIGH"), match { it.size == 100 }
            )
        }
    }

    @Test
    fun `severity query for an admin caller is paged in SQL`() = runBlocking {
        stubUnboundedReads()
        every { vulnerabilityRepository.findByCvssSeverity("HIGH", match<Pageable> { it != Pageable.UNPAGED }) } returns
            Page.of(listOf(accessibleHigh), Pageable.from(0, 100), 1L)

        val result = tool.execute(mapOf("severity" to "HIGH", "pageSize" to 100), ctx(null))

        assertThat(result.isError).isFalse()
        verify(exactly = 0) { vulnerabilityRepository.findByCvssSeverity(any(), Pageable.UNPAGED) }
        verify { vulnerabilityRepository.findByCvssSeverity("HIGH", match<Pageable> { it.size == 100 }) }
    }

    @Test
    fun `unfiltered query for an admin caller stays paged`() = runBlocking {
        stubUnboundedReads()
        every { vulnerabilityRepository.findAll(any<Pageable>()) } returns
            Page.of(listOf(accessibleHigh), Pageable.from(0, 100), 1L)

        val result = tool.execute(mapOf("pageSize" to 100), ctx(null))

        assertThat(result.isError).isFalse()
        verify(exactly = 0) { vulnerabilityRepository.findAll() }
        verify { vulnerabilityRepository.findAll(match<Pageable> { it.size == 100 }) }
    }

    @Test
    fun `asset-scoped query still uses the paged repository call`() = runBlocking {
        stubUnboundedReads()
        every { vulnerabilityRepository.findByAssetId(1L, any()) } returns
            Page.of(listOf(accessibleHigh), Pageable.from(0, 100), 1L)

        val result = tool.execute(mapOf("assetId" to 1, "pageSize" to 100), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        verify(exactly = 0) { vulnerabilityRepository.findAll() }
        verify { vulnerabilityRepository.findByAssetId(1L, match { it.size == 100 }) }
    }

    @Test
    fun `pagination metadata is preserved`() = runBlocking<Unit> {
        stubUnboundedReads()
        every { vulnerabilityRepository.findByAssetIdIn(setOf(1L), any()) } returns
            Page.of(listOf(accessibleHigh), Pageable.from(0, 100), 250L)

        val result = tool.execute(mapOf("pageSize" to 100), ctx(setOf(1L)))

        @Suppress("UNCHECKED_CAST")
        val payload = (result as McpToolResult.Success).content as Map<String, Any?>
        assertThat(payload["page"]).isEqualTo(0)
        assertThat(payload["pageSize"]).isEqualTo(100)
        assertThat(payload["totalPages"]).isEqualTo(3)
        assertThat(payload["hasMore"]).isEqualTo(true)
    }

    @Test
    fun `asset fields come from a hydrated asset, not the lazy proxy`() = runBlocking<Unit> {
        stubUnboundedReads()
        every { vulnerabilityRepository.findByAssetIdIn(setOf(1L), any()) } returns
            Page.of(listOf(accessibleHigh), Pageable.from(0, 100), 1L)

        val result = tool.execute(mapOf("pageSize" to 100), ctx(setOf(1L)))

        @Suppress("UNCHECKED_CAST")
        val payload = (result as McpToolResult.Success).content as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val asset = (payload["vulnerabilities"] as List<Map<String, Any?>>).single()["asset"] as Map<*, *>
        assertThat(asset["name"]).isEqualTo("web-01")
        assertThat(asset["type"]).isEqualTo("SERVER")
        assertThat(asset["ip"]).isEqualTo("10.0.0.1")
        verify { assetRepository.findByIdIn(setOf(1L)) }
    }

    @Test
    fun `empty accessible set short-circuits without querying`() = runBlocking {
        stubUnboundedReads()

        val result = tool.execute(mapOf("pageSize" to 100), ctx(emptySet()))

        assertThat(result.isError).isFalse()
        verify(exactly = 0) { vulnerabilityRepository.findAll() }
    }
}
