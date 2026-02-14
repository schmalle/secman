package com.secman.cli.commands

import picocli.CommandLine.*
import jakarta.inject.Singleton

/**
 * Parent command for user mapping management operations (Feature 049)
 *
 * Provides subcommands for creating, listing, and removing user-to-domain
 * and user-to-AWS-account mappings via CLI.
 *
 * Usage:
 *   ./gradlew cli:run --args='manage-user-mappings <subcommand> [options]'
 *
 * Subcommands:
 *   add-domain  - Add domain-to-user mappings
 *   add-aws     - Add AWS-account-to-user mappings
 *   list        - List existing mappings
 *   remove      - Remove user mappings
 *   import      - Batch import from CSV/JSON file
 *   import-s3   - Batch import from AWS S3 bucket (Feature 065)
 *   list-bucket - List objects in an S3 bucket (Feature 065)
 *
 * Authentication:
 *   All operations require ADMIN role
 *   Specify admin user via:
 *   - --admin-user flag
 *   - SECMAN_ADMIN_EMAIL environment variable
 */
@Singleton
@Command(
    name = "manage-user-mappings",
    description = ["Manage user mappings for domains and AWS accounts"],
    mixinStandardHelpOptions = true,
    subcommands = [
        AddDomainCommand::class,
        AddAwsCommand::class,
        ListCommand::class,
        RemoveCommand::class,
        ImportCommand::class,
        ImportS3Command::class,
        ListBucketCommand::class
    ]
)
class ManageUserMappingsCommand : Runnable {

    @Option(
        names = ["--admin-user", "-u"],
        description = ["Admin user email (defaults to SECMAN_ADMIN_EMAIL env var)"]
    )
    var adminUser: String? = null

    @Spec
    lateinit var spec: Model.CommandSpec

    /**
     * Get the admin user email from flag or environment variable
     *
     * @return Admin user email
     * @throws IllegalArgumentException if no admin user specified
     */
    fun getAdminUserOrThrow(): String {
        return adminUser
            ?: System.getenv("SECMAN_ADMIN_EMAIL")
            ?: throw IllegalArgumentException(
                "Admin user required. Use --admin-user flag or set SECMAN_ADMIN_EMAIL environment variable"
            )
    }

    override fun run() {
        // Show help if no subcommand specified
        spec.commandLine().usage(System.out)
    }
}
