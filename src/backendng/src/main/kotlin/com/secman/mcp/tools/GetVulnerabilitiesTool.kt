package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.repository.VulnerabilityRepository
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * MCP tool for retrieving vulnerability data with filtering and pagination.
 * Feature 006: MCP Tools for Asset Inventory, Scans, Vulnerabilities, and Products
 */
@Singleton
class GetVulnerabilitiesTool(
    @Inject private val vulnerabilityRepository: VulnerabilityRepository
) : McpTool {

    override val name = "get_vulnerabilities"
    override val description = "Retrieve vulnerability data with optional filtering and pagination"
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
            "cveId" to mapOf(
                "type" to "string",
                "description" to "Filter by CVE ID (partial match, case-insensitive)"
            ),
            "severity" to mapOf(
                "type" to "array",
                "description" to "Filter by CVSS severity levels",
                "items" to mapOf("type" to "string"),
                "enum" to listOf("Critical", "High", "Medium", "Low", "Info")
            ),
            "assetId" to mapOf(
                "type" to "number",
                "description" to "Filter by asset ID"
            ),
            "startDate" to mapOf(
                "type" to "string",
                "description" to "Filter vulnerabilities scanned after this date (ISO-8601)",
                "format" to "date-time"
            ),
            "endDate" to mapOf(
                "type" to "string",
                "description" to "Filter vulnerabilities scanned before this date (ISO-8601)",
                "format" to "date-time"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): McpToolResult {
        val page = (arguments["page"] as? Number)?.toInt() ?: 0
        val pageSize = (arguments["pageSize"] as? Number)?.toInt() ?: 100
        val cveIdFilter = arguments["cveId"] as? String
        val severityFilter = arguments["severity"] as? List<*>
        val assetIdFilter = (arguments["assetId"] as? Number)?.toLong()
        val startDateStr = arguments["startDate"] as? String
        val endDateStr = arguments["endDate"] as? String

        // Validate parameters
        if (pageSize < 1 || pageSize > 500) {
            return McpToolResult.error("INVALID_PAGE_SIZE", "Page size must be between 1 and 500")
        }
        if (page < 0) {
            return McpToolResult.error("INVALID_PAGE", "Page number must be 0 or greater")
        }

        try {
            val pageable = Pageable.from(page, pageSize)

            // Parse dates if provided
            val startDate = startDateStr?.let { LocalDateTime.parse(it, DateTimeFormatter.ISO_DATE_TIME) }
            val endDate = endDateStr?.let { LocalDateTime.parse(it, DateTimeFormatter.ISO_DATE_TIME) }

            // Query based on filters (priority order)
            val resultPage = when {
                // CVE ID search
                cveIdFilter != null -> vulnerabilityRepository.findByVulnerabilityIdContainingIgnoreCase(cveIdFilter, pageable)

                // Asset-specific vulnerabilities
                assetIdFilter != null -> vulnerabilityRepository.findByAssetId(assetIdFilter, pageable)

                // Date range filter
                startDate != null && endDate != null -> vulnerabilityRepository.findByScanTimestampBetween(startDate, endDate, pageable)

                // Severity filter (array)
                severityFilter != null && severityFilter.isNotEmpty() -> {
                    val severities = severityFilter.mapNotNull { it as? String }
                    if (severities.size == 1) {
                        vulnerabilityRepository.findByCvssSeverity(severities.first(), pageable)
                    } else {
                        vulnerabilityRepository.findByCvssSeverityIn(severities, pageable)
                    }
                }

                // No filters - get all
                else -> vulnerabilityRepository.findAll(pageable)
            }

            // Check total results limit
            if (resultPage.totalSize > 50000) {
                return McpToolResult.error(
                    "TOTAL_RESULTS_EXCEEDED",
                    "Query would return more than 50,000 results. Please add more filters.",
                    mapOf("totalResults" to resultPage.totalSize)
                )
            }

            // Map vulnerabilities to response format
            val vulnerabilities = resultPage.content.map { vuln ->
                mapOf(
                    "id" to vuln.id,
                    "assetId" to vuln.asset?.id,
                    "assetName" to vuln.asset?.name,
                    "vulnerabilityId" to vuln.vulnerabilityId,
                    "cvssSeverity" to vuln.cvssSeverity,
                    "vulnerableProductVersions" to vuln.vulnerableProductVersions,
                    "daysOpen" to vuln.daysOpen,
                    "scanTimestamp" to vuln.scanTimestamp?.toString(),
                    "createdAt" to vuln.createdAt?.toString()
                )
            }

            val response = mapOf(
                "vulnerabilities" to vulnerabilities,
                "total" to resultPage.totalSize,
                "page" to page,
                "pageSize" to pageSize,
                "totalPages" to resultPage.totalPages
            )

            return McpToolResult.success(response)

        } catch (e: java.time.format.DateTimeParseException) {
            return McpToolResult.error("INVALID_DATE_FORMAT", "Date must be in ISO-8601 format: ${e.message}")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve vulnerabilities: ${e.message}")
        }
    }
}
