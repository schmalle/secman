package com.secman.cli.commands

import com.secman.cli.service.UserMappingCliService
import picocli.CommandLine.*
import jakarta.inject.Singleton

/**
 * CLI command to add AWS-account-to-user mappings (Feature 049)
 *
 * Usage:
 *   ./gradlew cli:run --args='manage-user-mappings add-aws \
 *     --emails user1@example.com,user2@example.com \
 *     --accounts 123456789012,987654321098'
 *
 * Features:
 * - Creates n×m mappings (cross product)
 * - Validates AWS account ID format (12 digits)
 * - Skips duplicates with warning
 * - Creates pending mappings for non-existent users
 * - Requires ADMIN role
 */
@Singleton
@Command(
    name = "add-aws",
    description = ["Add AWS-account-to-user mapping(s)"],
    mixinStandardHelpOptions = true
)
class AddAwsCommand(
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
        names = ["--accounts"],
        description = ["AWS account IDs to assign (12 digits, comma-separated)"],
        required = true,
        split = ","
    )
    lateinit var accounts: List<String>

    @ParentCommand
    lateinit var parent: ManageUserMappingsCommand

    override fun run() {
        try {
            println("=" .repeat(60))
            println("Add AWS Account Mappings")
            println("=" .repeat(60))
            println()

            // Get admin user
            val adminEmail = parent.getAdminUserOrThrow()
            println("Admin user: $adminEmail")
            println()

            // Validate inputs
            val trimmedEmails = emails.map { it.trim() }.filter { it.isNotEmpty() }
            val trimmedAccounts = accounts.map { it.trim() }.filter { it.isNotEmpty() }

            if (trimmedEmails.isEmpty()) {
                System.err.println("❌ Error: No valid email addresses provided")
                System.exit(1)
            }

            if (trimmedAccounts.isEmpty()) {
                System.err.println("❌ Error: No valid AWS account IDs provided")
                System.exit(1)
            }

            println("Processing AWS account mappings...")
            println("Emails: ${trimmedEmails.joinToString()}")
            println("AWS Accounts: ${trimmedAccounts.joinToString()}")
            println()

            // Execute mapping creation
            val result = userMappingCliService.addAwsAccountMappings(
                emails = trimmedEmails,
                awsAccountIds = trimmedAccounts,
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

                println("$symbol ${op.email} → ${op.awsAccountId}$status")
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
