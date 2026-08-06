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
 * Regression tests for the unbounded reads described on VulnerabilityRepository.findByAssetIdIn.
 *
 * The no-filter branch of this tool was fixed earlier; the CVE, date-range and severity branches
 * still read every matching row with Pageable.UNPAGED and sliced the result in Kotlin. UNPAGED
 * emits no LIMIT clause, so those branches scanned the multi-million-row vulnerability table into
 * heap — and unlike the no-filter branch they did so for admins as well as delegated users.
 *
 * This is the tool the extensions/ clients call, so it is the highest-exposure of the three.
 */
class GetVulnerabilitiesToolTest {

    private val vulnerabilityRepository = mockk<VulnerabilityRepository>()
    private val exceptionService = mockk<VulnerabilityExceptionService>()
    private val assetRepository = mockk<AssetRepository>()

    private val tool = GetVulnerabilitiesTool(vulnerabilityRepository, exceptionService, assetRepository)

    private val accessibleAsset = Asset(
        id = 1L, name = "web-01", type = "SERVER", ip = "10.0.0.1", owner = "alice"
    )

    private val vuln = Vulnerability(
        id = 1L,
        asset = Asset(id = 1L, name = "", type = "", owner = ""),
        vulnerabilityId = "CVE-2026-1",
        cvssSeverity = "High",
        scanTimestamp = LocalDateTime.now()
    )

    private fun ctx(accessibleIds: Set<Long>?) = mockk<McpExecutionContext>().also {
        every { it.getFilterableAssetIds() } returns accessibleIds
        every { it.canAccessAsset(any()) } returns true
    }

    private fun onePage(size: Int = 50) = Page.of(listOf(vuln), Pageable.from(0, size), 1L)

    /** Trap: stub every UNPAGED read so buggy code still "works" and the verify is what fails. */
    private fun stubUnpagedReads() {
        every {
            vulnerabilityRepository.findByVulnerabilityIdContainingIgnoreCase(any(), Pageable.UNPAGED)
        } returns Page.of(listOf(vuln), Pageable.UNPAGED, 1L)
        every {
            vulnerabilityRepository.findByScanTimestampBetween(any(), any(), Pageable.UNPAGED)
        } returns Page.of(listOf(vuln), Pageable.UNPAGED, 1L)
        every {
            vulnerabilityRepository.findByCvssSeverity(any(), Pageable.UNPAGED)
        } returns Page.of(listOf(vuln), Pageable.UNPAGED, 1L)
        every {
            vulnerabilityRepository.findByCvssSeverityIn(any(), Pageable.UNPAGED)
        } returns Page.of(listOf(vuln), Pageable.UNPAGED, 1L)
        every { assetRepository.findByIdIn(any()) } returns listOf(accessibleAsset)
        every { exceptionService.getActiveExceptions() } returns emptyList()
    }

    private fun assertNoUnpagedRead() {
        verify(exactly = 0) { vulnerabilityRepository.findByVulnerabilityIdContainingIgnoreCase(any(), Pageable.UNPAGED) }
        verify(exactly = 0) { vulnerabilityRepository.findByScanTimestampBetween(any(), any(), Pageable.UNPAGED) }
        verify(exactly = 0) { vulnerabilityRepository.findByCvssSeverity(any(), Pageable.UNPAGED) }
        verify(exactly = 0) { vulnerabilityRepository.findByCvssSeverityIn(any(), Pageable.UNPAGED) }
    }

    // ------------------------------------------------------------------ CVE filter

    @Test
    fun `cve search is paged in SQL for a restricted caller`() = runBlocking {
        stubUnpagedReads()
        every {
            vulnerabilityRepository.findByAssetIdInAndVulnerabilityIdContainingIgnoreCase(setOf(1L), "CVE-2026", any())
        } returns onePage()

        val result = tool.execute(mapOf("cveId" to "CVE-2026", "pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        assertNoUnpagedRead()
        verify {
            vulnerabilityRepository.findByAssetIdInAndVulnerabilityIdContainingIgnoreCase(
                setOf(1L), "CVE-2026", match { it.size == 50 }
            )
        }
    }

    @Test
    fun `cve search is paged in SQL for an admin caller`() = runBlocking {
        stubUnpagedReads()
        every {
            vulnerabilityRepository.findByVulnerabilityIdContainingIgnoreCase("CVE-2026", match<Pageable> { it != Pageable.UNPAGED })
        } returns onePage()

        val result = tool.execute(mapOf("cveId" to "CVE-2026", "pageSize" to 50), ctx(null))

        assertThat(result.isError).isFalse()
        assertNoUnpagedRead()
    }

    // ------------------------------------------------------------------ date range

    @Test
    fun `date range is paged in SQL for a restricted caller`() = runBlocking {
        stubUnpagedReads()
        every {
            vulnerabilityRepository.findByAssetIdInAndScanTimestampBetween(setOf(1L), any(), any(), any())
        } returns onePage()

        val result = tool.execute(
            mapOf(
                "startDate" to "2026-01-01T00:00:00",
                "endDate" to "2026-02-01T00:00:00",
                "pageSize" to 50
            ),
            ctx(setOf(1L))
        )

        assertThat(result.isError).isFalse()
        assertNoUnpagedRead()
        verify {
            vulnerabilityRepository.findByAssetIdInAndScanTimestampBetween(
                setOf(1L), any(), any(), match { it.size == 50 }
            )
        }
    }

    @Test
    fun `date range is paged in SQL for an admin caller`() = runBlocking {
        stubUnpagedReads()
        every {
            vulnerabilityRepository.findByScanTimestampBetween(any(), any(), match<Pageable> { it != Pageable.UNPAGED })
        } returns onePage()

        val result = tool.execute(
            mapOf(
                "startDate" to "2026-01-01T00:00:00",
                "endDate" to "2026-02-01T00:00:00",
                "pageSize" to 50
            ),
            ctx(null)
        )

        assertThat(result.isError).isFalse()
        assertNoUnpagedRead()
    }

    // ------------------------------------------------------------------ severity

    @Test
    fun `single severity is paged in SQL for a restricted caller`() = runBlocking {
        stubUnpagedReads()
        every {
            vulnerabilityRepository.findByAssetIdInAndCvssSeverityIn(setOf(1L), listOf("HIGH"), any())
        } returns onePage()

        val result = tool.execute(mapOf("severity" to listOf("HIGH"), "pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        assertNoUnpagedRead()
    }

    @Test
    fun `multiple severities are paged in SQL for a restricted caller`() = runBlocking {
        stubUnpagedReads()
        every {
            vulnerabilityRepository.findByAssetIdInAndCvssSeverityIn(setOf(1L), listOf("CRITICAL", "HIGH"), any())
        } returns onePage()

        val result = tool.execute(
            mapOf("severity" to listOf("CRITICAL", "HIGH"), "pageSize" to 50),
            ctx(setOf(1L))
        )

        assertThat(result.isError).isFalse()
        assertNoUnpagedRead()
    }

    @Test
    fun `severity is paged in SQL for an admin caller`() = runBlocking {
        stubUnpagedReads()
        every {
            vulnerabilityRepository.findByCvssSeverityIn(listOf("CRITICAL", "HIGH"), match<Pageable> { it != Pageable.UNPAGED })
        } returns onePage()

        val result = tool.execute(
            mapOf("severity" to listOf("CRITICAL", "HIGH"), "pageSize" to 50),
            ctx(null)
        )

        assertThat(result.isError).isFalse()
        assertNoUnpagedRead()
    }

    // ------------------------------------------------------------------ untouched paths

    @Test
    fun `asset-scoped query keeps using the paged repository call`() = runBlocking {
        stubUnpagedReads()
        every { vulnerabilityRepository.findByAssetId(1L, any()) } returns onePage()

        val result = tool.execute(mapOf("assetId" to 1, "pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        assertNoUnpagedRead()
        verify { vulnerabilityRepository.findByAssetId(1L, match { it.size == 50 }) }
    }

    @Test
    fun `empty accessible set short-circuits without querying`() = runBlocking {
        stubUnpagedReads()

        val result = tool.execute(mapOf("pageSize" to 50), ctx(emptySet()))

        assertThat(result.isError).isFalse()
        assertNoUnpagedRead()
    }
}
