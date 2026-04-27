package com.secman.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.secman.cli.service.S3DownloadException
import com.secman.cli.service.S3DownloadService
import com.secman.cli.service.UserMappingCliService
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.slf4j.LoggerFactory
import picocli.CommandLine.*
import jakarta.inject.Singleton
import java.io.StringWriter
import java.nio.file.Path

/**
 * CLI command to download an AWS account mapping file from S3 and PRINT its
 * parsed contents to the console. Bypasses the secman backend entirely — the
 * source of truth is the S3 file, not the DB.
 *
 * Usage:
 *   ./scriptpp/secman manage-user-mappings print-s3 \
 *       --bucket my-bucket --key mappings.csv
 *
 *   # Show domain mappings instead of AWS account mappings
 *   ./scriptpp/secman manage-user-mappings print-s3 \
 *       --bucket my-bucket --key mappings.csv --type DOMAIN
 *
 *   # Print everything (AWS + domain) as JSON
 *   ./scriptpp/secman manage-user-mappings print-s3 \
 *       --bucket my-bucket --key mappings.csv --type ALL --format JSON
 *
 *   # Print as CSV (handy for piping into a diff with `list --output`)
 *   ./scriptpp/secman manage-user-mappings print-s3 \
 *       --bucket my-bucket --key mappings.csv --format CSV
 *
 * Differences vs the related S3 commands:
 *   - `import-s3` downloads AND POSTs to the secman backend.
 *   - `download-s3` only copies the raw file to a local path.
 *   - `print-s3` (this command) downloads, parses, and prints — never writes
 *     to disk and never contacts the backend. The temp file used during
 *     download is deleted on exit.
 *
 * Default `--type` is AWS because the typical use case is inspecting the AWS
 * account → email mappings carried in the S3 file. Override with --type
 * DOMAIN or --type ALL.
 *
 * Exit codes:
 *   0 = success (file parsed and printed)
 *   1 = parse errors found in the file (printed to stderr); valid rows still printed
 *   2 = S3, credentials, or argument error (fatal — won't succeed on retry)
 *   3 = unexpected error
 */
@Singleton
@Command(
    name = "print-s3",
    description = [
        "Download the AWS account mapping file from S3 and print the parsed " +
            "mappings to the console. Does NOT touch the secman backend and does " +
            "NOT write to disk. Default scope is AWS account mappings — use " +
            "--type DOMAIN or --type ALL to widen."
    ],
    mixinStandardHelpOptions = true
)
class PrintS3Command(
    private val s3DownloadService: S3DownloadService,
    private val userMappingCliService: UserMappingCliService
) : Runnable {

    private val log = LoggerFactory.getLogger(PrintS3Command::class.java)

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
        names = ["--type"],
        description = [
            "Restrict the printed output by mapping kind. AWS (default) prints AWS " +
                "account mappings only, DOMAIN prints domain mappings only, ALL " +
                "prints both."
        ],
        defaultValue = "AWS"
    )
    var type: String = "AWS"

    @Option(
        names = ["--format"],
        description = ["Output format: TABLE (default), JSON, or CSV"],
        defaultValue = "TABLE"
    )
    var format: String = "TABLE"

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
            "Suppress the header banner. The parsed mapping output (TABLE/JSON/CSV) " +
                "is still printed to stdout so the command remains pipeable."
        ]
    )
    var quiet: Boolean = false

    @ParentCommand
    lateinit var parent: ManageUserMappingsCommand

    override fun run() {
        var tempFilePath: Path? = null

        try {
            val typeUpper = type.uppercase()
            if (typeUpper !in setOf("AWS", "DOMAIN", "ALL")) {
                throw IllegalArgumentException("Invalid --type. Use AWS, DOMAIN, or ALL")
            }
            val formatUpper = format.uppercase()
            if (formatUpper !in setOf("TABLE", "JSON", "CSV")) {
                throw IllegalArgumentException("Invalid --format. Use TABLE, JSON, or CSV")
            }

            if (!quiet) {
                System.err.println("=".repeat(60))
                System.err.println("Print Mapping File from S3")
                System.err.println("=".repeat(60))
                System.err.println("Source: s3://$bucket/$key")
                System.err.println("Scope: $typeUpper  Format: $formatUpper  File-format: ${fileFormat.uppercase()}")
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

            // Apply --type filter
            val filtered = when (typeUpper) {
                "AWS" -> parseResult.entries.filter { it["awsAccountId"] != null }
                "DOMAIN" -> parseResult.entries.filter { it["domain"] != null }
                else -> parseResult.entries
            }

            when (formatUpper) {
                "TABLE" -> printTable(filtered, typeUpper)
                "JSON" -> printJson(filtered)
                "CSV" -> printCsv(filtered)
            }

            if (!quiet) {
                System.err.println()
                System.err.println("=".repeat(60))
                System.err.println(
                    "Parsed ${parseResult.entries.size} mapping(s) from " +
                        "s3://$bucket/$key (${filtered.size} after --type filter)"
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

    private fun printTable(entries: List<Map<String, Any?>>, typeUpper: String) {
        if (entries.isEmpty()) {
            println("No mappings found (scope: $typeUpper)")
            return
        }

        // Group by email so the user sees per-user roll-ups, like ListCommand.
        val grouped = entries.groupBy { it["email"] as String }

        grouped.forEach { (email, rows) ->
            println(email)
            val awsRows = rows.mapNotNull { it["awsAccountId"] as? String }
            val domainRows = rows.mapNotNull { it["domain"] as? String }
            if (awsRows.isNotEmpty()) {
                println("  AWS Accounts:")
                awsRows.forEach { println("    - $it") }
            }
            if (domainRows.isNotEmpty()) {
                println("  Domains:")
                domainRows.forEach { println("    - $it") }
            }
        }

        println()
        println("Total: ${entries.size} mapping(s) across ${grouped.size} user(s)")
    }

    private fun printJson(entries: List<Map<String, Any?>>) {
        val mapper = jacksonObjectMapper()
        val grouped = entries.groupBy { it["email"] as String }.map { (email, rows) ->
            mapOf(
                "email" to email,
                "awsAccounts" to rows.mapNotNull { it["awsAccountId"] as? String },
                "domains" to rows.mapNotNull { it["domain"] as? String }
            )
        }
        val output = mapOf(
            "totalUsers" to grouped.size,
            "totalMappings" to entries.size,
            "mappings" to grouped
        )
        println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output))
    }

    @Suppress("DEPRECATION")
    private fun printCsv(entries: List<Map<String, Any?>>) {
        val sw = StringWriter()
        val printer = CSVPrinter(
            sw,
            CSVFormat.DEFAULT.builder()
                .setHeader("Email", "Type", "Value")
                .build()
        )
        entries.forEach { entry ->
            val email = entry["email"] as String
            (entry["awsAccountId"] as? String)?.let { printer.printRecord(email, "AWS_ACCOUNT", it) }
            (entry["domain"] as? String)?.let { printer.printRecord(email, "DOMAIN", it) }
        }
        printer.flush()
        println(sw.toString())
    }
}
