package com.secman.cli.commands

import com.secman.cli.service.UserMappingCliService
import picocli.CommandLine.*
import jakarta.inject.Singleton

/**
 * CLI command to add domain-to-user mappings (Feature 049)
 *
 * Usage:
 *   ./bin/secman manage-user-mappings add-domain \
 *     --emails user1@example.com,user2@example.com \
 *     --domains example.com,corp.local
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

            // Authenticate with backend
            val backendUrl = parent.getEffectiveBackendUrl()
            val username = parent.getEffectiveUsername()
            val password = parent.getEffectivePassword()
            userMappingCliService.initHttpClient(backendUrl, parent.insecure)
            val token = userMappingCliService.authenticate(username, password, backendUrl)
                ?: throw IllegalArgumentException("Authentication failed - check username/password")

            println("Backend: $backendUrl")
            println()

            // Validate inputs
            val trimmedEmails = emails.map { it.trim() }.filter { it.isNotEmpty() }
            val trimmedDomains = domains.map { it.trim() }.filter { it.isNotEmpty() }

            if (trimmedEmails.isEmpty()) {
                System.err.println("Error: No valid email addresses provided")
                System.exit(1)
            }

            if (trimmedDomains.isEmpty()) {
                System.err.println("Error: No valid domains provided")
                System.exit(1)
            }

            println("Processing domain mappings...")
            println("Emails: ${trimmedEmails.joinToString()}")
            println("Domains: ${trimmedDomains.joinToString()}")
            println()

            // Execute mapping creation via HTTP
            val result = userMappingCliService.addDomainMappings(
                emails = trimmedEmails,
                domains = trimmedDomains,
                backendUrl = backendUrl,
                authToken = token
            )

            // Display results
            result.operations.forEach { op ->
                val symbol = when (op.operation) {
                    "CREATED" -> if (op.isPending) "!" else "+"
                    "SKIPPED_DUPLICATE" -> "~"
                    else -> "x"
                }

                val status = when {
                    op.operation == "CREATED" && op.isPending -> " (pending - user not found)"
                    op.operation == "SKIPPED_DUPLICATE" -> " (duplicate)"
                    op.operation == "ERROR" -> " - ${op.message}"
                    else -> ""
                }

                println("[$symbol] ${op.email} -> ${op.domain}$status")
            }

            println()
            println("=" .repeat(60))
            println("Summary")
            println("=" .repeat(60))
            println("Total: ${result.totalProcessed} mapping(s) processed")
            if (result.created > 0) println("Created: ${result.created} active")
            if (result.createdPending > 0) println("Created: ${result.createdPending} pending")
            if (result.skipped > 0) println("Skipped: ${result.skipped} duplicate(s)")
            if (result.errors.isNotEmpty()) {
                println("Errors: ${result.errors.size} failure(s)")
                result.errors.forEach { error -> println("  - $error") }
            }
            println()

            if (result.errors.isNotEmpty()) {
                println("Completed with errors")
                System.exit(1)
            } else {
                println("All mappings processed successfully")
            }

        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            System.exit(1)
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            e.printStackTrace()
            System.exit(1)
        }
    }
}
