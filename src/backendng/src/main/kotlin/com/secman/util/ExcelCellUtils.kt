package com.secman.util

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.slf4j.LoggerFactory

/**
 * Shared Apache POI cell helpers for Excel import services.
 *
 * Lifted verbatim from VulnerabilityImportService (the canonical copy) to replace
 * duplicated private helpers. Used by:
 * - VulnerabilityImportService
 * - UserMappingImportService
 */
object ExcelCellUtils {
    private val log = LoggerFactory.getLogger(ExcelCellUtils::class.java)

    /**
     * Get cell value from row by header name
     *
     * @param row Excel row
     * @param headerMap Column index mapping
     * @param headerName Header name
     * @return Cell value as string or null
     */
    fun getCellValue(row: Row, headerMap: Map<String, Int>, headerName: String): String? {
        val cellIndex = headerMap[headerName] ?: return null
        val cell = row.getCell(cellIndex) ?: return null
        return getCellValueAsString(cell)
    }

    /**
     * Convert cell value to string
     * Handles different cell types: STRING, NUMERIC, BOOLEAN, FORMULA
     *
     * Uses DataFormatter for numeric cells to preserve precision
     * (e.g. "123456789012" instead of "1.23E+11").
     *
     * @param cell Excel cell
     * @return Cell value as string
     */
    fun getCellValueAsString(cell: Cell): String {
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    cell.localDateTimeCellValue.toString()
                } else {
                    // Use DataFormatter for consistent number formatting
                    val formatter = DataFormatter()
                    formatter.formatCellValue(cell)
                }
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try {
                    when (cell.cachedFormulaResultType) {
                        CellType.STRING -> cell.richStringCellValue.string
                        CellType.NUMERIC -> {
                            val formatter = DataFormatter()
                            formatter.formatCellValue(cell)
                        }
                        CellType.BOOLEAN -> cell.booleanCellValue.toString()
                        else -> ""
                    }
                } catch (e: Exception) {
                    log.warn("Failed to read cached formula result: {}", e.message)
                    ""
                }
            }
            else -> ""
        }
    }
}
