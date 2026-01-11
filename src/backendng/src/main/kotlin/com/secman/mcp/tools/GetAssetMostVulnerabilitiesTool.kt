package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.VulnerabilityRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for getting the asset(s) with the most vulnerabilities.
 *
 * Returns the top N assets ranked by total vulnerability count, with severity breakdowns.
 * Respects access control - users only see assets they have access to.
 *
 * @see VulnerabilityRepository.findTopAssetsByVulnerabilitiesForAll
 * @see VulnerabilityRepository.findTopAssetsByVulnerabilitiesForAssets
 */
@Singleton
class GetAssetMostVulnerabilitiesTool(
    @Inject private val vulnerabilityRepository: VulnerabilityRepository
) : McpTool {

    override val name = "get_asset_most_vulnerabilities"
    override val description = "Get the asset(s) with the highest number of vulnerabilities, ranked by total count with severity breakdowns"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "topN" to mapOf(
                "type" to "number",
                "description" to "Number of top assets to return (default: 1, max: 10)",
                "minimum" to 1,
                "maximum" to 10
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        try {
            // Parse topN parameter (default 1, max 10)
            val topN = ((arguments["topN"] as? Number)?.toInt() ?: 1).coerceIn(1, 10)

            // Query with access control
            val accessibleIds = context.getFilterableAssetIds()
            val topAssets = if (accessibleIds != null && accessibleIds.isNotEmpty()) {
                vulnerabilityRepository.findTopAssetsByVulnerabilitiesForAssets(accessibleIds)
            } else if (accessibleIds != null && accessibleIds.isEmpty()) {
                // User has delegation but no accessible assets
                emptyList()
            } else {
                // No delegation - show all (for admin or unrestricted keys)
                vulnerabilityRepository.findTopAssetsByVulnerabilitiesForAll()
            }

            // Take requested number and transform results for better display
            val result = topAssets.take(topN).map { asset ->
                transformAssetResult(asset)
            }

            // Return appropriate format based on request
            return if (topN == 1) {
                McpToolResult.success(
                    mapOf(
                        "asset" to result.firstOrNull(),
                        "message" to if (result.isEmpty()) "No assets with vulnerabilities found" else null
                    ).filterValues { it != null }
                )
            } else {
                McpToolResult.success(
                    mapOf(
                        "assets" to result,
                        "count" to result.size,
                        "requestedTopN" to topN
                    )
                )
            }

        } catch (e: Exception) {
            return McpToolResult.error(
                "EXECUTION_ERROR",
                "Failed to retrieve top vulnerable assets: ${e.message}"
            )
        }
    }

    /**
     * Transform raw query result to provide better display names.
     * When asset_name is a CrowdStrike device ID (hex string), prefer showing IP as identifier.
     */
    private fun transformAssetResult(asset: Map<String, Any>): Map<String, Any> {
        val assetName = asset["asset_name"]?.toString() ?: ""
        val assetIp = asset["asset_ip"]?.toString()

        // Detect if name looks like a CrowdStrike device ID (uppercase hex, no dots/hyphens typical of hostnames)
        val looksLikeDeviceId = assetName.isNotBlank() &&
            assetName.matches(Regex("^[A-F0-9]{10,}$"))

        // Create a display-friendly identifier
        val displayName = when {
            looksLikeDeviceId && !assetIp.isNullOrBlank() -> assetIp  // Prefer IP over device ID
            assetName.isNotBlank() -> assetName
            !assetIp.isNullOrBlank() -> assetIp
            else -> "Unknown"
        }

        return asset.toMutableMap().apply {
            put("display_name", displayName)
            if (looksLikeDeviceId) {
                put("device_id", assetName)  // Preserve original device ID
                put("name_is_device_id", true)
            }
        }
    }
}
