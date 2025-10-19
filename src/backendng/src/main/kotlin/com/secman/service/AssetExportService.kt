package com.secman.service

import com.secman.domain.Asset
import com.secman.dto.AssetExportDto
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter

/**
 * Service for exporting assets to Excel format
 * Feature: 029-asset-bulk-operations (User Story 2 - Export Assets to File)
 *
 * Related Requirements:
 * - FR-010: Export assets to Excel with all fields
 * - FR-011: Apply workgroup-based access control to exports
 * - FR-012: Format export file with clear column headers
 * - FR-013: Include workgroup names in readable format
 * - FR-015: Handle empty asset list with error message
 *
 * Performance Target:
 * - Export 10K assets in <15 seconds (SC-002)
 *
 * Technical Approach:
 * - Use SXSSFWorkbook(100) for streaming export (constant memory)
 * - Create CellStyle objects ONCE before loop (not per cell)
 * - Use fixed column widths (auto-sizing adds 3 minutes for 10K rows)
 * - Dispose workbook in finally block to clean up temp files
 */
@Singleton
class AssetExportService(
    private val assetFilterService: AssetFilterService
) {

    private val log = LoggerFactory.getLogger(AssetExportService::class.java)

    // Date formatter for Excel cells
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * Export assets accessible to the authenticated user
     * FR-011: Workgroup-based access control (ADMIN sees all, non-ADMIN sees workgroup+owned)
     *
     * @param authentication Current user authentication
     * @return List of AssetExportDto ready for Excel serialization
     */
    fun exportAssets(authentication: Authentication): List<AssetExportDto> {
        log.info("Exporting assets for user: {}", authentication.name)

        // Get assets with workgroup filtering
        val assets: List<Asset> = assetFilterService.getAccessibleAssets(authentication)

        log.info("Found {} assets accessible to user {}", assets.size, authentication.name)

        // Convert to export DTOs
        return assets.map { asset -> AssetExportDto.fromAsset(asset) }
    }

    /**
     * Write asset export DTOs to Excel workbook
     * FR-012, FR-013: Format with clear headers and fixed column widths
     *
     * Performance optimizations from research.md:
     * - SXSSFWorkbook with 100-row memory window
     * - Create styles ONCE before loop
     * - Fixed column widths (not auto-sizing)
     * - Enable GZIP compression for temp files
     *
     * @param dtos List of asset export DTOs
     * @return ByteArrayOutputStream containing Excel workbook
     */
    fun writeToExcel(dtos: List<AssetExportDto>): ByteArrayOutputStream {
        log.info("Writing {} assets to Excel format", dtos.size)

        // Create streaming workbook with 100-row window
        val workbook = SXSSFWorkbook(100)
        workbook.setCompressTempFiles(true)

        try {
            val sheet = workbook.createSheet("Assets")

            // Create styles ONCE (not per cell) - critical for performance
            val styles = createAssetStyles(workbook)

            // Write header row
            createHeaderRow(sheet, styles.header, workbook)

            // Write data rows
            dtos.forEachIndexed { index, dto ->
                createAssetRow(sheet, index + 1, dto, styles)
            }

            // Set fixed column widths (auto-sizing adds 3 minutes for 10K rows)
            setFixedColumnWidths(sheet)

            // Write to output stream
            val outputStream = ByteArrayOutputStream()
            workbook.write(outputStream)

            log.info("Excel export complete: {} rows written", dtos.size)

            return outputStream

        } finally {
            // CRITICAL: Dispose workbook to clean up temp files
            workbook.dispose()
            log.debug("SXSSFWorkbook disposed, temp files cleaned")
        }
    }

    /**
     * Create cell styles for Excel export (created ONCE, reused for all cells)
     */
    private fun createAssetStyles(workbook: Workbook): AssetStyles {
        // Header style
        val headerStyle = workbook.createCellStyle().apply {
            fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            val font = workbook.createFont()
            font.bold = true
            setFont(font)
        }

        // Date style
        val dateStyle = workbook.createCellStyle().apply {
            dataFormat = workbook.creationHelper.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss")
        }

        // Text style (default)
        val textStyle = workbook.createCellStyle()

        // Wrap text style for descriptions
        val wrapTextStyle = workbook.createCellStyle().apply {
            wrapText = true
        }

        return AssetStyles(headerStyle, dateStyle, textStyle, wrapTextStyle)
    }

    /**
     * Create header row with column names
     */
    private fun createHeaderRow(sheet: org.apache.poi.ss.usermodel.Sheet, headerStyle: CellStyle, workbook: Workbook) {
        val headerRow = sheet.createRow(0)
        val headers = listOf(
            "Name", "Type", "IP Address", "Owner", "Description",
            "Groups", "Cloud Account ID", "Cloud Instance ID", "OS Version", "AD Domain",
            "Workgroups", "Created At", "Updated At", "Last Seen"
        )

        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerStyle
        }
    }

    /**
     * Create data row for single asset
     */
    private fun createAssetRow(
        sheet: org.apache.poi.ss.usermodel.Sheet,
        rowNum: Int,
        dto: AssetExportDto,
        styles: AssetStyles
    ) {
        val row = sheet.createRow(rowNum)

        // Name
        row.createCell(0).apply {
            setCellValue(dto.name)
            cellStyle = styles.text
        }

        // Type
        row.createCell(1).apply {
            setCellValue(dto.type)
            cellStyle = styles.text
        }

        // IP Address
        row.createCell(2).apply {
            setCellValue(dto.ip ?: "")
            cellStyle = styles.text
        }

        // Owner
        row.createCell(3).apply {
            setCellValue(dto.owner)
            cellStyle = styles.text
        }

        // Description
        row.createCell(4).apply {
            setCellValue(dto.description ?: "")
            cellStyle = styles.wrapText
        }

        // Groups
        row.createCell(5).apply {
            setCellValue(dto.groups ?: "")
            cellStyle = styles.text
        }

        // Cloud Account ID
        row.createCell(6).apply {
            setCellValue(dto.cloudAccountId ?: "")
            cellStyle = styles.text
        }

        // Cloud Instance ID
        row.createCell(7).apply {
            setCellValue(dto.cloudInstanceId ?: "")
            cellStyle = styles.text
        }

        // OS Version
        row.createCell(8).apply {
            setCellValue(dto.osVersion ?: "")
            cellStyle = styles.text
        }

        // AD Domain
        row.createCell(9).apply {
            setCellValue(dto.adDomain ?: "")
            cellStyle = styles.text
        }

        // Workgroups (comma-separated)
        row.createCell(10).apply {
            setCellValue(dto.workgroups)
            cellStyle = styles.text
        }

        // Created At
        row.createCell(11).apply {
            setCellValue(dto.createdAt?.format(dateFormatter) ?: "")
            cellStyle = styles.text
        }

        // Updated At
        row.createCell(12).apply {
            setCellValue(dto.updatedAt?.format(dateFormatter) ?: "")
            cellStyle = styles.text
        }

        // Last Seen
        row.createCell(13).apply {
            setCellValue(dto.lastSeen?.format(dateFormatter) ?: "")
            cellStyle = styles.text
        }
    }

    /**
     * Set fixed column widths (auto-sizing adds 3 minutes overhead for 10K rows)
     */
    private fun setFixedColumnWidths(sheet: org.apache.poi.ss.usermodel.Sheet) {
        sheet.setColumnWidth(0, 6000)  // Name
        sheet.setColumnWidth(1, 4000)  // Type
        sheet.setColumnWidth(2, 4000)  // IP Address
        sheet.setColumnWidth(3, 5000)  // Owner
        sheet.setColumnWidth(4, 8000)  // Description
        sheet.setColumnWidth(5, 5000)  // Groups
        sheet.setColumnWidth(6, 5000)  // Cloud Account ID
        sheet.setColumnWidth(7, 5000)  // Cloud Instance ID
        sheet.setColumnWidth(8, 4000)  // OS Version
        sheet.setColumnWidth(9, 5000)  // AD Domain
        sheet.setColumnWidth(10, 6000) // Workgroups
        sheet.setColumnWidth(11, 5000) // Created At
        sheet.setColumnWidth(12, 5000) // Updated At
        sheet.setColumnWidth(13, 5000) // Last Seen
    }

    /**
     * Data class for style objects (created once, reused)
     */
    private data class AssetStyles(
        val header: CellStyle,
        val date: CellStyle,
        val text: CellStyle,
        val wrapText: CellStyle
    )
}
