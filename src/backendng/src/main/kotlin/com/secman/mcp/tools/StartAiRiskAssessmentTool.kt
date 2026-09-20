package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.StartAiJobRequest
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AiSuggestionJobService
import com.secman.service.AssessmentOwnershipGuard
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton

/** Starts the same audited, budget-limited OpenRouter job used by the web UI. */
@Singleton
class StartAiRiskAssessmentTool(
    private val jobService: AiSuggestionJobService,
    private val ownershipGuard: AssessmentOwnershipGuard
) : McpTool {
    override val name = "start_ai_risk_assessment"
    override val description =
        "Ask the configured OpenRouter online model to research and draft answers for an accessible risk assessment"
    override val operation = McpOperation.WRITE
    override val inputSchema = aiJobStartSchema()

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(
            context, "ADMIN", "SECCHAMPION",
            message = "ADMIN or SECCHAMPION role required to start AI risk assessment jobs"
        )?.let { return it }
        val assessmentId = (arguments["assessmentId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "assessmentId is required")
        return riskAssessmentTool {
            val authentication = context.authentication()
            val assessment = ownershipGuard.check(assessmentId, authentication)
            jobService.startJob(
                assessment,
                StartAiJobRequest(scope = "WHOLE_ASSESSMENT", force = arguments["force"] as? Boolean ?: false),
                authentication
            )
        }
    }
}

private fun aiJobStartSchema(): Map<String, Any> = mapOf(
    "type" to "object",
    "required" to listOf("assessmentId"),
    "properties" to mapOf(
        "assessmentId" to mapOf("type" to "number", "minimum" to 1),
        "force" to mapOf(
            "type" to "boolean",
            "description" to "Replace AI-edited answers; manually entered answers are never overwritten"
        )
    )
)

internal fun McpExecutionContext.authentication(): Authentication = Authentication.build(
    delegatedUsername ?: throw SecurityException("Delegated username is required"),
    delegatedUserRoles?.toList() ?: emptyList(),
    mapOf("userId" to delegatedUserId, "email" to delegatedUserEmail)
)
