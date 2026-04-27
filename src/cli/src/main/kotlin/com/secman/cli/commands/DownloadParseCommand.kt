package com.secman.cli.commands

import com.secman.cli.service.S3DownloadException
import com.secman.cli.service.S3DownloadService
import com.secman.cli.service.UserMappingCliService
import org.slf4j.LoggerFactory
import picocli.CommandLine.*
import jakarta.inject.Singleton
import java.nio.file.Path

/**
 * CLI command to download an AWS account mapping file from S3 and print, per
 * user, which AWS accounts are mapped and how many. Bypasses the secman
 * backend entirely — only needs AWS credentials with `s3:GetObject` on the
 * target object. The downloaded file is held in a temp file for parsing and
 * deleted on exit (no on-disk artifact).
 *
 * Usage:
 *   ./scriptpp/secman manage-user-mappings download-parse \
 *       --bucket my-bucket --key mappings.csv
 *
 *   ./scriptpp/secman manage-user-mappings download-parse \
 *       --bucket my-bucket --key data/mappings.json --aws-profile prod
 *
 * Differences vs the related S3 commands:
 *   - `download-s3` only copies the raw file to a local path.
 *   - `print-s3` downloads, parses, and prints with configurable scope (AWS,
 *     DOMAIN, ALL) and format (TABLE, JSON, CSV).
 *   - `download-parse` (this command) is a focused, AWS-only summary: for each
 *     user it lists the mapped AWS account IDs and the per-user count. No
 *     output-format options — keep it greppable and compact.
 *
 * Exit codes:
 *   0 = success
 *   1 = parse errors found in the file (printed to stderr); valid rows still printed
 *   2 = S3, credentials, or argument error (fatal — won't succeed on retry)
 *   3 = unexpected error
 */
@Singleton
@Command(
    name = "download-parse",
    description = [
        "Download an AWS account mapping file from S3 and print, per user, " +
            "which AWS accounts are mapped and how many. Does NOT touch the " +
            "secman backend and does NOT write to disk."
    ],
    mixinStandardHelpOptions = true
)
class DownloadParseCommand(
    private val s3DownloadService: S3DownloadService,
    private val userMappingCliService: UserMappingCliService
) : Runnable {

    private val log = LoggerFactory.getLogger(DownloadParseCommand::class.java)

    @Option(
        names = ["--bucket", "-b"],
        description = ["S3 bucket name (plain name, not a URL or ARN)"],
        required = true
    )
    lateinit var bucket: String

    @Option(
        names = ["--key", "-k"],
        description = ["S3 object key (path to the mapping file inside the bucket)"],
        required = true
    )
    lateinit var key: String

    @Option(
        names = ["--file-format"],
        description = [
            "Format of the source file in S3: CSV, JSON, or AUTO (default — " +
                "auto-detect from extension or content)."
        ],
        defaultValue = "AUTO"
    )
    var fileFormat: String = "AUTO"

    @Option(
        names = ["--show-errors"],
        description = [
            "Print parse errors (e.g. malformed rows) to stderr at the end. " +
                "Without this flag, errors are silently dropped and only valid " +
                "rows are printed."
        ]
    )
    var showErrors: Boolean = false

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
        description = [
            "Custom S3 endpoint URL for local testing (e.g. http://localhost:9090 " +
                "for S3Mock). Also reads AWS_ENDPOINT_URL env var."
        ]
    )
    var endpointUrl: String? = null

    @Option(
        names = ["--quiet", "-q"],
        description = [
            "Suppress the header banner. The per-user output is still printed " +
                "to stdout so the command remains pipeable."
        ]
    )
    var quiet: Boolean = false

    @ParentCommand
    lateinit var parent: ManageUserMappingsCommand

    override fun run() {
        var tempFilePath: Path? = null

        try {
            if (!quiet) {
                System.err.println("=".repeat(60))
                System.err.println("Download & Parse Mapping File from S3")
                System.err.println("=".repeat(60))
                System.err.println("Source: s3://$bucket/$key")
                System.err.println("File-format: ${fileFormat.uppercase()}")
            }

            val resolvedEndpointUrl = endpointUrl ?: System.getenv("AWS_ENDPOINT_URL")
            val resolvedAccessKeyId = awsAccessKeyId ?: System.getenv("AWS_ACCESS_KEY_ID")
            val resolvedSecretAccessKey = awsSecretAccessKey ?: System.getenv("AWS_SECRET_ACCESS_KEY")
            val resolvedSessionToken = awsSessionToken ?: System.getenv("AWS_SESSION_TOKEN")

            if (!quiet && resolvedEndpointUrl != null) {
                System.err.println("S3 Endpoint: $resolvedEndpointUrl")
            }

            tempFilePath = s3DownloadService.downloadToTempFile(
                bucket = bucket,
                key = key,
                region = awsRegion,
                profile = awsProfile,
                accessKeyId = resolvedAccessKeyId,
                secretAccessKey = resolvedSecretAccessKey,
                sessionToken = resolvedSessionToken
            )

            val parseResult = userMappingCliService.parseLocalMappingFile(
                filePath = tempFilePath.toString(),
                format = fileFormat
            )

            // AWS-only: keep entries that have an awsAccountId
            val awsEntries = parseResult.entries.filter { it["awsAccountId"] != null }

            printPerUser(awsEntries)

            if (!quiet) {
                System.err.println()
                System.err.println("=".repeat(60))
                System.err.println(
                    "Parsed ${parseResult.entries.size} mapping(s) from " +
                        "s3://$bucket/$key (${awsEntries.size} AWS account mapping(s))"
                )
                if (parseResult.errors.isNotEmpty()) {
                    System.err.println("Parse errors: ${parseResult.errors.size}")
                }
            }

            if (showErrors && parseResult.errors.isNotEmpty()) {
                System.err.println()
                System.err.println("Parse errors:")
                parseResult.errors.forEach { System.err.println("  - $it") }
            }

            if (parseResult.errors.isNotEmpty()) {
                System.exit(1)
            }

        } catch (e: S3DownloadException) {
            System.err.println()
            System.err.println("ERROR: ${e.message}")
            System.exit(2)
        } catch (e: IllegalArgumentException) {
            System.err.println()
            System.err.println("ERROR: ${e.message}")
            System.exit(2)
        } catch (e: Exception) {
            System.err.println()
            System.err.println("ERROR: Unexpected error: ${e.message}")
            log.debug("Stack trace for unexpected error", e)
            System.exit(3)
        } finally {
            s3DownloadService.cleanupTempFile(tempFilePath)
        }
    }

    private fun printPerUser(entries: List<Map<String, Any?>>) {
        if (entries.isEmpty()) {
            println("No AWS account mappings found.")
            return
        }

        // Group accounts by user. Dedupe accounts within a user so the count
        // reflects distinct accounts, not file-row count.
        val grouped = entries
            .groupBy { it["email"] as String }
            .mapValues { (_, rows) ->
                rows.mapNotNull { it["awsAccountId"] as? String }
                    .distinct()
                    .sorted()
            }
            .toSortedMap()

        grouped.forEach { (email, accounts) ->
            val noun = if (accounts.size == 1) "account" else "accounts"
            println("$email (${accounts.size} $noun)")
            accounts.forEach { println("  - $it") }
        }

        val totalAccounts = grouped.values.sumOf { it.size }
        println()
        println("Total: $totalAccounts AWS account mapping(s) across ${grouped.size} user(s)")
    }
}
