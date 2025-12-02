package com.secman.cli.commands

import picocli.CommandLine.*
import jakarta.inject.Singleton

/**
 * Parent command for workgroup management operations
 *
 * Provides subcommands for assigning and removing assets from workgroups,
 * listing workgroups and assets, with support for pattern-based selection.
 *
 * Usage:
 *   ./gradlew cli:run --args='manage-workgroups <subcommand> [options]'
 *
 * Subcommands:
 *   list            - List workgroups or assets in a workgroup
 *   assign-assets   - Assign assets to a workgroup
 *   remove-assets   - Remove assets from a workgroup
 *
 * Authentication:
 *   All operations require ADMIN role
 *   Specify admin user via:
 *   - --admin-user flag
 *   - SECMAN_ADMIN_EMAIL environment variable
 *
 * Examples:
 *   # List all workgroups
 *   ./gradlew cli:run --args='manage-workgroups list'
 *
 *   # List assets in a workgroup
 *   ./gradlew cli:run --args='manage-workgroups list --workgroup Production'
 *
 *   # Assign assets by pattern
 *   ./gradlew cli:run --args='manage-workgroups assign-assets --workgroup Production --pattern "ip-10-*"'
 *
 *   # Remove all assets from workgroup
 *   ./gradlew cli:run --args='manage-workgroups remove-assets --workgroup Test --all'
 */
@Singleton
@Command(
    name = "manage-workgroups",
    description = ["Manage workgroup asset assignments"],
    mixinStandardHelpOptions = true,
    subcommands = [
        ListWorkgroupsCommand::class,
        AssignWorkgroupAssetsCommand::class,
        RemoveWorkgroupAssetsCommand::class
    ]
)
class ManageWorkgroupsCommand : Runnable {

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
