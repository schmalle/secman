package com.secman.mcp.tools

import com.secman.domain.McpOperation
import com.secman.domain.MappingStatus
import com.secman.domain.UserMapping
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * MCP tool for bulk importing user mappings.
 * Feature: 064-mcp-cli-user-mapping
 *
 * ADMIN role is required via User Delegation.
 * Accepts a list of mapping entries (email, awsAccountId, domain) and processes them in bulk.
 * Supports dry-run mode for validation without persistence.
 *
 * Input parameters:
 * - mappings (required): Array of mapping objects with email (required), awsAccountId (optional), domain (optional)
 * - dryRun (optional): If true, validates without creating mappings. Defaults to false.
 *
 * Returns:
 * - totalProcessed: Total number of entries processed
 * - created: Number of active mappings created (user exists)
 * - createdPending: Number of pending mappings created (user doesn't exist yet)
 * - skipped: Number of duplicate mappings skipped
 * - errors: List of validation errors with index, email, and message
 * - dryRun: Whether this was a dry-run
 */
@Singleton
class ImportUserMappingsTool(
    @Inject private val userMappingRepository: UserMappingRepository,
    @Inject private val userRepository: UserRepository
) : McpTool {

    private val log = LoggerFactory.getLogger(ImportUserMappingsTool::class.java)

    // Validation regex patterns (matching UserMappingCliService)
    private val emailRegex = Regex("^[^@]+@[^@]+\\.[^@]+$")
    private val awsAccountIdRegex = Regex("^\\d{12}$")
    private val domainRegex = Regex("^[a-zA-Z0-9][a-zA-Z0-9.-]*[a-zA-Z0-9]$|^[a-zA-Z0-9]$")

    override val name = "import_user_mappings"
    override val description = "Bulk import user mappings (ADMIN only, requires User Delegation)"
    override val operation = McpOperation.WRITE

    override val inputSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "mappings" to mapOf(
                "type" to "array",
                "description" to "List of user mapping entries to import",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "email" to mapOf(
                            "type" to "string",
                            "description" to "User email address (required)"
                        ),
                        "awsAccountId" to mapOf(
                            "type" to "string",
                            "description" to "AWS account ID (12 digits, optional)"
                        ),
                        "domain" to mapOf(
                            "type" to "string",
                            "description" to "AD domain (optional)"
                        )
                    ),
                    "required" to listOf("email")
                ),
                "maxItems" to 1000
            ),
            "dryRun" to mapOf(
                "type" to "boolean",
                "description" to "If true, validate without creating mappings",
                "default" to false
            )
        ),
        "required" to listOf("mappings")
    )

    override suspend fun execute(arguments: Map<String, Any>, context: McpExecutionContext): McpToolResult {
        // Require User Delegation
        if (!context.hasDelegation()) {
            return McpToolResult.error(
                "DELEGATION_REQUIRED",
                "User Delegation must be enabled to use this tool"
            )
        }

        // Require ADMIN role
        if (!context.isAdmin) {
            return McpToolResult.error(
                "ADMIN_REQUIRED",
                "ADMIN role required to import user mappings"
            )
        }

        // Extract parameters
        @Suppress("UNCHECKED_CAST")
        val mappings = arguments["mappings"] as? List<Map<String, Any?>>
        val dryRun = arguments["dryRun"] as? Boolean ?: false

        if (mappings == null || mappings.isEmpty()) {
            return McpToolResult.error(
                "VALIDATION_ERROR",
                "The 'mappings' parameter is required and must be a non-empty array"
            )
        }

        if (mappings.size > 1000) {
            return McpToolResult.error(
                "VALIDATION_ERROR",
                "Maximum 1000 mappings allowed per request. Received: ${mappings.size}"
            )
        }

        // Process mappings
        var created = 0
        var createdPending = 0
        var skipped = 0
        val errors = mutableListOf<Map<String, Any>>()

        mappings.forEachIndexed { index, mapping ->
            val email = (mapping["email"] as? String)?.lowercase()?.trim()
            val awsAccountId = (mapping["awsAccountId"] as? String)?.trim()
            val domain = (mapping["domain"] as? String)?.lowercase()?.trim()

            // Validate email
            if (email.isNullOrBlank()) {
                errors.add(mapOf(
                    "index" to index,
                    "email" to (email ?: ""),
                    "message" to "Email is required"
                ))
                return@forEachIndexed
            }

            if (!emailRegex.matches(email)) {
                errors.add(mapOf(
                    "index" to index,
                    "email" to email,
                    "message" to "Invalid email format"
                ))
                return@forEachIndexed
            }

            if (email.length < 3 || email.length > 255) {
                errors.add(mapOf(
                    "index" to index,
                    "email" to email,
                    "message" to "Email must be between 3 and 255 characters"
                ))
                return@forEachIndexed
            }

            // Validate at least one of awsAccountId or domain is provided
            if (awsAccountId.isNullOrBlank() && domain.isNullOrBlank()) {
                errors.add(mapOf(
                    "index" to index,
                    "email" to email,
                    "message" to "At least one of awsAccountId or domain must be provided"
                ))
                return@forEachIndexed
            }

            // Validate AWS account ID format if provided
            if (!awsAccountId.isNullOrBlank() && !awsAccountIdRegex.matches(awsAccountId)) {
                errors.add(mapOf(
                    "index" to index,
                    "email" to email,
                    "message" to "Invalid AWS account ID format (must be exactly 12 digits)"
                ))
                return@forEachIndexed
            }

            // Validate domain format if provided
            if (!domain.isNullOrBlank() && !domainRegex.matches(domain)) {
                errors.add(mapOf(
                    "index" to index,
                    "email" to email,
                    "message" to "Invalid domain format (alphanumeric with dots/hyphens, no leading/trailing special chars)"
                ))
                return@forEachIndexed
            }

            // Check for duplicate
            val normalizedAwsAccountId = awsAccountId?.takeIf { it.isNotBlank() }
            val normalizedDomain = domain?.takeIf { it.isNotBlank() }

            val exists = userMappingRepository.existsByEmailAndAwsAccountIdAndDomain(
                email, normalizedAwsAccountId, normalizedDomain
            )

            if (exists) {
                skipped++
                return@forEachIndexed
            }

            // For dry-run, count as would-create
            if (dryRun) {
                val user = userRepository.findByEmailIgnoreCase(email).orElse(null)
                if (user != null) {
                    created++
                } else {
                    createdPending++
                }
                return@forEachIndexed
            }

            // Create the mapping
            try {
                val user = userRepository.findByEmailIgnoreCase(email).orElse(null)
                val status = if (user != null) MappingStatus.ACTIVE else MappingStatus.PENDING

                val userMapping = UserMapping(
                    email = email,
                    user = user,
                    domain = normalizedDomain,
                    awsAccountId = normalizedAwsAccountId,
                    ipAddress = null,
                    status = status
                )

                if (user != null) {
                    userMapping.appliedAt = Instant.now()
                }

                userMappingRepository.save(userMapping)

                if (status == MappingStatus.ACTIVE) {
                    created++
                } else {
                    createdPending++
                }

                log.info(
                    "AUDIT: operation=MCP_IMPORT_USER_MAPPING, actor={}, " +
                    "email={}, awsAccountId={}, domain={}, status={}",
                    context.delegatedUserEmail, email, normalizedAwsAccountId, normalizedDomain, status
                )

            } catch (e: Exception) {
                log.error("Failed to create mapping for email={}", email, e)
                errors.add(mapOf(
                    "index" to index,
                    "email" to email,
                    "message" to "Failed to create mapping: ${e.message}"
                ))
            }
        }

        // Build result
        val result = mapOf(
            "totalProcessed" to mappings.size,
            "created" to created,
            "createdPending" to createdPending,
            "skipped" to skipped,
            "errors" to errors,
            "dryRun" to dryRun
        )

        val modeLabel = if (dryRun) " (dry-run)" else ""
        log.info(
            "MCP import_user_mappings{}: total={}, created={}, pending={}, skipped={}, errors={}, actor={}",
            modeLabel, mappings.size, created, createdPending, skipped, errors.size, context.delegatedUserEmail
        )

        return McpToolResult.success(result)
    }
}
