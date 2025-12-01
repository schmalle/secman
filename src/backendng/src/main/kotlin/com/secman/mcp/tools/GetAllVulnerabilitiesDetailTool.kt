package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.VulnerabilityRepository
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP Tool: get_all_vulnerabilities_detail
 *
 * Retrieves vulnerabilities with optional filtering and pagination.
 * Provides detailed vulnerability information including CVE IDs, severity, and affected assets.
 *
 * Feature 009: Enhanced vulnerability retrieval
 * Feature 052: MCP Access Control - Filters results based on delegated user's accessible assets
 */
@Singleton
class GetAllVulnerabilitiesDetailTool(
    @Inject private val vulnerabilityRepository: VulnerabilityRepository
) : McpTool {

    override val name = "get_all_vulnerabilities_detail"

    override val description = """
        Retrieve vulnerabilities with optional filtering and pagination.

        Supports filtering by:
        - severity: Severity level (CRITICAL, HIGH, MEDIUM, LOW)
        - assetId: Specific asset ID
        - minDaysOpen: Minimum number of days vulnerability has been open

        Pagination:
        - page: Page number (0-indexed, default=0)
        - pageSize: Items per page (default=100, max=1000)
    """.trimIndent()

    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "severity" to mapOf(
                "type" to "string",
                "description" to "Filter by severity level",
                "enum" to listOf("CRITICAL", "HIGH", "MEDIUM", "LOW")
            ),
            "assetId" to mapOf(
                "type" to "integer",
                "description" to "Filter by specific asset ID"
            ),
            "minDaysOpen" to mapOf(
                "type" to "integer",
                "description" to "Filter by minimum number of days vulnerability has been open",
                "minimum" to 0
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
            val severityFilter = arguments["severity"] as? String
            val assetIdFilter = (arguments["assetId"] as? Number)?.toLong()
            val minDaysOpen = (arguments["minDaysOpen"] as? Number)?.toInt()

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

            // Validate severity
            if (severityFilter != null && severityFilter !in listOf("CRITICAL", "HIGH", "MEDIUM", "LOW")) {
                return McpToolResult.error(
                    "INVALID_SEVERITY",
                    "Severity must be one of: CRITICAL, HIGH, MEDIUM, LOW"
                )
            }

            val pageable = Pageable.from(page, pageSize)

            // Get accessible asset IDs for access control filtering (Feature: 052-mcp-access-control)
            val accessibleIds = context.getFilterableAssetIds()

            // If delegation is active and user has no accessible assets, return empty result
            if (accessibleIds != null && accessibleIds.isEmpty()) {
                return McpToolResult.success(mapOf(
                    "vulnerabilities" to emptyList<Map<String, Any?>>(),
                    "total" to 0,
                    "page" to page,
                    "pageSize" to pageSize,
                    "totalPages" to 0,
                    "hasMore" to false
                ))
            }

            // If filtering by specific asset, check access first
            if (assetIdFilter != null && !context.canAccessAsset(assetIdFilter)) {
                return McpToolResult.error("ASSET_NOT_FOUND", "Asset with ID $assetIdFilter not found")
            }

            // Query based on filters with access control
            val resultPage = when {
                assetIdFilter != null -> {
                    vulnerabilityRepository.findByAssetId(assetIdFilter, pageable)
                }
                severityFilter != null -> {
                    val allMatching = vulnerabilityRepository.findByCvssSeverity(severityFilter.uppercase(), Pageable.UNPAGED).content
                    val filtered = if (accessibleIds != null) {
                        allMatching.filter { accessibleIds.contains(it.asset.id) }
                    } else allMatching
                    val total = filtered.size
                    val start = page * pageSize
                    val end = minOf(start + pageSize, total)
                    val content = if (start < total) filtered.subList(start, end) else emptyList()
                    io.micronaut.data.model.Page.of(content, pageable, total.toLong())
                }
                else -> {
                    if (accessibleIds != null) {
                        val allAccessible = vulnerabilityRepository.findAll()
                            .filter { accessibleIds.contains(it.asset.id) }
                        val total = allAccessible.size
                        val start = page * pageSize
                        val end = minOf(start + pageSize, total)
                        val content = if (start < total) allAccessible.subList(start, end) else emptyList()
                        io.micronaut.data.model.Page.of(content, pageable, total.toLong())
                    } else {
                        vulnerabilityRepository.findAll(pageable)
                    }
                }
            }

            // Apply minDaysOpen filter if specified (post-query filtering)
            var filteredContent: List<com.secman.domain.Vulnerability> = resultPage.content
            if (minDaysOpen != null) {
                val minDaysOpenValue: Int = minDaysOpen
                filteredContent = filteredContent.filter { vuln ->
                    // daysOpen is a String like "58 days", need to parse it
                    val days = vuln.daysOpen?.split(" ")?.firstOrNull()?.toIntOrNull() ?: 0
                    days >= minDaysOpenValue
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

            // Map vulnerabilities to response format
            val vulnerabilities = filteredContent.map { vuln ->
                mapOf(
                    "id" to vuln.id,
                    "vulnerabilityId" to vuln.vulnerabilityId,
                    "cvssSeverity" to vuln.cvssSeverity,
                    "vulnerableProductVersions" to vuln.vulnerableProductVersions,
                    "daysOpen" to vuln.daysOpen,
                    "scanTimestamp" to vuln.scanTimestamp.toString(),
                    "asset" to mapOf(
                        "id" to vuln.asset.id,
                        "name" to vuln.asset.name,
                        "type" to vuln.asset.type,
                        "ip" to vuln.asset.ip
                    ),
                    "createdAt" to vuln.createdAt?.toString()
                )
            }

            val response = mapOf(
                "vulnerabilities" to vulnerabilities,
                "total" to filteredContent.size,
                "page" to page,
                "pageSize" to pageSize,
                "totalPages" to resultPage.totalPages,
                "hasMore" to (page < resultPage.totalPages - 1)
            )

            return McpToolResult.success(response)

        } catch (e: IllegalArgumentException) {
            return McpToolResult.error("VALIDATION_ERROR", e.message ?: "Invalid input parameters")
        } catch (e: Exception) {
            return McpToolResult.error("INTERNAL_ERROR", "Failed to retrieve vulnerabilities: ${e.message}")
        }
    }

    /**
     * Create a manual Page object for lists that don't have native pagination support.
     */
    private fun <T> createManualPage(content: List<T>, total: Int, pageable: Pageable): io.micronaut.data.model.Page<T> {
        return io.micronaut.data.model.Page.of(content, pageable, total.toLong())
    }
}
