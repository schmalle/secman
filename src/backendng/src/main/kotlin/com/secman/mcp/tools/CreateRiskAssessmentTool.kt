package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RiskAssessmentMcpService
import jakarta.inject.Singleton
import java.time.LocalDate
import java.time.format.DateTimeParseException

@Singleton
class CreateRiskAssessmentTool(private val service: RiskAssessmentMcpService) : McpTool {
    override val name = "create_risk_assessment"
    override val description =
        "Create an AWS-account-based risk assessment for selected use cases and an assigned respondent; no asset is created"
    override val operation = McpOperation.WRITE
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "awsAccountId" to mapOf(
                "type" to "string", "pattern" to "^[0-9]{12}$",
                "description" to "The 12-digit AWS account number to assess directly"
            ),
            "useCaseIds" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "number", "minimum" to 1),
                "minItems" to 1,
                "maxItems" to RiskAssessmentMcpService.MAX_USE_CASES,
                "uniqueItems" to true,
                "description" to "One or more use cases whose requirements define the questionnaire"
            ),
            "useCaseId" to mapOf(
                "type" to "number", "minimum" to 1,
                "description" to "Deprecated single-use-case form; use useCaseIds for new integrations"
            ),
            "assessorEmail" to mapOf("type" to "string"),
            "respondentEmail" to mapOf("type" to "string"),
            "endDate" to mapOf("type" to "string", "description" to "ISO date (YYYY-MM-DD)"),
            "notes" to mapOf("type" to "string")
        ),
        "required" to listOf("awsAccountId", "assessorEmail", "respondentEmail", "endDate")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(
            context, "ADMIN", "SECCHAMPION",
            message = "ADMIN or SECCHAMPION role required to create AWS account risk assessments"
        )?.let { return it }
        val awsAccountId = (arguments["awsAccountId"] as? String)?.trim()
            ?: return McpToolResult.error("VALIDATION_ERROR", "awsAccountId is required")
        val useCaseIds = parseUseCaseIds(arguments)
            ?: return McpToolResult.error(
                "VALIDATION_ERROR",
                "Provide either a non-empty useCaseIds array or the deprecated useCaseId, not both"
            )
        val assessorEmail = (arguments["assessorEmail"] as? String)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.error("VALIDATION_ERROR", "assessorEmail is required")
        val respondentEmail = (arguments["respondentEmail"] as? String)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return McpToolResult.error("VALIDATION_ERROR", "respondentEmail is required")
        val endDate = try {
            LocalDate.parse(arguments["endDate"] as? String)
        } catch (e: DateTimeParseException) {
            return McpToolResult.error("VALIDATION_ERROR", "endDate must be an ISO date (YYYY-MM-DD)")
        } catch (e: NullPointerException) {
            return McpToolResult.error("VALIDATION_ERROR", "endDate is required")
        }
        return riskAssessmentTool {
            service.create(
                context, awsAccountId, useCaseIds, assessorEmail, respondentEmail, endDate,
                arguments["notes"] as? String
            )
        }
    }

    private fun parseUseCaseIds(arguments: Map<String, Any>): List<Long>? {
        val legacy = (arguments["useCaseId"] as? Number)?.toLong()
        val selected = (arguments["useCaseIds"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }
        if (legacy != null && selected != null) return null
        if (selected != null && selected.size != (arguments["useCaseIds"] as List<*>).size) return null
        return selected?.takeIf { it.isNotEmpty() } ?: legacy?.let(::listOf)
    }
}
