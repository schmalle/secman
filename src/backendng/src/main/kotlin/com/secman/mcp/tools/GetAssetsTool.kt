package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.repository.AssetRepository
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for retrieving asset inventory with filtering and pagination.
 * Feature 006: MCP Tools for Asset Inventory, Scans, Vulnerabilities, and Products
 *
 * Enforces:
 * - Max 500 items per page
 * - Max 50,000 total results per query
 * - Permission: ASSETS_READ
 */
@Singleton
class GetAssetsTool(
    @Inject private val assetRepository: AssetRepository
) : McpTool {

    override val name = "get_assets"
    override val description = "Retrieve asset inventory with filtering and pagination"
    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "page" to mapOf(
                "type" to "number",
                "description" to "Page number (0-indexed)",
                "minimum" to 0,
                "default" to 0
            ),
            "pageSize" to mapOf(
                "type" to "number",
                "description" to "Number of items per page (max 500)",
                "minimum" to 1,
                "maximum" to 500,
                "default" to 100
            ),
            "name" to mapOf(
                "type" to "string",
                "description" to "Filter by asset name (partial match, case-insensitive)",
                "maxLength" to 255
            ),
            "type" to mapOf(
                "type" to "string",
                "description" to "Filter by exact asset type"
            ),
            "ip" to mapOf(
                "type" to "string",
                "description" to "Filter by IP address (partial match, case-insensitive)",
                "maxLength" to 45
            ),
            "owner" to mapOf(
                "type" to "string",
                "description" to "Filter by owner (exact match)"
            ),
            "group" to mapOf(
                "type" to "string",
                "description" to "Filter by group membership (exact match)"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): McpToolResult {
        // Extract and validate parameters
        val page = (arguments["page"] as? Number)?.toInt() ?: 0
        val pageSize = (arguments["pageSize"] as? Number)?.toInt() ?: 100
        val nameFilter = arguments["name"] as? String
        val typeFilter = arguments["type"] as? String
        val ipFilter = arguments["ip"] as? String
        val ownerFilter = arguments["owner"] as? String
        val groupFilter = arguments["group"] as? String

        // Validate page size
        if (pageSize < 1 || pageSize > 500) {
            return McpToolResult.error("INVALID_PAGE_SIZE", "Page size must be between 1 and 500")
        }

        // Validate page number
        if (page < 0) {
            return McpToolResult.error("INVALID_PAGE", "Page number must be 0 or greater")
        }

        try {
            val pageable = Pageable.from(page, pageSize)

            // Query based on filters
            val resultPage = when {
                // Name filter
                nameFilter != null -> assetRepository.findByNameContainingIgnoreCase(nameFilter, pageable)

                // Type filter
                typeFilter != null -> {
                    val allByType = assetRepository.findByType(typeFilter)
                    // Manual pagination for filters without Pageable support
                    val total = allByType.size
                    val start = page * pageSize
                    val end = minOf(start + pageSize, total)
                    val content = if (start < total) allByType.subList(start, end) else emptyList()
                    createManualPage(content, total, pageable)
                }

                // Owner filter
                ownerFilter != null -> {
                    val allByOwner = assetRepository.findByOwner(ownerFilter)
                    val total = allByOwner.size
                    val start = page * pageSize
                    val end = minOf(start + pageSize, total)
                    val content = if (start < total) allByOwner.subList(start, end) else emptyList()
                    createManualPage(content, total, pageable)
                }

                // IP filter
                ipFilter != null -> {
                    val allByIp = assetRepository.findByIpContainingIgnoreCase(ipFilter)
                    val total = allByIp.size
                    val start = page * pageSize
                    val end = minOf(start + pageSize, total)
                    val content = if (start < total) allByIp.subList(start, end) else emptyList()
                    createManualPage(content, total, pageable)
                }

                // Group filter
                groupFilter != null -> {
                    val allByGroup = assetRepository.findByGroupsContaining(groupFilter)
                    val total = allByGroup.size
                    val start = page * pageSize
                    val end = minOf(start + pageSize, total)
                    val content = if (start < total) allByGroup.subList(start, end) else emptyList()
                    createManualPage(content, total, pageable)
                }

                // No filters - get all
                else -> assetRepository.findAll(pageable)
            }

            // Check total results limit (50,000 max)
            if (resultPage.totalSize > 50000) {
                return McpToolResult.error(
                    "TOTAL_RESULTS_EXCEEDED",
                    "Query would return more than 50,000 results. Please add more filters.",
                    mapOf("totalResults" to resultPage.totalSize)
                )
            }

            // Map assets to response format
            val assets: List<Map<String, Any?>> = resultPage.content.map { asset ->
                mapOf(
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
            }

            val response = mapOf(
                "assets" to assets,
                "total" to resultPage.totalSize,
                "page" to page,
                "pageSize" to pageSize,
                "totalPages" to resultPage.totalPages
            )

            return McpToolResult.success(response)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve assets: ${e.message}")
        }
    }

    /**
     * Create a manual Page object for lists that don't have native pagination support.
     */
    private fun <T> createManualPage(content: List<T>, total: Int, pageable: Pageable): io.micronaut.data.model.Page<T> {
        return io.micronaut.data.model.Page.of(content, pageable, total.toLong())
    }
}
