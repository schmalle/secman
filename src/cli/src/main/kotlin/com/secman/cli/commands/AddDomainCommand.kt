package com.secman.cli.commands

import com.secman.cli.service.UserMappingCliService
import picocli.CommandLine.*
import jakarta.inject.Singleton

/**
 * CLI command to add domain-to-user mappings (Feature 049)
 *
 * Usage:
 *   ./gradlew cli:run --args='manage-user-mappings add-domain \
 *     --emails user1@example.com,user2@example.com \
 *     --domains example.com,corp.local'
 *
 * Features:
 * - Creates n×m mappings (cross product)
 * - Validates email and domain formats
 * - Skips duplicates with warning
 * - Creates pending mappings for non-existent users
 * - Requires ADMIN role
 */
@Singleton
@Command(
    name = "add-domain",
    description = ["Add domain-to-user mapping(s)"],
    mixinStandardHelpOptions = true
)
class AddDomainCommand(
    private val userMappingCliService: UserMappingCliService
) : Runnable {

    @Option(
        names = ["--emails"],
        description = ["User email addresses (comma-separated)"],
        required = true,
        split = ","
    )
    lateinit var emails: List<String>

    @Option(
        names = ["--domains"],
        description = ["AD domains to assign (comma-separated)"],
        required = true,
        split = ","
    )
    lateinit var domains: List<String>

    @ParentCommand
    lateinit var parent: ManageUserMappingsCommand

    override fun run() {
        try {
            println("=" .repeat(60))
            println("Add Domain Mappings")
            println("=" .repeat(60))
            println()

            // Get admin user
            val adminEmail = parent.getAdminUserOrThrow()
            println("Admin user: $adminEmail")
            println()

            // Validate inputs
            val trimmedEmails = emails.map { it.trim() }.filter { it.isNotEmpty() }
            val trimmedDomains = domains.map { it.trim() }.filter { it.isNotEmpty() }

            if (trimmedEmails.isEmpty()) {
                System.err.println("❌ Error: No valid email addresses provided")
                System.exit(1)
            }

            if (trimmedDomains.isEmpty()) {
                System.err.println("❌ Error: No valid domains provided")
                System.exit(1)
            }

            println("Processing domain mappings...")
            println("Emails: ${trimmedEmails.joinToString()}")
            println("Domains: ${trimmedDomains.joinToString()}")
            println()

            // Execute mapping creation
            val result = userMappingCliService.addDomainMappings(
                emails = trimmedEmails,
                domains = trimmedDomains,
                adminEmail = adminEmail
            )

            // Display results
            result.operations.forEach { op ->
                val symbol = when (op.operation) {
                    "CREATED" -> if (op.isPending) "⚠️ " else "✅"
                    "SKIPPED_DUPLICATE" -> "⚠️ "
                    else -> "❌"
                }

                val status = when {
                    op.operation == "CREATED" && op.isPending -> " (pending - user not found)"
                    op.operation == "SKIPPED_DUPLICATE" -> " (duplicate)"
                    op.operation == "ERROR" -> " - ${op.message}"
                    else -> ""
                }

                println("$symbol ${op.email} → ${op.domain}$status")
            }

            println()
            println("=" .repeat(60))
            println("Summary")
            println("=" .repeat(60))
            println("Total: ${result.totalProcessed} mapping(s) processed")
            if (result.created > 0) {
                println("Created: ${result.created} active")
            }
            if (result.createdPending > 0) {
                println("Created: ${result.createdPending} pending")
            }
            if (result.skipped > 0) {
                println("Skipped: ${result.skipped} duplicate(s)")
            }
            if (result.errors.isNotEmpty()) {
                println("Errors: ${result.errors.size} failure(s)")
                result.errors.forEach { error ->
                    println("  - $error")
                }
            }
            println()

            // Exit with error code if there were errors
            if (result.errors.isNotEmpty()) {
                println("✗ Completed with errors")
                System.exit(1)
            } else {
                println("✓ All mappings processed successfully")
            }

        } catch (e: IllegalArgumentException) {
            System.err.println("❌ Error: ${e.message}")
            System.exit(1)
        } catch (e: Exception) {
            System.err.println("❌ Error: ${e.message}")
            e.printStackTrace()
            System.exit(1)
        }
    }
}
