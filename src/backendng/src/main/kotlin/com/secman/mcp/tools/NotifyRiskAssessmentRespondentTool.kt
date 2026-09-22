package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RiskAssessmentMcpService
import com.secman.service.RiskAssessmentReminderNotificationService
import jakarta.inject.Singleton

@Singleton
class NotifyRiskAssessmentRespondentTool(
    private val service: RiskAssessmentMcpService,
    private val notificationService: RiskAssessmentReminderNotificationService
) : McpTool {
    override val name = "notify_risk_assessment_respondent"
    override val description =
        "Notify the assigned respondent when an ongoing risk assessment still has unanswered requirements"
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "assessmentId" to mapOf("type" to "number", "minimum" to 1),
            "respondentEmail" to mapOf("type" to "string", "description" to "Required when several respondent sections are open"),
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "Return outstanding counts without sending email; default false"
            )
        ),
        "required" to listOf("assessmentId")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(
            context, "ADMIN", "RISK", "SECCHAMPION",
            message = "ADMIN, RISK or SECCHAMPION role required to notify an assessment respondent"
        )?.let { return it }
        val assessmentId = (arguments["assessmentId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "assessmentId is required")
        val dryRun = arguments["dryRun"] as? Boolean ?: false
        return riskAssessmentTool {
            val reminder = service.prepareOutstandingReminder(context, assessmentId, arguments["respondentEmail"] as? String)
            val outcome = notificationService.send(reminder, context.delegatedUserId!!, dryRun, context.apiKeyId)
            mapOf(
                "assessmentId" to reminder.assessmentId,
                "respondent" to reminder.recipientEmail,
                "requirementCount" to reminder.requirementCount,
                "unansweredCount" to reminder.unansweredCount,
                "dryRun" to dryRun,
                "sent" to (outcome == RiskAssessmentReminderNotificationService.SendOutcome.SENT),
                "reason" to outcome.name
            )
        }
    }
}
