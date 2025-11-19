package com.secman.cli.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.secman.domain.MappingStatus
import com.secman.domain.User
import com.secman.domain.UserMapping
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import jakarta.inject.Singleton
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.time.Instant

/**
 * CLI service for user mapping operations (Feature 049)
 *
 * Provides business logic for creating, listing, and removing user mappings
 * via CLI commands. Handles validation, duplicate detection, and audit logging.
 *
 * Related to: Feature 042 (Future User Mappings), Feature 049 (CLI User Mapping)
 */
@Singleton
class UserMappingCliService(
    private val userMappingRepository: UserMappingRepository,
    private val userRepository: UserRepository
) {
    private val log = LoggerFactory.getLogger(UserMappingCliService::class.java)

    // Validation regex patterns (from spec FR-003, FR-004, FR-006)
    private val emailRegex = Regex("^[^@]+@[^@]+\\.[^@]+$")
    private val awsAccountIdRegex = Regex("^\\d{12}$")
    private val domainRegex = Regex("^[a-zA-Z0-9.-]+$")

    /**
     * Add domain-to-user mappings
     *
     * Creates n×m mappings (cross product of emails and domains)
     * Handles duplicate detection, user existence check, and pending mappings
     *
     * @param emails List of user email addresses
     * @param domains List of AD domain names
     * @param adminEmail Admin user executing the command (for audit logging)
     * @return Result summary (created, skipped, errors)
     */
    fun addDomainMappings(
        emails: List<String>,
        domains: List<String>,
        adminEmail: String
    ): MappingResult {
        val results = mutableListOf<MappingOperationResult>()

        // Validate inputs
        val invalidEmails = emails.filter { !emailRegex.matches(it) }
        if (invalidEmails.isNotEmpty()) {
            throw IllegalArgumentException("Invalid email format: ${invalidEmails.joinToString()}")
        }

        val invalidDomains = domains.filter { !domainRegex.matches(it) }
        if (invalidDomains.isNotEmpty()) {
            throw IllegalArgumentException("Invalid domain format: ${invalidDomains.joinToString()}")
        }

        // Create cross-product mappings
        for (email in emails) {
            for (domain in domains) {
                val result = createMapping(
                    email = email.lowercase().trim(),
                    domain = domain.lowercase().trim(),
                    awsAccountId = null,
                    adminEmail = adminEmail,
                    operationType = "CREATE_DOMAIN_MAPPING"
                )
                results.add(result)
            }
        }

        return summarizeResults(results)
    }

    /**
     * Add AWS-account-to-user mappings
     *
     * Creates n×m mappings (cross product of emails and AWS accounts)
     * Validates 12-digit AWS account ID format
     *
     * @param emails List of user email addresses
     * @param awsAccountIds List of AWS account IDs (12 digits)
     * @param adminEmail Admin user executing the command
     * @return Result summary
     */
    fun addAwsAccountMappings(
        emails: List<String>,
        awsAccountIds: List<String>,
        adminEmail: String
    ): MappingResult {
        val results = mutableListOf<MappingOperationResult>()

        // Validate inputs
        val invalidEmails = emails.filter { !emailRegex.matches(it) }
        if (invalidEmails.isNotEmpty()) {
            throw IllegalArgumentException("Invalid email format: ${invalidEmails.joinToString()}")
        }

        val invalidAccounts = awsAccountIds.filter { !awsAccountIdRegex.matches(it) }
        if (invalidAccounts.isNotEmpty()) {
            throw IllegalArgumentException("Invalid AWS account ID (must be 12 digits): ${invalidAccounts.joinToString()}")
        }

        // Create cross-product mappings
        for (email in emails) {
            for (accountId in awsAccountIds) {
                val result = createMapping(
                    email = email.lowercase().trim(),
                    domain = null,
                    awsAccountId = accountId.trim(),
                    adminEmail = adminEmail,
                    operationType = "CREATE_AWS_MAPPING"
                )
                results.add(result)
            }
        }

        return summarizeResults(results)
    }

    /**
     * Create a single user mapping with duplicate detection and status determination
     *
     * @return Result indicating CREATED, SKIPPED_DUPLICATE, or error
     */
    private fun createMapping(
        email: String,
        domain: String?,
        awsAccountId: String?,
        adminEmail: String,
        operationType: String
    ): MappingOperationResult {
        try {
            // Check for duplicate
            val exists = userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                email, awsAccountId, domain
            )

            if (exists) {
                log.debug("Skipping duplicate mapping: email=$email, domain=$domain, awsAccountId=$awsAccountId")
                return MappingOperationResult(
                    success = true,
                    operation = "SKIPPED_DUPLICATE",
                    message = "Mapping already exists",
                    email = email,
                    domain = domain,
                    awsAccountId = awsAccountId
                )
            }

            // Check if user exists
            val user = userRepository.findByEmailIgnoreCase(email).orElse(null)
            val status = if (user != null) MappingStatus.ACTIVE else MappingStatus.PENDING

            // Create mapping
            val mapping = UserMapping(
                email = email,
                user = user,
                domain = domain,
                awsAccountId = awsAccountId,
                ipAddress = null,
                status = status
            )

            if (user != null) {
                mapping.appliedAt = Instant.now()
            }

            userMappingRepository.save(mapping)

            // Audit log
            log.info(
                "AUDIT: operation=$operationType, actor=$adminEmail, " +
                "email=$email, domain=$domain, awsAccountId=$awsAccountId, status=$status"
            )

            val messageStatus = if (status == MappingStatus.PENDING) " (pending - user not found)" else ""
            return MappingOperationResult(
                success = true,
                operation = "CREATED",
                message = "Created$messageStatus",
                email = email,
                domain = domain,
                awsAccountId = awsAccountId,
                isPending = status == MappingStatus.PENDING
            )

        } catch (e: Exception) {
            log.error("Failed to create mapping: email=$email, domain=$domain, awsAccountId=$awsAccountId", e)
            return MappingOperationResult(
                success = false,
                operation = "ERROR",
                message = e.message ?: "Unknown error",
                email = email,
                domain = domain,
                awsAccountId = awsAccountId
            )
        }
    }

    /**
     * List user mappings with optional filtering
     *
     * @param email Filter by specific email (optional, null = all users)
     * @param status Filter by status (ACTIVE, PENDING, or null = all statuses)
     * @return List of user mappings matching criteria
     */
    fun listMappings(
        email: String? = null,
        status: MappingStatus? = null
    ): List<UserMapping> {
        return when {
            email != null && status != null -> {
                // Filter by both email and status
                userMappingRepository.findByEmailAndStatus(email.lowercase().trim(), status)
            }
            email != null -> {
                // Filter by email only
                userMappingRepository.findByEmail(email.lowercase().trim())
            }
            status != null -> {
                // Filter by status only - need to get all and filter
                userMappingRepository.findAll().filter { it.status == status }
            }
            else -> {
                // No filters - return all
                userMappingRepository.findAll().toList()
            }
        }
    }

    /**
     * Remove user mappings based on criteria
     *
     * @param email User email (required)
     * @param domain Specific domain to remove (optional)
     * @param awsAccountId Specific AWS account to remove (optional)
     * @param removeAll Remove all mappings for user (default: false)
     * @param adminEmail Admin user executing the command
     * @return Number of mappings removed
     */
    fun removeMappings(
        email: String,
        domain: String? = null,
        awsAccountId: String? = null,
        removeAll: Boolean = false,
        adminEmail: String
    ): Int {
        val normalizedEmail = email.lowercase().trim()

        // Find mappings to delete
        val mappingsToDelete = when {
            removeAll -> {
                // Remove all mappings for this email
                userMappingRepository.findByEmail(normalizedEmail)
            }
            domain != null -> {
                // Remove specific domain mapping
                val mapping = userMappingRepository.findByEmailAndAwsAccountIdAndDomain(
                    normalizedEmail, null, domain.lowercase().trim()
                )
                if (mapping.isPresent) listOf(mapping.get()) else emptyList()
            }
            awsAccountId != null -> {
                // Remove specific AWS account mapping
                val mapping = userMappingRepository.findByEmailAndAwsAccountIdAndDomain(
                    normalizedEmail, awsAccountId.trim(), null
                )
                if (mapping.isPresent) listOf(mapping.get()) else emptyList()
            }
            else -> {
                throw IllegalArgumentException(
                    "Must specify either --domain, --account, or --all to indicate what to remove"
                )
            }
        }

        if (mappingsToDelete.isEmpty()) {
            throw IllegalArgumentException("No mappings found matching the specified criteria")
        }

        // Delete mappings
        userMappingRepository.deleteAll(mappingsToDelete)

        // Audit log
        mappingsToDelete.forEach { mapping ->
            log.info(
                "AUDIT: operation=DELETE_MAPPING, actor=$adminEmail, " +
                "email=${mapping.email}, domain=${mapping.domain}, awsAccountId=${mapping.awsAccountId}"
            )
        }

        return mappingsToDelete.size
    }

    /**
     * Import user mappings from CSV or JSON file
     *
     * @param filePath Path to file
     * @param format Format (CSV, JSON, or AUTO for auto-detection)
     * @param dryRun If true, validate without saving
     * @param adminEmail Admin user executing the command
     * @return Result summary
     */
    fun importMappingsFromFile(
        filePath: String,
        format: String,
        dryRun: Boolean,
        adminEmail: String
    ): MappingResult {
        val file = File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("File not found: $filePath")
        }

        // Auto-detect format if needed
        val detectedFormat = when {
            format.uppercase() != "AUTO" -> format.uppercase()
            filePath.endsWith(".csv", ignoreCase = true) -> "CSV"
            filePath.endsWith(".json", ignoreCase = true) -> "JSON"
            else -> {
                // Try to detect by reading first few bytes
                val content = file.readText()
                when {
                    content.trim().startsWith("[") || content.trim().startsWith("{") -> "JSON"
                    else -> "CSV"
                }
            }
        }

        log.info("Importing from $filePath (format: $detectedFormat, dryRun: $dryRun)")

        return when (detectedFormat) {
            "CSV" -> importFromCsv(file, dryRun, adminEmail)
            "JSON" -> importFromJson(file, dryRun, adminEmail)
            else -> throw IllegalArgumentException("Unsupported format: $detectedFormat")
        }
    }

    /**
     * Import mappings from CSV file
     *
     * Expected CSV format:
     * email,type,value
     * user@example.com,DOMAIN,example.com
     * user@example.com,AWS_ACCOUNT,123456789012
     */
    private fun importFromCsv(file: File, dryRun: Boolean, adminEmail: String): MappingResult {
        val results = mutableListOf<MappingOperationResult>()
        var lineNumber = 1

        try {
            FileReader(file).use { reader ->
                val csvParser = CSVParser(
                    reader,
                    CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreEmptyLines(true)
                        .setTrim(true)
                        .build()
                )

                csvParser.forEach { record ->
                    lineNumber++
                    try {
                        val email = record.get("email") ?: record.get("Email") ?: record.get("EMAIL")
                        val type = record.get("type") ?: record.get("Type") ?: record.get("TYPE")
                        val value = record.get("value") ?: record.get("Value") ?: record.get("VALUE")

                        if (email.isNullOrBlank() || type.isNullOrBlank() || value.isNullOrBlank()) {
                            results.add(
                                MappingOperationResult(
                                    success = false,
                                    operation = "ERROR",
                                    message = "Line $lineNumber: Missing required fields (email, type, value)",
                                    email = email ?: "",
                                    domain = null,
                                    awsAccountId = null
                                )
                            )
                            return@forEach
                        }

                        val result = when (type.uppercase()) {
                            "DOMAIN" -> {
                                if (!dryRun) {
                                    createMapping(
                                        email = email.trim(),
                                        domain = value.trim(),
                                        awsAccountId = null,
                                        adminEmail = adminEmail,
                                        operationType = "IMPORT_CSV_DOMAIN"
                                    )
                                } else {
                                    // Dry-run validation
                                    if (!emailRegex.matches(email.trim())) {
                                        MappingOperationResult(
                                            success = false,
                                            operation = "ERROR",
                                            message = "Line $lineNumber: Invalid email format",
                                            email = email.trim(),
                                            domain = value.trim()
                                        )
                                    } else if (!domainRegex.matches(value.trim())) {
                                        MappingOperationResult(
                                            success = false,
                                            operation = "ERROR",
                                            message = "Line $lineNumber: Invalid domain format",
                                            email = email.trim(),
                                            domain = value.trim()
                                        )
                                    } else {
                                        MappingOperationResult(
                                            success = true,
                                            operation = "WOULD_CREATE",
                                            message = "Would create mapping (dry-run)",
                                            email = email.trim(),
                                            domain = value.trim()
                                        )
                                    }
                                }
                            }
                            "AWS_ACCOUNT" -> {
                                if (!dryRun) {
                                    createMapping(
                                        email = email.trim(),
                                        domain = null,
                                        awsAccountId = value.trim(),
                                        adminEmail = adminEmail,
                                        operationType = "IMPORT_CSV_AWS"
                                    )
                                } else {
                                    // Dry-run validation
                                    if (!emailRegex.matches(email.trim())) {
                                        MappingOperationResult(
                                            success = false,
                                            operation = "ERROR",
                                            message = "Line $lineNumber: Invalid email format",
                                            email = email.trim(),
                                            awsAccountId = value.trim()
                                        )
                                    } else if (!awsAccountIdRegex.matches(value.trim())) {
                                        MappingOperationResult(
                                            success = false,
                                            operation = "ERROR",
                                            message = "Line $lineNumber: Invalid AWS account ID (must be 12 digits)",
                                            email = email.trim(),
                                            awsAccountId = value.trim()
                                        )
                                    } else {
                                        MappingOperationResult(
                                            success = true,
                                            operation = "WOULD_CREATE",
                                            message = "Would create mapping (dry-run)",
                                            email = email.trim(),
                                            awsAccountId = value.trim()
                                        )
                                    }
                                }
                            }
                            else -> {
                                MappingOperationResult(
                                    success = false,
                                    operation = "ERROR",
                                    message = "Line $lineNumber: Invalid type '$type' (must be DOMAIN or AWS_ACCOUNT)",
                                    email = email.trim(),
                                    domain = null,
                                    awsAccountId = null
                                )
                            }
                        }
                        results.add(result)

                    } catch (e: Exception) {
                        results.add(
                            MappingOperationResult(
                                success = false,
                                operation = "ERROR",
                                message = "Line $lineNumber: ${e.message}",
                                email = "",
                                domain = null,
                                awsAccountId = null
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse CSV file: ${e.message}", e)
        }

        return summarizeResults(results)
    }

    /**
     * Import mappings from JSON file
     *
     * Expected JSON format:
     * [
     *   {
     *     "email": "user@example.com",
     *     "domains": ["example.com", "corp.local"],
     *     "awsAccounts": ["123456789012"]
     *   }
     * ]
     */
    private fun importFromJson(file: File, dryRun: Boolean, adminEmail: String): MappingResult {
        val results = mutableListOf<MappingOperationResult>()
        val objectMapper = jacksonObjectMapper()

        try {
            val mappingsData: List<Map<String, Any>> = objectMapper.readValue(file)

            mappingsData.forEach { mapping ->
                val email = mapping["email"] as? String
                val domains = mapping["domains"] as? List<*>
                val awsAccounts = mapping["awsAccounts"] as? List<*>

                if (email.isNullOrBlank()) {
                    results.add(
                        MappingOperationResult(
                            success = false,
                            operation = "ERROR",
                            message = "Missing or invalid email field",
                            email = "",
                            domain = null,
                            awsAccountId = null
                        )
                    )
                    return@forEach
                }

                // Process domains
                domains?.forEach { domain ->
                    val domainStr = domain.toString()
                    val result = if (!dryRun) {
                        createMapping(
                            email = email.trim(),
                            domain = domainStr.trim(),
                            awsAccountId = null,
                            adminEmail = adminEmail,
                            operationType = "IMPORT_JSON_DOMAIN"
                        )
                    } else {
                        if (!emailRegex.matches(email.trim())) {
                            MappingOperationResult(
                                success = false,
                                operation = "ERROR",
                                message = "Invalid email format",
                                email = email.trim(),
                                domain = domainStr.trim()
                            )
                        } else if (!domainRegex.matches(domainStr.trim())) {
                            MappingOperationResult(
                                success = false,
                                operation = "ERROR",
                                message = "Invalid domain format",
                                email = email.trim(),
                                domain = domainStr.trim()
                            )
                        } else {
                            MappingOperationResult(
                                success = true,
                                operation = "WOULD_CREATE",
                                message = "Would create mapping (dry-run)",
                                email = email.trim(),
                                domain = domainStr.trim()
                            )
                        }
                    }
                    results.add(result)
                }

                // Process AWS accounts
                awsAccounts?.forEach { account ->
                    val accountStr = account.toString()
                    val result = if (!dryRun) {
                        createMapping(
                            email = email.trim(),
                            domain = null,
                            awsAccountId = accountStr.trim(),
                            adminEmail = adminEmail,
                            operationType = "IMPORT_JSON_AWS"
                        )
                    } else {
                        if (!emailRegex.matches(email.trim())) {
                            MappingOperationResult(
                                success = false,
                                operation = "ERROR",
                                message = "Invalid email format",
                                email = email.trim(),
                                awsAccountId = accountStr.trim()
                            )
                        } else if (!awsAccountIdRegex.matches(accountStr.trim())) {
                            MappingOperationResult(
                                success = false,
                                operation = "ERROR",
                                message = "Invalid AWS account ID (must be 12 digits)",
                                email = email.trim(),
                                awsAccountId = accountStr.trim()
                            )
                        } else {
                            MappingOperationResult(
                                success = true,
                                operation = "WOULD_CREATE",
                                message = "Would create mapping (dry-run)",
                                email = email.trim(),
                                awsAccountId = accountStr.trim()
                            )
                        }
                    }
                    results.add(result)
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse JSON file: ${e.message}", e)
        }

        return summarizeResults(results)
    }

    /**
     * Summarize operation results into counts
     */
    private fun summarizeResults(results: List<MappingOperationResult>): MappingResult {
        val created = results.count { it.operation == "CREATED" && !it.isPending }
        val createdPending = results.count { it.operation == "CREATED" && it.isPending }
        val skipped = results.count { it.operation == "SKIPPED_DUPLICATE" }
        val errors = results.filter { it.operation == "ERROR" }

        return MappingResult(
            totalProcessed = results.size,
            created = created,
            createdPending = createdPending,
            skipped = skipped,
            errors = errors.map { "${it.email} → ${it.domain ?: it.awsAccountId}: ${it.message}" },
            operations = results
        )
    }
}

/**
 * Result of a single mapping operation
 */
data class MappingOperationResult(
    val success: Boolean,
    val operation: String, // CREATED, SKIPPED_DUPLICATE, ERROR
    val message: String,
    val email: String,
    val domain: String? = null,
    val awsAccountId: String? = null,
    val isPending: Boolean = false
)

/**
 * Summary of multiple mapping operations
 */
data class MappingResult(
    val totalProcessed: Int,
    val created: Int,
    val createdPending: Int = 0,
    val skipped: Int,
    val errors: List<String>,
    val operations: List<MappingOperationResult>
)
