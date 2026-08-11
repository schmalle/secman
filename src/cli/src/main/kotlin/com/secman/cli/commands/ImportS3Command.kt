package com.secman.cli.commands

import com.secman.cli.service.MappingResult
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
 *   ./scripts/secman manage-user-mappings import-s3 --bucket my-bucket --key mappings.csv
 *   ./scripts/secman manage-user-mappings import-s3 --bucket my-bucket --key data/users.json --aws-profile prod
 *   ./scripts/secman manage-user-mappings import-s3 --bucket my-bucket --key mappings.csv --aws-region eu-west-1
 *   ./scripts/secman manage-user-mappings import-s3 --bucket my-bucket --key mappings.csv --dry-run
 *   ./scripts/secman manage-user-mappings import-s3 --bucket my-bucket --key mappings.csv \
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
 *
 * To notify ADMIN/REPORT users about the imported mappings, follow up with:
 *   ./scripts/secman manage-user-mappings list --send-email
 * See [ListCommand] for --send-email, --dry-run, and --verbose details.
 */
@Singleton
@Command(
    name = "import-s3",
    description = [
        "Import user mappings from AWS S3 bucket. " +
            "Use --send-email to email statistics to ADMIN/REPORT users " +
            "after a successful import."
    ],
    mixinStandardHelpOptions = true
)
class ImportS3Command(
    private val s3DownloadService: S3DownloadService,
    private val userMappingCliService: UserMappingCliService
) : Runnable {

    private val log = LoggerFactory.getLogger(ImportS3Command::class.java)

    @Option(
        names = ["--bucket", "-b"],
        description = ["S3 bucket name (or set AWS_ACCOUNT_BUCKET_NAME env var)"]
    )
    var bucket: String? = null

    @Option(
        names = ["--key", "-k"],
        description = ["S3 object key (path to file in bucket) (or set AWS_ACCOUNT_BUCKET_KEY_NAME env var)"]
    )
    var key: String? = null

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

    @Option(
        names = ["--start-risk-assessment"],
        description = [
            "Start a risk assessment for the owner of every brand-new AWS account " +
                "introduced by this import (assessor: a user with SECCHAMPION role)"
        ]
    )
    var startRiskAssessment: Boolean = false

    @Option(
        names = ["--risk-usecase"],
        description = [
            "Name of the use case the risk assessment is scoped to (required when --start-risk-assessment is set). "
                + "The assessment is measured against the ACTIVE requirements release."
        ]
    )
    var riskUseCase: String? = null

    @Option(
        names = ["--risk-deadline-days"],
        description = ["Days until the risk assessment deadline (default: \${DEFAULT-VALUE}, maximum: 3650)"],
        defaultValue = "7"
    )
    var riskDeadlineDays: Int = 7

    // Onboarding options, identical to ImportCommand's. Kept in step deliberately: an operator
    // who learned the flags on `import` must not find them missing on `import-s3`.
    @Option(
        names = ["--onboarding-mode"],
        description = [
            "What to do for the owner of every brand-new AWS account this import introduces. " +
                "WELCOME_ONLY sends a welcome mail only. DIRECT also starts a risk assessment " +
                "for --risk-usecase. GUIDED mails the owner a one-time link and creates the " +
                "assessment from their answers. Valid values: \${COMPLETION-CANDIDATES}. " +
                "Default: none, nothing is sent."
        ]
    )
    var onboardingMode: ImportCommand.OnboardingMode? = null

    @Option(
        names = ["--welcome-email"],
        negatable = true,
        description = [
            "Force the welcome mail on or off. On by default whenever --onboarding-mode is given; " +
                "off for a bare --start-risk-assessment."
        ]
    )
    var welcomeEmail: Boolean? = null

    @Option(
        names = ["--questionnaire-expiry-days"],
        description = ["Days the GUIDED questionnaire link stays valid (default: \${DEFAULT-VALUE}, range 1-90)"],
        defaultValue = "14"
    )
    var questionnaireExpiryDays: Int = 14

    /** The mode actually in effect, applying the same fallback the backend applies. */
    fun effectiveMode(): ImportCommand.OnboardingMode? =
        onboardingMode ?: if (startRiskAssessment) ImportCommand.OnboardingMode.DIRECT else null

    // Feature 085: Email distribution options (same as ListCommand)
    @Option(
        names = ["--send-email"],
        description = [
            "Email the statistics report to all ADMIN and REPORT users " +
                "after a successful import."
        ]
    )
    var sendEmail: Boolean = false

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
        var tempFilePath: java.nio.file.Path? = null

        try {
            println("=".repeat(60))
            println("Import User Mappings from S3")
            println("=".repeat(60))
            println()

            // Validate risk-assessment options (same rules as ImportCommand)
            if (startRiskAssessment && riskUseCase.isNullOrBlank()) {
                throw IllegalArgumentException("--risk-usecase is required when --start-risk-assessment is set")
            }
            if (startRiskAssessment && riskDeadlineDays < 1) {
                throw IllegalArgumentException("--risk-deadline-days must be at least 1 (got $riskDeadlineDays)")
            }
            if (startRiskAssessment && riskDeadlineDays > UserMappingCliService.MAX_RISK_DEADLINE_DAYS) {
                throw IllegalArgumentException(
                    "--risk-deadline-days must be at most " +
                        "${UserMappingCliService.MAX_RISK_DEADLINE_DAYS} (got $riskDeadlineDays)"
                )
            }
            // Same nudge ImportCommand gives: a use case without the enabling flag is a no-op,
            // and silently ignoring it is how an operator ends up believing assessments ran.
            if (!startRiskAssessment && onboardingMode == null && !riskUseCase.isNullOrBlank()) {
                println(
                    "Warning: --risk-usecase is ignored because neither --start-risk-assessment " +
                        "nor --onboarding-mode is set"
                )
            }

            // Onboarding options, validated by the same rules ImportCommand applies. The one
            // combination that is rejected rather than guessed is --start-risk-assessment with a
            // non-DIRECT mode: honouring either half would silently do something else.
            val mode = onboardingMode
            if (startRiskAssessment && mode != null && mode != ImportCommand.OnboardingMode.DIRECT) {
                throw IllegalArgumentException(
                    "--start-risk-assessment only applies to --onboarding-mode DIRECT (got $mode)"
                )
            }
            if (mode == ImportCommand.OnboardingMode.DIRECT && riskUseCase.isNullOrBlank()) {
                throw IllegalArgumentException("--risk-usecase is required when --onboarding-mode is DIRECT")
            }
            if (mode != null && mode != ImportCommand.OnboardingMode.DIRECT && !riskUseCase.isNullOrBlank()) {
                throw IllegalArgumentException("--risk-usecase only applies to --onboarding-mode DIRECT (got $mode)")
            }
            if (questionnaireExpiryDays < ImportCommand.MIN_QUESTIONNAIRE_EXPIRY_DAYS ||
                questionnaireExpiryDays > ImportCommand.MAX_QUESTIONNAIRE_EXPIRY_DAYS
            ) {
                throw IllegalArgumentException(
                    "--questionnaire-expiry-days must be between " +
                        "${ImportCommand.MIN_QUESTIONNAIRE_EXPIRY_DAYS} and " +
                        "${ImportCommand.MAX_QUESTIONNAIRE_EXPIRY_DAYS} (got $questionnaireExpiryDays)"
                )
            }
            if (welcomeEmail != null && mode == null && !startRiskAssessment) {
                throw IllegalArgumentException("--welcome-email requires --onboarding-mode")
            }

            // Resolve bucket/key: CLI flag takes priority, then env var (flags rule)
            val effectiveBucket = bucket
                ?: System.getenv("AWS_ACCOUNT_BUCKET_NAME")
                ?: throw IllegalArgumentException(
                    "S3 bucket required. Use --bucket flag or set AWS_ACCOUNT_BUCKET_NAME environment variable"
                )
            val effectiveKey = key
                ?: System.getenv("AWS_ACCOUNT_BUCKET_KEY_NAME")
                ?: throw IllegalArgumentException(
                    "S3 object key required. Use --key flag or set AWS_ACCOUNT_BUCKET_KEY_NAME environment variable"
                )

            // Authenticate with backend
            val backendUrl = parent.getEffectiveBackendUrl()
            val backendUsername = parent.getEffectiveUsername()
            val backendPassword = parent.getEffectivePassword()
            userMappingCliService.initHttpClient(backendUrl, parent.isEffectiveInsecure())
            val token = userMappingCliService.authenticate(backendUsername, backendPassword, backendUrl)
                ?: throw IllegalArgumentException("Authentication failed - check username/password")

            println("Backend: $backendUrl")
            println("Source: s3://$effectiveBucket/$effectiveKey")
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
            when (effectiveMode()) {
                ImportCommand.OnboardingMode.WELCOME_ONLY ->
                    println("Onboarding: WELCOME_ONLY (welcome mail to each new account owner)")
                ImportCommand.OnboardingMode.DIRECT -> {
                    val label = if (onboardingMode == null) "DIRECT (via --start-risk-assessment)" else "DIRECT"
                    println("Onboarding: $label (use case '$riskUseCase', deadline $riskDeadlineDays day(s))")
                }
                ImportCommand.OnboardingMode.GUIDED ->
                    println(
                        "Onboarding: GUIDED (welcome mail + guided assessment, " +
                            "link valid $questionnaireExpiryDays day(s))"
                    )
                null -> {}
            }
            if (dryRun) {
                println("Mode: DRY-RUN (validation only, no changes will be made)")
            }
            println()

            // Download from S3
            println("Downloading from S3...")
            tempFilePath = s3DownloadService.downloadToTempFile(
                bucket = effectiveBucket,
                key = effectiveKey,
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
                authToken = token,
                startRiskAssessment = startRiskAssessment,
                riskUseCase = riskUseCase,
                riskDeadlineDays = if (effectiveMode() == ImportCommand.OnboardingMode.DIRECT) {
                    riskDeadlineDays
                } else null,
                onboardingMode = onboardingMode?.name,
                sendWelcomeEmail = welcomeEmail,
                questionnaireExpiryDays = if (effectiveMode() == ImportCommand.OnboardingMode.GUIDED) {
                    questionnaireExpiryDays
                } else null
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

            // Onboarding block, printed for every mode including DIRECT, where it carries the
            // welcome-mail outcome the risk-assessment block below does not cover. Plain-text
            // markers here rather than emoji, matching this command's existing OK/SKIPPED/FAILED
            // vocabulary — it is commonly run from cron with a non-UTF-8 locale.
            var onboardingFailures = 0
            if (effectiveMode() != null) {
                println()
                if (result.onboarding.isEmpty()) {
                    println("No brand-new AWS accounts in this import — nothing to onboard.")
                } else {
                    if (dryRun) {
                        println("DRY-RUN — nothing persisted, nothing sent, no invite token minted.")
                        println(
                            "Would onboard ${result.onboarding.size} account/owner pair(s) " +
                                "in ${effectiveMode()} mode:"
                        )
                    } else {
                        println("Onboarding (${result.onboarding.size}):")
                    }
                    result.onboarding.forEach { ob ->
                        val where = "${ob.awsAccountId}  ${ob.ownerEmail}"
                        when {
                            ob.error != null -> {
                                onboardingFailures++
                                println("  FAILED  $where: ${ob.error}")
                            }
                            // A skip is an idempotent no-op, never a failure.
                            ob.skipped ->
                                println("  SKIPPED $where: ${ob.skipReason ?: "already onboarded"}")
                            ob.dryRun ->
                                println("  WOULD   $where  ->  ${ob.mode} onboarding")
                            ob.questionnaireInviteId != null ->
                                println(
                                    "  OK      $where  ->  questionnaire invite #${ob.questionnaireInviteId}" +
                                        (ob.questionnaireExpiresAt?.let { ", expires $it" } ?: "")
                                )
                            ob.riskAssessmentId != null ->
                                println("  OK      $where  ->  assessment #${ob.riskAssessmentId}" +
                                    (if (ob.welcomeEmailSent) ", welcome mail sent" else ""))
                            ob.welcomeEmailSent -> println("  OK      $where  ->  welcome mail sent")
                            else -> println("  WARN    $where  ->  nothing sent (check the email configuration)")
                        }
                    }
                }
            }

            var riskAssessmentFailures = 0
            if (startRiskAssessment || onboardingMode == ImportCommand.OnboardingMode.DIRECT) {
                println()
                if (dryRun) {
                    if (result.newAccounts.isNotEmpty()) {
                        println("Would start ${result.newAccounts.sumOf { it.emails.size }} risk assessment(s) " +
                            "for ${result.newAccounts.size} new AWS account(s) " +
                            "(use case '$riskUseCase', deadline $riskDeadlineDays day(s)).")
                    } else {
                        println("No brand-new AWS accounts in this import — no risk assessments would be started.")
                    }
                } else if (result.riskAssessments.isNotEmpty()) {
                    println("Risk assessments (${result.riskAssessments.size}):")
                    result.riskAssessments.forEach { ra ->
                        if (ra.error != null) {
                            riskAssessmentFailures++
                            println("  FAILED  ${ra.awsAccountId}  ${ra.ownerEmail}: ${ra.error}")
                        } else if (ra.skipped) {
                            // Idempotent no-op, deliberately NOT counted as a failure.
                            println("  SKIPPED ${ra.awsAccountId}  ${ra.ownerEmail}: " +
                                (ra.skipReason ?: "an assessment already exists"))
                        } else {
                            println("  OK      ${ra.awsAccountId}  ${ra.ownerEmail}  ->  assessment #${ra.riskAssessmentId}" +
                                (ra.assessor?.let { ", assessor $it" } ?: "") +
                                (ra.endDate?.let { ", due $it" } ?: "") +
                                (ra.releaseVersion?.let { ", requirements $it" } ?: "") +
                                (ra.requirementCount?.let { " ($it requirement(s))" } ?: ""))
                        }
                    }
                } else {
                    println("No brand-new AWS accounts in this import — no risk assessments started.")
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

            // Feature 085: send statistics email after import (even with partial errors)
            if (sendEmail) {
                sendStatisticsEmail(backendUrl, token, dryRun, result, "s3://$effectiveBucket/$effectiveKey")
            }

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
                    if (riskAssessmentFailures > 0) {
                        println("Warning: $riskAssessmentFailures risk assessment(s) could not be started")
                        // Exit code 1: partial success (mappings saved, assessments failed)
                        System.exit(1)
                    }
                    // Skips are excluded by construction — onboardingFailures counts only
                    // entries carrying an `error`.
                    if (onboardingFailures > 0) {
                        println("Warning: $onboardingFailures account(s) could not be onboarded")
                        System.exit(1)
                    }
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

    /**
     * Feature 085: POST to /api/cli/user-mappings/send-statistics-email after
     * a successful import. Reuses the same service method as [ListCommand].
     */
    private fun sendStatisticsEmail(backendUrl: String, token: String, dryRun: Boolean, importResult: MappingResult, source: String) {
        val importSummary: Map<String, Any?> = buildMap {
            put("source", source)
            put("totalProcessed", importResult.totalProcessed)
            put("created", importResult.created)
            put("createdPending", importResult.createdPending)
            put("skipped", importResult.skipped)
            put("errorCount", importResult.errors.size)
            importResult.comparison?.let { cmp ->
                put("dbMappingCount", cmp.dbMappingCount)
                put("fileMappingCount", cmp.fileMappingCount)
                put("newCount", cmp.newCount)
                put("unchangedCount", cmp.unchangedCount)
                put("removedCount", cmp.removedCount)
            }
        }
        val result = userMappingCliService.sendStatisticsEmail(
            backendUrl = backendUrl,
            authToken = token,
            filterEmail = null,
            filterStatus = null,
            dryRun = dryRun,
            verbose = verbose,
            importSummary = importSummary
        )

        val separator = "=".repeat(60)
        println()
        println(separator)

        when (result.statusCode) {
            200 -> {
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
}
