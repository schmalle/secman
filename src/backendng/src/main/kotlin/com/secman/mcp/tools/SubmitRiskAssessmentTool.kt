package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RiskAssessmentMcpService
import jakarta.inject.Singleton

@Singleton
class SubmitRiskAssessmentTool(private val service: RiskAssessmentMcpService) : McpTool {
    override val name = "submit_risk_assessment"
    override val description = "Complete an assessment after every questionnaire requirement has an answer"
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf("assessmentId" to mapOf("type" to "number", "minimum" to 1)),
        "required" to listOf("assessmentId")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(
            context, "ADMIN", "RISK", "SECCHAMPION",
            message = "ADMIN, RISK or SECCHAMPION role required to submit risk assessments"
        )?.let { return it }
        val id = (arguments["assessmentId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "assessmentId is required")
        return riskAssessmentTool { service.submit(context, id) }
    }
}
