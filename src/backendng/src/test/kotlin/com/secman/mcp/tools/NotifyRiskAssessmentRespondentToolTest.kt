package com.secman.mcp.tools

import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RiskAssessmentMcpService
import com.secman.service.RiskAssessmentReminderNotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

class NotifyRiskAssessmentRespondentToolTest {
    private val assessments = mockk<RiskAssessmentMcpService>()
    private val notifications = mockk<RiskAssessmentReminderNotificationService>()
    private val tool = NotifyRiskAssessmentRespondentTool(assessments, notifications)
    private val reminder = RiskAssessmentMcpService.OutstandingReminder(
        assessmentId = 40,
        recipientEmail = "owner@example.com",
        awsAccountId = "123456789012",
        useCaseNames = listOf("Cloud workload"),
        endDate = LocalDate.of(2026, 10, 1),
        unansweredCount = 2,
        requirementCount = 3
    )

    @Test
    fun `dry run returns outstanding count without sending`() = runBlocking<Unit> {
        val context = context()
        every { assessments.prepareOutstandingReminder(context, 40) } returns reminder
        every { notifications.send(reminder, 7, true, 5) } returns RiskAssessmentReminderNotificationService.SendOutcome.DRY_RUN

        val result = tool.execute(mapOf("assessmentId" to 40, "dryRun" to true), context)

        val content = (result as McpToolResult.Success).content as Map<*, *>
        assertThat(content["unansweredCount"]).isEqualTo(2)
        assertThat(content["reason"]).isEqualTo("DRY_RUN")
    }

    @Test
    fun `delegation is required before notification services are called`() = runBlocking<Unit> {
        val context = context(hasDelegation = false)

        val result = tool.execute(mapOf("assessmentId" to 40), context)

        assertThat((result as McpToolResult.Error).code).isEqualTo("DELEGATION_REQUIRED")
        verify(exactly = 0) { assessments.prepareOutstandingReminder(any(), any()) }
    }

    private fun context(hasDelegation: Boolean = true): McpExecutionContext = mockk {
        every { hasDelegation() } returns hasDelegation
        every { delegatedUserRoles } returns setOf("SECCHAMPION")
        every { delegatedUserId } returns 7
        every { apiKeyId } returns 5
    }
}
