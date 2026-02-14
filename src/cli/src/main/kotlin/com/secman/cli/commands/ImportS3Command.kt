package com.secman.cli.commands

import com.secman.cli.service.S3DownloadException
import com.secman.cli.service.S3DownloadService
import com.secman.cli.service.UserMappingCliService
import org.slf4j.LoggerFactory
import picocli.CommandLine.*
import jakarta.inject.Singleton

/**
 * CLI command to import user mappings from AWS S3 bucket (Feature 065)
 *
 * Usage:
 *   ./bin/secman manage-user-mappings import-s3 --bucket my-bucket --key mappings.csv
 *   ./bin/secman manage-user-mappings import-s3 --bucket my-bucket --key data/users.json --aws-profile prod
 *   ./bin/secman manage-user-mappings import-s3 --bucket my-bucket --key mappings.csv --aws-region eu-west-1
 *   ./bin/secman manage-user-mappings import-s3 --bucket my-bucket --key mappings.csv --dry-run
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
 * - Downloads file from S3 and imports using existing logic
 * - Supports AWS credential chain (env vars, profiles, IAM roles)
 * - Auto-detects format from file extension or content
 * - Dry-run mode for validation without importing
 * - Cron-friendly exit codes (0=success, 1=partial, 2+=fatal)
 * - 10MB file size limit
 * - Automatic temp file cleanup
 * - Requires ADMIN role
 */
@Singleton
@Command(
    name = "import-s3",
    description = ["Import user mappings from AWS S3 bucket"],
    mixinStandardHelpOptions = true
)
class ImportS3Command(
    private val s3DownloadService: S3DownloadService,
    private val userMappingCliService: UserMappingCliService
) : Runnable {

    private val log = LoggerFactory.getLogger(ImportS3Command::class.java)

    @Option(
        names = ["--bucket", "-b"],
        description = ["S3 bucket name"],
        required = true
    )
    lateinit var bucket: String

    @Option(
        names = ["--key", "-k"],
        description = ["S3 object key (path to file in bucket)"],
        required = true
    )
    lateinit var key: String

    @Option(
        names = ["--aws-region"],
        description = ["AWS region (default: use SDK default resolution from env/config)"]
    )
    var awsRegion: String? = null

    @Option(
        names = ["--aws-profile"],
        description = ["AWS credential profile name (default: use default credential chain)"]
    )
    var awsProfile: String? = null

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
        var tempFilePath: java.nio.file.Path? = null

        try {
            println("=".repeat(60))
            println("Import User Mappings from S3")
            println("=".repeat(60))
            println()

            // Get admin user from parent command
            val adminEmail = parent.getAdminUserOrThrow()
            println("Admin user: $adminEmail")
            println("Source: s3://$bucket/$key")
            if (awsRegion != null) {
                println("AWS Region: $awsRegion")
            }
            if (awsProfile != null) {
                println("AWS Profile: $awsProfile")
            }
            println("Format: $format")
            if (dryRun) {
                println("Mode: DRY-RUN (validation only, no changes will be made)")
            }
            println()

            // Download from S3
            println("Downloading from S3...")
            tempFilePath = s3DownloadService.downloadToTempFile(
                bucket = bucket,
                key = key,
                region = awsRegion,
                profile = awsProfile
            )
            println("Download complete.")
            println()

            // Execute import using existing logic
            val result = userMappingCliService.importMappingsFromFile(
                filePath = tempFilePath.toString(),
                format = format,
                dryRun = dryRun,
                adminEmail = adminEmail
            )

            // Display summary (matching existing ImportCommand format)
            println()
            println("=".repeat(60))
            println("Summary")
            println("=".repeat(60))
            println("Total: ${result.totalProcessed} mapping(s) processed")

            if (!dryRun) {
                if (result.created > 0) {
                    println("Created: ${result.created} active mapping(s)")
                }
                if (result.createdPending > 0) {
                    println("Created: ${result.createdPending} pending mapping(s)")
                }
                if (result.skipped > 0) {
                    println("Skipped: ${result.skipped} duplicate(s)")
                }
            } else {
                val wouldCreate = result.operations.count { it.operation == "WOULD_CREATE" }
                if (wouldCreate > 0) {
                    println("Would create: $wouldCreate mapping(s)")
                }
            }

            if (result.errors.isNotEmpty()) {
                println("Errors: ${result.errors.size} failure(s)")
                println()
                println("Errors:")
                result.errors.forEach { error ->
                    println("  - $error")
                }
            }
            println()

            // Exit status (T019-T022: cron-friendly exit codes)
            if (result.errors.isNotEmpty()) {
                if (dryRun) {
                    println("Validation failed (dry-run)")
                } else {
                    println("Import completed with errors")
                }
                // Exit code 1: partial success (some errors)
                System.exit(1)
            } else {
                if (dryRun) {
                    println("Validation successful (dry-run)")
                } else {
                    println("Import successful")
                }
                // Exit code 0: success
            }

        } catch (e: S3DownloadException) {
            // Exit code 2: fatal S3 error
            println()
            System.err.println("S3 Error: ${e.message}")
            System.exit(2)
        } catch (e: IllegalArgumentException) {
            // Exit code 2: configuration/argument error
            println()
            System.err.println("Error: ${e.message}")
            System.exit(2)
        } catch (e: Exception) {
            // Exit code 3: unexpected error
            println()
            System.err.println("Unexpected error: ${e.message}")
            log.debug("Stack trace for unexpected error", e)
            System.exit(3)
        } finally {
            // Clean up temp file
            s3DownloadService.cleanupTempFile(tempFilePath)
        }
    }
}
