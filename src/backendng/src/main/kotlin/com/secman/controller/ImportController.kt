package com.secman.controller

import com.secman.domain.Norm
import com.secman.domain.Requirement
import com.secman.domain.UseCase
import com.secman.repository.NormRepository
import com.secman.repository.RequirementRepository
import com.secman.service.NormParsingService
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.http.multipart.CompletedFileUpload
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Inject
import java.time.LocalDateTime
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

@Controller("/api/import")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class ImportController(
    private val requirementRepository: RequirementRepository,
    private val normRepository: NormRepository,
    private val normParsingService: NormParsingService,
    private val vulnerabilityImportService: com.secman.service.VulnerabilityImportService,
    private val masscanParserService: com.secman.service.MasscanParserService,
    private val assetRepository: com.secman.repository.AssetRepository,
    private val scanRepository: com.secman.repository.ScanRepository,
    private val scanResultRepository: com.secman.repository.ScanResultRepository,
    private val userMappingImportService: com.secman.service.UserMappingImportService,
    private val csvUserMappingParser: com.secman.service.CSVUserMappingParser,
    private val assetImportService: com.secman.service.AssetImportService,
    private val requirementImportService: com.secman.service.RequirementImportService,
    private val importCompletionNotifier: com.secman.service.ImportCompletionNotifier
) {
    
    private val log = LoggerFactory.getLogger(ImportController::class.java)
    
    companion object {
        private const val MAX_FILE_SIZE = 100 * 1024 * 1024L // 100MB - aligned with application.yml
        private const val REQUIRED_SHEET_NAME = "Reqs"

        /** Enough skipped rows to diagnose a file without turning the response into the file. */
        private const val MAX_REPORTED_SKIPS = 50
        private val REQUIRED_HEADERS = listOf(
            "Chapter", "Norm", "Short req", "DetailsEN", "MotivationEN", "ExampleEN", "UseCase"
        )

        private val VALID_EXCEL_CONTENT_TYPES = setOf(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "application/octet-stream"
        )

        private val VALID_XML_CONTENT_TYPES = setOf(
            "application/xml",
            "text/xml",
            "application/octet-stream"
        )

        fun isValidExcelContentType(contentType: String): Boolean {
            if (contentType.isEmpty()) return true
            return VALID_EXCEL_CONTENT_TYPES.any { contentType.startsWith(it) }
        }

        fun isValidXmlContentType(contentType: String): Boolean {
            if (contentType.isEmpty()) return true
            return VALID_XML_CONTENT_TYPES.any { contentType.startsWith(it) }
        }
    }

    @Serdeable
    data class ImportResponse(
        val message: String,
        val requirementsProcessed: Int,
        /**
         * Rows the file contained that did not become requirements, and why.
         *
         * Silence here was the actual defect behind "why did only N of my rows import?": skips were
         * a `log.warn` on the server and nothing at all in the response, so an admin comparing their
         * spreadsheet to the result had no way to tell a deliberate skip from a lost row.
         */
        val rowsSkipped: Int = 0,
        val skipReasons: List<String> = emptyList()
    )

    /** One row that did not import, carrying the spreadsheet row number the user can navigate to. */
    private data class SkippedRow(val rowNumber: Int, val reason: String) {
        fun describe(): String = "Row $rowNumber: $reason"
    }

    @Serdeable
    data class ErrorResponse(
        val error: String
    )

    @Post("/upload-xlsx")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Secured("ADMIN", "REQADMIN")
    open fun uploadXlsx(@Part xlsxFile: CompletedFileUpload): HttpResponse<*> {
        return try {
            log.debug("Processing Excel file upload: {}", xlsxFile.filename)
            
            // Validate file
            val validation = validateFile(xlsxFile)
            if (validation != null) {
                return HttpResponse.badRequest(ErrorResponse(validation))
            }
            
            // Process file
            val parsed = parseExcelFile(xlsxFile)

            if (parsed.rows.isEmpty()) {
                val detail = parsed.skipped.take(MAX_REPORTED_SKIPS).joinToString("; ") { it.describe() }
                return HttpResponse.badRequest(
                    ErrorResponse(
                        "No valid requirements found in file." +
                            if (detail.isNotBlank()) " $detail" else ""
                    )
                )
            }

            // Save requirements
            val outcome = saveRequirements(parsed.rows)
            val allSkipped = parsed.skipped + outcome.failed

            log.info(
                "Processed {} requirements from Excel file {} ({} row(s) skipped)",
                outcome.processed, sanitizeForLog(xlsxFile.filename), allSkipped.size
            )
            HttpResponse.ok(ImportResponse(
                message = if (allSkipped.isEmpty()) {
                    "File processed successfully."
                } else {
                    // Say it in the message too: the count alone is what left admins guessing.
                    "File processed. ${outcome.processed} imported, ${allSkipped.size} row(s) skipped."
                },
                requirementsProcessed = outcome.processed,
                rowsSkipped = allSkipped.size,
                // Bounded: a badly-formed file can skip thousands of rows, and an unbounded list
                // would be a response-size problem rather than a diagnosis.
                skipReasons = allSkipped.take(MAX_REPORTED_SKIPS).map { it.describe() } +
                    if (allSkipped.size > MAX_REPORTED_SKIPS) {
                        listOf("… and ${allSkipped.size - MAX_REPORTED_SKIPS} more (see server log)")
                    } else emptyList()
            ))

        } catch (e: IllegalArgumentException) {
            // Structural problems with the workbook — missing sheet, missing headers. These are the
            // caller's to fix and the message names exactly what is wrong, so it must reach them.
            // It used to fall into the generic handler below and surface as "An internal error
            // occurred", which left an admin with a rejected file and no way to tell why.
            log.warn("Rejected Excel upload {}: {}", sanitizeForLog(xlsxFile.filename), e.message)
            HttpResponse.badRequest(ErrorResponse(e.message ?: "The file could not be read"))
        } catch (e: Exception) {
            log.error("Error processing Excel file", e)
            HttpResponse.status<ErrorResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("An internal error occurred"))
        }
    }

    /** Strips line breaks and bounds length before a filename reaches a log line (log forging). */
    /**
     * The authenticated user's database id, or null when it cannot be determined.
     * Used only for audit attribution — never for an authorization decision, which
     * `@Secured("ADMIN")` on the endpoint has already made.
     */
    private fun userIdOrNull(authentication: Authentication): Long? =
        when (val userId = authentication.attributes["userId"]) {
            is Long -> userId
            is Int -> userId.toLong()
            is String -> userId.toLongOrNull()
            else -> null
        }

    private fun sanitizeForLog(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.replace(Regex("[\\p{Cntrl}\\u0085\\u2028\\u2029]"), " ").trim().take(200)
    }

    private fun validateFile(file: CompletedFileUpload): String? {
        // Check file size
        if (file.size > MAX_FILE_SIZE) {
            return "File size exceeds maximum limit of ${MAX_FILE_SIZE / 1024 / 1024}MB"
        }
        
        // Check file extension
        val filename = file.filename.orEmpty()
        if (!filename.lowercase().endsWith(".xlsx")) {
            return "Only .xlsx files are supported"
        }
        
        // Check content type
        val contentType = file.contentType.map { it.toString() }.orElse("")
        if (!isValidExcelContentType(contentType)) {
            return "Invalid file format. Please upload a valid Excel file."
        }

        // Check file is not empty
        if (file.size == 0L) {
            return "File is empty"
        }

        return null
    }

    /** A row that parsed, paired with the spreadsheet row number so a later save failure can name it. */
    private data class ParsedRow(val rowNumber: Int, val requirement: Requirement)

    private data class ParsedWorkbook(val rows: List<ParsedRow>, val skipped: List<SkippedRow>)

    private fun parseExcelFile(file: CompletedFileUpload): ParsedWorkbook {
        val rows = mutableListOf<ParsedRow>()
        val skipped = mutableListOf<SkippedRow>()

        file.inputStream.use { inputStream ->
            val workbook = XSSFWorkbook(inputStream)
            
            // Get required sheet
            val sheet = workbook.getSheet(REQUIRED_SHEET_NAME)
                ?: throw IllegalArgumentException("Required sheet '$REQUIRED_SHEET_NAME' not found")
            
            // Validate headers
            val headerValidation = validateHeaders(sheet)
            if (headerValidation != null) {
                throw IllegalArgumentException(headerValidation)
            }
            
            // Get header mapping
            val headerMap = getHeaderMapping(sheet)
            
            // Process data rows (skip header row 0)
            for (rowIndex in 1..sheet.lastRowNum) {
                // Excel numbers rows from 1, POI from 0. Report the number the user can navigate to.
                val rowNumber = rowIndex + 1
                val row = sheet.getRow(rowIndex) ?: continue

                try {
                    val requirement = parseRowToRequirement(row, headerMap)
                    when {
                        requirement != null -> rows.add(ParsedRow(rowNumber, requirement))
                        // A row with nothing in any mapped column is padding, not a loss — reporting
                        // it would bury the real skips under noise.
                        isBlankRow(row, headerMap) -> Unit
                        else -> skipped.add(SkippedRow(rowNumber, "no value in the 'Short req' column"))
                    }
                } catch (e: Exception) {
                    log.warn("Failed to parse row {}: {}", rowNumber, e.message)
                    skipped.add(SkippedRow(rowNumber, "could not be read (${e.message ?: e.javaClass.simpleName})"))
                }
            }

            workbook.close()
        }

        return ParsedWorkbook(rows, skipped)
    }

    /** True when every column the importer reads is empty for this row. */
    private fun isBlankRow(row: Row, headerMap: Map<String, Int>): Boolean =
        REQUIRED_HEADERS.all { getCellValue(row, headerMap, it).isNullOrBlank() }
    
    private fun validateHeaders(sheet: Sheet): String? {
        val headerRow = sheet.getRow(0) 
            ?: return "Header row not found"
        
        val actualHeaders = mutableListOf<String>()
        for (cellIndex in 0 until headerRow.lastCellNum) {
            val cell = headerRow.getCell(cellIndex)
            if (cell != null) {
                actualHeaders.add(getCellValueAsString(cell).trim())
            }
        }

        val missingHeaders = REQUIRED_HEADERS.filter { required ->
            actualHeaders.none { actual -> normalizeHeader(actual) == normalizeHeader(required) }
        }

        if (missingHeaders.isNotEmpty()) {
            return "Missing required headers: ${missingHeaders.joinToString(", ")}. " +
                "Found: ${actualHeaders.joinToString(", ").ifBlank { "(no header cells)" }}"
        }

        return null
    }

    /**
     * Reduces a header to letters and digits so spacing and case cannot decide whether a file imports.
     *
     * Real workbooks spell the same column "Short req", "ShortReq" and "Short Req"; the previous
     * comparison ignored case but not whitespace, so "ShortReq" was reported missing and the whole
     * upload was rejected. Stripping non-alphanumerics also absorbs the line breaks Excel leaves in
     * wrapped headers (e.g. "UseCase\nBPCS"). It cannot collide the required names with each other:
     * they normalize to chapter, norm, shortreq, detailsen, motivationen, exampleen, usecase — all
     * distinct, and distinct from neighbours like "usecasebpcs".
     */
    private fun normalizeHeader(header: String): String =
        header.lowercase().replace(Regex("[^a-z0-9]"), "")
    
    private fun getHeaderMapping(sheet: Sheet): Map<String, Int> {
        val headerRow = sheet.getRow(0)
        val headerMap = mutableMapOf<String, Int>()
        
        for (cellIndex in 0 until headerRow.lastCellNum) {
            val cell = headerRow.getCell(cellIndex)
            if (cell != null) {
                val headerName = getCellValueAsString(cell).trim()

                // Map to standardized header names; must use the same tolerance as validateHeaders,
                // or a file could pass validation and then read every cell as null.
                REQUIRED_HEADERS.forEach { required ->
                    if (normalizeHeader(headerName) == normalizeHeader(required)) {
                        headerMap[required] = cellIndex
                    }
                }
            }
        }
        
        return headerMap
    }
    
    private fun parseRowToRequirement(row: Row, headerMap: Map<String, Int>): Requirement? {
        // Get shortreq (mandatory field)
        val shortreq = getCellValue(row, headerMap, "Short req")?.trim()
        if (shortreq.isNullOrBlank()) {
            log.debug("Skipping row {} - missing Short req", row.rowNum + 1)
            return null
        }
        
        // Get other fields
        val chapter = getCellValue(row, headerMap, "Chapter")?.trim()
        val normString = getCellValue(row, headerMap, "Norm")?.trim()
        val details = getCellValue(row, headerMap, "DetailsEN")?.trim()
        val motivation = getCellValue(row, headerMap, "MotivationEN")?.trim()
        val example = getCellValue(row, headerMap, "ExampleEN")?.trim()
        val useCaseString = getCellValue(row, headerMap, "UseCase")?.trim()
        
        // Create requirement
        val requirement = Requirement(
            shortreq = shortreq,
            chapter = chapter?.takeIf { it.isNotEmpty() },
            norm = normString?.takeIf { it.isNotEmpty() },
            details = details?.takeIf { it.isNotEmpty() },
            motivation = motivation?.takeIf { it.isNotEmpty() },
            example = example?.takeIf { it.isNotEmpty() },
            usecase = useCaseString?.takeIf { it.isNotEmpty() }
        )
        
        // Parse and associate norms
        if (!normString.isNullOrEmpty()) {
            try {
                val norms = normParsingService.parseNorms(normString)
                requirement.norms = norms.toMutableSet()
                
                // Derive chapter from norm if chapter is empty
                if (chapter.isNullOrEmpty() && norms.isNotEmpty()) {
                    val firstNorm = norms.first().name
                    requirement.chapter = extractChapterFromNorm(firstNorm)
                }
            } catch (e: Exception) {
                log.warn("Failed to parse norms for requirement '{}': {}", shortreq, e.message)
            }
        }
        
        // Parse and create use cases
        if (!useCaseString.isNullOrEmpty()) {
            val useCaseNames = useCaseString.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val useCases = mutableListOf<UseCase>()
            for (name in useCaseNames) {
                try {
                    useCases.add(requirementImportService.findOrCreateUseCase(name))
                } catch (e: Exception) {
                    log.warn("Failed to create use case '{}' for requirement '{}': {}", name, shortreq, e.message)
                }
            }
            requirement.usecases = useCases.toMutableSet()
        }

        return requirement
    }
    
    private fun getCellValue(row: Row, headerMap: Map<String, Int>, headerName: String): String? {
        val cellIndex = headerMap[headerName] ?: return null
        val cell = row.getCell(cellIndex) ?: return null
        return getCellValueAsString(cell)
    }
    
    private fun getCellValueAsString(cell: Cell): String {
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
    
    private fun extractChapterFromNorm(normName: String): String? {
        // Extract chapter from norm name (e.g., "ISO 27001: A.8.1.1" -> "A.8.1.1")
        val colonIndex = normName.indexOf(':')
        return if (colonIndex > 0 && colonIndex < normName.length - 1) {
            normName.substring(colonIndex + 1).trim()
        } else {
            null
        }
    }
    
    private data class SaveOutcome(val processed: Int, val failed: List<SkippedRow>)

    private fun saveRequirements(rows: List<ParsedRow>): SaveOutcome {
        var processedCount = 0
        val failed = mutableListOf<SkippedRow>()

        for ((rowNumber, requirement) in rows) {
            try {
                val saved = requirementImportService.saveOne(requirement)
                log.debug("Saved requirement: {} with ID {}", saved.shortreq, saved.internalId)
                processedCount++
            } catch (e: Exception) {
                log.warn("Failed to save requirement '{}': {}", requirement.shortreq, e.message)
                // Continue with next requirement; its REQUIRES_NEW transaction rolled back
                // independently. Record it so the caller learns the row was lost — this used to be
                // a server-side warning only, which is how rows disappeared without explanation.
                failed.add(SkippedRow(rowNumber, "could not be saved (${e.message ?: e.javaClass.simpleName})"))
            }
        }

        return SaveOutcome(processedCount, failed)
    }

    /**
     * Upload vulnerability scan Excel file
     *
     * Related to: Feature 003-i-want-to (Vulnerability Management System)
     *
     * Endpoint: POST /api/import/upload-vulnerability-xlsx
     * Request: multipart/form-data with xlsxFile and scanDate
     * Response: VulnerabilityImportResponse with import counts
     *
     * @param xlsxFile Excel file containing vulnerability data
     * @param scanDate ISO 8601 datetime string when scan was performed
     * @return Import response with counts (imported, skipped, assetsCreated)
     */
    @Post("/upload-vulnerability-xlsx")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Secured("ADMIN", "VULN")
    open fun uploadVulnerabilityXlsx(
        @Part xlsxFile: CompletedFileUpload,
        @Part scanDate: String
    ): HttpResponse<*> {
        return try {
            log.debug("Processing vulnerability Excel file upload: {}, scan date: {}", xlsxFile.filename, scanDate)

            // Validate file
            val validation = validateVulnerabilityFile(xlsxFile)
            if (validation != null) {
                return HttpResponse.badRequest(ErrorResponse(validation))
            }

            // Parse scan date
            val scanDateTime = try {
                java.time.LocalDateTime.parse(scanDate)
            } catch (e: Exception) {
                return HttpResponse.badRequest(ErrorResponse("An internal error occurred"))
            }

            // Import vulnerabilities
            val response = xlsxFile.inputStream.use { inputStream ->
                vulnerabilityImportService.importFromExcel(inputStream, scanDateTime)
            }

            log.info("Successfully imported vulnerabilities: {}", response.message)
            HttpResponse.ok(response)

        } catch (e: Exception) {
            log.error("Error processing vulnerability Excel file", e)
            HttpResponse.status<ErrorResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("An internal error occurred"))
        }
    }

    /**
     * Validate vulnerability Excel file
     *
     * Checks: file size, extension, content type, not empty
     *
     * @param file Uploaded file
     * @return Error message if invalid, null if valid
     */
    private fun validateVulnerabilityFile(file: CompletedFileUpload): String? {
        // Check file size
        if (file.size > MAX_FILE_SIZE) {
            return "File size exceeds maximum limit of ${MAX_FILE_SIZE / 1024 / 1024}MB"
        }

        // Check file extension
        val filename = file.filename.orEmpty()
        if (!filename.lowercase().endsWith(".xlsx")) {
            return "Only .xlsx files are supported"
        }

        // Check content type
        val contentType = file.contentType.map { it.toString() }.orElse("")
        if (!isValidExcelContentType(contentType)) {
            return "Invalid file format. Please upload a valid Excel file (.xlsx)."
        }

        // Check file is not empty
        if (file.size == 0L) {
            return "File is empty"
        }

        return null
    }

    /**
     * Upload user mapping Excel file
     *
     * Feature: 013-user-mapping-upload
     *
     * Endpoint: POST /api/import/upload-user-mappings
     * Request: multipart/form-data with xlsxFile
     * Response: ImportResult with counts (imported, skipped, errors)
     * Access: ADMIN only
     *
     * Expected Excel format:
     * - Column 1: Email Address (required, valid email)
     * - Column 2: AWS Account ID (required, 12 digits)
     * - Column 3: Domain (required, alphanumeric + dots + hyphens)
     *
     * @param xlsxFile Excel file containing user mappings
     * @return Import response with counts and any error messages
     */
    @Post("/upload-user-mappings")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Secured("ADMIN")
    open fun uploadUserMappings(
        @Part xlsxFile: CompletedFileUpload
    ): HttpResponse<*> {
        return try {
            log.debug("Processing user mapping Excel file upload: {}", xlsxFile.filename)

            // Validate file (reuse existing validation method)
            val validation = validateVulnerabilityFile(xlsxFile)
            if (validation != null) {
                return HttpResponse.badRequest(ErrorResponse(validation))
            }

            // Import user mappings
            val response = xlsxFile.inputStream.use { inputStream ->
                userMappingImportService.importFromExcel(inputStream)
            }

            log.info("Successfully imported user mappings: {}", response.message)

            // Chat fan-out for "New AWS account import completed". This path parses the
            // sheet row by row and has no new-vs-known account breakdown, so the event
            // carries the counts only.
            importCompletionNotifier.awsAccountImportCompleted(
                source = "Excel upload (${xlsxFile.filename.orEmpty()})",
                triggeredBy = null,
                processed = response.imported + response.skipped,
                imported = response.imported,
                skipped = response.skipped,
                errorCount = response.errors.size
            )

            HttpResponse.ok(response)

        } catch (e: IllegalArgumentException) {
            // Validation errors (missing headers, etc.)
            log.warn("Validation error in user mapping file: {}", e.message)
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Invalid file format"))
        } catch (e: Exception) {
            log.error("Error processing user mapping Excel file", e)
            HttpResponse.status<ErrorResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("An internal error occurred"))
        }
    }

    /**
     * Upload user mapping CSV file
     *
     * Feature: 016-i-want-to (CSV-Based User Mapping Upload)
     *
     * Endpoint: POST /api/import/upload-user-mappings-csv
     * Request: multipart/form-data with csvFile
     * Response: ImportResult with counts (imported, skipped, errors)
     * Access: ADMIN only
     *
     * Expected CSV format:
     * - Required columns: account_id, owner_email (case-insensitive, any order)
     * - Optional column: domain (defaults to "-NONE-" if omitted)
     * - Max file size: 10MB
     * - Supported encodings: UTF-8, ISO-8859-1
     * - Supported delimiters: comma, semicolon, tab (auto-detected)
     * - Scientific notation: Handles AWS account IDs like 9.98987E+11
     *
     * @param csvFile CSV file containing user mappings
     * @param authentication Authentication context (for logging)
     * @return Import response with counts and any error messages
     */
    @Post("/upload-user-mappings-csv")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Secured("ADMIN")
    open fun uploadUserMappingsCSV(
        @Part csvFile: CompletedFileUpload,
        authentication: Authentication
    ): HttpResponse<*> {
        val startTime = System.currentTimeMillis()
        val username = authentication.name

        return try {
            log.info("CSV upload started: user={}, filename={}, size={}",
                     username, csvFile.filename, csvFile.size)

            // Validate file size
            if (csvFile.size > MAX_FILE_SIZE) {
                val errorMsg = "File size exceeds maximum limit of ${MAX_FILE_SIZE / 1024 / 1024}MB"
                log.warn("CSV upload rejected: {}", errorMsg)
                return HttpResponse.status<ErrorResponse>(HttpStatus.REQUEST_ENTITY_TOO_LARGE)
                    .body(ErrorResponse(errorMsg))
            }

            // Validate file extension
            val filename = csvFile.filename.orEmpty()
            if (!filename.lowercase().endsWith(".csv")) {
                val errorMsg = "Invalid file type: expected .csv file, received ${filename.substringAfterLast('.')}"
                log.warn("CSV upload rejected: {}", errorMsg)
                return HttpResponse.badRequest(ErrorResponse(errorMsg))
            }

            // Validate content type (allow text/csv, application/csv, or generic octet-stream)
            val contentType = csvFile.contentType.map { it.toString() }.orElse("")
            if (!contentType.contains("csv", ignoreCase = true) &&
                !contentType.contains("text", ignoreCase = true) &&
                !contentType.contains("octet-stream", ignoreCase = true)) {
                log.warn("CSV upload: unexpected content-type: {}", contentType)
                // Allow anyway since browsers may send different content types
            }

            // Check file is not empty
            if (csvFile.size == 0L) {
                val errorMsg = "Empty file uploaded"
                log.warn("CSV upload rejected: {}", errorMsg)
                return HttpResponse.badRequest(ErrorResponse(errorMsg))
            }

            // Save to temporary file for processing
            // Security: Use Files.createTempFile with restrictive permissions to prevent TOCTOU attacks
            val tempPath = try {
                // Try to create with restrictive POSIX permissions (owner read/write only)
                val attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                Files.createTempFile("csv_upload_", ".csv", attrs)
            } catch (e: UnsupportedOperationException) {
                // Fallback for non-POSIX systems (Windows)
                Files.createTempFile("csv_upload_", ".csv")
            }
            val tempFile = tempPath.toFile()
            try {
                csvFile.inputStream.use { input ->
                    Files.newOutputStream(tempPath).use { output ->
                        input.copyTo(output)
                    }
                }

                // Parse CSV. The actor is passed through so that any workgroup the
                // display_name column causes to be created records who caused it.
                val result = csvUserMappingParser.parse(tempFile, userIdOrNull(authentication))

                val duration = System.currentTimeMillis() - startTime
                log.info("CSV upload completed: user={}, imported={}, skipped={}, duration={}ms",
                         username, result.imported, result.skipped, duration)

                // Chat fan-out for "New AWS account import completed" (counts only — this
                // path has no new-vs-known account breakdown).
                importCompletionNotifier.awsAccountImportCompleted(
                    source = "CSV upload (${csvFile.filename.orEmpty()})",
                    triggeredBy = username,
                    processed = result.imported + result.skipped,
                    imported = result.imported,
                    skipped = result.skipped,
                    errorCount = result.errors.size
                )

                HttpResponse.ok(result)

            } finally {
                // Clean up temp file
                Files.deleteIfExists(tempPath)
            }

        } catch (e: IllegalArgumentException) {
            // Validation errors (missing headers, empty file, etc.)
            log.warn("CSV upload validation error: user={}, error={}", username, e.message)
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Invalid CSV format"))

        } catch (e: IOException) {
            // File I/O errors
            log.error("CSV upload I/O error: user={}, error={}", username, e.message, e)
            HttpResponse.status<ErrorResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("Failed to read CSV file"))

        } catch (e: Exception) {
            // Unexpected errors
            log.error("CSV upload unexpected error: user={}, error={}", username, e.message, e)
            HttpResponse.status<ErrorResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("An internal error occurred"))
        }
    }

    /**
     * Download CSV template for user mappings
     *
     * Feature: 016-i-want-to (CSV-Based User Mapping Upload)
     *
     * Endpoint: GET /api/import/user-mapping-template-csv
     * Response: CSV file with headers and example row
     * Access: ADMIN only
     *
     * Template format:
     * - Headers: account_id,owner_email,domain
     * - Example row: 123456789012,user@example.com,example.com
     *
     * @return CSV template file as download
     */
    @Get("/user-mapping-template-csv")
    @Produces(MediaType.TEXT_PLAIN)
    @Secured("ADMIN")
    open fun downloadUserMappingTemplateCSV(): HttpResponse<*> {
        return try {
            log.debug("CSV template download requested")

            // Load template from resources
            val templateStream = javaClass.classLoader.getResourceAsStream("templates/user-mapping-template.csv")
                ?: throw IllegalStateException("CSV template file not found in resources")

            val templateContent = templateStream.bufferedReader().use { it.readText() }

            log.info("CSV template downloaded successfully")

            HttpResponse.ok(templateContent)
                .contentType(MediaType.TEXT_PLAIN_TYPE)
                .header("Content-Disposition", "attachment; filename=\"user-mapping-template.csv\"")

        } catch (e: IllegalStateException) {
            log.error("CSV template file missing: {}", e.message)
            HttpResponse.status<ErrorResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("Template file not found"))

        } catch (e: Exception) {
            log.error("Error downloading CSV template: {}", e.message, e)
            HttpResponse.status<ErrorResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("An internal error occurred"))
        }
    }

    /**
     * Upload Masscan XML scan file
     *
     * Related to: Feature 005-add-funtionality-to (Masscan XML Import)
     *
     * Endpoint: POST /api/import/upload-masscan-xml
     * Request: multipart/form-data with xmlFile
     * Response: MasscanImportResponse with counts
     *
     * Default values for auto-created assets:
     * - owner: "Security Team"
     * - type: "Scanned Host"
     * - name: null (Masscan doesn't provide hostname)
     * - description: ""
     *
     * @param xmlFile Masscan XML file to import (max 10MB)
     * @return Import response with counts (assetsCreated, assetsUpdated, portsImported)
     */
    @Post("/upload-masscan-xml")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Secured("ADMIN", "VULN")
    open fun uploadMasscanXml(
        @Part xmlFile: CompletedFileUpload,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            log.debug("Processing Masscan XML file upload: {}", xmlFile.filename)

            val username = authentication.name

            // Validate file
            val validation = validateMasscanFile(xmlFile)
            if (validation != null) {
                return HttpResponse.badRequest(ErrorResponse(validation))
            }

            // Parse XML
            val scanData = masscanParserService.parseMasscanXml(xmlFile.bytes)

            // Create Scan entity
            val scan = com.secman.domain.Scan(
                scanType = "masscan",
                filename = xmlFile.filename,
                scanDate = scanData.scanDate,
                uploadedBy = username,
                hostCount = scanData.hosts.size,
                duration = null  // Masscan doesn't provide duration
            )
            val savedScan = scanRepository.save(scan)

            var assetsCreated = 0
            var assetsUpdated = 0
            var portsImported = 0

            // Import hosts and ports
            for (host in scanData.hosts) {
                try {
                    // Find or create asset by IP
                    val existingAsset = assetRepository.findByIp(host.ipAddress).firstOrNull()
                    val asset = if (existingAsset == null) {
                        // Create with defaults (name = IP since hostname not provided)
                        val newAsset = com.secman.domain.Asset(
                            name = host.ipAddress,  // Use IP as name
                            ip = host.ipAddress,
                            type = "Scanned Host",
                            owner = "Security Team",
                            description = ""
                        )
                        newAsset.lastSeen = host.timestamp
                        val saved = assetRepository.save(newAsset)
                        assetsCreated++
                        saved
                    } else {
                        // Update lastSeen
                        existingAsset.lastSeen = host.timestamp
                        assetRepository.save(existingAsset)
                        assetsUpdated++
                        existingAsset
                    }

                    // Create ScanResult for this host
                    val scanResult = com.secman.domain.ScanResult(
                        scan = savedScan,
                        asset = asset,
                        ipAddress = host.ipAddress,
                        hostname = null,  // Masscan doesn't provide hostname
                        discoveredAt = host.timestamp
                    )

                    // Import ports (only "open" already filtered by parser)
                    for (port in host.ports) {
                        try {
                            val scanPort = com.secman.domain.ScanPort(
                                scanResult = scanResult,
                                portNumber = port.portNumber,
                                protocol = port.protocol,
                                state = port.state,
                                service = null,  // Masscan doesn't provide service detection
                                version = null   // Masscan doesn't provide version detection
                            )
                            scanResult.addPort(scanPort)
                            portsImported++
                        } catch (e: Exception) {
                            log.warn("Failed to import port {}: {}", port.portNumber, e.message)
                        }
                    }

                    // Add result to scan and save
                    savedScan.addResult(scanResult)
                    asset.addScanResult(scanResult)

                } catch (e: Exception) {
                    log.warn("Failed to process host {}: {}", host.ipAddress, e.message)
                }
            }

            // Save scan with all results
            scanRepository.update(savedScan)

            log.info("Successfully imported Masscan scan: {} assets created, {} updated, {} ports imported",
                     assetsCreated, assetsUpdated, portsImported)

            HttpResponse.ok(MasscanImportResponse(
                message = "Imported $portsImported ports across $assetsCreated new assets" +
                         (if (assetsUpdated > 0) ", updated $assetsUpdated existing asset${if (assetsUpdated > 1) "s" else ""}" else ""),
                assetsCreated = assetsCreated,
                assetsUpdated = assetsUpdated,
                portsImported = portsImported
            ))

        } catch (e: Exception) {
            log.error("Error processing Masscan XML file", e)
            HttpResponse.status<ErrorResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("An internal error occurred"))
        }
    }

    /**
     * Validate Masscan XML file
     *
     * Checks: file size, extension, content type, not empty
     *
     * @param file Uploaded file
     * @return Error message if invalid, null if valid
     */
    private fun validateMasscanFile(file: CompletedFileUpload): String? {
        // Check file size
        if (file.size > MAX_FILE_SIZE) {
            return "File size exceeds maximum limit of ${MAX_FILE_SIZE / 1024 / 1024}MB"
        }

        // Check file extension
        val filename = file.filename.orEmpty()
        if (!filename.lowercase().endsWith(".xml")) {
            return "Only .xml files are supported"
        }

        // Check content type
        val contentType = file.contentType.map { it.toString() }.orElse("")
        if (!isValidXmlContentType(contentType)) {
            return "Invalid file format. Please upload a valid XML file."
        }

        // Check file is not empty
        if (file.size == 0L) {
            return "File is empty"
        }

        return null
    }

    @Serdeable
    data class MasscanImportResponse(
        val message: String,
        val assetsCreated: Int,
        val assetsUpdated: Int,
        val portsImported: Int
    )

    /**
     * Import assets from Excel file
     * Feature: 029-asset-bulk-operations (User Story 3 - Import Assets from File)
     *
     * POST /api/import/upload-assets-xlsx
     * Auth: Any authenticated user
     * Request: multipart/form-data with xlsxFile
     * Response: ImportResult
     *
     * Related Requirements:
     * - FR-017: Accept Excel files with validation for file size, format, required fields
     * - FR-018: Validate required fields (name, type, owner)
     * - FR-019: Validate data formats (IP address, type values)
     * - FR-020: Handle duplicate asset names by skipping
     * - FR-021: Associate imported assets with workgroups
     * - FR-022: Track importing user as creator
     * - FR-023: Provide import summary
     *
     * Error Responses:
     * - 400: Invalid file format, validation errors, missing headers
     * - 401: User not authenticated
     * - 500: Import failed
     */
    @Post("/upload-assets-xlsx")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Secured("ADMIN", "VULN")
    open fun uploadAssetsExcel(
        @Part xlsxFile: CompletedFileUpload,
        authentication: Authentication
    ): HttpResponse<*> {
        return try {
            log.info("Asset import request from user: {} with file: {}", authentication.name, xlsxFile.filename)

            // Validate file size
            if (xlsxFile.size > MAX_FILE_SIZE) {
                log.warn("File size exceeds maximum limit: {} bytes", xlsxFile.size)
                return HttpResponse.badRequest(ErrorResponse("File size exceeds maximum limit of 10MB"))
            }

            // Validate file extension
            if (!xlsxFile.filename.endsWith(".xlsx", ignoreCase = true)) {
                log.warn("Invalid file format: {}", xlsxFile.filename)
                return HttpResponse.badRequest(ErrorResponse("Invalid file format. Please upload a valid Excel file (.xlsx)."))
            }

            // Validate file is not empty
            if (xlsxFile.size == 0L) {
                log.warn("Empty file uploaded: {}", xlsxFile.filename)
                return HttpResponse.badRequest(ErrorResponse("File is empty"))
            }

            // Validate content type
            val contentType = xlsxFile.contentType.orElse(null)
            if (contentType != null &&
                !contentType.name.contains("spreadsheet") &&
                !contentType.name.contains("excel") &&
                !contentType.name.contains("application/vnd.openxmlformats")) {
                log.warn("Invalid content type: {}", contentType.name)
                return HttpResponse.badRequest(ErrorResponse("Invalid file format. Please upload an Excel file."))
            }

            // Import assets
            val result = xlsxFile.inputStream.use { stream ->
                assetImportService.importFromExcel(stream, authentication)
            }

            log.info("Asset import complete for user {}: {} imported, {} skipped",
                authentication.name, result.imported, result.skipped)

            HttpResponse.ok(result)

        } catch (e: IllegalArgumentException) {
            log.warn("Asset import validation error: {}", e.message)
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Validation error"))

        } catch (e: IOException) {
            log.error("Asset import IO error", e)
            HttpResponse.serverError<ErrorResponse>()
                .body(ErrorResponse("Failed to read uploaded file"))

        } catch (e: Exception) {
            log.error("Asset import failed for user: {}", authentication.name, e)
            HttpResponse.serverError<ErrorResponse>()
                .body(ErrorResponse("An internal error occurred"))
        }
    }

}