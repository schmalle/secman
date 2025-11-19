package com.secman.cli.commands

import com.secman.cli.service.UserMappingCliService
import picocli.CommandLine.*
import jakarta.inject.Singleton

/**
 * CLI command to remove user mappings (Feature 049)
 *
 * Usage:
 *   ./gradlew cli:run --args='manage-user-mappings remove --email user@example.com --domain example.com'
 *   ./gradlew cli:run --args='manage-user-mappings remove --email user@example.com --account 123456789012'
 *   ./gradlew cli:run --args='manage-user-mappings remove --email user@example.com --all'
 *
 * Features:
 * - Remove specific domain mapping
 * - Remove specific AWS account mapping
 * - Remove all mappings for a user
 * - Audit logging for all deletions
 * - Requires ADMIN role
 */
@Singleton
@Command(
    name = "remove",
    description = ["Remove user mapping(s)"],
    mixinStandardHelpOptions = true
)
class RemoveCommand(
    private val userMappingCliService: UserMappingCliService
) : Runnable {

    @Option(
        names = ["--email"],
        description = ["User email address"],
        required = true
    )
    lateinit var email: String

    @Option(
        names = ["--domain"],
        description = ["Specific domain to remove"]
    )
    var domain: String? = null

    @Option(
        names = ["--account"],
        description = ["Specific AWS account ID to remove"]
    )
    var awsAccountId: String? = null

    @Option(
        names = ["--all"],
        description = ["Remove all mappings for this user"]
    )
    var removeAll: Boolean = false

    @ParentCommand
    lateinit var parent: ManageUserMappingsCommand

    override fun run() {
        try {
            println("=" .repeat(60))
            println("Remove User Mappings")
            println("=" .repeat(60))
            println()

            // Get admin user
            val adminEmail = parent.getAdminUserOrThrow()
            println("Admin user: $adminEmail")
            println()

            // Validate inputs
            val trimmedEmail = email.trim()
            if (trimmedEmail.isEmpty()) {
                System.err.println("❌ Error: Email address is required")
                System.exit(1)
            }

            // Determine what to remove
            val description = when {
                removeAll -> "all mappings for $trimmedEmail"
                domain != null -> "domain mapping: $trimmedEmail → $domain"
                awsAccountId != null -> "AWS account mapping: $trimmedEmail → $awsAccountId"
                else -> {
                    System.err.println("❌ Error: Must specify --domain, --account, or --all")
                    System.exit(1)
                    return
                }
            }

            println("Removing: $description")
            println()

            // Execute removal
            val removedCount = userMappingCliService.removeMappings(
                email = trimmedEmail,
                domain = domain?.trim(),
                awsAccountId = awsAccountId?.trim(),
                removeAll = removeAll,
                adminEmail = adminEmail
            )

            println("=" .repeat(60))
            println("Summary")
            println("=" .repeat(60))
            println("✅ Removed $removedCount mapping(s)")
            println()

        } catch (e: IllegalArgumentException) {
            println()
            System.err.println("❌ Error: ${e.message}")
            System.exit(1)
        } catch (e: Exception) {
            println()
            System.err.println("❌ Error: ${e.message}")
            e.printStackTrace()
            System.exit(1)
        }
    }
}
