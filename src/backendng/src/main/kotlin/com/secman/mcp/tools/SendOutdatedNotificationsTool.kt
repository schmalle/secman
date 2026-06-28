package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import com.secman.repository.OutdatedAssetMaterializedViewRepository
import com.secman.repository.UserMappingRepository
import com.secman.service.NotificationService
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool that sends outdated asset reminder emails.
 *
 * Mirrors the CLI command `send-notifications`.
 * ADMIN role is required via User Delegation.
 *
 * Queries the outdated-asset materialized view, resolves owner emails, and sends
 * one consolidated reminder email per owner.
 */
@Singleton
class SendOutdatedNotificationsTool(
    @Inject private val notificationService: NotificationService,
    @Inject private val outdatedAssetRepository: OutdatedAssetMaterializedViewRepository,
    @Inject private val assetRepository: AssetRepository,
    @Inject private val userMappingRepository: UserMappingRepository
) : McpTool {

    override val name = "send_outdated_notifications"
    override val description = "Send outdated-asset reminder emails to owners with overdue vulnerabilities (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "Preview planned notifications without sending emails. Default: false"
            )
        ),
        "required" to emptyList<String>()
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        if (!context.hasDelegation()) {
            return McpToolResult.error("DELEGATION_REQUIRED", "User Delegation must be enabled to use this tool")
        }
        if (!context.isAdmin) {
            return McpToolResult.error("ADMIN_REQUIRED", "ADMIN role required to send outdated asset notifications")
        }

        val dryRun = arguments["dryRun"] as? Boolean ?: false

        return try {
            val outdatedAssets = buildOutdatedAssetList()
            val result = notificationService.processOutdatedAssets(outdatedAssets, dryRun)

            val response = mapOf(
                "success" to true,
                "assetsProcessed" to result.assetsProcessed,
                "emailsSent" to result.emailsSent,
                "failures" to result.failures,
                "skipped" to result.skipped,
                "message" to if (dryRun) {
                    "Dry run: would process ${outdatedAssets.size} outdated asset(s)"
                } else {
                    "Processed ${result.assetsProcessed} outdated asset(s), sent ${result.emailsSent} email(s)" +
                        if (result.failures > 0) " (${result.failures} failed)" else ""
                }
            )
            McpToolResult.success(response)
        } catch (e: Exception) {
            McpToolResult.error("EXECUTION_ERROR", "Failed to send outdated notifications: ${e.message}")
        }
    }

    private fun buildOutdatedAssetList(): List<NotificationService.OutdatedAssetData> {
        val page = outdatedAssetRepository.findOutdatedAssets(
            workgroupId = null,
            searchTerm = null,
            minSeverity = null,
            adDomain = null,
            pageable = Pageable.unpaged()
        )

        val results = mutableListOf<NotificationService.OutdatedAssetData>()
        for (view in page.content) {
            val asset = assetRepository.findById(view.assetId).orElse(null) ?: continue
            val mappings = userMappingRepository.findByAwsAccountId(asset.owner)
            if (mappings.isEmpty()) continue
            val ownerEmail = mappings.first().email

            val severity = when {
                view.criticalCount > 0 -> "CRITICAL"
                view.highCount > 0 -> "HIGH"
                view.mediumCount > 0 -> "MEDIUM"
                else -> "LOW"
            }

            results.add(
                NotificationService.OutdatedAssetData(
                    assetId = view.assetId,
                    assetName = view.assetName,
                    assetType = view.assetType,
                    ownerEmail = ownerEmail,
                    vulnerabilityCount = view.totalOverdueCount,
                    oldestVulnDays = view.oldestVulnDays,
                    oldestVulnId = view.oldestVulnId ?: "unknown",
                    severity = severity,
                    criticality = asset.getEffectiveCriticality().name
                )
            )
        }
        return results
    }
}
