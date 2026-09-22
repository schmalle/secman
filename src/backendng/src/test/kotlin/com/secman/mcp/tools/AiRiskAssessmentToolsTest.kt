package com.secman.mcp.tools

import com.secman.domain.McpPermission
import com.secman.domain.RiskAssessment
import com.secman.dto.StartAiJobResponse
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AiSuggestionJobService
import com.secman.service.AssessmentOwnershipGuard
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Locks MCP AI orchestration to the same ownership and job services used by the web UI. */
class AiRiskAssessmentToolsTest {
    private val jobs = mockk<AiSuggestionJobService>()
    private val guard = mockk<AssessmentOwnershipGuard>()
    private val assessment = mockk<RiskAssessment>()

    @Test
    fun `start tool launches whole assessment job through ownership guard`() = runBlocking<Unit> {
        every { guard.check(42, any()) } returns assessment
        every { jobs.startJob(assessment, any(), any(), 1L) } returns StartAiJobResponse(7, 3, BigDecimal.ONE)

        val result = StartAiRiskAssessmentTool(jobs, guard).execute(mapOf("assessmentId" to 42), context())

        assertThat(result).isInstanceOf(McpToolResult.Success::class.java)
        verify { jobs.startJob(assessment, match { it.scope == "WHOLE_ASSESSMENT" && !it.force }, any(), 1L) }
    }

    @Test
    fun `status tool rejects job from a different assessment`() = runBlocking<Unit> {
        every { guard.check(42, any()) } returns assessment
        every { jobs.getJobStatus(7, 42) } returns null

        val result = GetAiRiskAssessmentJobTool(jobs, guard)
            .execute(mapOf("assessmentId" to 42, "jobId" to 7), context())

        assertThat((result as McpToolResult.Error).code).isEqualTo("NOT_FOUND")
    }

    private fun context() = McpExecutionContext.forDelegatedUser(
        apiKeyId = 1,
        apiKeyName = "test",
        delegatedUserId = 2,
        delegatedUserEmail = "champion@example.test",
        delegatedUsername = "champion",
        delegatedUserRoles = setOf("SECCHAMPION"),
        effectivePermissions = setOf(McpPermission.ASSESSMENTS_READ, McpPermission.ASSESSMENTS_WRITE),
        isAdmin = false,
        accessibleAssetIds = emptySet(),
        accessibleWorkgroupIds = emptySet()
    )
}
