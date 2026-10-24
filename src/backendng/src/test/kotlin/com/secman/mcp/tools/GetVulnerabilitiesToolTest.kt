package com.secman.mcp.tools

import com.secman.domain.Asset
import com.secman.domain.Vulnerability
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.service.VulnerabilityExceptionService
import io.micronaut.data.model.Page
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * The tool must page, scope and apply the exception/installer visibility flags in SQL.
 *
 * History: its filter branches once read every matching row with Pageable.UNPAGED (no LIMIT) and
 * sliced in Kotlin, and later still dropped excepted/installer rows from an already fetched page,
 * so pages came back short and `total` counted only the filtered page. Everything now goes through
 * one paged repository call; these tests pin which filter it receives.
 *
 * This is the tool the extensions/ clients call, so it is the highest-exposure MCP read.
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

    private fun stubQuery(total: Long = 1L) {
        every {
            vulnerabilityRepository.findForMcpTool(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } answers { Page.of(listOf(vuln), arg(10), total) }
        every { assetRepository.findByIdIn(any()) } returns listOf(accessibleAsset)
    }

    @Test
    fun `cve search is scoped and paged in SQL for a restricted caller`() = runBlocking {
        stubQuery()

        val result = tool.execute(mapOf("cveId" to "CVE-2026", "pageSize" to 50), ctx(setOf(1L)))

        assertThat(result.isError).isFalse()
        verify {
            vulnerabilityRepository.findForMcpTool(
                true, setOf(1L), "CVE-2026", null, null, null, false, any(), false, false,
                match { it.size == 50 }
            )
        }
    }

    @Test
    fun `admin caller is unscoped`() = runBlocking {
        stubQuery()

        tool.execute(mapOf("cveId" to "CVE-2026", "pageSize" to 50), ctx(null))

        verify {
            vulnerabilityRepository.findForMcpTool(false, any(), "CVE-2026", null, null, null, false, any(), false, false, any())
        }
    }

    @Test
    fun `cve takes priority over every other filter`() = runBlocking {
        stubQuery()

        tool.execute(
            mapOf(
                "cveId" to "CVE-2026", "assetId" to 1, "severity" to listOf("HIGH"),
                "startDate" to "2026-01-01T00:00:00", "endDate" to "2026-02-01T00:00:00", "pageSize" to 50
            ),
            ctx(setOf(1L))
        )

        verify {
            vulnerabilityRepository.findForMcpTool(true, setOf(1L), "CVE-2026", null, null, null, false, any(), false, false, any())
        }
    }

    @Test
    fun `date range is applied in SQL`() = runBlocking {
        stubQuery()

        tool.execute(
            mapOf("startDate" to "2026-01-01T00:00:00", "endDate" to "2026-02-01T00:00:00", "pageSize" to 50),
            ctx(setOf(1L))
        )

        verify {
            vulnerabilityRepository.findForMcpTool(
                true, setOf(1L), null, null,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 0, 0),
                false, any(), false, false, any()
            )
        }
    }

    @Test
    fun `severities are applied in SQL`() = runBlocking {
        stubQuery()

        tool.execute(mapOf("severity" to listOf("CRITICAL", "HIGH"), "pageSize" to 50), ctx(setOf(1L)))

        verify {
            vulnerabilityRepository.findForMcpTool(
                true, setOf(1L), null, null, null, null, true, listOf("CRITICAL", "HIGH"), false, false, any()
            )
        }
    }

    @Test
    fun `asset filter is applied within the caller scope`() = runBlocking {
        stubQuery()

        tool.execute(mapOf("assetId" to 1, "pageSize" to 50), ctx(setOf(1L)))

        verify {
            vulnerabilityRepository.findForMcpTool(true, setOf(1L), null, 1L, null, null, false, any(), false, false, any())
        }
    }

    @Test
    fun `total is the SQL count, not the size of a filtered page`(): Unit = runBlocking {
        stubQuery(total = 1234L)

        val result = tool.execute(mapOf("pageSize" to 50), ctx(setOf(1L)))

        @Suppress("UNCHECKED_CAST")
        val content = (result as McpToolResult.Success).content as Map<String, Any?>
        assertThat(content["total"]).isEqualTo(1234L)
    }

    @Test
    fun `including excepted and installer rows is passed through`() = runBlocking {
        stubQuery()

        tool.execute(
            mapOf("includeExcepted" to true, "includeInstallerFindings" to true, "pageSize" to 50),
            ctx(setOf(1L))
        )

        verify {
            vulnerabilityRepository.findForMcpTool(true, setOf(1L), null, null, null, null, false, any(), true, true, any())
        }
    }

    @Test
    fun `empty accessible set short-circuits without querying`() = runBlocking {
        stubQuery()

        val result = tool.execute(mapOf("pageSize" to 50), ctx(emptySet()))

        assertThat(result.isError).isFalse()
        verify(exactly = 0) {
            vulnerabilityRepository.findForMcpTool(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }
}
