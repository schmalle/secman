package com.secman.cli.commands

import com.secman.cli.service.RequirementCliService
import jakarta.inject.Inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * CLI command to add a new security requirement
 * Feature: 057-cli-mcp-requirements
 *
 * Usage:
 *   secman add-requirement --shortreq <text> [--chapter <name>] [options]
 *
 * Examples:
 *   secman add-requirement --shortreq "All passwords must be at least 12 characters"
 *   secman add-requirement --shortreq "MFA required for admin access" --chapter "Authentication" --norm "ISO 27001"
 *
 * User Story:
 * - US3: Add new requirements from command line (P2)
 */
@Command(
    name = "add-requirement",
    description = ["Add a new security requirement"],
    mixinStandardHelpOptions = true
)
class AddRequirementCommand : Runnable {

    @Inject
    lateinit var requirementCliService: RequirementCliService

    // Required parameter
    @Option(
        names = ["--shortreq", "-s"],
        description = ["Short requirement text (required)"],
        required = true
    )
    lateinit var shortreq: String

    // Optional parameters
    @Option(
        names = ["--chapter", "-c"],
        description = ["Chapter/category for grouping"],
        required = false
    )
    var chapter: String? = null

    @Option(
        names = ["--details", "-d"],
        description = ["Detailed description"],
        required = false
    )
    var details: String? = null

    @Option(
        names = ["--motivation", "-m"],
        description = ["Why this requirement exists"],
        required = false
    )
    var motivation: String? = null

    @Option(
        names = ["--example", "-e"],
        description = ["Implementation example"],
        required = false
    )
    var example: String? = null

    @Option(
        names = ["--norm", "-n"],
        description = ["Regulatory norm reference (e.g., ISO 27001, GDPR)"],
        required = false
    )
    var norm: String? = null

    @Option(
        names = ["--usecase"],
        description = ["Use case description"],
        required = false
    )
    var usecase: String? = null

    @Option(
        names = ["--backend-url"],
        description = ["Backend API URL (default: http://localhost:8080)"],
        defaultValue = "http://localhost:8080"
    )
    var backendUrl: String = "http://localhost:8080"

    @Option(
        names = ["--username", "-u"],
        description = ["Backend username (or set SECMAN_USERNAME env var)"],
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

        // Validate shortreq is not blank
        if (shortreq.isBlank()) {
            System.err.println("Error: Short requirement text cannot be empty")
            System.exit(1)
            return
        }

        println("============================================================")
        println("Add Requirement")
        println("============================================================")
        println()

        // Step 1: Authenticate
        println("Authenticating with backend...")
        val token = requirementCliService.authenticate(effectiveUsername, effectivePassword, backendUrl)

        if (token == null) {
            System.err.println("Error: Authentication failed")
            System.err.println("   Check username and password")
            System.exit(1)
            return
        }
        println("Authentication successful")
        println()

        // Step 2: Display request details
        if (verbose) {
            println("Creating requirement...")
            println("Short Requirement: $shortreq")
            chapter?.let { println("Chapter: $it") }
            details?.let { println("Details: ${it.take(50)}...") }
            motivation?.let { println("Motivation: ${it.take(50)}...") }
            example?.let { println("Example: ${it.take(50)}...") }
            norm?.let { println("Norm: $it") }
            usecase?.let { println("Use Case: $it") }
            println()
        }

        // Step 3: Add requirement
        val result = requirementCliService.addRequirement(
            shortreq = shortreq,
            chapter = chapter,
            details = details,
            motivation = motivation,
            example = example,
            norm = norm,
            usecase = usecase,
            backendUrl = backendUrl,
            authToken = token
        )

        // Step 4: Display result
        if (result.success) {
            println("+ ${result.message}")
            println()
            println("============================================================")
            println("Summary")
            println("============================================================")
            println("Requirement ID: ${result.id}")
            println("Short Req: ${shortreq.take(60)}${if (shortreq.length > 60) "..." else ""}")
            chapter?.let { println("Chapter: $it") }
            println("Operation: ${result.operation}")
        } else {
            System.err.println("Error: ${result.message}")
            System.exit(result.exitCode)
            return
        }
    }
}
