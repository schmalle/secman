package com.secman.controller

import com.secman.domain.Norm
import com.secman.domain.Requirement
import com.secman.domain.UseCase
import com.secman.repository.NormRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.UseCaseRepository
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
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import java.time.LocalDateTime
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.LoggerFactory
import java.io.IOException

@Controller("/api/import")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class ImportController(
    private val requirementRepository: RequirementRepository,
    private val normRepository: NormRepository,
    private val useCaseRepository: UseCaseRepository,
    private val normParsingService: NormParsingService,
    private val entityManager: EntityManager,
    private val vulnerabilityImportService: com.secman.service.VulnerabilityImportService,
    private val masscanParserService: com.secman.service.MasscanParserService,
    private val assetRepository: com.secman.repository.AssetRepository,
    private val scanRepository: com.secman.repository.ScanRepository,
    private val scanResultRepository: com.secman.repository.ScanResultRepository,
    private val userMappingImportService: com.secman.service.UserMappingImportService,
    private val csvUserMappingParser: com.secman.service.CSVUserMappingParser
) {
    
    private val log = LoggerFactory.getLogger(ImportController::class.java)
    
    companion object {
        private const val MAX_FILE_SIZE = 10 * 1024 * 1024L // 10MB
        private const val REQUIRED_SHEET_NAME = "Reqs"
        private val REQUIRED_HEADERS = listOf(
            "Chapter", "Norm", "Short req", "DetailsEN", "MotivationEN", "ExampleEN", "UseCase"
        )
    }

    @Serdeable
    data class ImportResponse(
        val message: String,
        val requirementsProcessed: Int
    )

    @Serdeable
    data class ErrorResponse(
        val error: String
    )

    @Post("/upload-xlsx")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    open fun uploadXlsx(@Part xlsxFile: CompletedFileUpload): HttpResponse<*> {
        return try {
            log.debug("Processing Excel file upload: {}", xlsxFile.filename)
            
            // Validate file
            val validation = validateFile(xlsxFile)
            if (validation != null) {
                return HttpResponse.badRequest(ErrorResponse(validation))
            }
            
            // Process file
            val requirements = parseExcelFile(xlsxFile)
            
            if (requirements.isEmpty()) {
                return HttpResponse.badRequest(ErrorResponse("No valid requirements found in file"))
            }
            
            // Save requirements
            val processedCount = saveRequirements(requirements)
            
            log.info("Successfully processed {} requirements from Excel file", processedCount)
            HttpResponse.ok(ImportResponse(
                message = "File processed successfully.",
                requirementsProcessed = processedCount
            ))
            
        } catch (e: Exception) {
            log.error("Error processing Excel file", e)
            HttpResponse.status<ErrorResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("Error processing file: ${e.message}"))
        }
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
        if (!contentType.contains("spreadsheetml.sheet") && !contentType.contains("excel")) {
            return "Invalid file format. Please upload a valid Excel file."
        }
        
        // Check file is not empty
        if (file.size == 0L) {
            return "File is empty"
        }
        
        return null
    }
    
    private fun parseExcelFile(file: CompletedFileUpload): List<Requirement> {
        val requirements = mutableListOf<Requirement>()
        
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
                val row = sheet.getRow(rowIndex) ?: continue
                
                try {
                    val requirement = parseRowToRequirement(row, headerMap)
                    if (requirement != null) {
                        requirements.add(requirement)
                    }
                } catch (e: Exception) {
                    log.warn("Failed to parse row {}: {}", rowIndex + 1, e.message)
                    // Continue processing other rows
                }
            }
            
            workbook.close()
        }
        
        return requirements
    }
    
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
        
        // Check if all required headers are present (case-insensitive)
        val missingHeaders = REQUIRED_HEADERS.filter { required ->
            actualHeaders.none { actual -> actual.equals(required, ignoreCase = true) }
        }
        
        if (missingHeaders.isNotEmpty()) {
            return "Missing required headers: ${missingHeaders.joinToString(", ")}"
        }
        
        return null
    }
    
    private fun getHeaderMapping(sheet: Sheet): Map<String, Int> {
        val headerRow = sheet.getRow(0)
        val headerMap = mutableMapOf<String, Int>()
        
        for (cellIndex in 0 until headerRow.lastCellNum) {
            val cell = headerRow.getCell(cellIndex)
            if (cell != null) {
                val headerName = getCellValueAsString(cell).trim()
                
                // Map to standardized header names (case-insensitive)
                REQUIRED_HEADERS.forEach { required ->
                    if (headerName.equals(required, ignoreCase = true)) {
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
            try {
                val useCases = parseAndCreateUseCases(useCaseString)
                requirement.usecases = useCases.toMutableSet()
            } catch (e: Exception) {
                log.warn("Failed to parse use cases for requirement '{}': {}", shortreq, e.message)
            }
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
                    val evaluator = cell.sheet.workbook.creationHelper.createFormulaEvaluator()
                    val result = evaluator.evaluate(cell)
                    when (result.cellType) {
                        CellType.STRING -> result.stringValue
                        CellType.NUMERIC -> result.numberValue.toString()
                        CellType.BOOLEAN -> result.booleanValue.toString()
                        else -> ""
                    }
                } catch (e: Exception) {
                    log.warn("Failed to evaluate formula in cell: {}", e.message)
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
    
    @Transactional
    open fun parseAndCreateUseCases(useCaseString: String): List<UseCase> {
        val useCases = mutableListOf<UseCase>()
        
        // Split by comma and create individual use cases
        val useCaseNames = useCaseString.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        for (name in useCaseNames) {
            try {
                val useCase = findOrCreateUseCase(name)
                useCases.add(useCase)
            } catch (e: Exception) {
                log.warn("Failed to create use case '{}': {}", name, e.message)
            }
        }
        
        return useCases
    }
    
    @Transactional
    open fun findOrCreateUseCase(name: String): UseCase {
        // Try to find existing use case (case-insensitive)
        val existing = useCaseRepository.findByNameIgnoreCase(name).orElse(null)
        if (existing != null) {
            log.debug("Found existing use case: {}", name)
            return existing
        }
        
        // Create new use case
        val newUseCase = UseCase(name = name)
        val saved = useCaseRepository.save(newUseCase)
        log.debug("Created new use case: {}", name)
        
        return saved
    }
    
    @Transactional
    open fun saveRequirements(requirements: List<Requirement>): Int {
        var processedCount = 0
        
        for (requirement in requirements) {
            try {
                // Save requirement first
                val savedRequirement = requirementRepository.save(requirement)
                
                // Force flush to ensure it's persisted
                entityManager.flush()
                
                log.debug("Saved requirement: {}", savedRequirement.shortreq)
                processedCount++
                
            } catch (e: Exception) {
                log.warn("Failed to save requirement '{}': {}", requirement.shortreq, e.message)
                // Continue with next requirement instead of failing entire import
            }
        }

        return processedCount
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
    @Transactional
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
                return HttpResponse.badRequest(ErrorResponse("Invalid scan date format. Expected ISO 8601: ${e.message}"))
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
                .body(ErrorResponse("Error processing file: ${e.message}"))
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
        if (!contentType.contains("spreadsheetml.sheet") && !contentType.contains("excel") && !contentType.contains("octet-stream")) {
            return "Invalid file format. Please upload a valid Excel file."
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
    @Transactional
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
            HttpResponse.ok(response)

        } catch (e: IllegalArgumentException) {
            // Validation errors (missing headers, etc.)
            log.warn("Validation error in user mapping file: {}", e.message)
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Invalid file format"))
        } catch (e: Exception) {
            log.error("Error processing user mapping Excel file", e)
            HttpResponse.status<ErrorResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("Error processing file: ${e.message}"))
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
    @Transactional
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
            val tempFile = java.io.File.createTempFile("csv_upload_", ".csv")
            try {
                csvFile.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // Parse CSV
                val result = csvUserMappingParser.parse(tempFile)

                val duration = System.currentTimeMillis() - startTime
                log.info("CSV upload completed: user={}, imported={}, skipped={}, duration={}ms",
                         username, result.imported, result.skipped, duration)

                HttpResponse.ok(result)

            } finally {
                // Clean up temp file
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }

        } catch (e: IllegalArgumentException) {
            // Validation errors (missing headers, empty file, etc.)
            log.warn("CSV upload validation error: user={}, error={}", username, e.message)
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Invalid CSV format"))

        } catch (e: IOException) {
            // File I/O errors
            log.error("CSV upload I/O error: user={}, error={}", username, e.message, e)
            HttpResponse.status<ErrorResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("Error reading CSV file: ${e.message}"))

        } catch (e: Exception) {
            // Unexpected errors
            log.error("CSV upload unexpected error: user={}, error={}", username, e.message, e)
            HttpResponse.status<ErrorResponse>(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse("Error processing CSV file: ${e.message}"))
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
                .body(ErrorResponse("Error downloading template: ${e.message}"))
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
    @Transactional
    @Secured(SecurityRule.IS_AUTHENTICATED)
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
                .body(ErrorResponse("Error processing file: ${e.message}"))
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
        if (!contentType.contains("xml") && !contentType.contains("octet-stream")) {
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
}