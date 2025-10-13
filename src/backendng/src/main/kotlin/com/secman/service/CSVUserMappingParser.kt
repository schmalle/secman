package com.secman.service

import com.secman.domain.UserMapping
import com.secman.repository.UserMappingRepository
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Service for parsing and importing user mappings from CSV files
 *
 * Feature: 016-i-want-to (CSV-Based User Mapping Upload)
 *
 * Responsibilities:
 * - Parse CSV files (RFC 4180 compliant)
 * - Auto-detect encoding (UTF-8 BOM, UTF-8, ISO-8859-1 fallback)
 * - Auto-detect delimiter (comma, semicolon, tab)
 * - Handle scientific notation in AWS account IDs (e.g., 9.98987E+11)
 * - Validate headers (case-insensitive matching)
 * - Validate each row (email, AWS account ID format, domain format)
 * - Skip invalid rows, continue processing valid rows
 * - Detect and skip duplicate mappings
 * - Return detailed import results with structured error information
 *
 * CSV Format:
 * - Required columns: account_id, owner_email (case-insensitive, any order)
 * - Optional column: domain (defaults to "-NONE-" if omitted)
 * - Extra columns: Ignored
 * - Max file size: 10MB
 * - Supported encodings: UTF-8, ISO-8859-1
 * - Supported delimiters: comma, semicolon, tab
 *
 * Related to: Feature 016 (CSV-Based User Mapping Upload), Feature 013 (Excel upload)
 */
@Singleton
open class CSVUserMappingParser(
    private val userMappingRepository: UserMappingRepository
) {
    private val log = LoggerFactory.getLogger(CSVUserMappingParser::class.java)

    companion object {
        private val REQUIRED_HEADERS = listOf("account_id", "owner_email")
        private const val AWS_ACCOUNT_ID_PATTERN = "^\\d{12}$"
        private const val DOMAIN_PATTERN = "^[a-z0-9.-]+$"
        private const val DEFAULT_DOMAIN = "-NONE-"
        private const val MAX_ERRORS_RETURNED = 50
    }

    /**
     * Data class for structured import error information
     */
    @Serdeable
    data class ImportError(
        val line: Int,
        val field: String?,
        val reason: String,
        val value: String?
    )

    /**
     * Data class for import results
     */
    @Serdeable
    data class ImportResult(
        val message: String,
        val imported: Int,
        val skipped: Int,
        val errors: List<ImportError> = emptyList()
    )

    /**
     * Parse and import user mappings from CSV file
     *
     * @param file CSV file to parse
     * @return ImportResult with counts and error details
     * @throws IllegalArgumentException if file format is invalid or headers are missing
     */
    @Transactional
    open fun parse(file: File): ImportResult {
        log.info("Starting CSV parsing: file={}, size={}", file.name, file.length())

        val errors = mutableListOf<ImportError>()
        val validMappings = mutableListOf<UserMapping>()
        val seenMappings = mutableSetOf<String>() // Track duplicates within file
        var imported = 0
        var skipped = 0
        var lineNumber = 1 // Start at 1 for header

        try {
            // Step 1: Detect encoding and create BufferedReader
            val reader = detectEncodingAndRead(file)

            // Step 2: Read first line for delimiter detection
            val firstLine = reader.readLine()
            if (firstLine == null) {
                throw IllegalArgumentException("Empty file uploaded")
            }

            // Step 3: Detect delimiter
            val delimiter = detectDelimiter(firstLine)
            log.debug("Detected delimiter: '{}'", if (delimiter == '\t') "TAB" else delimiter)

            // Step 4: Create CSVParser with detected settings
            // Recreate reader since we consumed the first line
            reader.close()
            val newReader = detectEncodingAndRead(file)

            val csvFormat = CSVFormat.RFC4180.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build()

            val parser = CSVParser(newReader, csvFormat)

            // Step 5: Validate headers
            val headerMap = parser.headerMap
            val missingHeaders = validateHeaders(headerMap)
            if (missingHeaders != null) {
                throw IllegalArgumentException(missingHeaders)
            }

            log.debug("CSV headers validated: {}", headerMap.keys)

            // Step 6: Process each row
            for (record in parser) {
                lineNumber++

                try {
                    val mapping = parseRecord(record, headerMap, lineNumber, seenMappings)

                    if (mapping != null) {
                        // Check for duplicate in database
                        if (userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                            mapping.email, mapping.awsAccountId, mapping.domain
                        )) {
                            skipped++
                            errors.add(ImportError(
                                line = lineNumber,
                                field = null,
                                reason = "Duplicate mapping already exists in database",
                                value = "${mapping.email} / ${mapping.awsAccountId} / ${mapping.domain}"
                            ))
                            log.debug("Skipped duplicate (DB) at line {}: {}", lineNumber, mapping)
                        } else {
                            validMappings.add(mapping)
                        }
                    } else {
                        // Null means skipped (duplicate within file or empty row)
                        skipped++
                    }
                } catch (e: Exception) {
                    skipped++
                    errors.add(ImportError(
                        line = lineNumber,
                        field = null,
                        reason = e.message ?: "Unknown error",
                        value = null
                    ))
                    log.warn("Failed to parse line {}: {}", lineNumber, e.message)
                }
            }

            // Step 7: Batch persist all valid mappings
            if (validMappings.isNotEmpty()) {
                userMappingRepository.saveAll(validMappings)
                imported = validMappings.size
                log.info("Batch persisted {} user mappings", imported)
            }

            parser.close()

        } catch (e: IllegalArgumentException) {
            // Re-throw validation errors (missing headers, empty file)
            throw e
        } catch (e: Exception) {
            log.error("CSV parsing failed: {}", e.message, e)
            throw IllegalArgumentException("Unable to parse CSV file: ${e.message}")
        }

        val message = when {
            imported > 0 -> "Successfully imported $imported user mappings"
            skipped > 0 -> "No valid mappings found, skipped $skipped rows"
            else -> "No data rows found in file"
        }

        log.info("CSV parsing complete: imported={}, skipped={}, errors={}",
            imported, skipped, errors.size)

        return ImportResult(
            message = message,
            imported = imported,
            skipped = skipped,
            errors = errors.take(MAX_ERRORS_RETURNED)
        )
    }

    /**
     * Detect encoding and create BufferedReader
     *
     * Checks for UTF-8 BOM (EF BB BF), otherwise attempts UTF-8 with ISO-8859-1 fallback
     *
     * @param file File to read
     * @return BufferedReader with detected encoding
     */
    private fun detectEncodingAndRead(file: File): BufferedReader {
        val inputStream = FileInputStream(file)
        val bomBytes = ByteArray(3)
        val bytesRead = inputStream.read(bomBytes)

        // Check for UTF-8 BOM (EF BB BF)
        if (bytesRead == 3 &&
            bomBytes[0] == 0xEF.toByte() &&
            bomBytes[1] == 0xBB.toByte() &&
            bomBytes[2] == 0xBF.toByte()) {

            log.debug("Detected UTF-8 BOM, using UTF-8 encoding")
            // BOM detected, skip it and use UTF-8
            return InputStreamReader(inputStream, Charsets.UTF_8).buffered()
        }

        // No BOM, close and try UTF-8 (most common)
        inputStream.close()

        return try {
            log.debug("No BOM detected, attempting UTF-8 encoding")
            file.bufferedReader(Charsets.UTF_8)
        } catch (e: Exception) {
            log.debug("UTF-8 decoding failed, falling back to ISO-8859-1")
            file.bufferedReader(Charsets.ISO_8859_1)
        }
    }

    /**
     * Detect CSV delimiter from first line
     *
     * Counts occurrences of comma, semicolon, and tab, returns most frequent
     * Defaults to comma if no delimiters found
     *
     * @param firstLine First line of CSV (header row)
     * @return Detected delimiter character
     */
    private fun detectDelimiter(firstLine: String): Char {
        val commaCount = firstLine.count { it == ',' }
        val semicolonCount = firstLine.count { it == ';' }
        val tabCount = firstLine.count { it == '\t' }

        return when {
            commaCount >= semicolonCount && commaCount >= tabCount -> ','
            semicolonCount >= commaCount && semicolonCount >= tabCount -> ';'
            tabCount >= commaCount && tabCount >= semicolonCount -> '\t'
            else -> ',' // Default to comma
        }
    }

    /**
     * Parse AWS account ID, handling scientific notation
     *
     * Excel exports large numbers in scientific notation (e.g., 9.98987E+11)
     * This method uses BigDecimal to preserve precision and convert to 12-digit string
     *
     * @param value Raw account ID value (may be numeric string or scientific notation)
     * @return 12-digit account ID string, or null if invalid
     */
    private fun parseAccountId(value: String): String? {
        val trimmed = value.trim()

        // Try direct parsing first (for normal strings like "123456789012")
        if (trimmed.matches(Regex("^\\d{12}$"))) {
            return trimmed
        }

        // Handle scientific notation (e.g., "9.98987E+11")
        return try {
            val bigDecimal = BigDecimal(trimmed)
            val longValue = bigDecimal.toLong()
            val accountId = longValue.toString()

            // Validate exactly 12 digits
            if (accountId.matches(Regex("^\\d{12}$"))) {
                accountId
            } else {
                null // Invalid length
            }
        } catch (e: NumberFormatException) {
            null // Invalid format
        }
    }

    /**
     * Validate email address format
     *
     * Basic validation: must contain @, length 3-255, @ not at start/end
     *
     * @param email Email address to validate
     * @return true if valid, false otherwise
     */
    private fun validateEmail(email: String): Boolean {
        return email.contains("@") &&
               email.length >= 3 &&
               email.length <= 255 &&
               email.indexOf("@") > 0 &&
               email.indexOf("@") < email.length - 1
    }

    /**
     * Validate domain format
     *
     * Allowed: lowercase alphanumeric, dots, hyphens
     * Special case: "-NONE-" sentinel value is valid
     *
     * @param domain Domain name to validate
     * @return true if valid, false otherwise
     */
    private fun validateDomain(domain: String): Boolean {
        val normalized = domain.lowercase()

        // Special case: sentinel value
        if (normalized == DEFAULT_DOMAIN.lowercase()) {
            return true
        }

        return normalized.matches(Regex(DOMAIN_PATTERN)) &&
               !normalized.startsWith(".") &&
               !normalized.endsWith(".") &&
               !normalized.startsWith("-") &&
               !normalized.endsWith("-") &&
               !normalized.contains(" ")
    }

    /**
     * Validate that required headers are present (case-insensitive)
     *
     * @param headerMap CSV header map from parser
     * @return Error message if headers missing, null if valid
     */
    private fun validateHeaders(headerMap: Map<String, Int>): String? {
        val lowerHeaderMap = headerMap.keys.map { it.lowercase() }

        val missingHeaders = REQUIRED_HEADERS.filter { required ->
            !lowerHeaderMap.contains(required.lowercase())
        }

        if (missingHeaders.isNotEmpty()) {
            return "Missing required columns: ${missingHeaders.joinToString(", ")}"
        }

        return null
    }

    /**
     * Get CSV column value by header name (case-insensitive)
     *
     * @param record CSV record
     * @param headerMap Header map from parser
     * @param headerName Header name to look up (case-insensitive)
     * @return Column value or null if not found
     */
    private fun getColumnValue(
        record: CSVRecord,
        headerMap: Map<String, Int>,
        headerName: String
    ): String? {
        // Try exact match first
        if (headerMap.containsKey(headerName)) {
            return record.get(headerName)?.trim()
        }

        // Try case-insensitive match
        val matchingKey = headerMap.keys.find {
            it.equals(headerName, ignoreCase = true)
        }

        return matchingKey?.let { record.get(it)?.trim() }
    }

    /**
     * Parse a single CSV record into a UserMapping
     *
     * @param record CSV record to parse
     * @param headerMap Header mapping from parser
     * @param lineNumber Line number for error reporting (1-based)
     * @param seenMappings Set to track duplicates within file
     * @return UserMapping if valid, null if should be skipped
     * @throws IllegalArgumentException if validation fails
     */
    private fun parseRecord(
        record: CSVRecord,
        headerMap: Map<String, Int>,
        lineNumber: Int,
        seenMappings: MutableSet<String>
    ): UserMapping? {
        // Extract values (case-insensitive header matching)
        val accountIdRaw = getColumnValue(record, headerMap, "account_id")
        val ownerEmail = getColumnValue(record, headerMap, "owner_email")
        val domainRaw = getColumnValue(record, headerMap, "domain")

        // Validate required fields
        if (ownerEmail.isNullOrBlank()) {
            throw IllegalArgumentException("owner_email is required")
        }

        if (accountIdRaw.isNullOrBlank()) {
            throw IllegalArgumentException("account_id is required")
        }

        // Parse account ID (handle scientific notation)
        val accountId = parseAccountId(accountIdRaw)
            ?: throw IllegalArgumentException("account_id must be exactly 12 numeric digits (got: $accountIdRaw)")

        // Validate email format
        if (!validateEmail(ownerEmail)) {
            throw IllegalArgumentException("Invalid email format: $ownerEmail")
        }

        // Handle domain (default to "-NONE-" if empty)
        val domain = if (domainRaw.isNullOrBlank()) {
            DEFAULT_DOMAIN
        } else {
            domainRaw
        }

        // Validate domain format
        if (!validateDomain(domain)) {
            throw IllegalArgumentException("Invalid domain format: $domain")
        }

        // Normalize
        val normalizedEmail = ownerEmail.lowercase()
        val normalizedDomain = domain.lowercase()

        // Check for duplicate within file
        val mappingKey = "$normalizedEmail|$accountId|$normalizedDomain"
        if (seenMappings.contains(mappingKey)) {
            log.debug("Skipped duplicate within file at line {}: {}", lineNumber, mappingKey)
            // Return null to skip without error
            return null
        }
        seenMappings.add(mappingKey)

        return UserMapping(
            email = normalizedEmail,
            awsAccountId = accountId,
            domain = normalizedDomain
        )
    }
}
