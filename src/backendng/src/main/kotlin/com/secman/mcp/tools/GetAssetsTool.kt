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

            // Get accessible asset IDs for access control filtering
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
                    // Access filter and paging both in SQL: Pageable.UNPAGED emits no LIMIT, so
                    // the previous version read every name match into heap before slicing.
                    if (accessibleIds != null) {
                        assetRepository.findByIdInAndNameContainingIgnoreCase(accessibleIds, nameFilter, pageable)
                    } else {
                        assetRepository.findByNameContainingIgnoreCase(nameFilter, pageable)
                    }
                }

                // Type filter with access control
                typeFilter != null -> {
                    if (accessibleIds != null) {
                        assetRepository.findByIdInAndType(accessibleIds, typeFilter, pageable)
                    } else {
                        assetRepository.findByType(typeFilter, pageable)
                    }
                }

                // Owner filter with access control
                ownerFilter != null -> {
                    if (accessibleIds != null) {
                        assetRepository.findByIdInAndOwner(accessibleIds, ownerFilter, pageable)
                    } else {
                        assetRepository.findByOwner(ownerFilter, pageable)
                    }
                }

                // IP filter with access control
                ipFilter != null -> {
                    if (accessibleIds != null) {
                        assetRepository.findByIdInAndIpContainingIgnoreCase(accessibleIds, ipFilter, pageable)
                    } else {
                        assetRepository.findByIpContainingIgnoreCase(ipFilter, pageable)
                    }
                }

                // Group filter with access control
                groupFilter != null -> {
                    if (accessibleIds != null) {
                        assetRepository.findByIdInAndGroupsContaining(accessibleIds, groupFilter, pageable)
                    } else {
                        assetRepository.findByGroupsContaining(groupFilter, pageable)
                    }
                }

                // No filters - get all with access control
                else -> {
                    if (accessibleIds != null) {
                        // Name sort preserved from the Kotlin sortedBy it replaces; it also makes
                        // paging deterministic, which an unordered LIMIT/OFFSET is not.
                        assetRepository.findByIdIn(accessibleIds, pageable.order("name"))
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
                    "uri" to asset.uri,
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
    private fun <T : Any> createManualPage(content: List<T>, total: Int, pageable: Pageable): io.micronaut.data.model.Page<T> {
        return io.micronaut.data.model.Page.of(content, pageable, total.toLong())
    }
}
