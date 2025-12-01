package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AssetRepository
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for retrieving asset inventory with filtering and pagination.
 * Feature 006: MCP Tools for Asset Inventory, Scans, Vulnerabilities, and Products
 * Feature 052: MCP Access Control - Filters results based on delegated user's access rights
 *
 * Enforces:
 * - Max 500 items per page
 * - Max 50,000 total results per query
 * - Permission: ASSETS_READ
 * - Row-level access control when user delegation is active
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

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
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

            // Get accessible asset IDs for access control filtering (Feature: 052-mcp-access-control)
            val accessibleIds = context.getFilterableAssetIds()

            // If delegation is active and user has no accessible assets, return empty result
            if (accessibleIds != null && accessibleIds.isEmpty()) {
                return McpToolResult.success(mapOf(
                    "assets" to emptyList<Map<String, Any?>>(),
                    "total" to 0,
                    "page" to page,
                    "pageSize" to pageSize,
                    "totalPages" to 0
                ))
            }

            // Query based on filters, applying access control where needed
            val resultPage = when {
                // Name filter with access control
                nameFilter != null -> {
                    if (accessibleIds != null) {
                        // Filter by accessible IDs and name
                        val filtered = assetRepository.findByNameContainingIgnoreCase(nameFilter, Pageable.UNPAGED)
                            .content.filter { accessibleIds.contains(it.id) }
                        val total = filtered.size
                        val start = page * pageSize
                        val end = minOf(start + pageSize, total)
                        val content = if (start < total) filtered.subList(start, end) else emptyList()
                        createManualPage(content, total, pageable)
                    } else {
                        assetRepository.findByNameContainingIgnoreCase(nameFilter, pageable)
                    }
                }

                // Type filter with access control
                typeFilter != null -> {
                    val allByType = assetRepository.findByType(typeFilter)
                    val filtered = if (accessibleIds != null) {
                        allByType.filter { accessibleIds.contains(it.id) }
                    } else allByType
                    val total = filtered.size
                    val start = page * pageSize
                    val end = minOf(start + pageSize, total)
                    val content = if (start < total) filtered.subList(start, end) else emptyList()
                    createManualPage(content, total, pageable)
                }

                // Owner filter with access control
                ownerFilter != null -> {
                    val allByOwner = assetRepository.findByOwner(ownerFilter)
                    val filtered = if (accessibleIds != null) {
                        allByOwner.filter { accessibleIds.contains(it.id) }
                    } else allByOwner
                    val total = filtered.size
                    val start = page * pageSize
                    val end = minOf(start + pageSize, total)
                    val content = if (start < total) filtered.subList(start, end) else emptyList()
                    createManualPage(content, total, pageable)
                }

                // IP filter with access control
                ipFilter != null -> {
                    val allByIp = assetRepository.findByIpContainingIgnoreCase(ipFilter)
                    val filtered = if (accessibleIds != null) {
                        allByIp.filter { accessibleIds.contains(it.id) }
                    } else allByIp
                    val total = filtered.size
                    val start = page * pageSize
                    val end = minOf(start + pageSize, total)
                    val content = if (start < total) filtered.subList(start, end) else emptyList()
                    createManualPage(content, total, pageable)
                }

                // Group filter with access control
                groupFilter != null -> {
                    val allByGroup = assetRepository.findByGroupsContaining(groupFilter)
                    val filtered = if (accessibleIds != null) {
                        allByGroup.filter { accessibleIds.contains(it.id) }
                    } else allByGroup
                    val total = filtered.size
                    val start = page * pageSize
                    val end = minOf(start + pageSize, total)
                    val content = if (start < total) filtered.subList(start, end) else emptyList()
                    createManualPage(content, total, pageable)
                }

                // No filters - get all with access control
                else -> {
                    if (accessibleIds != null) {
                        // Get only accessible assets
                        val allAccessible = assetRepository.findAll()
                            .filter { accessibleIds.contains(it.id) }
                            .sortedBy { it.name }
                        val total = allAccessible.size
                        val start = page * pageSize
                        val end = minOf(start + pageSize, total)
                        val content = if (start < total) allAccessible.subList(start, end) else emptyList()
                        createManualPage(content, total, pageable)
                    } else {
                        assetRepository.findAll(pageable)
                    }
                }
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
