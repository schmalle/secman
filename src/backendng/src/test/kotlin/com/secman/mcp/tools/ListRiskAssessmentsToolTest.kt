package com.secman.mcp.tools

import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RiskAssessmentMcpService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ListRiskAssessmentsToolTest {
    private val service = mockk<RiskAssessmentMcpService>()
    private val tool = ListRiskAssessmentsTool(service)

    @Test
    fun `schema exposes openOnly and the closed assessmentType enum`() {
        @Suppress("UNCHECKED_CAST")
        val properties = tool.inputSchema["properties"] as Map<String, Map<String, Any>>

        assertThat(properties.getValue("openOnly")["type"]).isEqualTo("boolean")
        assertThat(properties.getValue("assessmentType")["type"]).isEqualTo("string")
        assertThat(properties.getValue("assessmentType")["enum"])
            .isEqualTo(listOf("DEMAND", "ASSET", "AWS_ACCOUNT"))
    }

    @Test
    fun `forwards openOnly and assessmentType to the service`() = runBlocking<Unit> {
        val context = context()
        every { service.list(context, null, null, true, "AWS_ACCOUNT", 0, 20) } returns emptyMap()

        val result = tool.execute(mapOf("openOnly" to true, "assessmentType" to "AWS_ACCOUNT"), context)

        assertThat(result).isInstanceOf(McpToolResult.Success::class.java)
        verify { service.list(context, null, null, true, "AWS_ACCOUNT", 0, 20) }
    }

    @Test
    fun `conflicting openOnly and status is a validation error`() = runBlocking<Unit> {
        val context = context()
        every { service.list(context, "COMPLETED", null, true, null, 0, 20) } throws
            IllegalArgumentException("openOnly cannot be combined with status COMPLETED")

        val result = tool.execute(mapOf("openOnly" to true, "status" to "COMPLETED"), context)

        assertThat((result as McpToolResult.Error).code).isEqualTo("VALIDATION_ERROR")
    }

    @Test
    fun `invalid assessmentType is a validation error`() = runBlocking<Unit> {
        val context = context()
        every { service.list(context, null, null, false, "SERVER", 0, 20) } throws
            IllegalArgumentException("assessmentType must be DEMAND, ASSET or AWS_ACCOUNT")

        val result = tool.execute(mapOf("assessmentType" to "SERVER"), context)

        assertThat((result as McpToolResult.Error).code).isEqualTo("VALIDATION_ERROR")
    }

    @Test
    fun `pageSize above 100 is rejected without calling the service`() = runBlocking<Unit> {
        val result = tool.execute(mapOf("pageSize" to 101), context())

        assertThat((result as McpToolResult.Error).code).isEqualTo("VALIDATION_ERROR")
        verify(exactly = 0) { service.list(any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun context(): McpExecutionContext = mockk {
        every { hasDelegation() } returns true
        every { delegatedUserRoles } returns setOf("SECCHAMPION")
    }
}
