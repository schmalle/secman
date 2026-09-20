package com.secman.mcp.tools

import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RiskAssessmentMcpService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CreateRiskAssessmentToolTest {
    private val service = mockk<RiskAssessmentMcpService>()
    private val tool = CreateRiskAssessmentTool(service)

    @Test
    fun `passes all selected use cases to service`() = runBlocking<Unit> {
        val context = context()
        every { service.create(context, any(), null, listOf(10, 11), any(), any(), any(), any()) } returns emptyMap()

        val result = tool.execute(arguments(useCaseIds = listOf(10, 11)), context)

        assertThat(result).isInstanceOf(McpToolResult.Success::class.java)
        verify { service.create(context, any(), null, listOf(10, 11), any(), any(), any(), any()) }
    }

    @Test
    fun `keeps deprecated singular use case compatible`() = runBlocking<Unit> {
        val context = context()
        every { service.create(context, any(), null, listOf(10), any(), any(), any(), any()) } returns emptyMap()

        val result = tool.execute(arguments(useCaseId = 10), context)

        assertThat(result).isInstanceOf(McpToolResult.Success::class.java)
    }

    @Test
    fun `rejects ambiguous use case selectors`() = runBlocking<Unit> {
        val result = tool.execute(arguments(useCaseIds = listOf(10), useCaseId = 10), context())

        assertThat((result as McpToolResult.Error).code).isEqualTo("VALIDATION_ERROR")
        verify(exactly = 0) { service.create(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun arguments(useCaseIds: List<Int>? = null, useCaseId: Int? = null): Map<String, Any> =
        buildMap {
            put("awsAccountId", "123456789012")
            put("assessorEmail", "assessor@example.com")
            put("respondentEmail", "owner@example.com")
            put("endDate", "2026-10-01")
            useCaseIds?.let { put("useCaseIds", it) }
            useCaseId?.let { put("useCaseId", it) }
        }

    private fun context(): McpExecutionContext = mockk {
        every { hasDelegation() } returns true
        every { delegatedUserRoles } returns setOf("SECCHAMPION")
    }
}
