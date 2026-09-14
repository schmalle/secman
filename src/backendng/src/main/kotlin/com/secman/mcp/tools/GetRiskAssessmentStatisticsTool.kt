package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.McpStatisticsService
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
class GetRiskAssessmentStatisticsTool(private val statisticsService: McpStatisticsService) : McpTool {
    private val log = LoggerFactory.getLogger(GetRiskAssessmentStatisticsTool::class.java)
    override val name = "get_risk_assessment_statistics"
    override val description =
        "Get delegated-user-visible risk assessment counts by status, optionally filtered by exact use case name"
    override val operation = McpOperation.READ
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "useCaseName" to mapOf(
                "type" to "string",
                "maxLength" to 255,
                "description" to "Optional exact, case-insensitive use case name"
            )
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(
            context, "ADMIN", "RISK", "SECCHAMPION",
            message = "ADMIN, RISK or SECCHAMPION role required to read risk assessment statistics"
        )?.let { return it }
        return try {
            McpToolResult.success(statisticsService.riskAssessments(context, arguments["useCaseName"] as? String))
        } catch (e: IllegalArgumentException) {
            McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid statistics filter")
        } catch (e: Exception) {
            log.error("Risk assessment statistics failed for MCP actor {}", context.delegatedUserId, e)
            McpToolResult.error("INTERNAL_ERROR", "Failed to retrieve risk assessment statistics")
        }
    }
}
