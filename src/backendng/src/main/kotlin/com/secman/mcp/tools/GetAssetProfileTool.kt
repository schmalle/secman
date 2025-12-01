package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import com.secman.repository.ScanResultRepository
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for retrieving comprehensive asset profile.
 * Feature 006: MCP Tools for Asset Inventory, Scans, Vulnerabilities, and Products
 * Feature 052: MCP Access Control - Checks delegated user's access to the asset
 *
 * Provides complete view of a single asset including:
 * - Asset details
 * - Current vulnerabilities
 * - Scan history
 * - Open ports and services
 */
@Singleton
class GetAssetProfileTool(
    @Inject private val assetRepository: AssetRepository,
    @Inject private val vulnerabilityRepository: VulnerabilityRepository,
    @Inject private val scanResultRepository: ScanResultRepository
) : McpTool {

    override val name = "get_asset_profile"
    override val description = "Retrieve comprehensive profile for a single asset"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "assetId" to mapOf(
                "type" to "number",
                "description" to "The asset ID to retrieve profile for"
            ),
            "includeVulnerabilities" to mapOf(
                "type" to "boolean",
                "description" to "Include vulnerability data (default: true)",
                "default" to true
            ),
            "includeScanHistory" to mapOf(
                "type" to "boolean",
                "description" to "Include scan history (default: true)",
                "default" to true
            ),
            "vulnerabilityLimit" to mapOf(
                "type" to "number",
                "description" to "Max number of vulnerabilities to return (max 100)",
                "minimum" to 1,
                "maximum" to 100,
                "default" to 20
            ),
            "scanHistoryLimit" to mapOf(
                "type" to "number",
                "description" to "Max number of scan results to return (max 50)",
                "minimum" to 1,
                "maximum" to 50,
                "default" to 10
            )
        ),
        "required" to listOf("assetId")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        val assetId = (arguments["assetId"] as? Number)?.toLong()
            ?: return McpToolResult.error("MISSING_ASSET_ID", "assetId parameter is required")

        val includeVulnerabilities = (arguments["includeVulnerabilities"] as? Boolean) ?: true
        val includeScanHistory = (arguments["includeScanHistory"] as? Boolean) ?: true
        val vulnerabilityLimit = ((arguments["vulnerabilityLimit"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)
        val scanHistoryLimit = ((arguments["scanHistoryLimit"] as? Number)?.toInt() ?: 10).coerceIn(1, 50)

        try {
            // Access control check (Feature: 052-mcp-access-control)
            // Returns generic "not found" to avoid revealing asset existence
            if (!context.canAccessAsset(assetId)) {
                return McpToolResult.error("ASSET_NOT_FOUND", "Asset with ID $assetId not found")
            }

            // Fetch asset
            val asset = assetRepository.findById(assetId).orElse(null)
                ?: return McpToolResult.error("ASSET_NOT_FOUND", "Asset with ID $assetId not found")

            // Build response
            val response: MutableMap<String, Any?> = mutableMapOf(
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
                    "updatedAt" to asset.updatedAt?.toString()
                )
            )

            // Include vulnerabilities if requested
            if (includeVulnerabilities) {
                val vulnPageable = Pageable.from(0, vulnerabilityLimit, Sort.of(Sort.Order.desc("scanTimestamp")))
                val vulnPage = vulnerabilityRepository.findByAssetId(assetId, vulnPageable)

                val vulnerabilities = vulnPage.content.map { vuln ->
                    mapOf(
                        "id" to vuln.id,
                        "vulnerabilityId" to vuln.vulnerabilityId,
                        "cvssSeverity" to vuln.cvssSeverity,
                        "vulnerableProductVersions" to vuln.vulnerableProductVersions,
                        "daysOpen" to vuln.daysOpen,
                        "scanTimestamp" to vuln.scanTimestamp?.toString()
                    )
                }

                response["vulnerabilities"] = mapOf(
                    "items" to vulnerabilities,
                    "total" to vulnPage.totalSize,
                    "shown" to vulnerabilities.size
                )

                // Vulnerability statistics
                val totalVulns = vulnerabilityRepository.countByAssetId(assetId)
                val severityCounts = vulnPage.content.groupingBy { it.cvssSeverity ?: "Unknown" }.eachCount()

                response["vulnerabilityStats"] = mapOf(
                    "total" to totalVulns,
                    "bySeverity" to severityCounts
                )
            }

            // Include scan history if requested
            if (includeScanHistory) {
                val scanPageable = Pageable.from(0, scanHistoryLimit, Sort.of(Sort.Order.desc("discoveredAt")))
                val scanPage = scanResultRepository.findByAssetIdOrderByDiscoveredAtDesc(assetId, scanPageable)

                val scanHistory = scanPage.content.map { scanResult ->
                    mapOf(
                        "id" to scanResult.id,
                        "scanId" to scanResult.scan?.id,
                        "ipAddress" to scanResult.ipAddress,
                        "hostname" to scanResult.hostname,
                        "discoveredAt" to scanResult.discoveredAt.toString(),
                        "portCount" to scanResult.ports.size,
                        "openPorts" to scanResult.ports.filter { it.state == "open" }.map { port ->
                            mapOf(
                                "port" to port.portNumber,
                                "protocol" to port.protocol,
                                "service" to port.service,
                                "version" to port.version
                            )
                        }
                    )
                }

                response["scanHistory"] = mapOf(
                    "items" to scanHistory,
                    "total" to scanPage.totalSize,
                    "shown" to scanHistory.size
                )

                // Latest scan ports summary
                if (scanHistory.isNotEmpty()) {
                    val latestScan = scanPage.content.firstOrNull()
                    latestScan?.let {
                        val openPorts = it.ports.filter { port -> port.state == "open" }
                        val services = openPorts.mapNotNull { port -> port.service }.distinct().sorted()

                        response["currentState"] = mapOf(
                            "lastScanDate" to it.discoveredAt.toString(),
                            "totalOpenPorts" to openPorts.size,
                            "services" to services,
                            "serviceCount" to services.size
                        )
                    }
                }
            }

            return McpToolResult.success(response)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve asset profile: ${e.message}")
        }
    }
}
