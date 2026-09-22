package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AiSuggestionJobService
import com.secman.service.AssessmentOwnershipGuard
import jakarta.inject.Singleton

/** Polling endpoint for agent clients that cannot consume the web UI's SSE stream. */
@Singleton
class GetAiRiskAssessmentJobTool(
    private val jobService: AiSuggestionJobService,
    private val ownershipGuard: AssessmentOwnershipGuard
) : McpTool {
    override val name = "get_ai_risk_assessment_job"
    override val description = "Get progress, cost and terminal state for an AI risk-assessment job"
    override val operation = McpOperation.READ
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "assessmentId" to mapOf("type" to "number", "minimum" to 1),
            "jobId" to mapOf("type" to "number", "minimum" to 1)
        ),
        "required" to listOf("assessmentId", "jobId")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(
            context, "ADMIN", "SECCHAMPION",
            message = "ADMIN or SECCHAMPION role required to inspect AI risk assessment jobs"
        )?.let { return it }
        val assessmentId = (arguments["assessmentId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "assessmentId is required")
        val jobId = (arguments["jobId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "jobId is required")
        return riskAssessmentTool {
            ownershipGuard.check(assessmentId, context.authentication())
            jobService.getJobStatus(jobId, assessmentId)
                ?: throw NoSuchElementException("AI risk assessment job not found")
        }
    }
}
