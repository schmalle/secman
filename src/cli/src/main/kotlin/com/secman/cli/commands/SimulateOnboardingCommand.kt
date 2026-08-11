package com.secman.cli.commands

import com.secman.cli.service.AccountOnboardingCliService
import jakarta.inject.Singleton
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand

/**
 * Run the whole account-onboarding path against an AWS account id and email address you make up.
 *
 * Why this exists: onboarding is otherwise only reachable when a mapping import happens to
 * introduce an account SecMan has never seen, which is not something you can arrange on demand.
 * This subcommand takes that account and owner as arguments and runs *exactly* the same backend
 * code path — no test double, no shortcut — so what it exercises is production behaviour.
 *
 * It really sends mail. `--dry-run` reports what would happen and mints nothing.
 *
 * Usage:
 *   secman manage-user-mappings simulate-onboarding \
 *       --aws-account-id 999999999999 --owner-email you@example.com --mode GUIDED
 *
 * Requires ADMIN or SECCHAMPION.
 */
@Singleton
@Command(
    name = "simulate-onboarding",
    description = ["Run account onboarding against a test AWS account id and email address"],
    mixinStandardHelpOptions = true,
    footer = [
        "",
        "Examples:",
        "  # Preview the guided flow without sending anything",
        "  secman manage-user-mappings simulate-onboarding \\",
        "      --aws-account-id 999999999999 --owner-email you@example.com \\",
        "      --mode GUIDED --dry-run",
        "",
        "  # Actually mail yourself a questionnaire link and click it",
        "  secman manage-user-mappings simulate-onboarding \\",
        "      --aws-account-id 999999999999 --owner-email you@example.com --mode GUIDED",
        "",
        "  # Welcome mail plus an assessment you scope yourself",
        "  secman manage-user-mappings simulate-onboarding \\",
        "      --aws-account-id 999999999999 --owner-email you@example.com \\",
        "      --mode DIRECT --risk-usecase 'Cloud Onboarding'",
        "",
        "Exit codes: 0 success (a skip is NOT a failure) - 1 onboarding failed - 2 invalid options.",
        ""
    ]
)
class SimulateOnboardingCommand(
    private val onboardingCliService: AccountOnboardingCliService
) : Runnable {

    @Option(
        names = ["--aws-account-id"],
        description = ["The AWS account id to onboard, exactly 12 digits. May be fictitious."],
        required = true
    )
    lateinit var awsAccountId: String

    @Option(
        names = ["--owner-email"],
        description = ["Where the mail goes. Without --dry-run this address really receives it."],
        required = true
    )
    lateinit var ownerEmail: String

    @Option(
        names = ["--mode"],
        description = [
            "WELCOME_ONLY sends a welcome mail only. DIRECT also starts a risk assessment for " +
                "--risk-usecase. GUIDED mails a one-time questionnaire link and creates the " +
                "assessment from the answers. Valid values: \${COMPLETION-CANDIDATES}."
        ],
        required = true
    )
    lateinit var mode: ImportCommand.OnboardingMode

    @Option(
        names = ["--risk-usecase"],
        description = ["Use case the assessment is scoped to (required for --mode DIRECT)"]
    )
    var riskUseCase: String? = null

    @Option(
        names = ["--risk-deadline-days"],
        description = ["Days until the assessment deadline (default: \${DEFAULT-VALUE}, maximum: 3650)"],
        defaultValue = "7"
    )
    var riskDeadlineDays: Int = 7

    @Option(
        names = ["--questionnaire-expiry-days"],
        description = ["Days the GUIDED link stays valid (default: \${DEFAULT-VALUE}, range 1-90)"],
        defaultValue = "14"
    )
    var questionnaireExpiryDays: Int = 14

    @Option(
        names = ["--welcome-email"],
        negatable = true,
        description = ["Force the welcome mail on or off (default: on)"]
    )
    var welcomeEmail: Boolean? = null

    @Option(
        names = ["--dry-run"],
        description = ["Report what would happen without persisting, sending, or minting a token"]
    )
    var dryRun: Boolean = false

    @ParentCommand
    lateinit var parent: ManageUserMappingsCommand

    /**
     * Local pre-flight, mirroring the backend so the message names the flag rather than the
     * JSON field. Everything here is re-checked server side.
     */
    fun validateOptions(): String? {
        if (!ACCOUNT_ID_PATTERN.matches(awsAccountId.trim())) {
            return "--aws-account-id must be exactly 12 digits (got '${awsAccountId.trim()}')"
        }
        if (!EMAIL_PATTERN.matches(ownerEmail.trim())) {
            return "--owner-email must be a valid single email address"
        }
        if (mode == ImportCommand.OnboardingMode.DIRECT && riskUseCase.isNullOrBlank()) {
            return "--risk-usecase is required when --mode is DIRECT"
        }
        if (mode != ImportCommand.OnboardingMode.DIRECT && !riskUseCase.isNullOrBlank()) {
            return "--risk-usecase only applies to --mode DIRECT (got $mode)"
        }
        if (riskDeadlineDays < 1 || riskDeadlineDays > MAX_RISK_DEADLINE_DAYS) {
            return "--risk-deadline-days must be between 1 and $MAX_RISK_DEADLINE_DAYS (got $riskDeadlineDays)"
        }
        if (questionnaireExpiryDays < ImportCommand.MIN_QUESTIONNAIRE_EXPIRY_DAYS ||
            questionnaireExpiryDays > ImportCommand.MAX_QUESTIONNAIRE_EXPIRY_DAYS
        ) {
            return "--questionnaire-expiry-days must be between " +
                "${ImportCommand.MIN_QUESTIONNAIRE_EXPIRY_DAYS} and " +
                "${ImportCommand.MAX_QUESTIONNAIRE_EXPIRY_DAYS} (got $questionnaireExpiryDays)"
        }
        return null
    }

    override fun run() {
        try {
            println("=".repeat(60))
            println("Simulate Account Onboarding")
            println("=".repeat(60))
            println()

            validateOptions()?.let { msg ->
                System.err.println("❌ Error: $msg")
                System.exit(2)
                return
            }

            // Said before anything happens, not after. A live run mails a real person, and the
            // whole point of the command is that it is indistinguishable from the real thing.
            if (dryRun) {
                println("DRY-RUN — nothing persisted, nothing sent, no invite token minted.")
            } else {
                println("⚠️  SIMULATION — this creates real rows and sends real mail to ${ownerEmail.trim()}.")
                println("    Add --dry-run to preview without sending.")
            }
            println()

            val backendUrl = parent.getEffectiveBackendUrl()
            onboardingCliService.initHttpClient(backendUrl, parent.isEffectiveInsecure())
            val token = onboardingCliService.authenticate(
                parent.getEffectiveUsername(), parent.getEffectivePassword(), backendUrl
            ) ?: throw IllegalArgumentException("Authentication failed - check username/password")

            println("Backend: $backendUrl")
            println("Account: ${awsAccountId.trim()}")
            println("Owner:   ${ownerEmail.trim()}")
            println("Mode:    $mode")
            println()

            val result = onboardingCliService.simulate(
                backendUrl = backendUrl,
                authToken = token,
                awsAccountId = awsAccountId.trim(),
                ownerEmail = ownerEmail.trim(),
                mode = mode.name,
                riskUseCase = riskUseCase,
                riskDeadlineDays = if (mode == ImportCommand.OnboardingMode.DIRECT) riskDeadlineDays else null,
                questionnaireExpiryDays = if (mode == ImportCommand.OnboardingMode.GUIDED) {
                    questionnaireExpiryDays
                } else null,
                sendWelcomeEmail = welcomeEmail,
                dryRun = dryRun
            )

            println("=".repeat(60))
            println("Result")
            println("=".repeat(60))

            var failures = 0
            result.onboarding.forEach { ob ->
                val where = "${ob.awsAccountId}  ${ob.ownerEmail}"
                when {
                    ob.error != null -> {
                        failures++
                        println("  ❌ $where  ->  ${ob.error}")
                    }
                    ob.skipped ->
                        println("  ⏭️  $where  ->  skipped — ${ob.skipReason ?: "already onboarded"}")
                    ob.dryRun ->
                        println("  ▶️  $where  ->  would run ${ob.mode} onboarding" +
                            (ob.questionnaireExpiresAt?.let { ", link valid until $it" } ?: ""))
                    ob.questionnaireInviteId != null ->
                        println("  🔗 $where  ->  questionnaire invite #${ob.questionnaireInviteId}" +
                            (ob.questionnaireExpiresAt?.let { ", expires $it" } ?: "") +
                            "\n      Check the mailbox — the link is in the mail, never printed here.")
                    ob.riskAssessmentId != null ->
                        println("  ✅ $where  ->  assessment #${ob.riskAssessmentId}" +
                            (if (ob.welcomeEmailSent) ", welcome mail sent" else ""))
                    ob.welcomeEmailSent -> println("  ✉️  $where  ->  welcome mail sent")
                    else -> println("  ⚠️  $where  ->  nothing sent (check the email configuration)")
                }
            }

            result.riskAssessments.filter { it.error == null && !it.skipped }.forEach { ra ->
                println(
                    "     assessment #${ra.riskAssessmentId}" +
                        (ra.assessor?.let { ", assessor $it" } ?: "") +
                        (ra.endDate?.let { ", due $it" } ?: "") +
                        (ra.useCase?.let { ", use case(s) $it" } ?: "") +
                        (ra.releaseVersion?.let { ", requirements $it" } ?: "") +
                        (ra.requirementCount?.let { " ($it requirement(s))" } ?: "")
                )
            }

            val matrix = result.ruleMatrix
            if (matrix != null) {
                println()
                println("Rule matrix that would apply:")
                if (matrix.rules.isEmpty()) {
                    println("  (none configured — an owner answering the questionnaire could not be scoped)")
                }
                matrix.rules.forEachIndexed { index, rule ->
                    val prefix = if (rule.isDefault) "[*]" else "[${index + 1}]"
                    val condition = if (rule.combination.isEmpty()) {
                        "default fallback (no rule matched)"
                    } else {
                        rule.combination.joinToString(" AND ")
                    }
                    println("  $prefix \"${rule.name}\"  $condition  ->  ${rule.useCases.joinToString(", ")}")
                }
                if (!matrix.hasDefaultRule) {
                    println("  ⚠️  No fallback rule: an owner whose answers match nothing will be told")
                    println("      a security champion will follow up.")
                }
                println(
                    "Reachable use cases: ${matrix.reachableUseCases.size}  ->  " +
                        "${matrix.reachableRequirementCount} requirement(s)" +
                        (matrix.releaseVersion?.let { " in ACTIVE release $it" } ?: " (no ACTIVE release)")
                )
            }

            println()
            if (failures > 0) {
                println("✗ Onboarding completed with $failures failure(s)")
                System.exit(1)
            } else {
                println(if (dryRun) "✓ Dry run successful" else "✓ Onboarding successful")
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

    companion object {
        private val ACCOUNT_ID_PATTERN = Regex("^\\d{12}$")

        /**
         * Mirrors the backend's `EmailAddressValidator`: an address that becomes an SMTP
         * recipient must not be able to carry a comma, a colon or a line break. Duplicated
         * because the CLI is a separate Gradle module with no dependency on backendng — keep
         * the two in step.
         */
        private val EMAIL_PATTERN = Regex("^[^\\s@,;:<>\"\\\\]+@[^\\s@,;:<>\"\\\\]+\\.[^\\s@,;:<>\"\\\\]+$")

        const val MAX_RISK_DEADLINE_DAYS = 3650
    }
}
