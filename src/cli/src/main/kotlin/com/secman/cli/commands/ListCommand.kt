package com.secman.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.secman.cli.service.UserMappingCliResponse
import com.secman.cli.service.UserMappingCliService
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import picocli.CommandLine.*
import jakarta.inject.Singleton
import java.io.File
import java.io.StringWriter

/**
 * CLI command to list user mappings (Feature 049)
 *
 * Usage:
 *   ./scriptpp/secman manage-user-mappings list
 *   ./scriptpp/secman manage-user-mappings list --email user@example.com
 *   ./scriptpp/secman manage-user-mappings list --status PENDING
 *   ./scriptpp/secman manage-user-mappings list --format JSON
 *   ./scriptpp/secman manage-user-mappings list --format CSV
 *
 * Download mappings to a file (CSV is round-trip compatible with `import`):
 *   ./scriptpp/secman manage-user-mappings list --type AWS --format CSV --output aws.csv
 *   ./scriptpp/secman manage-user-mappings list --type DOMAIN --format JSON --output domains.json
 *   ./scriptpp/secman manage-user-mappings list --format CSV --output all-mappings.csv
 *
 * Filters:
 *   --email   single user
 *   --status  ACTIVE | PENDING | ALL
 *   --type    AWS | DOMAIN | ALL  (scope by mapping kind)
 *   --format  TABLE | JSON | CSV  (TABLE is interactive only; auto-coerced to
 *             CSV when --output is set)
 *   --output  write rendered output to FILE; the byte count and absolute path
 *             are reported on stderr so stdout stays clean for piping
 */
@Singleton
@Command(
    name = "list",
    description = [
        "List existing user mappings; optionally email statistics " +
            "to all ADMIN/REPORT users via --send-email (Feature 085)."
    ],
    mixinStandardHelpOptions = true
)
class ListCommand(
    private val userMappingCliService: UserMappingCliService
) : Runnable {

    @Option(
        names = ["--email"],
        description = ["Filter by user email address"]
    )
    var email: String? = null

    @Option(
        names = ["--status"],
        description = ["Filter by status (ACTIVE, PENDING, ALL)"]
    )
    var statusFilter: String? = null

    @Option(
        names = ["--format"],
        description = ["Output format (TABLE, JSON, CSV)"],
        defaultValue = "TABLE"
    )
    var format: String = "TABLE"

    @Option(
        names = ["--output", "-o"],
        description = [
            "Download mappings to FILE instead of printing to stdout. " +
                "When combined with --format CSV the file is round-trip " +
                "compatible with 'manage-user-mappings import'."
        ]
    )
    var outputFile: String? = null

    @Option(
        names = ["--type"],
        description = [
            "Restrict downloaded mappings by kind: AWS (AWS account mappings only), " +
                "DOMAIN (domain mappings only), or ALL (default)."
        ],
        defaultValue = "ALL"
    )
    var type: String = "ALL"

    // Feature 085: Email distribution options
    @Option(
        names = ["--send-email"],
        description = [
            "Email the statistics report to all ADMIN and REPORT users. " +
                "Console output is still printed."
        ]
    )
    var sendEmail: Boolean = false

    @Option(
        names = ["--dry-run"],
        description = [
            "Used with --send-email: print intended recipient list without " +
                "dispatching any email."
        ]
    )
    var dryRun: Boolean = false

    @Option(
        names = ["--verbose", "-v"],
        description = [
            "Used with --send-email: print per-recipient send status."
        ]
    )
    var verbose: Boolean = false

    @ParentCommand
    lateinit var parent: ManageUserMappingsCommand

    override fun run() {
        // Feature 085: --dry-run is only meaningful with --send-email
        if (dryRun && !sendEmail) {
            System.err.println("Error: --dry-run requires --send-email")
            System.exit(1)
            return
        }

        try {
            // Authenticate with backend
            val backendUrl = parent.getEffectiveBackendUrl()
            val username = parent.getEffectiveUsername()
            val password = parent.getEffectivePassword()
            userMappingCliService.initHttpClient(backendUrl, parent.isEffectiveInsecure())
            val token = userMappingCliService.authenticate(username, password, backendUrl)
                ?: throw IllegalArgumentException("Authentication failed - check username/password")

            // Parse status filter
            val status = when (statusFilter?.uppercase()) {
                "ACTIVE" -> "ACTIVE"
                "PENDING" -> "PENDING"
                "ALL", null -> null
                else -> {
                    System.err.println("Error: Invalid status. Use ACTIVE, PENDING, or ALL")
                    System.exit(1)
                    return
                }
            }

            // Fetch mappings via HTTP
            val rawMappings = userMappingCliService.listMappings(
                email = email,
                status = status,
                backendUrl = backendUrl,
                authToken = token
            )

            // Apply --type filter (AWS-only / DOMAIN-only / ALL)
            val mappings = when (type.uppercase()) {
                "AWS" -> rawMappings.filter { it.awsAccountId != null }
                "DOMAIN" -> rawMappings.filter { it.domain != null }
                "ALL" -> rawMappings
                else -> {
                    System.err.println("Error: Invalid --type. Use AWS, DOMAIN, or ALL")
                    System.exit(1)
                    return
                }
            }

            // --output forces a file-friendly format (TABLE is interactive only)
            val effectiveFormat = if (outputFile != null && format.uppercase() == "TABLE") {
                "CSV"
            } else {
                format.uppercase()
            }

            when (effectiveFormat) {
                "TABLE" -> displayTable(mappings)
                "JSON" -> writeOrPrint(renderJson(mappings))
                "CSV" -> writeOrPrint(renderCsv(mappings))
                else -> {
                    System.err.println("Error: Invalid format. Use TABLE, JSON, or CSV")
                    System.exit(1)
                }
            }

            // Feature 085: optionally distribute the report by email
            if (sendEmail) {
                sendStatisticsEmail(backendUrl, token, status)
            }

        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            System.exit(1)
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            e.printStackTrace()
            System.exit(1)
        }
    }

    /**
     * Feature 085: POST to /api/cli/user-mappings/send-statistics-email, print a
     * summary block, and exit with a failure-mode-specific exit code.
     *
     * Exit codes (applies only when --send-email was set):
     *   0 = SUCCESS or DRY_RUN
     *   1 = generic error (network, parse, unexpected)
     *   2 = authorization denied (HTTP 403)
     *   3 = no eligible recipients (status=FAILURE + recipientCount=0)
     *   4 = partial failure (status=PARTIAL_FAILURE)
     *   5 = full failure (status=FAILURE + recipientCount>0)
     */
    private fun sendStatisticsEmail(backendUrl: String, token: String, status: String?) {
        val result = userMappingCliService.sendStatisticsEmail(
            backendUrl = backendUrl,
            authToken = token,
            filterEmail = email,
            filterStatus = status,
            dryRun = dryRun,
            verbose = verbose
        )

        val separator = "=".repeat(60)
        println()
        println(separator)

        when (result.statusCode) {
            200 -> {
                // Backend responded successfully — inspect payload
                val body = result.body
                if (body == null) {
                    System.err.println("Email Distribution")
                    println(separator)
                    System.err.println("Error: empty response body from backend")
                    System.exit(1)
                    return
                }

                val backendStatus = body["status"]?.toString() ?: "UNKNOWN"
                val recipientCount = (body["recipientCount"] as? Number)?.toInt() ?: 0
                val emailsSent = (body["emailsSent"] as? Number)?.toInt() ?: 0
                val emailsFailed = (body["emailsFailed"] as? Number)?.toInt() ?: 0

                @Suppress("UNCHECKED_CAST")
                val recipients = (body["recipients"] as? List<String>) ?: emptyList()
                @Suppress("UNCHECKED_CAST")
                val failedRecipients = (body["failedRecipients"] as? List<String>) ?: emptyList()

                when (backendStatus) {
                    "DRY_RUN" -> {
                        println("Email Distribution (DRY RUN)")
                        println(separator)
                        println("Would send to $recipientCount ADMIN/REPORT recipients:")
                        recipients.forEach { println("  - $it") }
                        println("No emails dispatched.")
                        System.exit(0)
                    }

                    "SUCCESS" -> {
                        println("Email Distribution")
                        println(separator)
                        println("Recipients: $recipientCount")
                        println("Emails sent: $emailsSent")
                        println("Failures: $emailsFailed")
                        if (verbose) {
                            recipients.forEach { println("  SUCCESS $it") }
                        }
                        println("Statistics delivered successfully.")
                        System.exit(0)
                    }

                    "PARTIAL_FAILURE" -> {
                        println("Email Distribution")
                        println(separator)
                        println("Recipients: $recipientCount")
                        println("Emails sent: $emailsSent")
                        println("Failures: $emailsFailed")
                        if (verbose) {
                            recipients.forEach { println("  SUCCESS $it") }
                            failedRecipients.forEach { println("  FAILED  $it") }
                        }
                        println("Failed recipients:")
                        failedRecipients.forEach { println("  - $it") }
                        println("Email distribution completed with failures.")
                        System.exit(4)
                    }

                    "FAILURE" -> {
                        println("Email Distribution")
                        println(separator)
                        if (recipientCount == 0) {
                            println("No eligible recipients found.")
                            println("Reason: no users with ADMIN or REPORT role have a valid email address.")
                            System.exit(3)
                        } else {
                            println("Recipients: $recipientCount")
                            println("Emails sent: $emailsSent")
                            println("Failures: $emailsFailed")
                            if (failedRecipients.isNotEmpty()) {
                                println("Failed recipients:")
                                failedRecipients.forEach { println("  - $it") }
                            }
                            println("Email distribution failed — zero successful sends.")
                            System.exit(5)
                        }
                    }

                    else -> {
                        System.err.println("Email Distribution")
                        println(separator)
                        System.err.println("Error: unexpected backend status '$backendStatus'")
                        System.exit(1)
                    }
                }
            }

            403 -> {
                System.err.println("Email Distribution")
                println(separator)
                System.err.println("Error: ADMIN role required to send email — use an ADMIN account")
                System.exit(2)
            }

            400 -> {
                System.err.println("Email Distribution")
                println(separator)
                val msg = result.body?.get("message")?.toString() ?: "validation error"
                System.err.println("Error: $msg")
                System.exit(1)
            }

            -1 -> {
                System.err.println("Email Distribution")
                println(separator)
                System.err.println("Error: network or client error contacting backend")
                System.exit(1)
            }

            else -> {
                System.err.println("Email Distribution")
                println(separator)
                System.err.println("Error: backend returned HTTP ${result.statusCode}")
                System.exit(1)
            }
        }
    }

    private fun displayTable(mappings: List<UserMappingCliResponse>) {
        println("=" .repeat(80))
        println("User Mappings")
        println("=" .repeat(80))
        println()

        if (mappings.isEmpty()) {
            println("No mappings found")
            println()
            return
        }

        // Group by email
        val groupedByEmail = mappings.groupBy { it.email }

        groupedByEmail.forEach { (email, userMappings) ->
            val statusBadge = when {
                userMappings.all { it.status == "ACTIVE" } -> "[active]"
                userMappings.all { it.status == "PENDING" } -> "[pending]"
                else -> "[mixed]"
            }

            println("$statusBadge $email")

            // Group by type (domain vs AWS account)
            val domains = userMappings.filter { it.domain != null }
            val awsAccounts = userMappings.filter { it.awsAccountId != null }

            if (domains.isNotEmpty()) {
                println("  Domains:")
                domains.forEach { mapping ->
                    val status = if (mapping.status == "PENDING") " (pending)" else ""
                    println("    - ${mapping.domain}$status")
                }
            }

            if (awsAccounts.isNotEmpty()) {
                println("  AWS Accounts:")
                awsAccounts.forEach { mapping ->
                    val status = if (mapping.status == "PENDING") " (pending)" else ""
                    println("    - ${mapping.awsAccountId}$status")
                }
            }

            println()
        }

        // Summary statistics
        displaySummary(mappings, groupedByEmail.size)
    }

    private fun renderJson(mappings: List<UserMappingCliResponse>): String {
        val objectMapper = jacksonObjectMapper()

        val groupedByEmail = mappings.groupBy { it.email }.map { (email, userMappings) ->
            mapOf(
                "email" to email,
                "domains" to userMappings.filter { it.domain != null }.map {
                    mapOf(
                        "domain" to it.domain,
                        "status" to it.status,
                        "createdAt" to it.createdAt,
                        "appliedAt" to it.appliedAt
                    )
                },
                "awsAccounts" to userMappings.filter { it.awsAccountId != null }.map {
                    mapOf(
                        "awsAccountId" to it.awsAccountId,
                        "status" to it.status,
                        "createdAt" to it.createdAt,
                        "appliedAt" to it.appliedAt
                    )
                }
            )
        }

        val output = mapOf(
            "totalUsers" to groupedByEmail.size,
            "totalMappings" to mappings.size,
            "mappings" to groupedByEmail
        )

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output)
    }

    @Suppress("DEPRECATION")
    private fun renderCsv(mappings: List<UserMappingCliResponse>): String {
        val stringWriter = StringWriter()
        val csvPrinter = CSVPrinter(
            stringWriter,
            CSVFormat.DEFAULT.builder()
                .setHeader("Email", "Type", "Value", "Status", "Created At", "Applied At")
                .build()
        )

        mappings.forEach { mapping ->
            when {
                mapping.domain != null -> csvPrinter.printRecord(
                    mapping.email, "DOMAIN", mapping.domain, mapping.status,
                    mapping.createdAt, mapping.appliedAt ?: ""
                )
                mapping.awsAccountId != null -> csvPrinter.printRecord(
                    mapping.email, "AWS_ACCOUNT", mapping.awsAccountId, mapping.status,
                    mapping.createdAt, mapping.appliedAt ?: ""
                )
            }
        }

        csvPrinter.flush()
        return stringWriter.toString()
    }

    /**
     * If --output is set, write the rendered payload to disk and emit a short
     * confirmation line on stdout. Otherwise print to stdout as before.
     * Refuses to overwrite an existing file unless the path points to /dev/null
     * or the parent dir doesn't exist (the latter surfaces as an IOException).
     */
    private fun writeOrPrint(content: String) {
        val target = outputFile
        if (target == null) {
            println(content)
            return
        }
        val file = File(target)
        if (file.exists() && !file.isFile) {
            throw IllegalArgumentException("--output path '$target' exists and is not a regular file")
        }
        file.writeText(content)
        System.err.println("Downloaded ${file.length()} bytes to ${file.absolutePath}")
    }

    private fun displaySummary(mappings: List<UserMappingCliResponse>, userCount: Int) {
        println("=" .repeat(80))
        println("Summary")
        println("=" .repeat(80))

        val activeCount = mappings.count { it.status == "ACTIVE" }
        val pendingCount = mappings.count { it.status == "PENDING" }
        val domainCount = mappings.count { it.domain != null }
        val awsAccountCount = mappings.count { it.awsAccountId != null }

        println("Total users: $userCount")
        println("Total mappings: ${mappings.size}")
        println("  - Active: $activeCount")
        println("  - Pending: $pendingCount")
        println("  - Domains: $domainCount")
        println("  - AWS Accounts: $awsAccountCount")
        println()
    }
}
