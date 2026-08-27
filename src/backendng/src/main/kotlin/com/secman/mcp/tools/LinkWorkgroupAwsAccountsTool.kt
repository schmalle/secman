package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.WorkgroupAccountLinkService
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * MCP tool for the workgroup-linking correction path.
 *
 * ADMIN role is required via User Delegation.
 *
 * Delegates to [WorkgroupAccountLinkService] — the same code path the import side effect
 * and REST `POST /api/user-mappings/link-workgroup-accounts` use, so the naming rule
 * ("aws-" + display name, matched case-insensitively) cannot drift between surfaces.
 *
 * Input parameters:
 * - dryRun: report what would be linked without creating workgroups or assignments.
 *
 * Returns processed, workgroupsCreated, linked, alreadyLinked, failed, dryRun, truncated
 * and a bounded links[] array. A links[] entry carries either `error` (failed),
 * `alreadyLinked` (an idempotent no-op, not a failure) or `linked`.
 */
@Singleton
class LinkWorkgroupAwsAccountsTool(
    @Inject private val workgroupAccountLinkService: WorkgroupAccountLinkService
) : McpTool {

    private val log = LoggerFactory.getLogger(LinkWorkgroupAwsAccountsTool::class.java)

    override val name = "link_workgroup_aws_accounts"
    override val description =
        "Link every AWS account that carries a display name to the workgroup named " +
            "'aws-<display name>', creating the workgroup if it does not exist. Corrects " +
            "mappings imported before display names were captured (ADMIN only, requires " +
            "User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "If true, report what would be linked without creating " +
                    "workgroups or assignments",
                "default" to false
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        requireDelegation(context)?.let { return it }

        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to link workgroup AWS accounts"
            )
        }

        val dryRun = arguments["dryRun"] as? Boolean ?: false

        return try {
            val summary = workgroupAccountLinkService
                .linkFromStoredMappings(context.delegatedUserId, dryRun)

            log.info(
                "AUDIT: operation=MCP_LINK_WORKGROUP_AWS_ACCOUNTS, actor={}, dryRun={}, " +
                    "processed={}, workgroupsCreated={}, linked={}, alreadyLinked={}, failed={}",
                context.delegatedUserEmail, dryRun, summary.processed, summary.workgroupsCreated,
                summary.linked, summary.alreadyLinked, summary.failed
            )

            McpToolResult.success(
                mapOf(
                    "processed" to summary.processed,
                    "workgroupsCreated" to summary.workgroupsCreated,
                    "linked" to summary.linked,
                    "alreadyLinked" to summary.alreadyLinked,
                    "failed" to summary.failed,
                    "dryRun" to summary.dryRun,
                    // True when more accounts were processed than links[] reports, or more
                    // exist than one run covers — never a silent cap.
                    "truncated" to summary.truncated,
                    "links" to summary.links.map { link ->
                        mapOf(
                            "awsAccountId" to link.awsAccountId,
                            "displayName" to link.displayName,
                            "workgroupName" to link.workgroupName,
                            "workgroupId" to link.workgroupId,
                            "workgroupCreated" to link.workgroupCreated,
                            "linked" to link.linked,
                            "alreadyLinked" to link.alreadyLinked,
                            "dryRun" to link.dryRun,
                            "skipped" to link.skipped,
                            "skipReason" to link.skipReason,
                            "error" to link.error
                        )
                    }
                )
            )
        } catch (e: IllegalArgumentException) {
            McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid request")
        } catch (e: Exception) {
            log.error("MCP link_workgroup_aws_accounts failed", e)
            McpToolResult.error(
                "EXECUTION_ERROR",
                "Failed to link workgroup AWS accounts: ${e.message}"
            )
        }
    }
}
