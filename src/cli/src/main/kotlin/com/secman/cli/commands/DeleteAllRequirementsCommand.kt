package com.secman.cli.commands

import com.secman.cli.service.RequirementCliService
import jakarta.inject.Inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * CLI command to delete all security requirements
 * Feature: 057-cli-mcp-requirements
 *
 * Usage:
 *   secman delete-all-requirements --confirm [options]
 *
 * Examples:
 *   secman delete-all-requirements --confirm
 *   secman delete-all-requirements --confirm --verbose
 *
 * User Story:
 * - US4: Delete all requirements from command line (P3)
 *
 * WARNING: This is a destructive operation that requires explicit confirmation
 * and ADMIN role privileges.
 */
@Command(
    name = "delete-all-requirements",
    description = ["Delete ALL security requirements (requires ADMIN role)"],
    mixinStandardHelpOptions = true
)
class DeleteAllRequirementsCommand : Runnable {

    @Inject
    lateinit var requirementCliService: RequirementCliService

    // Required confirmation flag for safety
    @Option(
        names = ["--confirm"],
        description = ["Required flag to confirm deletion of ALL requirements"],
        required = true
    )
    var confirm: Boolean = false

    @Option(
        names = ["--backend-url"],
        description = ["Backend API URL (default: http://localhost:8080)"],
        defaultValue = "http://localhost:8080"
    )
    var backendUrl: String = "http://localhost:8080"

    @Option(
        names = ["--username", "-u"],
        description = ["Backend username with ADMIN role (or set SECMAN_USERNAME env var)"],
        required = false
    )
    var username: String? = null

    @Option(
        names = ["--password", "-p"],
        description = ["Backend password (or set SECMAN_PASSWORD env var)"],
        required = false
    )
    var password: String? = null

    @Option(
        names = ["--verbose", "-v"],
        description = ["Enable verbose output"]
    )
    var verbose: Boolean = false

    override fun run() {
        // Check confirmation flag
        if (!confirm) {
            System.err.println("Error: You must specify --confirm to delete all requirements")
            System.err.println("   This is a destructive operation that cannot be undone!")
            System.exit(1)
            return
        }

        // Resolve credentials from environment variables if not provided via CLI
        val effectiveUsername = username
            ?: System.getenv("SECMAN_USERNAME")
            ?: run {
                System.err.println("Error: Username required via --username or SECMAN_USERNAME env var")
                System.exit(1)
                return
            }

        val effectivePassword = password
            ?: System.getenv("SECMAN_PASSWORD")
            ?: run {
                System.err.println("Error: Password required via --password or SECMAN_PASSWORD env var")
                System.exit(1)
                return
            }

        println("============================================================")
        println("Delete All Requirements")
        println("============================================================")
        println()
        println("WARNING: This will permanently delete ALL requirements!")
        println()

        // Step 1: Authenticate
        println("Authenticating with backend...")
        val token = requirementCliService.authenticate(effectiveUsername, effectivePassword, backendUrl)

        if (token == null) {
            System.err.println("Error: Authentication failed")
            System.err.println("   Check username and password")
            System.err.println("   Note: This operation requires ADMIN role")
            System.exit(1)
            return
        }
        println("Authentication successful")
        println()

        if (verbose) {
            println("Executing delete operation...")
            println("Backend: $backendUrl")
            println()
        }

        // Step 2: Delete all requirements
        val result = requirementCliService.deleteAllRequirements(
            backendUrl = backendUrl,
            authToken = token
        )

        // Step 3: Display result
        if (result.success) {
            println("Delete operation completed!")
            println()
            println("============================================================")
            println("Summary")
            println("============================================================")
            println("Status: ${result.operation}")
            println("Message: ${result.message}")
        } else {
            System.err.println("Error: ${result.message}")
            System.exit(result.exitCode)
            return
        }
    }
}
