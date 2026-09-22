package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RiskAssessmentMcpService
import jakarta.inject.Singleton

@Singleton
class GetRiskAssessmentAnswersTool(private val service: RiskAssessmentMcpService) : McpTool {
    override val name = "get_risk_assessment_answers"
    override val description =
        "Get the saved answers, comments, sources, and respondent metadata for one visible risk assessment"
    override val operation = McpOperation.READ
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf("assessmentId" to mapOf("type" to "number", "minimum" to 1)),
        "required" to listOf("assessmentId")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(
            context, "USER", "ADMIN", "RISK", "SECCHAMPION",
            message = "ADMIN, RISK or SECCHAMPION role required to view risk assessment answers"
        )?.let { return it }
        val assessmentId = (arguments["assessmentId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "assessmentId is required")
        return riskAssessmentTool { service.answers(context, assessmentId) }
    }
}
