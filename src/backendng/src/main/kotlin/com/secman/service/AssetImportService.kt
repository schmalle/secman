package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.Workgroup
import com.secman.dto.AssetImportDto
import com.secman.dto.ImportResult
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import io.micronaut.security.authentication.Authentication
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Service for importing assets from Excel files
 * Feature: 029-asset-bulk-operations (User Story 3 - Import Assets from File)
 *
 * Related Requirements:
 * - FR-017: Accept Excel files with validation for file size, format, required fields
 * - FR-018: Validate required fields (name, type, owner)
 * - FR-019: Validate data formats (IP address, type values)
 * - FR-020: Handle duplicate asset names by skipping (no updates)
 * - FR-021: Associate imported assets with workgroups
 * - FR-022: Track importing user as creator
 * - FR-023: Provide import summary with counts and errors
 *
 * Performance Target:
 * - Import 5K assets in <60 seconds with 95%+ success rate (SC-003)
 */
@Singleton
open class AssetImportService(
    private val assetRepository: AssetRepository,
    private val workgroupRepository: WorkgroupRepository,
    private val userRepository: UserRepository
) {

    private val log = LoggerFactory.getLogger(AssetImportService::class.java)

    // Date formatters for parsing Excel date fields
    private val dateFormatters = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ISO_LOCAL_DATE
    )

    /**
     * Import assets from Excel file
     * FR-017, FR-018, FR-019, FR-020, FR-021, FR-022, FR-023
     *
     * @param inputStream Excel file input stream
     * @param authentication Current user authentication
     * @return ImportResult with imported/skipped counts and errors
     */
    @Transactional
    open fun importFromExcel(inputStream: InputStream, authentication: Authentication): ImportResult {
        log.info("Starting asset import for user: {}", authentication.name)

        val workbook = XSSFWorkbook(inputStream)
        val sheet = workbook.getSheetAt(0)

        // Validate headers
        val headerRow = sheet.getRow(0) ?: throw IllegalArgumentException("Missing header row")
        val headers = validateAndMapHeaders(headerRow)

        // Get importing user for creator tracking
        val importingUser = userRepository.findByUsername(authentication.name).orElse(null)

        // Parse all rows
        val validAssets = mutableListOf<Asset>()
        val errors = mutableListOf<String>()
        var skippedCount = 0

        for (rowNum in 1..sheet.lastRowNum) {
            val row = sheet.getRow(rowNum) ?: continue

            try {
                val dto = parseRow(row, headers, rowNum)

                // Validate required fields
                if (dto.name.isBlank()) {
                    errors.add("Row ${rowNum + 1}: Asset name is required")
                    skippedCount++
                    continue
                }

                if (dto.type.isBlank()) {
                    errors.add("Row ${rowNum + 1}: Asset type is required")
                    skippedCount++
                    continue
                }

                if (dto.owner.isBlank()) {
                    errors.add("Row ${rowNum + 1}: Asset owner is required")
                    skippedCount++
                    continue
                }

                // Check for duplicate by name (skip, don't update)
                val existing = assetRepository.findByName(dto.name.trim()).orElse(null)
                if (existing != null) {
                    errors.add("Row ${rowNum + 1}: Duplicate asset name '${dto.name}'")
                    skippedCount++
                    continue
                }

                // Resolve workgroup names to entities
                val workgroups = resolveWorkgroups(dto.workgroupNames)

                // Convert DTO to entity
                val asset = dto.toAsset(workgroups)

                // Set creator
                if (importingUser != null) {
                    asset.manualCreator = importingUser
                }

                validAssets.add(asset)

            } catch (e: Exception) {
                log.warn("Error parsing row {}: {}", rowNum + 1, e.message)
                errors.add("Row ${rowNum + 1}: ${e.message}")
                skippedCount++
            }
        }

        // Batch save valid assets
        val savedAssets = if (validAssets.isNotEmpty()) {
            assetRepository.saveAll(validAssets).toList()
        } else {
            emptyList()
        }

        val importedCount = savedAssets.size

        log.info("Asset import complete: {} imported, {} skipped, {} errors", importedCount, skippedCount, errors.size)

        // Limit errors to first 20 for performance
        val limitedErrors = errors.take(20)

        return ImportResult(
            message = "Created $importedCount assets, updated 0, skipped $skippedCount",
            imported = importedCount,
            skipped = skippedCount,
            assetsCreated = importedCount,
            assetsUpdated = 0,
            errors = limitedErrors
        )
    }

    /**
     * Validate headers and create column index map (case-insensitive)
     */
    private fun validateAndMapHeaders(headerRow: Row): Map<String, Int> {
        val headers = mutableMapOf<String, Int>()

        for (cellNum in 0 until headerRow.lastCellNum) {
            val cell = headerRow.getCell(cellNum) ?: continue
            val headerName = cell.stringCellValue.trim().lowercase()
            headers[headerName] = cellNum
        }

        // Check required columns (case-insensitive)
        val requiredHeaders = listOf("name", "type", "owner")
        val missingHeaders = requiredHeaders.filter { required ->
            headers.keys.none { it == required }
        }

        if (missingHeaders.isNotEmpty()) {
            throw IllegalArgumentException("Missing required columns: ${missingHeaders.joinToString(", ")}")
        }

        log.debug("Validated headers: {}", headers.keys)
        return headers
    }

    /**
     * Parse single row to AssetImportDto
     */
    private fun parseRow(row: Row, headers: Map<String, Int>, rowNum: Int): AssetImportDto {
        return AssetImportDto(
            name = getCellValueAsString(row, headers, "name") ?: "",
            type = getCellValueAsString(row, headers, "type") ?: "",
            ip = getCellValueAsString(row, headers, "ip address"),
            owner = getCellValueAsString(row, headers, "owner") ?: "",
            description = getCellValueAsString(row, headers, "description"),
            groups = getCellValueAsString(row, headers, "groups"),
            cloudAccountId = getCellValueAsString(row, headers, "cloud account id"),
            cloudInstanceId = getCellValueAsString(row, headers, "cloud instance id"),
            osVersion = getCellValueAsString(row, headers, "os version"),
            adDomain = getCellValueAsString(row, headers, "ad domain"),
            workgroupNames = getCellValueAsString(row, headers, "workgroups"),
            createdAt = getCellValueAsDate(row, headers, "created at"),
            updatedAt = getCellValueAsDate(row, headers, "updated at"),
            lastSeen = getCellValueAsDate(row, headers, "last seen")
        )
    }

    /**
     * Get cell value as string (handles different cell types)
     */
    private fun getCellValueAsString(row: Row, headers: Map<String, Int>, columnName: String): String? {
        val cellIndex = headers[columnName] ?: return null
        val cell = row.getCell(cellIndex) ?: return null

        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> cell.numericCellValue.toLong().toString()
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.BLANK -> null
            else -> null
        }
    }

    /**
     * Get cell value as LocalDateTime (tries multiple date formats)
     */
    private fun getCellValueAsDate(row: Row, headers: Map<String, Int>, columnName: String): LocalDateTime? {
        val cellIndex = headers[columnName] ?: return null
        val cell = row.getCell(cellIndex) ?: return null

        // Try as date cell
        if (cell.cellType == CellType.NUMERIC) {
            return try {
                cell.localDateTimeCellValue
            } catch (e: Exception) {
                null
            }
        }

        // Try parsing as string with multiple formats
        if (cell.cellType == CellType.STRING) {
            val dateStr = cell.stringCellValue.trim()
            for (formatter in dateFormatters) {
                try {
                    return LocalDateTime.parse(dateStr, formatter)
                } catch (e: Exception) {
                    // Try next formatter
                }
            }
        }

        return null
    }

    /**
     * Resolve workgroup names to entities (case-insensitive)
     * FR-021: Associate imported assets with workgroups
     */
    private fun resolveWorkgroups(workgroupNames: String?): Set<Workgroup> {
        if (workgroupNames.isNullOrBlank()) {
            return emptySet()
        }

        val names = workgroupNames.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val workgroups = mutableSetOf<Workgroup>()

        for (name in names) {
            val workgroup = workgroupRepository.findByNameIgnoreCase(name).orElse(null)
            if (workgroup != null) {
                workgroups.add(workgroup)
            } else {
                log.warn("Workgroup not found: '{}', skipping", name)
            }
        }

        return workgroups
    }
}
