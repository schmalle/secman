package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.service.AssetFilterService
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP Tool: get_all_assets_detail
 *
 * Retrieves all assets with optional filtering and pagination.
 * Feature 009: Enhanced asset retrieval with comprehensive filtering
 * Feature 052: MCP Access Control - Filters results based on delegated user's access rights
 */
@Singleton
class GetAllAssetsDetailTool(
    @Inject private val assetRepository: com.secman.repository.AssetRepository
) : McpTool {

    override val name = "get_all_assets_detail"

    override val description = """
        Retrieve all assets with optional filtering and pagination.

        Supports filtering by:
        - name: Asset name (case-insensitive contains)
        - type: Asset type (exact match, e.g., "SERVER", "CLIENT", "NETWORK_DEVICE")
        - ip: IP address (contains)
        - owner: Owner name (contains)
        - group: Group membership (exact match)

        Pagination:
        - page: Page number (0-indexed, default=0)
        - pageSize: Items per page (default=100, max=1000)

        Access control: Returns only assets from user's workgroups plus assets created/uploaded by user.
    """.trimIndent()

    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf(
                "type" to "string",
                "description" to "Filter by asset name (case-insensitive contains)"
            ),
            "type" to mapOf(
                "type" to "string",
                "description" to "Filter by asset type (exact match, e.g., SERVER, CLIENT, NETWORK_DEVICE)"
            ),
            "ip" to mapOf(
                "type" to "string",
                "description" to "Filter by IP address (contains)"
            ),
            "owner" to mapOf(
                "type" to "string",
                "description" to "Filter by owner name (contains)"
            ),
            "group" to mapOf(
                "type" to "string",
                "description" to "Filter by group membership (exact match)"
            ),
            "page" to mapOf(
                "type" to "integer",
                "description" to "Page number (0-indexed, default=0)",
                "minimum" to 0,
                "default" to 0
            ),
            "pageSize" to mapOf(
                "type" to "integer",
                "description" to "Items per page (default=100, max=1000)",
                "minimum" to 1,
                "maximum" to 1000,
                "default" to 100
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        try {
            // Extract and validate parameters
            val page = (arguments["page"] as? Number)?.toInt() ?: 0
            val pageSize = (arguments["pageSize"] as? Number)?.toInt() ?: 100
            val nameFilter = arguments["name"] as? String
            val typeFilter = arguments["type"] as? String
            val ipFilter = arguments["ip"] as? String
            val ownerFilter = arguments["owner"] as? String
            val groupFilter = arguments["group"] as? String

            // Validate page size
            if (pageSize < 1 || pageSize > 1000) {
                return McpToolResult.error(
                    "INVALID_PAGE_SIZE",
                    "Page size must be between 1 and 1000"
                )
            }

            // Validate page number
            if (page < 0) {
                return McpToolResult.error(
                    "INVALID_PAGE",
                    "Page number must be 0 or greater"
                )
            }

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
                    "totalPages" to 0,
                    "hasMore" to false
                ))
            }

            // Get all assets with access control applied
            val resultPage = when {
                // Access filter and paging both run in SQL. Reading the whole asset table with
                // findAll() (or every name match with Pageable.UNPAGED, which emits no LIMIT) and
                // slicing in Kotlin made cost grow with the table rather than the requested page.
                nameFilter != null -> {
                    if (accessibleIds != null) {
                        assetRepository.findByIdInAndNameContainingIgnoreCase(accessibleIds, nameFilter, pageable)
                    } else {
                        assetRepository.findByNameContainingIgnoreCase(nameFilter, pageable)
                    }
                }
                // type / ip / owner / group were parsed from the arguments and then never used,
                // so this tool silently ignored four of the five filters its own description
                // advertises — `type=SERVER` returned every asset. Same priority order as
                // get_assets so the two tools agree when several filters are supplied.
                typeFilter != null -> {
                    if (accessibleIds != null) {
                        assetRepository.findByIdInAndType(accessibleIds, typeFilter, pageable)
                    } else {
                        assetRepository.findByType(typeFilter, pageable)
                    }
                }
                ownerFilter != null -> {
                    if (accessibleIds != null) {
                        assetRepository.findByIdInAndOwner(accessibleIds, ownerFilter, pageable)
                    } else {
                        assetRepository.findByOwner(ownerFilter, pageable)
                    }
                }
                ipFilter != null -> {
                    if (accessibleIds != null) {
                        assetRepository.findByIdInAndIpContainingIgnoreCase(accessibleIds, ipFilter, pageable)
                    } else {
                        assetRepository.findByIpContainingIgnoreCase(ipFilter, pageable)
                    }
                }
                groupFilter != null -> {
                    if (accessibleIds != null) {
                        assetRepository.findByIdInAndGroupsContaining(accessibleIds, groupFilter, pageable)
                    } else {
                        assetRepository.findByGroupsContaining(groupFilter, pageable)
                    }
                }
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

            // Check total results limit
            if (resultPage.totalSize > 100_000) {
                return McpToolResult.error(
                    "TOTAL_RESULTS_EXCEEDED",
                    "Query would return more than 100,000 results. Please add more filters.",
                    mapOf("totalResults" to resultPage.totalSize)
                )
            }

            // Re-read this page's assets with their workgroups fetch-joined. Reading the lazy
            // `workgroups` collection off a detached entity threw "no session" and made this tool
            // fail for every caller, admin included. Bounded to one page, and deliberately
            // unpaginated — a collection fetch join plus a Pageable paginates in memory.
            val pageAssetIds = resultPage.content.mapNotNull { it.id }
            val workgroupsByAssetId: Map<Long, List<Map<String, Any?>>> = if (pageAssetIds.isEmpty()) {
                emptyMap()
            } else {
                assetRepository.findAllWithWorkgroups(pageAssetIds).associate { hydrated ->
                    hydrated.id!! to hydrated.workgroups.map { mapOf("id" to it.id, "name" to it.name) }
                }
            }

            // Map assets to response format
            val assets = resultPage.content.map { asset ->
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
                    "updatedAt" to asset.updatedAt?.toString(),
                    "workgroups" to (workgroupsByAssetId[asset.id] ?: emptyList())
                )
            }

            val response = mapOf(
                "assets" to assets,
                "total" to resultPage.totalSize,
                "page" to page,
                "pageSize" to pageSize,
                "totalPages" to resultPage.totalPages,
                "hasMore" to (page < resultPage.totalPages - 1)
            )

            return McpToolResult.success(response)

        } catch (e: IllegalArgumentException) {
            return McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid input parameters")
        } catch (e: Exception) {
            return McpToolResult.error("INTERNAL_ERROR", "Failed to retrieve assets: ${e.message}")
        }
    }
}
