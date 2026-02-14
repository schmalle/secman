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
 *   ./bin/secman manage-user-mappings list-bucket --bucket my-bucket
 *   ./bin/secman manage-user-mappings list-bucket --bucket my-bucket --prefix user-mappings/
 *   ./bin/secman manage-user-mappings list-bucket --bucket my-bucket --aws-profile prod
 *   ./bin/secman manage-user-mappings list-bucket --bucket my-bucket --aws-region eu-west-1
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

            // Require admin identity for audit consistency
            val adminEmail = parent.getAdminUserOrThrow()
            log.info("AUDIT: operation=LIST_BUCKET, actor=$adminEmail, bucket=$bucket, prefix=$prefix")

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
            println()

            println("Listing objects...")
            val objects = s3DownloadService.listObjects(
                bucket = bucket,
                prefix = prefix,
                region = awsRegion,
                profile = awsProfile
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
            System.err.println("S3 Error: ${e.message}")
            System.exit(2)
        } catch (e: IllegalArgumentException) {
            println()
            System.err.println("Error: ${e.message}")
            System.exit(2)
        } catch (e: Exception) {
            println()
            System.err.println("Unexpected error: ${e.message}")
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
