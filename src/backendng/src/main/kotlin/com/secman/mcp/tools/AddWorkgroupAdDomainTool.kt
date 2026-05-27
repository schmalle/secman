package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.WorkgroupAdDomainDto
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.DuplicateAdDomainException
import com.secman.service.WorkgroupAdDomainService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

@Singleton
class AddWorkgroupAdDomainTool(
    @Inject private val workgroupAdDomainService: WorkgroupAdDomainService
) : McpTool {
    private val log = LoggerFactory.getLogger(AddWorkgroupAdDomainTool::class.java)

    override val name = "add_workgroup_ad_domain"
    override val description = "Add an AD domain to a workgroup (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "workgroupId" to mapOf("type" to "number", "description" to "ID of the workgroup"),
            "adDomain" to mapOf("type" to "string", "description" to "Active Directory domain")
        ),
        "required" to listOf("workgroupId", "adDomain")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error("DELEGATION_REQUIRED", "User Delegation must be enabled to use this tool")
        }
        if (!context.isAdmin) {
            return McpToolResult.error("ADMIN_REQUIRED", "ADMIN role required to add AD domains to a workgroup")
        }
        val workgroupId = (arguments["workgroupId"] as? Number)?.toLong()
            ?: return McpToolResult.error("VALIDATION_ERROR", "workgroupId is required and must be a valid number")
        val adDomain = arguments["adDomain"] as? String
            ?: return McpToolResult.error("VALIDATION_ERROR", "adDomain is required and must be a string")
        val actorId = context.delegatedUserId
            ?: return McpToolResult.error("DELEGATION_REQUIRED", "Delegated user id is required")

        return try {
            val saved = workgroupAdDomainService.add(workgroupId, adDomain, actorId)
            log.info("AUDIT: MCP add_workgroup_ad_domain: workgroupId={}, adDomain={}, actorId={}", workgroupId, adDomain, actorId)
            McpToolResult.success(mapOf(
                "assignment" to WorkgroupAdDomainDto.from(saved),
                "message" to "AD domain ${saved.adDomain} added to workgroup $workgroupId"
            ))
        } catch (e: DuplicateAdDomainException) {
            McpToolResult.error("CONFLICT", e.message ?: "AD domain already assigned to this workgroup")
        } catch (e: IllegalArgumentException) {
            McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid request")
        } catch (e: Exception) {
            log.error("Failed to add AD domain to workgroup", e)
            McpToolResult.error("EXECUTION_ERROR", "Failed to add AD domain to workgroup: ${e.message}")
        }
    }
}
