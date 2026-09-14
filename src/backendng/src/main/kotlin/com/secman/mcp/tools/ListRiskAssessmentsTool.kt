package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.RiskAssessmentMcpService
import jakarta.inject.Singleton

@Singleton
class ListRiskAssessmentsTool(private val service: RiskAssessmentMcpService) : McpTool {
    override val name = "list_risk_assessments"
    override val description =
        "List delegated-user-visible risk assessments, filterable by status and use case name"
    override val operation = McpOperation.READ
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "status" to mapOf("type" to "string", "enum" to listOf("STARTED", "COMPLETED")),
            "useCaseName" to mapOf("type" to "string"),
            "page" to mapOf("type" to "number", "minimum" to 0),
            "pageSize" to mapOf("type" to "number", "minimum" to 1, "maximum" to 100)
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(
            context, "ADMIN", "RISK", "SECCHAMPION",
            message = "ADMIN, RISK or SECCHAMPION role required to list risk assessments"
        )?.let { return it }
        val page = (arguments["page"] as? Number)?.toInt() ?: 0
        val pageSize = (arguments["pageSize"] as? Number)?.toInt() ?: 20
        if (page < 0 || pageSize !in 1..100) {
            return McpToolResult.error("VALIDATION_ERROR", "page must be at least 0 and pageSize between 1 and 100")
        }
        return riskAssessmentTool {
            service.list(context, arguments["status"] as? String, arguments["useCaseName"] as? String, page, pageSize)
        }
    }
}
