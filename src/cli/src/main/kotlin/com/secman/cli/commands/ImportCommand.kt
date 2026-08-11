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
 * - Optional auto-start of risk assessments for owners of brand-new AWS
 *   accounts (--start-risk-assessment --risk-usecase <name>
 *   [--risk-deadline-days <n>, default 7]); the assessor is a user with the
 *   SECCHAMPION role and owners are reminded 2 days and 1 day before the deadline.
 *   Each assessment is pinned to the ACTIVE requirements release, so its
 *   questionnaire is the requirements of that version tagged with the use case
 * - Requires ADMIN role
 */
@Singleton
@Command(
    name = "import",
    description = ["Batch import user mappings from CSV or JSON file"],
    mixinStandardHelpOptions = true,
    // Worked examples in --help, because that is the documentation operators actually read.
    footer = [
        "",
        "Onboarding examples:",
        "  # Welcome mail only, to the owner of every brand-new AWS account",
        "  secman manage-user-mappings import -f m.csv --onboarding-mode WELCOME_ONLY",
        "",
        "  # Welcome mail + an assessment you scope yourself",
        "  secman manage-user-mappings import -f m.csv --onboarding-mode DIRECT \\",
        "      --risk-usecase 'Cloud Onboarding' --risk-deadline-days 14",
        "",
        "  # Welcome mail + let the owner scope it by answering a few questions",
        "  secman manage-user-mappings import -f m.csv --onboarding-mode GUIDED \\",
        "      --questionnaire-expiry-days 21",
        "",
        "  # Preview any of the above without sending or persisting anything",
        "  secman manage-user-mappings import -f m.csv --onboarding-mode GUIDED --dry-run",
        "",
        "Exit codes: 0 success (skips are NOT failures) - 1 import or onboarding failure -",
        "2 invalid options.",
        ""
    ]
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

    @Option(
        names = ["--createnotify"],
        description = ["Send an email to --notify-address when the import introduces brand-new AWS account(s)"]
    )
    var createnotify: Boolean = false

    @Option(
        names = ["--notify-address"],
        description = ["Recipient email for new-account notifications (required when --createnotify is set)"]
    )
    var notifyAddress: String? = null

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

    @Option(
        names = ["--onboarding-mode"],
        description = [
            "What to do for the owner of every brand-new AWS account this import introduces. " +
                "WELCOME_ONLY sends a welcome mail only. DIRECT also starts a risk assessment " +
                "immediately for --risk-usecase (what --start-risk-assessment does). " +
                "GUIDED mails the owner a one-time link; the assessment is created from the " +
                "use cases their answers resolve to. " +
                "Valid values: \${COMPLETION-CANDIDATES}. Default: none, nothing is sent."
        ]
    )
    var onboardingMode: OnboardingMode? = null

    @Option(
        names = ["--welcome-email"],
        negatable = true,
        description = [
            "Force the welcome mail on or off. On by default whenever --onboarding-mode is given; " +
                "off for a bare --start-risk-assessment, which keeps that flag's behaviour unchanged."
        ]
    )
    var welcomeEmail: Boolean? = null

    @Option(
        names = ["--questionnaire-expiry-days"],
        description = [
            "Days the GUIDED questionnaire link stays valid (default: \${DEFAULT-VALUE}, range 1-90)"
        ],
        defaultValue = "14"
    )
    var questionnaireExpiryDays: Int = 14

    @ParentCommand
    lateinit var parent: ManageUserMappingsCommand

    /**
     * The three onboarding modes, as a picocli enum so `--help` lists them and an unknown
     * value is rejected by the parser rather than by the backend after a round trip.
     * Mirrors `com.secman.domain.AccountOnboardingMode`; the CLI is a separate Gradle module
     * with no dependency on backendng, so keep the two in step.
     */
    enum class OnboardingMode { WELCOME_ONLY, DIRECT, GUIDED }

    /**
     * Validate the notification options. Returns an error message if invalid,
     * or null if OK. --createnotify requires a non-blank --notify-address.
     */
    fun validateNotifyOptions(): String? {
        if (createnotify && notifyAddress.isNullOrBlank()) {
            return "--notify-address is required when --createnotify is set"
        }
        return null
    }

    /**
     * Validate the risk-assessment options. Returns an error message if invalid,
     * or null if OK. --start-risk-assessment requires a non-blank --risk-usecase
     * and a deadline between 1 and [UserMappingCliService.MAX_RISK_DEADLINE_DAYS] days.
     *
     * The backend re-checks all of this (`AwsAccountRiskAssessmentService.validateStartRequest`);
     * checking here too saves a round trip and names the flag rather than the JSON field.
     */
    fun validateRiskAssessmentOptions(): String? {
        if (startRiskAssessment && riskUseCase.isNullOrBlank()) {
            return "--risk-usecase is required when --start-risk-assessment is set"
        }
        if (startRiskAssessment && riskDeadlineDays < 1) {
            return "--risk-deadline-days must be at least 1 (got $riskDeadlineDays)"
        }
        if (startRiskAssessment && riskDeadlineDays > UserMappingCliService.MAX_RISK_DEADLINE_DAYS) {
            return "--risk-deadline-days must be at most " +
                "${UserMappingCliService.MAX_RISK_DEADLINE_DAYS} (got $riskDeadlineDays)"
        }
        return null
    }

    /**
     * Validate the onboarding options. Returns an error message if invalid, or null if OK.
     *
     * Mirrors the backend so the message names the *flag* rather than the JSON field — the same
     * reason [validateRiskAssessmentOptions] exists. Everything here is also re-checked server
     * side; nothing depends on this having run.
     *
     * The one combination that is rejected rather than guessed is `--start-risk-assessment`
     * together with a non-DIRECT mode: picking either half would silently do something the
     * operator did not ask for.
     */
    fun validateOnboardingOptions(): String? {
        val mode = onboardingMode
        if (startRiskAssessment && mode != null && mode != OnboardingMode.DIRECT) {
            return "--start-risk-assessment only applies to --onboarding-mode DIRECT (got $mode)"
        }
        if (mode == OnboardingMode.DIRECT && riskUseCase.isNullOrBlank()) {
            return "--risk-usecase is required when --onboarding-mode is DIRECT"
        }
        if (mode != null && mode != OnboardingMode.DIRECT && !riskUseCase.isNullOrBlank()) {
            return "--risk-usecase only applies to --onboarding-mode DIRECT (got $mode)"
        }
        if (questionnaireExpiryDays < MIN_QUESTIONNAIRE_EXPIRY_DAYS ||
            questionnaireExpiryDays > MAX_QUESTIONNAIRE_EXPIRY_DAYS
        ) {
            return "--questionnaire-expiry-days must be between $MIN_QUESTIONNAIRE_EXPIRY_DAYS " +
                "and $MAX_QUESTIONNAIRE_EXPIRY_DAYS (got $questionnaireExpiryDays)"
        }
        if (welcomeEmail != null && mode == null && !startRiskAssessment) {
            return "--welcome-email requires --onboarding-mode"
        }
        if (mode == OnboardingMode.DIRECT && riskDeadlineDays > UserMappingCliService.MAX_RISK_DEADLINE_DAYS) {
            return "--risk-deadline-days must be at most " +
                "${UserMappingCliService.MAX_RISK_DEADLINE_DAYS} (got $riskDeadlineDays)"
        }
        if (mode == OnboardingMode.DIRECT && riskDeadlineDays < 1) {
            return "--risk-deadline-days must be at least 1 (got $riskDeadlineDays)"
        }
        return null
    }

    /** The mode actually in effect, applying the same fallback the backend applies. */
    fun effectiveMode(): OnboardingMode? =
        onboardingMode ?: if (startRiskAssessment) OnboardingMode.DIRECT else null

    /** Marker for a dry-run line: what the pair *would* get. */
    private fun marker(ob: com.secman.cli.service.CliAccountOnboarding): String = when (ob.mode) {
        "GUIDED" -> "🔗"
        "DIRECT" -> "✅"
        else -> "✉️ "
    }

    private fun wouldDo(ob: com.secman.cli.service.CliAccountOnboarding): String = when (ob.mode) {
        "GUIDED" -> "would mail a questionnaire link (valid $questionnaireExpiryDays days" +
            (ob.questionnaireExpiresAt?.let { ", until $it" } ?: "") + ")"
        "DIRECT" -> "would start an assessment for '$riskUseCase' (due in $riskDeadlineDays day(s))"
        else -> "would send a welcome mail"
    }

    companion object {
        const val MIN_QUESTIONNAIRE_EXPIRY_DAYS = 1
        const val MAX_QUESTIONNAIRE_EXPIRY_DAYS = 90
    }

    override fun run() {
        try {
            println("=" .repeat(60))
            println("Import User Mappings")
            println("=" .repeat(60))
            println()

            validateNotifyOptions()?.let { msg ->
                System.err.println("❌ Error: $msg")
                System.exit(2)
                return
            }
            if (!createnotify && !notifyAddress.isNullOrBlank()) {
                println("⚠️  --notify-address is ignored because --createnotify is not set")
            }
            validateRiskAssessmentOptions()?.let { msg ->
                System.err.println("❌ Error: $msg")
                System.exit(2)
                return
            }
            if (!startRiskAssessment && onboardingMode == null && !riskUseCase.isNullOrBlank()) {
                println("⚠️  --risk-usecase is ignored because neither --start-risk-assessment nor --onboarding-mode is set")
            }
            validateOnboardingOptions()?.let { msg ->
                System.err.println("❌ Error: $msg")
                System.exit(2)
                return
            }
            // A warning, not an error: the flag is harmless in the other modes, and refusing the
            // whole import over an unused value would be disproportionate.
            if (effectiveMode() != OnboardingMode.GUIDED && questionnaireExpiryDays != 14) {
                println("⚠️  --questionnaire-expiry-days is ignored because --onboarding-mode is not GUIDED")
            }

            // Authenticate with backend
            val backendUrl = parent.getEffectiveBackendUrl()
            val username = parent.getEffectiveUsername()
            val password = parent.getEffectivePassword()
            userMappingCliService.initHttpClient(backendUrl, parent.isEffectiveInsecure())
            val token = userMappingCliService.authenticate(username, password, backendUrl)
                ?: throw IllegalArgumentException("Authentication failed - check username/password")

            println("Backend: $backendUrl")
            println("File: $filePath")
            println("Format: $format")
            when (effectiveMode()) {
                OnboardingMode.WELCOME_ONLY ->
                    println("Onboarding: WELCOME_ONLY (welcome mail to each new account owner)")
                OnboardingMode.DIRECT -> {
                    val label = if (onboardingMode == null) "DIRECT (via --start-risk-assessment)" else "DIRECT"
                    println("Onboarding: $label")
                    println("  Use case:  $riskUseCase")
                    println("  Deadline:  $riskDeadlineDays day(s)")
                    println("  Welcome:   ${if (welcomeEmail ?: (onboardingMode != null)) "yes" else "no"}")
                }
                OnboardingMode.GUIDED -> {
                    println("Onboarding: GUIDED (welcome mail + guided assessment)")
                    println("  Link expiry: $questionnaireExpiryDays day(s)")
                    println("  Deadline:    $riskDeadlineDays day(s) after the owner submits")
                    println("  Welcome:     ${if (welcomeEmail ?: true) "yes" else "no"}")
                }
                null -> {}
            }
            if (dryRun) {
                println("Mode: DRY-RUN (validation only, no changes will be made)")
            }
            println()

            // Execute import via HTTP
            val result = userMappingCliService.importMappingsFromFile(
                filePath = filePath,
                format = format,
                dryRun = dryRun,
                backendUrl = backendUrl,
                authToken = token,
                notifyNewAccounts = createnotify,
                notifyAddress = notifyAddress,
                startRiskAssessment = startRiskAssessment,
                riskUseCase = riskUseCase,
                riskDeadlineDays = if (effectiveMode() == OnboardingMode.DIRECT) riskDeadlineDays else null,
                onboardingMode = onboardingMode?.name,
                sendWelcomeEmail = welcomeEmail,
                questionnaireExpiryDays = if (effectiveMode() == OnboardingMode.GUIDED) questionnaireExpiryDays else null
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
                val comparison = result.comparison
                if (comparison != null && comparison.dbAvailable) {
                    println("Comparison:")
                    println("  Backend:   ${comparison.dbMappingCount} existing mapping(s)")
                    println("  File:      ${comparison.fileMappingCount} mapping(s) from file")
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

            if (createnotify && result.newAccounts.isNotEmpty()) {
                println()
                if (dryRun) {
                    println("Would notify ${notifyAddress} about ${result.newAccounts.size} new AWS account(s):")
                } else {
                    println("New AWS account(s) detected (${result.newAccounts.size}):")
                }
                result.newAccounts.forEach { acct ->
                    println("  - ${acct.awsAccountId}  ->  ${acct.emails.joinToString(", ")}")
                }
                if (!dryRun) {
                    when {
                        result.notificationError != null ->
                            println("❌ Notification email to ${result.notificationRecipient ?: notifyAddress} failed: ${result.notificationError}")
                        result.notificationSent ->
                            println("✅ Notification email sent to ${result.notificationRecipient ?: notifyAddress}")
                    }
                }
            } else if (createnotify && !dryRun) {
                println()
                println("No brand-new AWS accounts in this import — no notification sent.")
            }

            // Onboarding block. Printed for every mode including DIRECT, where it carries the
            // welcome-mail outcome that the risk-assessment block below does not cover.
            var onboardingFailures = 0
            if (effectiveMode() != null) {
                println()
                if (result.onboarding.isEmpty()) {
                    println("No brand-new AWS accounts in this import — nothing to onboard.")
                } else {
                    if (dryRun) {
                        println("DRY-RUN — nothing persisted, nothing sent, no invite token minted.")
                        println("Would onboard ${result.onboarding.size} account/owner pair(s) in ${effectiveMode()} mode:")
                    } else {
                        println("Onboarding (${result.onboarding.size}):")
                    }
                    result.onboarding.forEach { ob ->
                        val where = "${ob.awsAccountId}  ${ob.ownerEmail}"
                        when {
                            ob.error != null -> {
                                onboardingFailures++
                                println("  ❌ $where  ->  ${ob.error}")
                            }
                            // A skip is an idempotent no-op, deliberately NOT a failure: the pair
                            // already has a live invite or assessment, which is the intended
                            // outcome of re-running an import.
                            ob.skipped ->
                                println("  ⏭️  $where  ->  skipped — ${ob.skipReason ?: "already onboarded"}")
                            ob.dryRun -> println("  ${marker(ob)} $where  ->  ${wouldDo(ob)}")
                            ob.questionnaireInviteId != null ->
                                println(
                                    "  🔗 $where  ->  questionnaire invite #${ob.questionnaireInviteId}" +
                                        (ob.questionnaireExpiresAt?.let { ", expires $it" } ?: "")
                                )
                            ob.riskAssessmentId != null ->
                                println("  ✅ $where  ->  assessment #${ob.riskAssessmentId}" +
                                    (if (ob.welcomeEmailSent) ", welcome mail sent" else ""))
                            ob.welcomeEmailSent -> println("  ✉️  $where  ->  welcome mail sent")
                            else -> println("  ⚠️  $where  ->  nothing sent (check the email configuration)")
                        }
                    }
                }
            }

            var riskAssessmentFailures = 0
            if (startRiskAssessment || onboardingMode == OnboardingMode.DIRECT) {
                println()
                if (dryRun) {
                    if (result.newAccounts.isNotEmpty()) {
                        println("Would start ${result.newAccounts.sumOf { it.emails.size }} risk assessment(s) " +
                            "for the ${result.newAccounts.size} new AWS account(s) above " +
                            "(use case '$riskUseCase', deadline $riskDeadlineDays day(s)).")
                    } else {
                        println("No brand-new AWS accounts in this import — no risk assessments would be started.")
                    }
                } else if (result.riskAssessments.isNotEmpty()) {
                    println("Risk assessments (${result.riskAssessments.size}):")
                    result.riskAssessments.forEach { ra ->
                        if (ra.error != null) {
                            riskAssessmentFailures++
                            println("  ❌ ${ra.awsAccountId}  ${ra.ownerEmail}: ${ra.error}")
                        } else if (ra.skipped) {
                            // Idempotent no-op, deliberately NOT counted as a failure: re-running
                            // an import that finds the pair already covered is the intended
                            // outcome, not something the operator has to act on.
                            println("  ⏭️  ${ra.awsAccountId}  ${ra.ownerEmail}: skipped — " +
                                (ra.skipReason ?: "an assessment already exists"))
                        } else {
                            println("  ✅ ${ra.awsAccountId}  ${ra.ownerEmail}  ->  assessment #${ra.riskAssessmentId}" +
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
                    if (createnotify && result.notificationError != null) {
                        System.exit(1)
                    }
                    if (riskAssessmentFailures > 0) {
                        println("⚠️  $riskAssessmentFailures risk assessment(s) could not be started")
                        System.exit(1)
                    }
                    // Skips are excluded by construction — onboardingFailures only counts
                    // entries carrying an `error`, never a `skipped`.
                    if (onboardingFailures > 0) {
                        println("⚠️  $onboardingFailures account(s) could not be onboarded")
                        System.exit(1)
                    }
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
