package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import picocli.CommandLine.*
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.io.File

/**
 * CLI command to batch import GitHub owner email mappings from a CSV file.
 *
 * Usage:
 *   ./scripts/secman manage-github-owner-mappings import --file mappings.csv
 *
 * CSV format:
 *   owner,email
 *   acme-corp,security@acme-corp.example.com
 */
@Singleton
@Command(
    name = "import",
    description = ["Batch import GitHub owner email mappings from a CSV file (owner,email columns)"],
    mixinStandardHelpOptions = true
)
class ImportGithubOwnerMappingsCommand : Runnable {

    @Option(names = ["--file", "-f"], description = ["Path to CSV file"], required = true)
    lateinit var filePath: String

    @ParentCommand
    lateinit var parent: ManageGithubOwnerMappingsCommand

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    override fun run() {
        try {
            val file = File(filePath)
            if (!file.isFile) {
                throw IllegalArgumentException("File not found: $filePath")
            }

            val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                throw IllegalArgumentException("Empty file: $filePath")
            }

            val header = lines.first().split(",").map { it.trim().lowercase() }
            val ownerIdx = header.indexOf("owner")
            val emailIdx = header.indexOf("email")
            if (ownerIdx == -1 || emailIdx == -1) {
                throw IllegalArgumentException("CSV must have 'owner' and 'email' columns")
            }

            val backendUrl = parent.getEffectiveBackendUrl()
            val authToken = cliHttpClient.authenticate(parent.getEffectiveUsername(), parent.getEffectivePassword(), backendUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")

            var imported = 0
            var skipped = 0
            val errors = mutableListOf<String>()

            lines.drop(1).forEachIndexed { idx, line ->
                val lineNumber = idx + 2
                val cols = line.split(",").map { it.trim() }
                val owner = cols.getOrNull(ownerIdx)
                val email = cols.getOrNull(emailIdx)

                if (owner.isNullOrBlank() || email.isNullOrBlank()) {
                    skipped++
                    errors.add("Line $lineNumber: owner and email are required")
                    return@forEachIndexed
                }

                val (status, result) = cliHttpClient.postMapWithStatus(
                    "$backendUrl/api/github/owner-email-mappings",
                    mapOf("owner" to owner, "email" to email),
                    authToken
                )

                if (status in 200..299) {
                    imported++
                } else {
                    skipped++
                    errors.add("Line $lineNumber ($owner): ${result?.get("error")?.toString() ?: "HTTP $status"}")
                }
            }

            println("Imported: $imported")
            println("Skipped: $skipped")
            if (errors.isNotEmpty()) {
                println("Errors:")
                errors.forEach { println("  - $it") }
            }

            if (skipped > 0 && imported == 0) {
                System.exit(1)
            }
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            System.exit(1)
        }
    }
}
