package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.repository.ScanPortRepository
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP Tool: get_asset_scan_results
 *
 * Retrieves scan ports (open ports, services) with optional filtering and pagination.
 * Provides detailed port-level information from network scans.
 *
 * Feature 009: Enhanced scan result retrieval
 */
@Singleton
class GetAssetScanResultsTool(
    @Inject private val scanPortRepository: ScanPortRepository
) : McpTool {

    override val name = "get_asset_scan_results"

    override val description = """
        Retrieve scan results (open ports, services, products) with optional filtering and pagination.

        Supports filtering by:
        - portMin, portMax: Port range (1-65535)
        - service: Service name (contains)
        - state: Port state (open, filtered, closed)

        Pagination:
        - page: Page number (0-indexed, default=0)
        - pageSize: Items per page (default=100, max=1000)
    """.trimIndent()

    override val operation = McpOperation.READ

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "portMin" to mapOf(
                "type" to "integer",
                "description" to "Minimum port number (1-65535)",
                "minimum" to 1,
                "maximum" to 65535
            ),
            "portMax" to mapOf(
                "type" to "integer",
                "description" to "Maximum port number (1-65535)",
                "minimum" to 1,
                "maximum" to 65535
            ),
            "service" to mapOf(
                "type" to "string",
                "description" to "Filter by service name (case-insensitive contains)"
            ),
            "state" to mapOf(
                "type" to "string",
                "description" to "Filter by port state",
                "enum" to listOf("open", "filtered", "closed")
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

    override suspend fun execute(arguments: Map<String, Any>): McpToolResult {
        try {
            // Extract and validate parameters
            val page = (arguments["page"] as? Number)?.toInt() ?: 0
            val pageSize = (arguments["pageSize"] as? Number)?.toInt() ?: 100
            val portMin = (arguments["portMin"] as? Number)?.toInt()
            val portMax = (arguments["portMax"] as? Number)?.toInt()
            val serviceFilter = arguments["service"] as? String
            val stateFilter = arguments["state"] as? String

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

            // Validate port range
            if (portMin != null && (portMin < 1 || portMin > 65535)) {
                return McpToolResult.error(
                    "INVALID_PORT_MIN",
                    "Port minimum must be between 1 and 65535"
                )
            }
            if (portMax != null && (portMax < 1 || portMax > 65535)) {
                return McpToolResult.error(
                    "INVALID_PORT_MAX",
                    "Port maximum must be between 1 and 65535"
                )
            }

            val pageable = Pageable.from(page, pageSize)

            // Query based on filters
            val resultPage = when {
                serviceFilter != null -> {
                    scanPortRepository.findByServiceContainingIgnoreCase(serviceFilter, pageable)
                }
                else -> {
                    scanPortRepository.findAll(pageable)
                }
            }

            // Apply filters (post-query filtering for simplicity)
            var filteredContent: List<com.secman.domain.ScanPort> = resultPage.content
            if (portMin != null) {
                filteredContent = filteredContent.filter { it.portNumber >= portMin }
            }
            if (portMax != null) {
                filteredContent = filteredContent.filter { it.portNumber <= portMax }
            }
            if (stateFilter != null) {
                filteredContent = filteredContent.filter { it.state.equals(stateFilter, ignoreCase = true) }
            }

            // Check total results limit
            if (resultPage.totalSize > 100_000) {
                return McpToolResult.error(
                    "TOTAL_RESULTS_EXCEEDED",
                    "Query would return more than 100,000 results. Please add more filters.",
                    mapOf("totalResults" to resultPage.totalSize)
                )
            }

            // Map scan ports to response format
            val scanResults = filteredContent.map { scanPort ->
                mapOf(
                    "id" to scanPort.id,
                    "portNumber" to scanPort.portNumber,
                    "protocol" to scanPort.protocol,
                    "state" to scanPort.state,
                    "service" to scanPort.service,
                    "version" to scanPort.version,
                    "asset" to mapOf(
                        "id" to scanPort.scanResult.asset.id,
                        "name" to scanPort.scanResult.asset.name,
                        "ip" to scanPort.scanResult.asset.ip
                    ),
                    "discoveredAt" to scanPort.scanResult.discoveredAt.toString()
                )
            }

            val response = mapOf(
                "scanResults" to scanResults,
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
            return McpToolResult.error("INTERNAL_ERROR", "Failed to retrieve scan results: ${e.message}")
        }
    }
}
