package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RiskAssessmentMcpService
import jakarta.inject.Singleton

@Singleton
class ListRiskAssessmentsTool(private val service: RiskAssessmentMcpService) : McpTool {
    override val name = "list_risk_assessments"
    override val description =
        "List delegated-user-visible risk assessments, filterable by status, open state, assessment type and use case name"
    override val operation = McpOperation.READ
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "status" to mapOf("type" to "string", "enum" to listOf("STARTED", "COMPLETED")),
            "openOnly" to mapOf("type" to "boolean", "default" to false),
            "assessmentType" to mapOf("type" to "string", "enum" to listOf("DEMAND", "ASSET", "AWS_ACCOUNT")),
            "useCaseName" to mapOf("type" to "string"),
            "page" to mapOf("type" to "integer", "minimum" to 0),
            "pageSize" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 100)
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(
            context, "USER", "ADMIN", "RISK", "SECCHAMPION",
            message = "ADMIN, RISK or SECCHAMPION role required to list risk assessments"
        )?.let { return it }
        if ((arguments.containsKey("openOnly") && arguments["openOnly"] !is Boolean) ||
            listOf("status", "assessmentType", "useCaseName").any { arguments.containsKey(it) && arguments[it] !is String } ||
            listOf("page", "pageSize").any { key -> arguments.containsKey(key) &&
                ((arguments[key] as? Number)?.toDouble()?.let { !it.isFinite() || it % 1.0 != 0.0 || it > Int.MAX_VALUE || it < Int.MIN_VALUE } != false) }) {
            return McpToolResult.error("VALIDATION_ERROR", "Filters must use their declared types and pagination must use integers")
        }
        val page = (arguments["page"] as? Number)?.toInt() ?: 0
        val pageSize = (arguments["pageSize"] as? Number)?.toInt() ?: 20
        if (page < 0 || pageSize !in 1..100) {
            return McpToolResult.error("VALIDATION_ERROR", "page must be at least 0 and pageSize between 1 and 100")
        }
        return riskAssessmentTool {
            service.list(
                context,
                arguments["status"] as? String,
                arguments["useCaseName"] as? String,
                arguments["openOnly"] as? Boolean ?: false,
                arguments["assessmentType"] as? String,
                page,
                pageSize
            )
        }
    }
}
