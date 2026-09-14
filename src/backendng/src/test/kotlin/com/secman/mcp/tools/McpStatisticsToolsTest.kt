package com.secman.mcp.tools

import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.McpStatisticsService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class McpStatisticsToolsTest {
    private val service = mockk<McpStatisticsService>()

    @Test
    fun `global statistics require delegated admin`() = runBlocking<Unit> {
        val tool = GetSecmanStatisticsTool(service)
        val denied = tool.execute(emptyMap(), context(setOf("VULN")))

        assertThat((denied as McpToolResult.Error).code).isEqualTo("AUTHORIZATION_ERROR")
        verify(exactly = 0) { service.global(any()) }

        val admin = context(setOf("ADMIN"))
        every { service.global(admin) } returns mapOf("assets" to 1L)
        assertThat(tool.execute(emptyMap(), admin)).isInstanceOf(McpToolResult.Success::class.java)
    }

    @Test
    fun `security statistics accept vulnerability role and delegate scope to service`() = runBlocking<Unit> {
        val tool = GetMySecurityStatisticsTool(service)
        val context = context(setOf("VULN"))
        every { service.securityPosture(context) } returns mapOf("assetCount" to 2L)

        val result = tool.execute(emptyMap(), context)

        assertThat((result as McpToolResult.Success).content).isEqualTo(mapOf("assetCount" to 2L))
        verify { service.securityPosture(context) }
    }

    @Test
    fun `risk assessment statistics pass optional use case filter`() = runBlocking<Unit> {
        val tool = GetRiskAssessmentStatisticsTool(service)
        val context = context(setOf("RISK"))
        every { service.riskAssessments(context, "Cloud") } returns mapOf("total" to 3L)

        val result = tool.execute(mapOf("useCaseName" to "Cloud"), context)

        assertThat((result as McpToolResult.Success).content).isEqualTo(mapOf("total" to 3L))
        verify { service.riskAssessments(context, "Cloud") }
    }

    private fun context(roles: Set<String>, hasDelegation: Boolean = true): McpExecutionContext = mockk {
        every { hasDelegation() } returns hasDelegation
        every { delegatedUserRoles } returns roles
        every { delegatedUserId } returns 7
    }
}
