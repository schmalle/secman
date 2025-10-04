package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.repository.ScanRepository
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * MCP tool for retrieving scan history with filtering and pagination.
 * Feature 006: MCP Tools for Asset Inventory, Scans, Vulnerabilities, and Products
 */
@Singleton
class GetScansTool(
    @Inject private val scanRepository: ScanRepository
) : McpTool {

    override val name = "get_scans"
    override val description = "Retrieve scan history with optional filtering and pagination"
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
            "scanType" to mapOf(
                "type" to "string",
                "description" to "Filter by scan type",
                "enum" to listOf("nmap", "masscan")
            ),
            "uploadedBy" to mapOf(
                "type" to "string",
                "description" to "Filter by uploader username"
            ),
            "startDate" to mapOf(
                "type" to "string",
                "description" to "Filter scans after this date (ISO-8601)",
                "format" to "date-time"
            ),
            "endDate" to mapOf(
                "type" to "string",
                "description" to "Filter scans before this date (ISO-8601)",
                "format" to "date-time"
            )
        )
    )

    override suspend fun execute(arguments: Map<String, Any>): McpToolResult {
        val page = (arguments["page"] as? Number)?.toInt() ?: 0
        val pageSize = (arguments["pageSize"] as? Number)?.toInt() ?: 100
        val scanTypeFilter = arguments["scanType"] as? String
        val uploadedByFilter = arguments["uploadedBy"] as? String
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

            // Query based on filters
            val resultPage = when {
                startDate != null && endDate != null -> scanRepository.findByScanDateBetween(startDate, endDate, pageable)
                scanTypeFilter != null -> scanRepository.findByScanType(scanTypeFilter, pageable)
                uploadedByFilter != null -> scanRepository.findByUploadedByOrderByScanDateDesc(uploadedByFilter, pageable)
                else -> scanRepository.findAllOrderByScanDateDesc(pageable)
            }

            // Check total results limit
            if (resultPage.totalSize > 50000) {
                return McpToolResult.error(
                    "TOTAL_RESULTS_EXCEEDED",
                    "Query would return more than 50,000 results. Please add more filters.",
                    mapOf("totalResults" to resultPage.totalSize)
                )
            }

            // Map scans to response format
            val scans = resultPage.content.map { scan ->
                mapOf(
                    "id" to scan.id,
                    "scanType" to scan.scanType,
                    "filename" to scan.filename,
                    "scanDate" to scan.scanDate?.toString(),
                    "uploadedBy" to scan.uploadedBy,
                    "hostCount" to scan.hostCount,
                    "duration" to scan.duration,
                    "createdAt" to scan.createdAt?.toString()
                )
            }

            val response = mapOf(
                "scans" to scans,
                "total" to resultPage.totalSize,
                "page" to page,
                "pageSize" to pageSize,
                "totalPages" to resultPage.totalPages
            )

            return McpToolResult.success(response)

        } catch (e: java.time.format.DateTimeParseException) {
            return McpToolResult.error("INVALID_DATE_FORMAT", "Date must be in ISO-8601 format: ${e.message}")
        } catch (e: Exception) {
            return McpToolResult.error("EXECUTION_ERROR", "Failed to retrieve scans: ${e.message}")
        }
    }
}
