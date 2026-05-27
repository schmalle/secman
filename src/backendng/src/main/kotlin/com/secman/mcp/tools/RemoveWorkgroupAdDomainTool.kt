package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.WorkgroupAdDomainService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
class RemoveWorkgroupAdDomainTool(
    @Inject private val workgroupAdDomainService: WorkgroupAdDomainService
) : McpTool {
    private val log = LoggerFactory.getLogger(RemoveWorkgroupAdDomainTool::class.java)

    override val name = "remove_workgroup_ad_domain"
    override val description = "Remove an AD domain from a workgroup (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.DELETE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "workgroupId" to mapOf("type" to "number", "description" to "ID of the workgroup"),
            "adDomain" to mapOf("type" to "string", "description" to "AD domain to remove")
        ),
        "required" to listOf("workgroupId", "adDomain")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error("DELEGATION_REQUIRED", "User Delegation must be enabled to use this tool")
        }
        if (!context.isAdmin) {
            return McpToolResult.error("ADMIN_REQUIRED", "ADMIN role required to remove AD domains from a workgroup")
        }
        val workgroupId = (arguments["workgroupId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "workgroupId is required and must be a valid number")
        val adDomain = arguments["adDomain"] as? String
            ?: return McpToolResult.error("VALIDATION_ERROR", "adDomain is required and must be a string")

        return try {
            val deleted = workgroupAdDomainService.remove(workgroupId, adDomain)
            log.info("AUDIT: MCP remove_workgroup_ad_domain: workgroupId={}, adDomain={}, deleted={}, actor={}", workgroupId, adDomain, deleted, context.delegatedUserEmail)
            McpToolResult.success(mapOf("deleted" to deleted))
        } catch (e: Exception) {
            log.error("Failed to remove AD domain from workgroup", e)
            McpToolResult.error("EXECUTION_ERROR", "Failed to remove AD domain from workgroup: ${e.message}")
        }
    }
}
