package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.McpStatisticsService
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
class GetMySecurityStatisticsTool(private val statisticsService: McpStatisticsService) : McpTool {
    private val log = LoggerFactory.getLogger(GetMySecurityStatisticsTool::class.java)
    override val name = "get_my_security_statistics"
    override val description =
        "Get asset and vulnerability counts scoped to the delegated user's accessible assets, with vulnerability severity distribution"
    override val operation = McpOperation.READ
    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to emptyMap<String, Any>(),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }
        requireAnyUserRole(
            context, "ADMIN", "VULN", "SECCHAMPION",
            message = "ADMIN, VULN or SECCHAMPION role required to read security statistics"
        )?.let { return it }
        return try {
            McpToolResult.success(statisticsService.securityPosture(context))
        } catch (e: Exception) {
            log.error("Scoped security statistics failed for MCP actor {}", context.delegatedUserId, e)
            McpToolResult.error("INTERNAL_ERROR", "Failed to retrieve security statistics")
        }
    }
}
