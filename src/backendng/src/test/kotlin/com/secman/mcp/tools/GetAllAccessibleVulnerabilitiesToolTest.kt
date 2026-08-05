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
 * The tool used to call vulnerabilityRepository.findAll() and filter in Kotlin, which
 * materialised the whole `vulnerability` table (~1.1M entities) into a 1 GB heap and killed
 * the backend before the access-control filter or the row cap ever ran.
 */
class GetAllAccessibleVulnerabilitiesToolTest {

    private val vulnerabilityRepository = mockk<VulnerabilityRepository>()
    private val exceptionService = mockk<VulnerabilityExceptionService>()
    private val assetRepository = mockk<AssetRepository>()

    private val tool = GetAllAccessibleVulnerabilitiesTool(
        vulnerabilityRepository, exceptionService, assetRepository
    )

    private val accessibleAsset = Asset(id = 1L, name = "web-01", type = "SERVER", owner = "alice")
    private val foreignAsset = Asset(id = 2L, name = "db-01", type = "SERVER", owner = "bob")

    private fun vuln(id: Long, asset: Asset, severity: String) = Vulnerability(
        id = id,
        asset = asset,
        vulnerabilityId = "CVE-2026-$id",
        cvssSeverity = severity,
        scanTimestamp = LocalDateTime.now()
    )

    private val accessibleCritical = vuln(1L, accessibleAsset, "CRITICAL")
    private val accessibleLow = vuln(2L, accessibleAsset, "LOW")
    private val foreignCritical = vuln(3L, foreignAsset, "CRITICAL")

    private fun ctx(accessibleIds: Set<Long>?) = mockk<McpExecutionContext>().also {
        every { it.getFilterableAssetIds() } returns accessibleIds
    }

    @Suppress("UNCHECKED_CAST")
    private fun payloadOf(result: McpToolResult) =
        (result as McpToolResult.Success).content as Map<String, Any?>

    /**
     * The trap: stub the unbounded findAll() so buggy code still "works" and the test fails
     * on the verify below rather than on an unstubbed-call error.
     */
    private fun stubWholeTable() {
        every { vulnerabilityRepository.findAll() } returns
            listOf(accessibleCritical, accessibleLow, foreignCritical)
    }

    private fun stubSupportingLookups() {
        every { assetRepository.findByIdIn(any()) } returns listOf(accessibleAsset)
        every { exceptionService.getActiveExceptions() } returns emptyList()
    }

    @Test
    fun `never reads the whole vulnerability table for a restricted caller`() = runBlocking {
        stubWholeTable()
        stubSupportingLookups()
        every { vulnerabilityRepository.findByAssetIdIn(setOf(1L), any()) } returns
            Page.of(listOf(accessibleCritical, accessibleLow), Pageable.from(0, 5000), 2L)

        val result = tool.execute(mapOf("limit" to 5000), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        verify(exactly = 0) { vulnerabilityRepository.findAll() }
        verify { vulnerabilityRepository.findByAssetIdIn(setOf(1L), match { it.size == 5000 }) }
    }

    @Test
    fun `never reads the whole vulnerability table for an admin caller`() = runBlocking {
        stubWholeTable()
        stubSupportingLookups()
        every { vulnerabilityRepository.findAll(any<Pageable>()) } returns
            Page.of(listOf(accessibleCritical), Pageable.from(0, 5000), 1L)

        val result = tool.execute(mapOf("limit" to 5000), ctx(null))

        assertThat(result.isError).isFalse()
        verify(exactly = 0) { vulnerabilityRepository.findAll() }
        verify { vulnerabilityRepository.findAll(match<Pageable> { it.size == 5000 }) }
    }

    @Test
    fun `severity filter is pushed into SQL so the cap cannot hide matches`() = runBlocking {
        stubWholeTable()
        stubSupportingLookups()
        every {
            vulnerabilityRepository.findByAssetIdInAndCvssSeverityIn(setOf(1L), listOf("CRITICAL"), any())
        } returns Page.of(listOf(accessibleCritical), Pageable.from(0, 5000), 1L)

        val result = tool.execute(
            mapOf("limit" to 5000, "severity" to listOf("critical")),
            ctx(setOf(1L))
        )

        assertThat(result.isError).isFalse()
        verify(exactly = 0) { vulnerabilityRepository.findAll() }
        verify {
            vulnerabilityRepository.findByAssetIdInAndCvssSeverityIn(
                setOf(1L), listOf("CRITICAL"), match { it.size == 5000 }
            )
        }
    }

    @Test
    fun `the requested limit bounds the SQL page size`() = runBlocking {
        stubWholeTable()
        stubSupportingLookups()
        every { vulnerabilityRepository.findByAssetIdIn(setOf(1L), any()) } returns
            Page.of(listOf(accessibleCritical), Pageable.from(0, 10), 1L)

        tool.execute(mapOf("limit" to 10), ctx(setOf(1L)))

        verify { vulnerabilityRepository.findByAssetIdIn(setOf(1L), match { it.size == 10 }) }
    }

    @Test
    fun `response keeps the fields the client script reads`() = runBlocking<Unit> {
        stubWholeTable()
        stubSupportingLookups()
        every { vulnerabilityRepository.findByAssetIdIn(setOf(1L), any()) } returns
            Page.of(listOf(accessibleCritical, accessibleLow), Pageable.from(0, 5000), 2L)

        val result = tool.execute(mapOf("limit" to 5000), ctx(setOf(1L)))

        val payload = payloadOf(result)
        assertThat(payload).containsKeys("vulnerabilities", "total", "returned", "truncated", "exceptedFiltered")
        assertThat(payload["total"]).isEqualTo(2)
        assertThat(payload["returned"]).isEqualTo(2)
        assertThat(payload["truncated"]).isEqualTo(false)
    }

    @Test
    fun `truncated is reported when more rows match than the cap returns`() = runBlocking<Unit> {
        stubWholeTable()
        stubSupportingLookups()
        every { vulnerabilityRepository.findByAssetIdIn(setOf(1L), any()) } returns
            Page.of(listOf(accessibleCritical), Pageable.from(0, 1), 500L)

        val result = tool.execute(mapOf("limit" to 1), ctx(setOf(1L)))

        val payload = payloadOf(result)
        assertThat(payload["truncated"]).isEqualTo(true)
        assertThat(payload["total"]).isEqualTo(500)
        assertThat(payload["returned"]).isEqualTo(1)
    }

    @Test
    fun `empty accessible set short-circuits without querying`() = runBlocking {
        stubWholeTable()

        val result = tool.execute(mapOf("limit" to 5000), ctx(emptySet()))

        assertThat(result.isError).isFalse()
        verify(exactly = 0) { vulnerabilityRepository.findAll() }
    }
}
