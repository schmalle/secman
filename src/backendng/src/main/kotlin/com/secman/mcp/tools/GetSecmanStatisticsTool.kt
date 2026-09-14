package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.McpStatisticsService
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
class GetSecmanStatisticsTool(private val statisticsService: McpStatisticsService) : McpTool {
    private val log = LoggerFactory.getLogger(GetSecmanStatisticsTool::class.java)
    override val name = "get_secman_statistics"
    override val description =
        "Get global SecMan counts including assets, vulnerabilities, users, seven-day login activity, requirements, use cases, and risk assessments (ADMIN only)"
    override val operation = McpOperation.READ
    override val inputSchema = emptyObjectSchema()

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(context, "ADMIN", message = "ADMIN role required to read global SecMan statistics")
            ?.let { return it }
        return try {
            McpToolResult.success(statisticsService.global(context))
        } catch (e: Exception) {
            log.error("Global SecMan statistics failed for MCP actor {}", context.delegatedUserId, e)
            McpToolResult.error("INTERNAL_ERROR", "Failed to retrieve SecMan statistics")
        }
    }
}

private fun emptyObjectSchema(): Map<String, Any> = mapOf(
    "type" to "object",
    "properties" to emptyMap<String, Any>(),
    "required" to emptyList<String>()
)
