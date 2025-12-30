package com.secman.cli.commands

import com.secman.cli.service.RequirementCliService
import jakarta.inject.Inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * CLI command to export requirements to Excel or Word format
 * Feature: 057-cli-mcp-requirements
 *
 * Usage:
 *   secman export-requirements --format <xlsx|docx> [--output <path>] [options]
 *
 * Examples:
 *   secman export-requirements --format xlsx
 *   secman export-requirements --format docx --output requirements.docx
 *   secman export-requirements --format xlsx --output /path/to/export.xlsx --verbose
 *
 * User Stories:
 * - US1: Export requirements to Excel format (P1)
 * - US2: Export requirements to Word format (P1)
 */
@Command(
    name = "export-requirements",
    description = ["Export all requirements to Excel (xlsx) or Word (docx) format"],
    mixinStandardHelpOptions = true
)
class ExportRequirementsCommand : Runnable {

    @Inject
    lateinit var requirementCliService: RequirementCliService

    @Option(
        names = ["--format", "-f"],
        description = ["Export format: xlsx (Excel) or docx (Word)"],
        required = true,
        completionCandidates = FormatCandidates::class
    )
    lateinit var format: String

    @Option(
        names = ["--output", "-o"],
        description = ["Output file path (default: requirements_export_YYYYMMDD.{format})"],
        required = false
    )
    var output: String? = null

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

        // Validate format
        val normalizedFormat = format.lowercase()
        if (normalizedFormat !in listOf("xlsx", "docx")) {
            System.err.println("Error: Format must be xlsx or docx")
            System.err.println("   Received: $format")
            System.exit(1)
            return
        }

        println("============================================================")
        println("Export Requirements")
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

        // Step 2: Generate output filename if not specified
        val outputPath = output ?: generateDefaultFilename(normalizedFormat)
        val outputFile = File(outputPath)

        // Validate output directory exists
        val parentDir = outputFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            System.err.println("Error: Output directory does not exist: ${parentDir.absolutePath}")
            System.exit(1)
            return
        }

        if (verbose) {
            println("Exporting requirements...")
            println("Format: $normalizedFormat")
            println("Output: ${outputFile.absolutePath}")
            println("Backend: $backendUrl")
            println()
        }

        // Step 3: Export requirements
        val result = requirementCliService.exportRequirements(
            format = normalizedFormat,
            backendUrl = backendUrl,
            authToken = token
        )

        // Step 4: Handle result
        if (result.success && result.data != null) {
            // Write file
            try {
                outputFile.writeBytes(result.data)

                println("Export successful!")
                println()
                println("============================================================")
                println("Summary")
                println("============================================================")
                println("File: ${outputFile.absolutePath}")
                println("Size: ${formatFileSize(result.data.size)}")
                println("Format: ${normalizedFormat.uppercase()}")
            } catch (e: Exception) {
                System.err.println("Error: Failed to write file: ${e.message}")
                System.exit(1)
                return
            }
        } else {
            System.err.println("Error: ${result.message}")
            System.exit(result.exitCode)
            return
        }
    }

    /**
     * Generate default filename with timestamp
     */
    private fun generateDefaultFilename(format: String): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return "requirements_export_$timestamp.$format"
    }

    /**
     * Format file size in human-readable format
     */
    private fun formatFileSize(bytes: Int): String {
        return when {
            bytes < 1024 -> "$bytes bytes"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * Completion candidates for format option
     */
    class FormatCandidates : Iterable<String> {
        override fun iterator(): Iterator<String> {
            return listOf("xlsx", "docx").iterator()
        }
    }
}
