package com.secman.cli.commands

import com.secman.cli.service.S3DownloadException
import com.secman.cli.service.S3DownloadService
import org.slf4j.LoggerFactory
import picocli.CommandLine.*
import jakarta.inject.Singleton
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * CLI command to download an AWS account mapping file directly from an S3
 * bucket to a local file path. Bypasses the secman backend entirely — this
 * command never authenticates against the secman API and never reads from the
 * `user_mapping` table. It only requires AWS credentials with `s3:GetObject`
 * (and optionally `s3:HeadObject` for a pre-download size check) on the
 * target object.
 *
 * Usage:
 *   ./scriptpp/secman manage-user-mappings download-s3 \
 *       --bucket my-bucket --key mappings.csv --output aws-mappings.csv
 *
 *   ./scriptpp/secman manage-user-mappings download-s3 \
 *       --bucket my-bucket --key data/users.json --aws-profile prod \
 *       --output ./users.json
 *
 *   ./scriptpp/secman manage-user-mappings download-s3 \
 *       --bucket my-bucket --key mappings.csv \
 *       --aws-access-key-id AKIA... --aws-secret-access-key ... \
 *       --output mappings.csv
 *
 * AWS credential resolution (highest priority first):
 *   1. Explicit CLI flags: --aws-access-key-id + --aws-secret-access-key [+ --aws-session-token]
 *   2. Environment variables: AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY [+ AWS_SESSION_TOKEN]
 *   3. Named profile: --aws-profile (reads ~/.aws/credentials)
 *   4. Default credential chain: IAM role, SSO, etc.
 *
 * Differences vs `import-s3`:
 *   - `import-s3` downloads from S3 AND POSTs to the secman backend to apply
 *     the mappings — needs both AWS and secman credentials.
 *   - `download-s3` ONLY copies the S3 object to a local file. Useful for
 *     inspecting the source-of-truth file, diffing against backend state, or
 *     piping into other tooling.
 *
 * Exit codes (cron-friendly):
 *   0 = success
 *   1 = generic / I/O error
 *   2 = S3, credentials, or argument error (fatal — won't succeed on retry)
 *   3 = unexpected error
 */
@Singleton
@Command(
    name = "download-s3",
    description = [
        "Download the AWS account mapping file directly from an S3 bucket to a " +
            "local file path. Does NOT touch the secman backend; only needs " +
            "AWS credentials with s3:GetObject on the target object."
    ],
    mixinStandardHelpOptions = true
)
class DownloadS3Command(
    private val s3DownloadService: S3DownloadService
) : Runnable {

    private val log = LoggerFactory.getLogger(DownloadS3Command::class.java)

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
        names = ["--output", "-o"],
        description = [
            "Local destination path. The S3 object is written to this file. " +
                "Refuses to overwrite an existing file unless --force is set. " +
                "Parent directories must already exist."
        ],
        required = true
    )
    lateinit var output: String

    @Option(
        names = ["--force", "-f"],
        description = ["Overwrite the destination file if it already exists."]
    )
    var force: Boolean = false

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
        description = ["Suppress progress output. Errors are still printed to stderr."]
    )
    var quiet: Boolean = false

    @ParentCommand
    lateinit var parent: ManageUserMappingsCommand

    override fun run() {
        var tempFilePath: Path? = null

        try {
            if (!quiet) {
                println("=".repeat(60))
                println("Download AWS Account Mapping from S3")
                println("=".repeat(60))
                println()
                println("Source: s3://$bucket/$key")
                println("Destination: $output")
            }

            val destination = File(output)
            if (destination.exists()) {
                if (!destination.isFile) {
                    throw IllegalArgumentException(
                        "--output path '$output' exists and is not a regular file"
                    )
                }
                if (!force) {
                    throw IllegalArgumentException(
                        "--output path '$output' already exists. " +
                            "Use --force to overwrite."
                    )
                }
            }
            val parentDir = destination.absoluteFile.parentFile
            if (parentDir != null && !parentDir.exists()) {
                throw IllegalArgumentException(
                    "Parent directory '${parentDir.absolutePath}' does not exist. " +
                        "Create it first."
                )
            }

            // Resolve endpoint URL: CLI arg takes priority, then env var
            val resolvedEndpointUrl = endpointUrl ?: System.getenv("AWS_ENDPOINT_URL")

            // Resolve AWS credentials: CLI args take priority, then env vars
            val resolvedAccessKeyId = awsAccessKeyId ?: System.getenv("AWS_ACCESS_KEY_ID")
            val resolvedSecretAccessKey = awsSecretAccessKey ?: System.getenv("AWS_SECRET_ACCESS_KEY")
            val resolvedSessionToken = awsSessionToken ?: System.getenv("AWS_SESSION_TOKEN")

            if (!quiet) {
                if (awsRegion != null) println("AWS Region: $awsRegion")
                if (awsProfile != null) println("AWS Profile: $awsProfile")
                if (resolvedEndpointUrl != null) println("S3 Endpoint: $resolvedEndpointUrl")
                println(
                    "AWS Credentials: " + when {
                        resolvedAccessKeyId != null ->
                            "explicit (access key ${resolvedAccessKeyId.take(4)}...)"
                        awsProfile != null -> "profile '$awsProfile'"
                        else -> "default chain (env/config/IAM)"
                    }
                )
                println()
                println("Downloading from S3...")
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

            // Atomically move into place. Fall back to a non-atomic copy when
            // crossing filesystems (e.g. /tmp on tmpfs vs. user's home dir).
            try {
                Files.move(
                    tempFilePath,
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.copy(tempFilePath, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.deleteIfExists(tempFilePath)
            }
            tempFilePath = null  // moved — don't clean up

            val bytes = destination.length()
            if (!quiet) {
                println()
                println("=".repeat(60))
                println("Summary")
                println("=".repeat(60))
                println("Wrote $bytes bytes to ${destination.absolutePath}")
                println("Download successful")
            } else {
                // In quiet mode, still emit one informational line on stderr so
                // shell wrappers can log it without polluting stdout.
                System.err.println("Wrote $bytes bytes to ${destination.absolutePath}")
            }

        } catch (e: S3DownloadException) {
            System.err.println()
            System.err.println("ERROR: ${e.message}")
            System.err.println()
            System.err.println(
                "Usage: manage-user-mappings download-s3 --bucket <bucket-name> " +
                    "--key <object-key> --output <file>"
            )
            System.err.println("  The --bucket value must be a plain S3 bucket name (e.g. 'my-bucket'),")
            System.err.println("  not a URL or ARN.")
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
}
