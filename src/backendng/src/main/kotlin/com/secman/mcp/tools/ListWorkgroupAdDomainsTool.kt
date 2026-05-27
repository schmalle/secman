package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.WorkgroupAdDomainDto
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.WorkgroupAdDomainService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
class ListWorkgroupAdDomainsTool(
    @Inject private val workgroupAdDomainService: WorkgroupAdDomainService
) : McpTool {
    private val log = LoggerFactory.getLogger(ListWorkgroupAdDomainsTool::class.java)

    override val name = "list_workgroup_ad_domains"
    override val description = "List all AD domain assignments for a workgroup (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "workgroupId" to mapOf("type" to "number", "description" to "ID of the workgroup")
        ),
        "required" to listOf("workgroupId")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error("DELEGATION_REQUIRED", "User Delegation must be enabled to use this tool")
        }
        if (!context.isAdmin) {
            return McpToolResult.error("ADMIN_REQUIRED", "ADMIN role required to list workgroup AD domains")
        }
        val workgroupId = (arguments["workgroupId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "workgroupId is required and must be a valid number")

        return try {
            val assignments = workgroupAdDomainService.list(workgroupId).map(WorkgroupAdDomainDto::from)
            log.debug("MCP list_workgroup_ad_domains: workgroupId={}, count={}, actor={}", workgroupId, assignments.size, context.delegatedUserEmail)
            McpToolResult.success(mapOf("assignments" to assignments))
        } catch (e: IllegalArgumentException) {
            McpToolResult.error("NOT_FOUND", e.message ?: "Workgroup not found")
        } catch (e: Exception) {
            log.error("Failed to list workgroup AD domains", e)
            McpToolResult.error("EXECUTION_ERROR", "Failed to list workgroup AD domains: ${e.message}")
        }
    }
}
