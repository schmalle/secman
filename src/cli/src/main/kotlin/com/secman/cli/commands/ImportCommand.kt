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
    mixinStandardHelpOptions = true
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

    @ParentCommand
    lateinit var parent: ManageUserMappingsCommand

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
            if (!startRiskAssessment && !riskUseCase.isNullOrBlank()) {
                println("⚠️  --risk-usecase is ignored because --start-risk-assessment is not set")
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
            if (startRiskAssessment) {
                println("Risk assessment: enabled (use case '$riskUseCase', deadline $riskDeadlineDays day(s))")
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
                riskDeadlineDays = if (startRiskAssessment) riskDeadlineDays else null
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

            var riskAssessmentFailures = 0
            if (startRiskAssessment) {
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
