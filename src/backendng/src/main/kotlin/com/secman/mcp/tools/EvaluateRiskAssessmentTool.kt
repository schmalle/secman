package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RiskAssessmentMcpService
import jakarta.inject.Singleton

@Singleton
class EvaluateRiskAssessmentTool(private val service: RiskAssessmentMcpService) : McpTool {
    override val name = "evaluate_risk_assessment"
    override val description =
        "Evaluate a completed questionnaire and return compliance counts plus non-compliant findings"
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
            message = "ADMIN, RISK or SECCHAMPION role required to evaluate risk assessments"
        )?.let { return it }
        val id = (arguments["assessmentId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "assessmentId is required")
        return riskAssessmentTool { service.evaluate(context, id) }
    }
}
