package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP Tool: get_asset_complete_profile
 *
 * Retrieves complete asset profile including base details, vulnerabilities, and scan results.
 * Provides a comprehensive view of a single asset.
 *
 * Feature 009: Enhanced asset profile retrieval
 * Feature 052: MCP Access Control - Checks delegated user's access to the asset
 */
@Singleton
class GetAssetCompleteProfileTool(
    @Inject private val assetRepository: AssetRepository
) : McpTool {

    override val name = "get_asset_complete_profile"

    override val description = """
        Retrieve complete asset profile including base details, vulnerabilities, and scan results.

        Input:
        - assetId: The asset ID to retrieve (required)
        - includeVulnerabilities: Include vulnerability data (optional, default=true)
        - includeScanResults: Include scan result data (optional, default=true)

        Returns complete asset information with related vulnerabilities and scan results.
    """.trimIndent()

    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "assetId" to mapOf(
                "type" to "integer",
                "description" to "The asset ID to retrieve (required)"
            ),
            "includeVulnerabilities" to mapOf(
                "type" to "boolean",
                "description" to "Include vulnerability data (default=true)",
                "default" to true
            ),
            "includeScanResults" to mapOf(
                "type" to "boolean",
                "description" to "Include scan result data (default=true)",
                "default" to true
            )
        ),
        "required" to listOf("assetId")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        try {
            // Extract and validate parameters
            val assetId = (arguments["assetId"] as? Number)?.toLong()
                ?: return McpToolResult.error(
                    "VALIDATION_ERROR",
                    "Required parameter 'assetId' is missing or invalid"
                )

            val includeVulnerabilities = (arguments["includeVulnerabilities"] as? Boolean) ?: true
            val includeScanResults = (arguments["includeScanResults"] as? Boolean) ?: true

            // Access control check (Feature: 052-mcp-access-control)
            // Returns generic "not found" to avoid revealing asset existence
            if (!context.canAccessAsset(assetId)) {
                return McpToolResult.error(
                    "ASSET_NOT_FOUND",
                    "Asset with ID $assetId not found"
                )
            }

            // Get asset
            val asset = assetRepository.findById(assetId).orElse(null)
                ?: return McpToolResult.error(
                    "ASSET_NOT_FOUND",
                    "Asset with ID $assetId not found"
                )

            // Build response
            val response = mutableMapOf<String, Any>(
                "asset" to mapOf(
                    "id" to asset.id,
                    "name" to asset.name,
                    "type" to asset.type,
                    "ip" to asset.ip,
                    "owner" to asset.owner,
                    "description" to asset.description,
                    "groups" to (asset.groups?.split(",")?.map { it.trim() } ?: emptyList<String>()),
                    "cloudAccountId" to asset.cloudAccountId,
                    "cloudInstanceId" to asset.cloudInstanceId,
                    "adDomain" to asset.adDomain,
                    "osVersion" to asset.osVersion,
                    "lastSeen" to asset.lastSeen?.toString(),
                    "createdAt" to asset.createdAt?.toString(),
                    "updatedAt" to asset.updatedAt?.toString(),
                    "workgroups" to asset.workgroups.map { mapOf("id" to it.id, "name" to it.name) }
                )
            )

            // Add vulnerabilities if requested
            if (includeVulnerabilities) {
                val vulnerabilities = asset.vulnerabilities.map { vuln ->
                    mapOf(
                        "id" to vuln.id,
                        "vulnerabilityId" to vuln.vulnerabilityId,
                        "cvssSeverity" to vuln.cvssSeverity,
                        "vulnerableProductVersions" to vuln.vulnerableProductVersions,
                        "daysOpen" to vuln.daysOpen,
                        "scanTimestamp" to vuln.scanTimestamp.toString()
                    )
                }
                response["vulnerabilities"] = vulnerabilities
                response["vulnerabilityCount"] = vulnerabilities.size

                // Calculate vulnerability statistics
                val stats = mapOf(
                    "total" to vulnerabilities.size,
                    "critical" to vulnerabilities.count {
                        (it["cvssSeverity"] as? String)?.equals("CRITICAL", ignoreCase = true) == true
                    },
                    "high" to vulnerabilities.count {
                        (it["cvssSeverity"] as? String)?.equals("HIGH", ignoreCase = true) == true
                    },
                    "medium" to vulnerabilities.count {
                        (it["cvssSeverity"] as? String)?.equals("MEDIUM", ignoreCase = true) == true
                    },
                    "low" to vulnerabilities.count {
                        (it["cvssSeverity"] as? String)?.equals("LOW", ignoreCase = true) == true
                    }
                )
                response["vulnerabilityStats"] = stats
            }

            // Add scan results if requested
            if (includeScanResults) {
                val scanResults = asset.scanResults.map { scanResult ->
                    mapOf(
                        "id" to scanResult.id,
                        "ipAddress" to scanResult.ipAddress,
                        "hostname" to scanResult.hostname,
                        "discoveredAt" to scanResult.discoveredAt.toString(),
                        "scanId" to scanResult.scan.id,
                        "scanType" to scanResult.scan.scanType,
                        "ports" to scanResult.ports.map { port ->
                            mapOf(
                                "portNumber" to port.portNumber,
                                "protocol" to port.protocol,
                                "state" to port.state,
                                "service" to port.service,
                                "version" to port.version
                            )
                        }
                    )
                }
                response["scanResults"] = scanResults
                response["scanResultCount"] = scanResults.size

                // Calculate port summary
                val allPorts = asset.scanResults.flatMap { it.ports }
                val openPorts = allPorts.filter { it.isOpen() }
                val portSummary = mapOf(
                    "totalPorts" to allPorts.size,
                    "openPorts" to openPorts.size,
                    "uniqueOpenPortNumbers" to openPorts.map { it.portNumber }.distinct().sorted(),
                    "uniqueServices" to openPorts.mapNotNull { it.service }.distinct().sorted()
                )
                response["portSummary"] = portSummary
            }

            return McpToolResult.success(response)

        } catch (e: IllegalArgumentException) {
            return McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid input parameters")
        } catch (e: Exception) {
            return McpToolResult.error("INTERNAL_ERROR", "Failed to retrieve asset profile: ${e.message}")
        }
    }
}
