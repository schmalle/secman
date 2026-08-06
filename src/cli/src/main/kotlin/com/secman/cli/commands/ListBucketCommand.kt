package com.secman.cli.commands

import com.secman.cli.service.S3DownloadException
import com.secman.cli.service.S3DownloadService
import org.slf4j.LoggerFactory
import picocli.CommandLine.*
import jakarta.inject.Singleton
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * CLI command to list objects in an AWS S3 bucket (Feature 065)
 *
 * Usage:
 *   ./scripts/secman manage-user-mappings list-bucket --bucket my-bucket
 *   ./scripts/secman manage-user-mappings list-bucket --bucket my-bucket --prefix user-mappings/
 *   ./scripts/secman manage-user-mappings list-bucket --bucket my-bucket --aws-profile prod
 *   ./scripts/secman manage-user-mappings list-bucket --bucket my-bucket --aws-region eu-west-1
 *
 * Displays a table of S3 objects with key, size, and last modified timestamp.
 * Useful for discovering available mapping files before running import-s3.
 *
 * Exit codes:
 *   0 = success
 *   2 = S3/configuration error
 *   3 = unexpected error
 */
@Singleton
@Command(
    name = "list-bucket",
    description = ["List objects in an AWS S3 bucket"],
    mixinStandardHelpOptions = true
)
class ListBucketCommand(
    private val s3DownloadService: S3DownloadService
) : Runnable {

    private val log = LoggerFactory.getLogger(ListBucketCommand::class.java)

    @Option(
        names = ["--bucket", "-b"],
        description = ["S3 bucket name"],
        required = true
    )
    lateinit var bucket: String

    @Option(
        names = ["--prefix", "-p"],
        description = ["Filter objects by key prefix (e.g. 'user-mappings/')"]
    )
    var prefix: String? = null

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

    @ParentCommand
    lateinit var parent: ManageUserMappingsCommand

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
    }

    override fun run() {
        try {
            println("=".repeat(60))
            println("List S3 Bucket Contents")
            println("=".repeat(60))
            println()

            // Audit log — use admin-user if provided, else username, else "unknown"
            val auditActor = parent.adminUser ?: System.getenv("SECMAN_ADMIN_EMAIL") ?: parent.username ?: System.getenv("SECMAN_ADMIN_NAME") ?: "unknown"
            log.info("AUDIT: operation=LIST_BUCKET, actor=$auditActor, bucket=$bucket, prefix=$prefix")

            println("Bucket: $bucket")
            if (prefix != null) {
                println("Prefix: $prefix")
            }
            if (awsRegion != null) {
                println("AWS Region: $awsRegion")
            }
            if (awsProfile != null) {
                println("AWS Profile: $awsProfile")
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
            println()

            println("Listing objects...")
            val objects = s3DownloadService.listObjects(
                bucket = bucket,
                prefix = prefix,
                region = awsRegion,
                profile = awsProfile,
                accessKeyId = resolvedAccessKeyId,
                secretAccessKey = resolvedSecretAccessKey,
                sessionToken = resolvedSessionToken
            )

            if (objects.isEmpty()) {
                println("No objects found.")
                println()
                return
            }

            // Calculate column widths
            val keyWidth = maxOf(objects.maxOf { it.key.length }, 3)
            val sizeWidth = maxOf(objects.maxOf { formatSize(it.size).length }, 4)
            val dateWidth = 19 // yyyy-MM-dd HH:mm:ss

            // Print table header
            val header = String.format("%-${keyWidth}s  %${sizeWidth}s  %-${dateWidth}s", "Key", "Size", "Last Modified")
            println(header)
            println("-".repeat(header.length))

            // Print rows
            objects.forEach { obj ->
                println(String.format(
                    "%-${keyWidth}s  %${sizeWidth}s  %-${dateWidth}s",
                    obj.key,
                    formatSize(obj.size),
                    DATE_FORMATTER.format(obj.lastModified)
                ))
            }

            println()
            println("Total: ${objects.size} object(s)")
            if (objects.size == 1000) {
                println("Note: Results may be truncated. Use --prefix to narrow results.")
            }
            println()

        } catch (e: S3DownloadException) {
            println()
            System.err.println("ERROR: ${e.message}")
            System.err.println()
            System.err.println("Usage: manage-user-mappings list-bucket --bucket <bucket-name> [--prefix <prefix>]")
            System.err.println("  The --bucket value must be a plain S3 bucket name (e.g. 'my-bucket'),")
            System.err.println("  not a URL or ARN.")
            System.exit(2)
        } catch (e: IllegalArgumentException) {
            println()
            System.err.println("ERROR: ${e.message}")
            System.exit(2)
        } catch (e: Exception) {
            println()
            System.err.println("ERROR: Unexpected error: ${e.message}")
            log.debug("Stack trace for unexpected error", e)
            System.exit(3)
        }
    }

    /**
     * Format byte size into human-readable string
     */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
