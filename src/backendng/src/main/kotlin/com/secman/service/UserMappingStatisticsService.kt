package com.secman.service

import com.secman.domain.ExecutionStatus
import com.secman.domain.MappingStatus
import com.secman.domain.UserMapping
import com.secman.domain.UserMappingStatisticsLog
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserMappingStatisticsLogRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Service for sending user-mapping statistics email reports to ADMIN and REPORT users.
 * Feature: 085-cli-mappings-email
 *
 * Mirrors the AdminSummaryService pattern from feature 070 but targets user mappings.
 * Reuses AdminSummaryService.getAdminRecipients() for the ADMIN+REPORT recipient set
 * to avoid duplicating role-filtering logic.
 */
@Singleton
class UserMappingStatisticsService(
    private val userMappingRepository: UserMappingRepository,
    private val statisticsLogRepository: UserMappingStatisticsLogRepository,
    private val adminSummaryService: AdminSummaryService,
    private val emailService: EmailService
) {
    private val logger = LoggerFactory.getLogger(UserMappingStatisticsService::class.java)

    // --- Service-layer data classes ---

    data class UserMappingStatisticsReport(
        val generatedAt: Instant,
        val appliedFilters: Map<String, String>,
        val aggregates: Aggregates,
        val users: List<UserMappingEntry>
    )

    data class Aggregates(
        val totalUsers: Int,
        val totalMappings: Int,
        val activeMappings: Int,
        val pendingMappings: Int,
        val domainMappings: Int,
        val awsAccountMappings: Int
    )

    data class UserMappingEntry(
        val email: String,
        val overallStatus: String,          // "ACTIVE", "PENDING", or "MIXED"
        val domains: List<DomainEntry>,
        val awsAccounts: List<AwsAccountEntry>
    )

    data class DomainEntry(val domain: String, val status: String)
    data class AwsAccountEntry(val awsAccountId: String, val status: String)

    data class ImportSummary(
        val source: String? = null,
        val totalProcessed: Int? = null,
        val created: Int? = null,
        val createdPending: Int? = null,
        val skipped: Int? = null,
        val errorCount: Int? = null,
        val dbMappingCount: Int? = null,
        val fileMappingCount: Int? = null,
        val newCount: Int? = null,
        val unchangedCount: Int? = null,
        val removedCount: Int? = null
    )

    data class UserMappingStatisticsSendResult(
        val recipientCount: Int,
        val emailsSent: Int,
        val emailsFailed: Int,
        val status: ExecutionStatus,
        val recipients: List<String>,
        val failedRecipients: List<String>,
        val appliedFilters: Map<String, String>,
        val aggregates: Aggregates
    )

    // --- Public API ---

    /**
     * Compute the user-mapping statistics report for the given filters.
     * Uses the same filter precedence as [com.secman.controller.UserMappingController.listMappings]:
     * email > status > all. Per-user grouping mirrors the CLI [ListCommand] TABLE view.
     */
    fun computeReport(filterEmail: String?, filterStatus: String?): UserMappingStatisticsReport {
        val parsedStatus: MappingStatus? = parseStatus(filterStatus)

        // Fetch base set based on filter precedence (email > status > all)
        var mappings: List<UserMapping> = when {
            filterEmail != null -> userMappingRepository.findByEmail(filterEmail)
            parsedStatus != null -> userMappingRepository.findByStatus(parsedStatus)
            else -> userMappingRepository.findAll().toList()
        }

        // Apply status as a secondary filter when email was the primary filter
        if (filterEmail != null && parsedStatus != null) {
            mappings = mappings.filter { it.status == parsedStatus }
        }

        val grouped = mappings.groupBy { it.email }
        val users = grouped.map { (email, userMappings) ->
            val domains = userMappings.filter { it.domain != null }.map {
                DomainEntry(domain = it.domain!!, status = it.status.name)
            }
            val awsAccounts = userMappings.filter { it.awsAccountId != null }.map {
                AwsAccountEntry(awsAccountId = it.awsAccountId!!, status = it.status.name)
            }
            val statuses = userMappings.map { it.status }.toSet()
            val overallStatus = when {
                statuses.size == 1 && statuses.first() == MappingStatus.ACTIVE -> "ACTIVE"
                statuses.size == 1 && statuses.first() == MappingStatus.PENDING -> "PENDING"
                else -> "MIXED"
            }
            UserMappingEntry(email, overallStatus, domains, awsAccounts)
        }.sortedBy { it.email }

        val aggregates = Aggregates(
            totalUsers = grouped.size,
            totalMappings = mappings.size,
            activeMappings = mappings.count { it.status == MappingStatus.ACTIVE },
            pendingMappings = mappings.count { it.status == MappingStatus.PENDING },
            domainMappings = mappings.count { it.domain != null },
            awsAccountMappings = mappings.count { it.awsAccountId != null }
        )

        val applied = buildMap {
            if (!filterEmail.isNullOrBlank()) put("email", filterEmail)
            if (!filterStatus.isNullOrBlank()) put("status", filterStatus.uppercase())
        }

        return UserMappingStatisticsReport(
            generatedAt = Instant.now(),
            appliedFilters = applied,
            aggregates = aggregates,
            users = users
        )
    }

    /**
     * Compute the report, render email templates, dispatch to every ADMIN/REPORT recipient
     * with a valid email address, and write one audit log row. Follows the structure of
     * [AdminSummaryService.sendSummaryEmail] exactly.
     *
     * @param filterEmail Optional user-email filter (passed through to the report query)
     * @param filterStatus Optional status filter ("ACTIVE" or "PENDING"; case-insensitive)
     * @param dryRun If true, skip dispatch but still compute the report and log the execution
     * @param verbose If true, log per-recipient delivery status at INFO level
     * @param invokedBy Username of the CLI caller (used in the audit log)
     */
    fun sendStatisticsEmail(
        filterEmail: String?,
        filterStatus: String?,
        dryRun: Boolean,
        verbose: Boolean,
        invokedBy: String,
        importSummary: ImportSummary? = null
    ): UserMappingStatisticsSendResult {
        val report = computeReport(filterEmail, filterStatus)
        val recipients = adminSummaryService.getAdminRecipients()

        // Zero-recipient case
        if (recipients.isEmpty()) {
            logger.warn("No ADMIN/REPORT users with valid email found for user-mapping statistics report")
            val result = UserMappingStatisticsSendResult(
                recipientCount = 0,
                emailsSent = 0,
                emailsFailed = 0,
                status = ExecutionStatus.FAILURE,
                recipients = emptyList(),
                failedRecipients = emptyList(),
                appliedFilters = report.appliedFilters,
                aggregates = report.aggregates
            )
            logExecution(report, result, invokedBy, dryRun)
            return result
        }

        // Dry-run case
        if (dryRun) {
            logger.info("Dry run mode - would send user-mapping statistics to {} recipients", recipients.size)
            val result = UserMappingStatisticsSendResult(
                recipientCount = recipients.size,
                emailsSent = 0,
                emailsFailed = 0,
                status = ExecutionStatus.DRY_RUN,
                recipients = recipients.map { it.email },
                failedRecipients = emptyList(),
                appliedFilters = report.appliedFilters,
                aggregates = report.aggregates
            )
            logExecution(report, result, invokedBy, dryRun)
            return result
        }

        // Render templates
        val executionDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(report.generatedAt)

        val textContent = renderTextTemplate(report, executionDate, importSummary)
        val htmlContent = renderHtmlTemplate(report, executionDate, importSummary)
        val inlineImages = loadLogoInlineImage()

        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()

        recipients.forEach { user ->
            try {
                if (verbose) {
                    logger.info("Sending user-mapping statistics email to {}", user.email)
                }
                val success = emailService.sendEmailWithInlineImages(
                    to = user.email,
                    subject = "SecMan User Mapping Statistics Report",
                    textContent = textContent,
                    htmlContent = htmlContent,
                    inlineImages = inlineImages
                ).get()
                if (success) {
                    sent.add(user.email)
                    if (verbose) logger.info("Successfully sent to {}", user.email)
                } else {
                    failed.add(user.email)
                    logger.warn("Failed to send email to {}", user.email)
                }
            } catch (e: Exception) {
                failed.add(user.email)
                logger.error("Error sending email to {}: {}", user.email, e.message)
            }
        }

        val status = when {
            failed.isEmpty() -> ExecutionStatus.SUCCESS
            sent.isEmpty() -> ExecutionStatus.FAILURE
            else -> ExecutionStatus.PARTIAL_FAILURE
        }

        val result = UserMappingStatisticsSendResult(
            recipientCount = recipients.size,
            emailsSent = sent.size,
            emailsFailed = failed.size,
            status = status,
            recipients = sent,
            failedRecipients = failed,
            appliedFilters = report.appliedFilters,
            aggregates = report.aggregates
        )

        logExecution(report, result, invokedBy, dryRun)
        return result
    }

    // --- Private helpers ---

    private fun parseStatus(raw: String?): MappingStatus? = when (raw?.uppercase()) {
        "ACTIVE" -> MappingStatus.ACTIVE
        "PENDING" -> MappingStatus.PENDING
        null, "", "ALL" -> null
        else -> throw IllegalArgumentException("Invalid status filter: $raw (use ACTIVE or PENDING)")
    }

    private fun logExecution(
        report: UserMappingStatisticsReport,
        result: UserMappingStatisticsSendResult,
        invokedBy: String,
        dryRun: Boolean
    ) {
        try {
            val log = UserMappingStatisticsLog(
                executedAt = Instant.now(),
                invokedBy = invokedBy,
                filterEmail = report.appliedFilters["email"],
                filterStatus = report.appliedFilters["status"],
                totalUsers = report.aggregates.totalUsers,
                totalMappings = report.aggregates.totalMappings,
                activeMappings = report.aggregates.activeMappings,
                pendingMappings = report.aggregates.pendingMappings,
                domainMappings = report.aggregates.domainMappings,
                awsAccountMappings = report.aggregates.awsAccountMappings,
                recipientCount = result.recipientCount,
                emailsSent = result.emailsSent,
                emailsFailed = result.emailsFailed,
                status = result.status,
                dryRun = dryRun
            )
            statisticsLogRepository.save(log)
            logger.debug("Logged user-mapping statistics execution: status={}", result.status)
        } catch (e: Exception) {
            logger.error("Failed to log user-mapping statistics execution: {}", e.message)
        }
    }

    private fun loadLogoInlineImage(): Map<String, Pair<ByteArray, String>> {
        return try {
            val logoBytes = javaClass.getResourceAsStream("/email-templates/SecManLogo.png")?.readAllBytes()
            if (logoBytes != null) {
                mapOf("secman-logo" to (logoBytes to "image/png"))
            } else {
                logger.warn("SecManLogo.png not found in classpath, email will be sent without logo")
                emptyMap()
            }
        } catch (e: Exception) {
            logger.warn("Failed to load SecManLogo.png: {}", e.message)
            emptyMap()
        }
    }

    private fun renderTextTemplate(report: UserMappingStatisticsReport, executionDate: String, importSummary: ImportSummary?): String {
        return try {
            val tmpl = javaClass.getResourceAsStream("/email-templates/user-mapping-statistics.txt")
                ?: throw IllegalStateException("Text template not found")
            tmpl.bufferedReader().use { reader ->
                reader.readText()
                    .replace("\${executionDate}", executionDate)
                    .replace("\${appliedFiltersText}", renderAppliedFiltersText(report.appliedFilters))
                    .replace("\${totalUsers}", report.aggregates.totalUsers.toString())
                    .replace("\${totalMappings}", report.aggregates.totalMappings.toString())
                    .replace("\${activeMappings}", report.aggregates.activeMappings.toString())
                    .replace("\${pendingMappings}", report.aggregates.pendingMappings.toString())
                    .replace("\${domainMappings}", report.aggregates.domainMappings.toString())
                    .replace("\${awsAccountMappings}", report.aggregates.awsAccountMappings.toString())
                    .replace("\${importSummaryText}", renderImportSummaryText(importSummary))
            }
        } catch (e: Exception) {
            logger.error("Failed to render text template: {}", e.message)
            """
            SecMan User Mapping Statistics Report
            Generated on: $executionDate

            Users: ${report.aggregates.totalUsers}
            Mappings: ${report.aggregates.totalMappings}
            Active: ${report.aggregates.activeMappings}
            Pending: ${report.aggregates.pendingMappings}
            """.trimIndent()
        }
    }

    private fun renderHtmlTemplate(report: UserMappingStatisticsReport, executionDate: String, importSummary: ImportSummary?): String {
        return try {
            val tmpl = javaClass.getResourceAsStream("/email-templates/user-mapping-statistics.html")
                ?: throw IllegalStateException("HTML template not found")
            tmpl.bufferedReader().use { reader ->
                reader.readText()
                    .replace("\${executionDate}", executionDate)
                    .replace("\${appliedFiltersHtml}", renderAppliedFiltersHtml(report.appliedFilters))
                    .replace("\${totalUsers}", report.aggregates.totalUsers.toString())
                    .replace("\${totalMappings}", report.aggregates.totalMappings.toString())
                    .replace("\${activeMappings}", report.aggregates.activeMappings.toString())
                    .replace("\${pendingMappings}", report.aggregates.pendingMappings.toString())
                    .replace("\${domainMappings}", report.aggregates.domainMappings.toString())
                    .replace("\${awsAccountMappings}", report.aggregates.awsAccountMappings.toString())
                    .replace("\${importSummaryHtml}", renderImportSummaryHtml(importSummary))
            }
        } catch (e: Exception) {
            logger.error("Failed to render HTML template: {}", e.message)
            """
            <html><body>
            <h1>SecMan User Mapping Statistics Report</h1>
            <p>Generated on: $executionDate</p>
            <ul>
              <li>Users: ${report.aggregates.totalUsers}</li>
              <li>Mappings: ${report.aggregates.totalMappings}</li>
              <li>Active: ${report.aggregates.activeMappings}</li>
              <li>Pending: ${report.aggregates.pendingMappings}</li>
            </ul>
            </body></html>
            """.trimIndent()
        }
    }

    private fun renderAppliedFiltersText(filters: Map<String, String>): String {
        if (filters.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("APPLIED FILTERS\n")
        sb.append("---------------\n")
        filters.forEach { (k, v) -> sb.append("  $k: $v\n") }
        sb.append('\n')
        return sb.toString()
    }

    private fun renderAppliedFiltersHtml(filters: Map<String, String>): String {
        if (filters.isEmpty()) return ""
        val rows = filters.entries.joinToString("") { (k, v) ->
            "<div><strong>${escapeHtml(k)}:</strong> ${escapeHtml(v)}</div>"
        }
        return """<div class="filters"><strong>Applied filters</strong>$rows</div>"""
    }

    private fun renderImportSummaryText(summary: ImportSummary?): String {
        if (summary == null) return ""
        val sb = StringBuilder()
        sb.append("IMPORT SUMMARY\n")
        sb.append("--------------\n")
        if (summary.source != null) {
            sb.append("Source:        ${summary.source}\n")
        }
        if (summary.totalProcessed != null) {
            sb.append("Processed:     ${summary.totalProcessed} mapping(s)\n")
        }
        if (summary.created != null) {
            sb.append("Created:       ${summary.created} active mapping(s)\n")
        }
        if (summary.createdPending != null) {
            sb.append("Created:       ${summary.createdPending} pending mapping(s)\n")
        }
        if (summary.skipped != null) {
            sb.append("Skipped:       ${summary.skipped} duplicate(s)\n")
        }
        if (summary.errorCount != null && summary.errorCount > 0) {
            sb.append("Errors:        ${summary.errorCount} failure(s)\n")
        }
        // Comparison data (dry-run or post-import)
        if (summary.dbMappingCount != null) {
            sb.append("\nComparison:\n")
            sb.append("  Backend:     ${summary.dbMappingCount} existing mapping(s)\n")
            sb.append("  File:        ${summary.fileMappingCount ?: 0} mapping(s) from file\n")
            sb.append("  New:         ${summary.newCount ?: 0} mapping(s) (in file, not in DB)\n")
            sb.append("  Unchanged:   ${summary.unchangedCount ?: 0} mapping(s) (in both)\n")
            sb.append("  Removed:     ${summary.removedCount ?: 0} mapping(s) (in DB, not in file)\n")
        }
        return sb.toString()
    }

    private fun renderImportSummaryHtml(summary: ImportSummary?): String {
        if (summary == null) return ""
        val rows = mutableListOf<Pair<String, String>>()
        if (summary.source != null) {
            rows.add("Source" to summary.source)
        }
        if (summary.totalProcessed != null) {
            rows.add("Processed" to "${summary.totalProcessed} mapping(s)")
        }
        if (summary.created != null) {
            rows.add("Created (active)" to "${summary.created} mapping(s)")
        }
        if (summary.createdPending != null) {
            rows.add("Created (pending)" to "${summary.createdPending} mapping(s)")
        }
        if (summary.skipped != null) {
            rows.add("Skipped" to "${summary.skipped} duplicate(s)")
        }
        if (summary.errorCount != null && summary.errorCount > 0) {
            rows.add("Errors" to "${summary.errorCount} failure(s)")
        }
        if (summary.dbMappingCount != null) {
            rows.add("Backend (existing)" to "${summary.dbMappingCount} mapping(s)")
            rows.add("File" to "${summary.fileMappingCount ?: 0} mapping(s)")
            rows.add("New (in file, not in DB)" to "${summary.newCount ?: 0} mapping(s)")
            rows.add("Unchanged (in both)" to "${summary.unchangedCount ?: 0} mapping(s)")
            rows.add("Removed (in DB, not in file)" to "${summary.removedCount ?: 0} mapping(s)")
        }
        val statItems = rows.joinToString("\n") { (label, value) ->
            """<div class="stat-item">
                    <span class="stat-label">${escapeHtml(label)}</span>
                    <span class="stat-value">${escapeHtml(value)}</span>
                </div>"""
        }
        return """<h3 class="section-title">Import Summary</h3>
            <div class="stats-box">
                $statItems
            </div>"""
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
