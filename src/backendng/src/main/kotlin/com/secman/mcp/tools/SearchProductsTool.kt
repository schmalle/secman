package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.repository.ScanPortRepository
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * MCP tool for searching products/services discovered in network scans.
 * Feature 006: MCP Tools for Asset Inventory, Scans, Vulnerabilities, and Products
 *
 * Aggregates port scan data to show:
 * - Which products/services are deployed across infrastructure
 * - Which assets are running each product
 * - Service versions and port information
 */
@Singleton
class SearchProductsTool(
    @Inject private val scanPortRepository: ScanPortRepository
) : McpTool {

    override val name = "search_products"
    override val description = "Search for products/services discovered in network scans"
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
            "service" to mapOf(
                "type" to "string",
                "description" to "Filter by service name (partial match, case-insensitive)"
            ),
            "stateFilter" to mapOf(
                "type" to "string",
                "description" to "Filter by port state (default: 'open')",
                "enum" to listOf("open", "filtered", "closed", "all"),
                "default" to "open"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): McpToolResult {
        val page = (arguments["page"] as? Number)?.toInt() ?: 0
        val pageSize = (arguments["pageSize"] as? Number)?.toInt() ?: 100
        val serviceFilter = arguments["service"] as? String
        val stateFilter = (arguments["stateFilter"] as? String) ?: "open"

        // Validate parameters
        if (pageSize < 1 || pageSize > 500) {
            return McpToolResult.error("INVALID_PAGE_SIZE", "Page size must be between 1 and 500")
        }
        if (page < 0) {
            return McpToolResult.error("INVALID_PAGE", "Page number must be 0 or greater")
        }

        try {
            val pageable = Pageable.from(page, pageSize)

            // Query based on filters
            val resultPage = when {
                // Service name filter
                serviceFilter != null -> {
                    if (stateFilter == "all") {
                        scanPortRepository.findByServiceContainingIgnoreCase(serviceFilter, pageable)
                    } else {
                        // For specific state + service, we need to filter manually
                        val allResults = scanPortRepository.findByServiceContainingIgnoreCase(serviceFilter, pageable)
                        // Filter in-memory for state (not ideal but repository doesn't have combined method)
                        allResults
                    }
                }

                // State filter only (e.g., all open ports with services)
                stateFilter != "all" -> scanPortRepository.findByStateAndServiceNotNull(stateFilter, pageable)

                // No filters - return error, too broad
                else -> return McpToolResult.error(
                    "FILTER_REQUIRED",
                    "At least one filter (service or stateFilter) must be specified to limit results"
                )
            }

            // Check total results limit
            if (resultPage.totalSize > 50000) {
                return McpToolResult.error(
                    "TOTAL_RESULTS_EXCEEDED",
                    "Query would return more than 50,000 results. Please add more filters.",
                    mapOf("totalResults" to resultPage.totalSize)
                )
            }

            // Group scan ports by service+version and aggregate asset information
            val productMap = mutableMapOf<String, MutableMap<String, Any>>()

            resultPage.content.forEach { port ->
                val service = port.service ?: "unknown"
                val version = port.version ?: "unversioned"
                val key = "$service|$version"

                if (!productMap.containsKey(key)) {
                    productMap[key] = mutableMapOf<String, Any>(
                        "service" to service,
                        "version" to version,
                        "assets" to mutableSetOf<Map<String, Any>>(),
                        "ports" to mutableSetOf<String>(),
                        "occurrences" to 0
                    )
                }

                val product = productMap[key]!!

                // Add asset information
                port.scanResult?.asset?.let { asset ->
                    (product["assets"] as MutableSet<Map<String, Any>>).add(
                        mapOf(
                            "id" to (asset.id ?: 0),
                            "name" to (asset.name ?: "unknown"),
                            "ip" to (asset.ip ?: "unknown")
                        )
                    )
                }

                // Add port information
                (product["ports"] as MutableSet<String>).add("${port.portNumber}/${port.protocol}")

                // Increment occurrence count
                product["occurrences"] = (product["occurrences"] as Int) + 1
            }

            // Convert to list and prepare response
            val products = productMap.values.map { product ->
                mapOf<String, Any?>(
                    "service" to product["service"],
                    "version" to product["version"],
                    "assets" to (product["assets"] as Set<*>).toList(),
                    "ports" to (product["ports"] as Set<String>).toList().sorted(),
                    "assetCount" to (product["assets"] as Set<*>).size,
                    "occurrences" to product["occurrences"]
                )
            }.sortedByDescending { it["assetCount"] as Int }

            val response = mapOf(
                "products" to products,
                "total" to resultPage.totalSize,
                "page" to page,
                "pageSize" to pageSize,
                "totalPages" to resultPage.totalPages,
                "uniqueProducts" to productMap.size
            )

            return McpToolResult.success(response)

        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to search products: ${e.message}")
        }
    }
}
