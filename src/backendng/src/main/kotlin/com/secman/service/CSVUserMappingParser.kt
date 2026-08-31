package com.secman.service

import com.secman.domain.UserMapping
import com.secman.dto.WorkgroupAccountLinkSummary
import com.secman.repository.UserMappingRepository
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
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
 * - Optional column: display_name (AWS account display name — stored on the mapping and
 *   used to link the account to the workgroup named "aws-<display_name>")
 * - Extra columns: Ignored
 * - Max file size: 10MB
 * - Supported encodings: UTF-8, ISO-8859-1
 * - Supported delimiters: comma, semicolon, tab
 */
@Singleton
open class CSVUserMappingParser(
    private val userMappingRepository: UserMappingRepository,
    private val workgroupAccountLinkService: WorkgroupAccountLinkService
) {
    private val log = LoggerFactory.getLogger(CSVUserMappingParser::class.java)

    companion object {
        private val REQUIRED_HEADERS = listOf("account_id", "owner_email")
        private const val AWS_ACCOUNT_ID_PATTERN = "^\\d{12}$"
        private const val DOMAIN_PATTERN = "^[a-z0-9.-]+$"
        private const val DEFAULT_DOMAIN = "-NONE-"
        /** Matches the aws_account_name column width (V260). */
        private const val MAX_ACCOUNT_NAME_LENGTH = 255
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
        val errors: List<ImportError> = emptyList(),
        /**
         * What workgroup linking did for the accounts whose rows carried a display_name.
         * Null when the file had no such column — i.e. for every file that imported
         * cleanly before this column was understood.
         */
        val workgroupLinks: WorkgroupAccountLinkSummary? = null
    )

    /**
     * Parse and import user mappings from CSV file
     *
     * @param file CSV file to parse
     * @param actorId the ADMIN performing the upload — recorded as the creator of any
     *        workgroup or account assignment the display_name column causes. Defaults to
     *        null for callers that do not have one.
     * @return ImportResult with counts and error details
     * @throws IllegalArgumentException if file format is invalid or headers are missing
     */
    @Suppress("DEPRECATION")
    open fun parse(file: File, actorId: Long? = null): ImportResult {
        log.info("Starting CSV parsing: file={}, size={}", file.name, file.length())

        val tally = RowTally()
        var imported = 0

        try {
            val parser = openParser(file)
            val headerMap = parser.headerMap
            validateHeaders(headerMap)?.let { throw IllegalArgumentException(it) }
            log.debug("CSV headers validated: {}", headerMap.keys)

            readRows(parser, headerMap, tally)

            if (tally.validMappings.isNotEmpty()) {
                userMappingRepository.saveAll(tally.validMappings)
                imported = tally.validMappings.size
                log.info("Batch persisted {} user mappings", imported)
            }

            parser.close()
        } catch (e: IllegalArgumentException) {
            // A malformed file is the caller's answer, not our failure — pass it through
            // untouched so the message reaching the operator is the specific one.
            throw e
        } catch (e: Exception) {
            log.error("CSV parsing failed: {}", e.message, e)
            throw IllegalArgumentException("Unable to parse CSV file: ${e.message}")
        }

        // The name describes the account, so it is written for every mapping of it —
        // including the rows this run skipped as duplicates, which is the common case on
        // a re-import and the only way the correction path can see them later.
        applyAccountNames(tally.displayNamePairs)

        // After the rows are persisted, never before: a workgroup that cannot be created
        // must not cost us the mappings themselves.
        val workgroupLinks = linkWorkgroups(tally.displayNamePairs, actorId)

        val message = when {
            imported > 0 -> "Successfully imported $imported user mappings"
            tally.skipped > 0 -> "No valid mappings found, skipped ${tally.skipped} rows"
            else -> "No data rows found in file"
        }

        log.info("CSV parsing complete: imported={}, skipped={}, errors={}",
            imported, tally.skipped, tally.errors.size)

        return ImportResult(
            message = message,
            imported = imported,
            skipped = tally.skipped,
            errors = tally.errors.take(MAX_ERRORS_RETURNED),
            workgroupLinks = workgroupLinks
        )
    }

    /**
     * What [parse] accumulates while reading rows.
     *
     * These travel together because one row commonly touches several at once — a row
     * already in the database bumps `skipped`, adds an error, and still contributes its
     * display name — and threading six separate accumulators through the call chain is
     * how they drift out of step.
     */
    private class RowTally {
        val errors = mutableListOf<ImportError>()
        val validMappings = mutableListOf<UserMapping>()
        val displayNamePairs = mutableListOf<WorkgroupAccountLinkService.AccountDisplayName>()
        val seenMappings = mutableSetOf<String>()
        var skipped = 0
    }

    /**
     * Opens the file twice on purpose: the delimiter can only be guessed from the header
     * line, and the parser then has to start *at* that line rather than after it, so the
     * probing read cannot be reused.
     */
    private fun openParser(file: File): CSVParser {
        val probe = detectEncodingAndRead(file)
        val firstLine = try {
            probe.readLine()
        } finally {
            probe.close()
        } ?: throw IllegalArgumentException("Empty file uploaded")

        val delimiter = detectDelimiter(firstLine)
        log.debug("Detected delimiter: '{}'", if (delimiter == '\t') "TAB" else delimiter)

        val csvFormat = CSVFormat.RFC4180.builder()
            .setDelimiter(delimiter)
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build()

        return CSVParser(detectEncodingAndRead(file), csvFormat)
    }

    /**
     * Reads every row into [tally]. A row that throws is recorded and skipped rather than
     * failing the import: one malformed line in a ten-thousand-row export must not cost
     * the other 9,999.
     */
    private fun readRows(parser: CSVParser, headerMap: Map<String, Int>, tally: RowTally) {
        var lineNumber = 1 // the header is line 1

        for (record in parser) {
            lineNumber++
            try {
                val mapping = parseRecord(record, headerMap, lineNumber, tally.seenMappings)
                if (mapping == null) {
                    tally.skipped++ // duplicate within the file, or an empty row
                    continue
                }
                recordMapping(mapping, lineNumber, tally)
            } catch (e: Exception) {
                tally.skipped++
                tally.errors.add(ImportError(
                    line = lineNumber,
                    field = null,
                    reason = e.message ?: "Unknown error",
                    value = null
                ))
                log.warn("Failed to parse line {}: {}", lineNumber, e.message)
            }
        }
    }

    /**
     * Files a single parsed row.
     *
     * The display name is collected before the duplicate check, not after: a mapping
     * imported before the display_name column was understood is already in the database,
     * so it lands here as a skip — and that is exactly the row whose workgroup link is
     * still missing.
     */
    private fun recordMapping(mapping: UserMapping, lineNumber: Int, tally: RowTally) {
        val accountId = mapping.awsAccountId
        val displayName = mapping.awsAccountName
        if (accountId != null && displayName != null) {
            tally.displayNamePairs.add(
                WorkgroupAccountLinkService.AccountDisplayName(accountId, displayName)
            )
        }

        val alreadyStored = userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
            mapping.email, mapping.awsAccountId, mapping.domain
        )
        if (!alreadyStored) {
            tally.validMappings.add(mapping)
            return
        }

        tally.skipped++
        tally.errors.add(ImportError(
            line = lineNumber,
            field = null,
            reason = "Duplicate mapping already exists in database",
            value = "${mapping.email} / ${mapping.awsAccountId} / ${mapping.domain}"
        ))
        log.debug("Skipped duplicate (DB) at line {}: {}", lineNumber, mapping)
    }

    /**
     * A failure here degrades to a summary reporting every account as failed. The
     * mappings are already committed by this point and must not be lost to a workgroup
     * problem, so the exception stops at this boundary.
     */
    private fun linkWorkgroups(
        pairs: List<WorkgroupAccountLinkService.AccountDisplayName>,
        actorId: Long?
    ): WorkgroupAccountLinkSummary? {
        if (pairs.isEmpty()) return null
        return try {
            workgroupAccountLinkService.link(pairs, actorId, dryRun = false)
        } catch (e: Exception) {
            log.error("Workgroup linking failed after a successful CSV mapping import", e)
            WorkgroupAccountLinkSummary(failed = pairs.size)
        }
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
     * Parse AWS account ID, handling scientific notation and leading zero padding
     *
     * Excel/database exports may:
     * 1. Strip leading zeros (e.g., "041001014175" → "41001014175")
     * 2. Use scientific notation for large numbers (e.g., "487510000000" → "4.8751E+11")
     *
     * This method:
     * - Uses BigDecimal to preserve precision and convert scientific notation
     * - Pads account IDs with leading zeros if they're 1-11 digits (AWS IDs are always 12 digits)
     *
     * @param value Raw account ID value (may be numeric string, scientific notation, or missing leading zeros)
     * @return 12-digit account ID string (padded with leading zeros if needed), or null if invalid
     */
    private fun parseAccountId(value: String): String? {
        val trimmed = value.trim()

        // Try direct parsing first (for normal strings like "123456789012")
        if (trimmed.matches(Regex("^\\d{12}$"))) {
            return trimmed
        }

        // Handle numeric strings (including those with stripped leading zeros) and scientific notation
        return try {
            val bigDecimal = BigDecimal(trimmed)
            val longValue = bigDecimal.toLong()
            val accountId = longValue.toString()

            // AWS account IDs are always 12 digits
            // If we have 1-11 digits, pad with leading zeros (leading zeros were likely stripped during export)
            // If we have more than 12 digits or 0 digits, reject as invalid
            when (accountId.length) {
                in 1..11 -> accountId.padStart(12, '0') // Pad with leading zeros
                12 -> accountId // Already correct length
                else -> null // Invalid length (0 or >12 digits)
            }
        } catch (e: NumberFormatException) {
            null // Invalid format (non-numeric)
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
        // Optional. Over-long names are dropped rather than truncated — a truncated name
        // would resolve to a different (wrong) workgroup.
        val displayName = getColumnValue(record, headerMap, "display_name")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_ACCOUNT_NAME_LENGTH }

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

        // Domain handling: an empty/sentinel domain ("-NONE-", "none", etc.)
        // means "no domain assigned" and is stored as SQL NULL. Earlier
        // versions of this parser substituted the literal "-NONE-" string;
        // that produced rows that the (then NULL-blind) dedup check could not
        // match against real-NULL rows from other import paths, leaving two
        // physical rows for the same logical mapping. Coercing to NULL here
        // keeps every import path on the same canonical key.
        val normalizedDomain: String? = if (domainRaw.isNullOrBlank()) {
            null
        } else {
            val canonical = UserMapping.normalizeNullSentinel(domainRaw.lowercase().trim())
            if (canonical != null && !validateDomain(canonical)) {
                throw IllegalArgumentException("Invalid domain format: $domainRaw")
            }
            canonical
        }

        // Normalize email
        val normalizedEmail = ownerEmail.lowercase()

        // Check for duplicate within file (use canonical key — null collapses
        // empties and sentinels into the same bucket)
        val mappingKey = "$normalizedEmail|$accountId|${normalizedDomain ?: ""}"
        if (seenMappings.contains(mappingKey)) {
            log.debug("Skipped duplicate within file at line {}: {}", lineNumber, mappingKey)
            // Return null to skip without error
            return null
        }
        seenMappings.add(mappingKey)

        return UserMapping(
            email = normalizedEmail,
            awsAccountId = accountId,
            awsAccountName = displayName,
            domain = normalizedDomain
        )
    }

    /**
     * Apply the display names the file carried to every mapping of each account — one
     * statement per account, including rows this run skipped as duplicates.
     *
     * Best-effort: the import's real work is the new rows, so a failed write is logged
     * and parsing continues.
     */
    private fun applyAccountNames(pairs: List<WorkgroupAccountLinkService.AccountDisplayName>) {
        val now = java.time.Instant.now()
        pairs.associate { it.awsAccountId to it.displayName }.forEach { (awsAccountId, displayName) ->
            try {
                userMappingRepository.updateAwsAccountName(awsAccountId, displayName, now)
            } catch (e: Exception) {
                log.warn("Could not set AWS account display name for {}: {}", awsAccountId, e.message)
            }
        }
    }
}
