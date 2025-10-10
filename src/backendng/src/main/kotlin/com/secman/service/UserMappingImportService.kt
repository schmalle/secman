package com.secman.service

import com.secman.domain.UserMapping
import com.secman.repository.UserMappingRepository
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.InputStream

/**
 * Service for importing user mappings from Excel files
 *
 * Feature: 013-user-mapping-upload
 *
 * Responsibilities:
 * - Parse Excel files (.xlsx format)
 * - Validate headers (case-insensitive matching)
 * - Validate each row (email + at least one of AWS account ID or domain)
 * - Skip invalid rows, continue processing valid rows
 * - Detect and skip duplicate mappings
 * - Return detailed import results
 *
 * Excel Format:
 * - Column 1: Email Address (required, must contain @)
 * - Column 2: AWS Account ID (optional, exactly 12 numeric digits when provided)
 * - Column 3: Domain (optional, alphanumeric + dots + hyphens when provided)
 * - At least one of AWS Account ID or Domain must be provided
 *
 * Related to: Feature 013 (User Mapping Upload)
 */
@Singleton
open class UserMappingImportService(
    private val userMappingRepository: UserMappingRepository
) {
    private val log = LoggerFactory.getLogger(UserMappingImportService::class.java)
    
    companion object {
        private val REQUIRED_HEADERS = listOf(
            "Email Address", "AWS Account ID", "Domain"
        )
        private const val AWS_ACCOUNT_ID_PATTERN = "^\\d{12}$"
        private const val DOMAIN_PATTERN = "^[a-z0-9.-]+$"
    }

    /**
     * Data class for import results
     */
    @Serdeable
    data class ImportResult(
        val message: String,
        val imported: Int,
        val skipped: Int,
        val errors: List<String> = emptyList()
    )

    /**
     * Import user mappings from Excel file
     * 
     * @param inputStream Excel file input stream (.xlsx format)
     * @return ImportResult with counts and error details
     * @throws IllegalArgumentException if file format is invalid
     */
    @Transactional
    open fun importFromExcel(inputStream: InputStream): ImportResult {
        val workbook = XSSFWorkbook(inputStream)
        val sheet = workbook.getSheetAt(0) 
            ?: throw IllegalArgumentException("Excel file has no sheets")

        // Validate headers
        val headerValidation = validateHeaders(sheet)
        if (headerValidation != null) {
            workbook.close()
            throw IllegalArgumentException(headerValidation)
        }

        val headerMap = getHeaderMapping(sheet)
        val errors = mutableListOf<String>()
        var imported = 0
        var skipped = 0

        // Process rows (skip header row 0)
        for (rowIndex in 1..sheet.lastRowNum) {
            val row = sheet.getRow(rowIndex) ?: continue
            
            // Skip completely empty rows
            if (isRowEmpty(row)) {
                continue
            }
            
            try {
                val mapping = parseRowToUserMapping(row, headerMap, rowIndex + 1)
                if (mapping != null) {
                    // Check for duplicate
                    if (userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                        mapping.email, mapping.awsAccountId, mapping.domain
                    )) {
                        skipped++
                        log.debug("Skipped duplicate mapping at row {}: {}", rowIndex + 1, mapping)
                    } else {
                        userMappingRepository.save(mapping)
                        imported++
                        log.debug("Imported mapping at row {}: {}", rowIndex + 1, mapping)
                    }
                }
            } catch (e: Exception) {
                skipped++
                val errorMsg = "Row ${rowIndex + 1}: ${e.message}"
                errors.add(errorMsg)
                log.warn("Failed to parse row {}: {}", rowIndex + 1, e.message)
            }
        }

        workbook.close()

        val message = if (imported > 0 || skipped > 0) {
            "Imported $imported mappings, skipped $skipped"
        } else {
            "No data rows found in file"
        }

        return ImportResult(
            message = message,
            imported = imported,
            skipped = skipped,
            errors = errors.take(20) // Limit error list to first 20
        )
    }

    /**
     * Validate that all required headers are present
     */
    private fun validateHeaders(sheet: Sheet): String? {
        val headerRow = sheet.getRow(0) ?: return "Header row not found"
        
        val actualHeaders = mutableListOf<String>()
        for (cellIndex in 0 until headerRow.lastCellNum) {
            val cell = headerRow.getCell(cellIndex)
            if (cell != null) {
                actualHeaders.add(getCellValueAsString(cell).trim())
            }
        }

        val missingHeaders = REQUIRED_HEADERS.filter { required ->
            actualHeaders.none { it.equals(required, ignoreCase = true) }
        }

        if (missingHeaders.isNotEmpty()) {
            return "Missing required columns: ${missingHeaders.joinToString(", ")}"
        }

        return null
    }

    /**
     * Create a mapping from header names to column indices
     */
    private fun getHeaderMapping(sheet: Sheet): Map<String, Int> {
        val headerRow = sheet.getRow(0)
        val headerMap = mutableMapOf<String, Int>()

        for (cellIndex in 0 until headerRow.lastCellNum) {
            val cell = headerRow.getCell(cellIndex)
            if (cell != null) {
                val headerName = getCellValueAsString(cell).trim()
                REQUIRED_HEADERS.forEach { required ->
                    if (headerName.equals(required, ignoreCase = true)) {
                        headerMap[required] = cellIndex
                    }
                }
            }
        }

        return headerMap
    }

    /**
     * Parse a single row into a UserMapping
     *
     * @param row Excel row to parse
     * @param headerMap Mapping of header names to column indices
     * @param rowNumber Row number for error messages (1-based)
     * @return UserMapping if valid, null if row should be skipped
     * @throws IllegalArgumentException if validation fails
     */
    private fun parseRowToUserMapping(row: Row, headerMap: Map<String, Int>, rowNumber: Int): UserMapping? {
        val email = getCellValue(row, headerMap, "Email Address")?.trim()
        val awsAccountId = getCellValue(row, headerMap, "AWS Account ID")?.trim()
        val domain = getCellValue(row, headerMap, "Domain")?.trim()

        // Validate required fields
        if (email.isNullOrBlank()) {
            throw IllegalArgumentException("Email address is required")
        }

        // At least one of AWS Account ID or Domain must be provided
        if (awsAccountId.isNullOrBlank() && domain.isNullOrBlank()) {
            throw IllegalArgumentException("At least one of AWS Account ID or Domain must be provided")
        }

        // Validate email format
        if (!validateEmail(email)) {
            throw IllegalArgumentException("Invalid email format: $email")
        }

        // Validate AWS account ID format (12 digits) - only if provided
        if (!awsAccountId.isNullOrBlank() && !validateAwsAccountId(awsAccountId)) {
            throw IllegalArgumentException("AWS Account ID must be exactly 12 numeric digits: $awsAccountId")
        }

        // Validate domain format - only if provided
        if (!domain.isNullOrBlank() && !validateDomain(domain)) {
            throw IllegalArgumentException("Invalid domain format: $domain")
        }

        return UserMapping(
            email = email.lowercase(),
            awsAccountId = if (awsAccountId.isNullOrBlank()) null else awsAccountId,
            domain = if (domain.isNullOrBlank()) null else domain.lowercase()
        )
    }

    /**
     * Validate email address format
     */
    private fun validateEmail(email: String): Boolean {
        return email.contains("@") && 
               email.length >= 3 && 
               email.length <= 255 &&
               email.indexOf("@") > 0 &&
               email.indexOf("@") < email.length - 1
    }

    /**
     * Validate AWS account ID format (exactly 12 numeric digits)
     */
    private fun validateAwsAccountId(accountId: String): Boolean {
        return accountId.matches(Regex(AWS_ACCOUNT_ID_PATTERN))
    }

    /**
     * Validate domain format
     */
    private fun validateDomain(domain: String): Boolean {
        val normalized = domain.lowercase()
        return normalized.matches(Regex(DOMAIN_PATTERN)) &&
               !normalized.startsWith(".") &&
               !normalized.endsWith(".") &&
               !normalized.startsWith("-") &&
               !normalized.endsWith("-") &&
               !normalized.contains(" ")
    }

    /**
     * Get cell value from row using header mapping
     */
    private fun getCellValue(row: Row, headerMap: Map<String, Int>, headerName: String): String? {
        val cellIndex = headerMap[headerName] ?: return null
        val cell = row.getCell(cellIndex) ?: return null
        return getCellValueAsString(cell)
    }

    /**
     * Convert cell value to string, handling different cell types
     * CRITICAL: Use DataFormatter to preserve AWS account ID precision
     */
    private fun getCellValueAsString(cell: Cell): String {
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                // Use DataFormatter to preserve precision
                // Without this, "123456789012" becomes "1.23E+11"
                val formatter = DataFormatter()
                formatter.formatCellValue(cell)
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> {
                try {
                    val evaluator = cell.sheet.workbook.creationHelper.createFormulaEvaluator()
                    val result = evaluator.evaluate(cell)
                    when (result.cellType) {
                        CellType.STRING -> result.stringValue
                        CellType.NUMERIC -> {
                            val formatter = DataFormatter()
                            formatter.formatCellValue(cell)
                        }
                        CellType.BOOLEAN -> result.booleanValue.toString()
                        else -> ""
                    }
                } catch (e: Exception) {
                    log.warn("Failed to evaluate formula in cell: {}", e.message)
                    ""
                }
            }
            CellType.BLANK -> ""
            else -> ""
        }
    }

    /**
     * Check if a row is completely empty
     */
    private fun isRowEmpty(row: Row): Boolean {
        for (cellIndex in 0 until row.lastCellNum) {
            val cell = row.getCell(cellIndex)
            if (cell != null && cell.cellType != CellType.BLANK) {
                val value = getCellValueAsString(cell).trim()
                if (value.isNotEmpty()) {
                    return false
                }
            }
        }
        return true
    }
}
