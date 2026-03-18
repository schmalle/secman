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
 *   ./bin/secman manage-user-mappings import-s3 --bucket my-bucket --key mappings.csv \
 *       --aws-access-key-id AKIA... --aws-secret-access-key ...
 *
 * AWS Credential Resolution (highest priority first):
 *   1. Explicit CLI flags: --aws-access-key-id + --aws-secret-access-key [+ --aws-session-token]
 *   2. Environment variables: AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY [+ AWS_SESSION_TOKEN]
 *   3. Named profile: --aws-profile (reads ~/.aws/credentials)
 *   4. Default credential chain: IAM role, SSO, etc.
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
        names = ["--aws-access-key-id"],
        description = ["AWS access key ID (or set AWS_ACCESS_KEY_ID env var)"]
    )
    var awsAccessKeyId: String? = null

    @Option(
        names = ["--aws-secret-access-key"],
        description = ["AWS secret access key (or set AWS_SECRET_ACCESS_KEY env var)"]
    )
    var awsSecretAccessKey: String? = null

    @Option(
        names = ["--aws-session-token"],
        description = ["AWS session token for temporary credentials (or set AWS_SESSION_TOKEN env var)"]
    )
    var awsSessionToken: String? = null

    @Option(
        names = ["--endpoint-url"],
        description = ["Custom S3 endpoint URL for local testing (e.g. http://localhost:9090 for S3Mock). Also reads AWS_ENDPOINT_URL env var."]
    )
    var endpointUrl: String? = null

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

            // Authenticate with backend
            val backendUrl = parent.getEffectiveBackendUrl()
            val backendUsername = parent.getEffectiveUsername()
            val backendPassword = parent.getEffectivePassword()
            userMappingCliService.initHttpClient(backendUrl, parent.insecure)
            val token = userMappingCliService.authenticate(backendUsername, backendPassword, backendUrl)
                ?: throw IllegalArgumentException("Authentication failed - check username/password")

            println("Backend: $backendUrl")
            println("Source: s3://$bucket/$key")
            if (awsRegion != null) {
                println("AWS Region: $awsRegion")
            }
            if (awsProfile != null) {
                println("AWS Profile: $awsProfile")
            }

            // Resolve endpoint URL: CLI arg takes priority, then env var
            val resolvedEndpointUrl = endpointUrl ?: System.getenv("AWS_ENDPOINT_URL")
            if (resolvedEndpointUrl != null) {
                println("S3 Endpoint: $resolvedEndpointUrl")
            }

            // Resolve AWS credentials: CLI args take priority, then env vars
            val resolvedAccessKeyId = awsAccessKeyId ?: System.getenv("AWS_ACCESS_KEY_ID")
            val resolvedSecretAccessKey = awsSecretAccessKey ?: System.getenv("AWS_SECRET_ACCESS_KEY")
            val resolvedSessionToken = awsSessionToken ?: System.getenv("AWS_SESSION_TOKEN")

            if (resolvedAccessKeyId != null) {
                println("AWS Credentials: explicit (access key ${resolvedAccessKeyId.take(4)}...)")
            } else if (awsProfile != null) {
                println("AWS Credentials: profile '$awsProfile'")
            } else {
                println("AWS Credentials: default chain (env/config/IAM)")
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
                profile = awsProfile,
                accessKeyId = resolvedAccessKeyId,
                secretAccessKey = resolvedSecretAccessKey,
                sessionToken = resolvedSessionToken
            )
            println("Download complete.")
            println()

            // Execute import via HTTP
            val result = userMappingCliService.importMappingsFromFile(
                filePath = tempFilePath.toString(),
                format = format,
                dryRun = dryRun,
                backendUrl = backendUrl,
                authToken = token
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
                val comparison = result.comparison
                if (comparison != null && comparison.dbAvailable) {
                    println("Comparison:")
                    println("  Backend:   ${comparison.dbMappingCount} existing mapping(s)")
                    println("  File:      ${comparison.fileMappingCount} mapping(s) from S3")
                    println("  New:       ${comparison.newCount} mapping(s) (in file, not in DB)")
                    println("  Unchanged: ${comparison.unchangedCount} mapping(s) (in both)")
                    println("  Removed:   ${comparison.removedCount} mapping(s) (in DB, not in file)")
                } else {
                    val wouldCreate = result.operations.count { it.operation == "WOULD_CREATE" }
                    if (wouldCreate > 0) {
                        println("Would create: $wouldCreate mapping(s)")
                    }
                    if (comparison != null && !comparison.dbAvailable) {
                        println("Note: Database unavailable, comparison skipped (format validation only)")
                    }
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
            System.err.println("ERROR: ${e.message}")
            System.err.println()
            System.err.println("Usage: manage-user-mappings import-s3 --bucket <bucket-name> --key <object-key>")
            System.err.println("  The --bucket value must be a plain S3 bucket name (e.g. 'my-bucket'),")
            System.err.println("  not a URL or ARN.")
            System.exit(2)
        } catch (e: IllegalArgumentException) {
            // Exit code 2: configuration/argument error
            println()
            System.err.println("ERROR: ${e.message}")
            System.exit(2)
        } catch (e: Exception) {
            // Exit code 3: unexpected error
            println()
            System.err.println("ERROR: Unexpected error: ${e.message}")
            log.debug("Stack trace for unexpected error", e)
            System.exit(3)
        } finally {
            // Clean up temp file
            s3DownloadService.cleanupTempFile(tempFilePath)
        }
    }
}
