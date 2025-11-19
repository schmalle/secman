package com.secman.cli.commands

import com.secman.cli.service.UserMappingCliService
import picocli.CommandLine.*
import jakarta.inject.Singleton

/**
 * CLI command to import user mappings from CSV or JSON file (Feature 049)
 *
 * Usage:
 *   ./gradlew cli:run --args='manage-user-mappings import --file mappings.csv'
 *   ./gradlew cli:run --args='manage-user-mappings import --file mappings.json'
 *   ./gradlew cli:run --args='manage-user-mappings import --file data.txt --format CSV'
 *   ./gradlew cli:run --args='manage-user-mappings import --file mappings.csv --dry-run'
 *
 * CSV Format:
 *   email,type,value
 *   user@example.com,DOMAIN,example.com
 *   user@example.com,AWS_ACCOUNT,123456789012
 *
 * JSON Format:
 *   [
 *     {
 *       "email": "user@example.com",
 *       "domains": ["example.com", "corp.local"],
 *       "awsAccounts": ["123456789012"]
 *     }
 *   ]
 *
 * Features:
 * - Auto-detects format from file extension or content
 * - Line-by-line validation with error reporting
 * - Dry-run mode for testing
 * - Partial success mode (continues on errors)
 * - Duplicate detection
 * - Pending mapping creation for non-existent users
 * - Requires ADMIN role
 */
@Singleton
@Command(
    name = "import",
    description = ["Batch import user mappings from CSV or JSON file"],
    mixinStandardHelpOptions = true
)
class ImportCommand(
    private val userMappingCliService: UserMappingCliService
) : Runnable {

    @Option(
        names = ["--file", "-f"],
        description = ["Path to CSV or JSON file"],
        required = true
    )
    lateinit var filePath: String

    @Option(
        names = ["--format"],
        description = ["File format: CSV, JSON, or AUTO (default: AUTO for auto-detection)"],
        defaultValue = "AUTO"
    )
    var format: String = "AUTO"

    @Option(
        names = ["--dry-run"],
        description = ["Validate file without creating mappings"]
    )
    var dryRun: Boolean = false

    @ParentCommand
    lateinit var parent: ManageUserMappingsCommand

    override fun run() {
        try {
            println("=" .repeat(60))
            println("Import User Mappings")
            println("=" .repeat(60))
            println()

            // Get admin user
            val adminEmail = parent.getAdminUserOrThrow()
            println("Admin user: $adminEmail")
            println("File: $filePath")
            println("Format: $format")
            if (dryRun) {
                println("Mode: DRY-RUN (validation only, no changes will be made)")
            }
            println()

            // Execute import
            val result = userMappingCliService.importMappingsFromFile(
                filePath = filePath,
                format = format,
                dryRun = dryRun,
                adminEmail = adminEmail
            )

            // Display summary
            println()
            println("=" .repeat(60))
            println("Summary")
            println("=" .repeat(60))
            println("Total: ${result.totalProcessed} mapping(s) processed")

            if (!dryRun) {
                if (result.created > 0) {
                    println("✅ Created: ${result.created} active mapping(s)")
                }
                if (result.createdPending > 0) {
                    println("⚠️  Created: ${result.createdPending} pending mapping(s)")
                }
                if (result.skipped > 0) {
                    println("⚠️  Skipped: ${result.skipped} duplicate(s)")
                }
            } else {
                val wouldCreate = result.operations.count { it.operation == "WOULD_CREATE" }
                if (wouldCreate > 0) {
                    println("✅ Would create: $wouldCreate mapping(s)")
                }
            }

            if (result.errors.isNotEmpty()) {
                println("❌ Errors: ${result.errors.size} failure(s)")
                println()
                println("Errors:")
                result.errors.forEach { error ->
                    println("  - $error")
                }
            }
            println()

            // Exit status
            if (result.errors.isNotEmpty()) {
                if (dryRun) {
                    println("✗ Validation failed (dry-run)")
                } else {
                    println("✗ Import completed with errors")
                }
                System.exit(1)
            } else {
                if (dryRun) {
                    println("✓ Validation successful (dry-run)")
                } else {
                    println("✓ Import successful")
                }
            }

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
