package com.secman.service

import com.secman.domain.ExceptionRequestStatus
import com.secman.repository.VulnerabilityExceptionRequestRepository
import io.micronaut.data.model.Pageable
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Service for exporting vulnerability exception requests to Excel.
 *
 * **Export Features**:
 * - Streams large datasets using SXSSFWorkbook (100-row memory window)
 * - Filters by status, date range, requester, reviewer
 * - Professional formatting with headers and styles
 * - Fixed column widths for consistent appearance
 *
 * **Excel Format**:
 * - Request ID, CVE ID, Asset Name, Asset IP, Requester, Submission Date
 * - Status, Reviewer, Review Date, Reason, Review Comment, Expiration Date, Auto-Approved
 *
 * Feature: 031-vuln-exception-approval
 * User Story 8: Analytics & Reporting (P3)
 * Phase 11: Analytics & Reporting
 * Reference: spec.md FR-026, acceptance scenario US8-3
 */
@Singleton
open class ExceptionRequestExportService(
    @Inject private val requestRepository: VulnerabilityExceptionRequestRepository
) {
    private val logger = LoggerFactory.getLogger(ExceptionRequestExportService::class.java)

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val DATE_ONLY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    /**
     * Export exception requests to Excel with optional filtering.
     *
     * @param status Optional status filter
     * @param dateRange Optional date range (7days, 30days, 90days, alltime)
     * @param requesterId Optional requester user ID filter
     * @param reviewerId Optional reviewer user ID filter
     * @return ByteArrayOutputStream containing Excel workbook
     */
    open fun exportToExcel(
        status: ExceptionRequestStatus? = null,
        dateRange: String? = null,
        requesterId: Long? = null,
        reviewerId: Long? = null
    ): ByteArrayOutputStream {
        logger.info("Exporting exception requests: status={}, dateRange={}, requesterId={}, reviewerId={}",
            status, dateRange, requesterId, reviewerId)

        // Get filtered requests
        val requests = getFilteredRequests(status, dateRange, requesterId, reviewerId)

        logger.debug("Exporting {} exception requests to Excel", requests.size)

        // Create streaming workbook with 100-row window
        val workbook = SXSSFWorkbook(100)
        workbook.setCompressTempFiles(true)

        try {
            val sheet = workbook.createSheet("Exception Requests")

            // Create styles once and reuse (prevent 64K style limit)
            val styles = createStyles(workbook)

            // Create header row
            createHeaderRow(sheet, styles.header)

            // Create data rows
            var rowNum = 1
            for (request in requests) {
                createDataRow(sheet, rowNum++, request, styles.normal)
            }

            // Set column widths (fixed widths for performance)
            setColumnWidths(sheet)

            // Write to output stream
            val outputStream = ByteArrayOutputStream()
            workbook.write(outputStream)

            logger.info("Excel export completed: {} rows written", requests.size)

            return outputStream

        } finally {
            // CRITICAL: Close workbook to clean up temp files
            workbook.close()
            logger.debug("SXSSFWorkbook closed, temp files cleaned")
        }
    }

    /**
     * Get filtered exception requests based on parameters.
     */
    private fun getFilteredRequests(
        status: ExceptionRequestStatus?,
        dateRange: String?,
        requesterId: Long?,
        reviewerId: Long?
    ): List<com.secman.domain.VulnerabilityExceptionRequest> {
        // Calculate date range start
        val since = when (dateRange?.lowercase()) {
            "7days" -> LocalDateTime.now().minusDays(7)
            "30days" -> LocalDateTime.now().minusDays(30)
            "90days" -> LocalDateTime.now().minusDays(90)
            "alltime", null -> null
            else -> LocalDateTime.now().minusDays(30)
        }

        // Build filter query
        return when {
            // Status and date filter
            status != null && since != null -> {
                requestRepository.findByStatusAndCreatedAtAfter(status, since)
            }
            // Status only
            status != null -> {
                requestRepository.findByStatus(status, Pageable.UNPAGED).content
            }
            // Date only
            since != null -> {
                requestRepository.findByCreatedAtAfter(since)
            }
            // No filters - all requests
            else -> {
                requestRepository.findAll().toList()
            }
        }.filter { request ->
            // Apply additional filters (requester, reviewer)
            val matchesRequester = requesterId == null || request.requestedByUser?.id == requesterId
            val matchesReviewer = reviewerId == null || request.reviewedByUser?.id == reviewerId
            matchesRequester && matchesReviewer
        }
    }

    /**
     * Create header row with column titles.
     */
    private fun createHeaderRow(sheet: Sheet, headerStyle: CellStyle) {
        val headerRow = sheet.createRow(0)

        val headers = listOf(
            "Request ID",
            "CVE ID",
            "Asset Name",
            "Asset IP",
            "Requester",
            "Submission Date",
            "Status",
            "Reviewer",
            "Review Date",
            "Reason",
            "Review Comment",
            "Expiration Date",
            "Auto-Approved"
        )

        headers.forEachIndexed { index, title ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(title)
            cell.cellStyle = headerStyle
        }
    }

    /**
     * Create data row for an exception request.
     */
    private fun createDataRow(
        sheet: Sheet,
        rowNum: Int,
        request: com.secman.domain.VulnerabilityExceptionRequest,
        normalStyle: CellStyle
    ) {
        val row = sheet.createRow(rowNum)

        var colNum = 0

        // Request ID
        row.createCell(colNum++).apply {
            setCellValue(request.id?.toDouble() ?: 0.0)
            cellStyle = normalStyle
        }

        // CVE ID
        row.createCell(colNum++).apply {
            setCellValue(request.vulnerability?.vulnerabilityId ?: "N/A")
            cellStyle = normalStyle
        }

        // Asset Name
        row.createCell(colNum++).apply {
            setCellValue(request.vulnerability?.asset?.name ?: "N/A")
            cellStyle = normalStyle
        }

        // Asset IP
        row.createCell(colNum++).apply {
            setCellValue(request.vulnerability?.asset?.ip ?: "N/A")
            cellStyle = normalStyle
        }

        // Requester
        row.createCell(colNum++).apply {
            setCellValue(request.requestedByUsername)
            cellStyle = normalStyle
        }

        // Submission Date
        row.createCell(colNum++).apply {
            setCellValue(request.createdAt?.format(DATE_FORMATTER) ?: "N/A")
            cellStyle = normalStyle
        }

        // Status
        row.createCell(colNum++).apply {
            setCellValue(request.status.name)
            cellStyle = normalStyle
        }

        // Reviewer
        row.createCell(colNum++).apply {
            setCellValue(request.reviewedByUsername ?: "N/A")
            cellStyle = normalStyle
        }

        // Review Date
        row.createCell(colNum++).apply {
            setCellValue(request.reviewDate?.format(DATE_FORMATTER) ?: "N/A")
            cellStyle = normalStyle
        }

        // Reason
        row.createCell(colNum++).apply {
            setCellValue(request.reason)
            cellStyle = normalStyle
        }

        // Review Comment
        row.createCell(colNum++).apply {
            setCellValue(request.reviewComment ?: "N/A")
            cellStyle = normalStyle
        }

        // Expiration Date
        row.createCell(colNum++).apply {
            setCellValue(request.expirationDate.format(DATE_ONLY_FORMATTER))
            cellStyle = normalStyle
        }

        // Auto-Approved
        row.createCell(colNum++).apply {
            setCellValue(if (request.autoApproved) "Yes" else "No")
            cellStyle = normalStyle
        }
    }

    /**
     * Set fixed column widths for consistent appearance.
     */
    private fun setColumnWidths(sheet: Sheet) {
        sheet.setColumnWidth(0, 3000)  // Request ID
        sheet.setColumnWidth(1, 5000)  // CVE ID
        sheet.setColumnWidth(2, 8000)  // Asset Name
        sheet.setColumnWidth(3, 4000)  // Asset IP
        sheet.setColumnWidth(4, 5000)  // Requester
        sheet.setColumnWidth(5, 5500)  // Submission Date
        sheet.setColumnWidth(6, 3500)  // Status
        sheet.setColumnWidth(7, 5000)  // Reviewer
        sheet.setColumnWidth(8, 5500)  // Review Date
        sheet.setColumnWidth(9, 12000) // Reason
        sheet.setColumnWidth(10, 12000) // Review Comment
        sheet.setColumnWidth(11, 4000) // Expiration Date
        sheet.setColumnWidth(12, 4000) // Auto-Approved
    }

    /**
     * Create cell styles for header and normal cells.
     *
     * IMPORTANT: Create styles once and reuse to avoid exceeding 64K style limit.
     */
    private fun createStyles(workbook: Workbook): ExportStyles {
        // Header style (bold, gray background)
        val headerFont = workbook.createFont().apply {
            bold = true
            fontHeightInPoints = 11
        }
        val headerStyle = workbook.createCellStyle().apply {
            setFont(headerFont)
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            wrapText = false
        }

        // Normal cell style
        val normalStyle = workbook.createCellStyle().apply {
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
            alignment = HorizontalAlignment.LEFT
            verticalAlignment = VerticalAlignment.TOP
            wrapText = true
        }

        return ExportStyles(headerStyle, normalStyle)
    }

    /**
     * Container for reusable cell styles.
     */
    private data class ExportStyles(
        val header: CellStyle,
        val normal: CellStyle
    )
}
