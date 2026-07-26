package com.secman.service

import com.secman.config.AppConfig
import com.secman.domain.ExecutionStatus
import com.secman.domain.User
import com.secman.repository.UserRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Emails the "accounts with the longest-open findings" report.
 *
 * Recipients are ADMIN-role users only — deliberately narrower than
 * AdminSummaryService, which also mails REPORT users.
 *
 * Spec: docs/superpowers/specs/2026-07-26-account-finding-age-design.md
 */
@Singleton
open class AccountFindingAgeReportService(
    private val accountFindingAgeService: AccountFindingAgeService,
    private val userRepository: UserRepository,
    private val emailService: EmailService,
    private val appConfig: AppConfig
) {
    private val logger = LoggerFactory.getLogger(AccountFindingAgeReportService::class.java)

    data class ReportResult(
        val recipientCount: Int,
        val emailsSent: Int,
        val emailsFailed: Int,
        val status: ExecutionStatus,
        val recipients: List<String>,
        val failedRecipients: List<String>,
        val accountCount: Int
    )

    open fun sendReport(
        limit: Int = AccountFindingAgeService.DEFAULT_LIMIT,
        dryRun: Boolean = false,
        verbose: Boolean = false
    ): ReportResult {
        val accounts = accountFindingAgeService.getTopAccountsByOldestFinding(limit)
        val recipients = userRepository.findByRolesContaining(User.Role.ADMIN)
            .filter { it.email.isNotBlank() }
            .distinctBy { it.email.lowercase() }

        if (accounts.isEmpty()) {
            logger.info("Account finding-age report: no accounts with open findings, nothing sent")
            return ReportResult(
                recipientCount = recipients.size,
                emailsSent = 0,
                emailsFailed = 0,
                status = ExecutionStatus.SUCCESS,
                recipients = emptyList(),
                failedRecipients = emptyList(),
                accountCount = 0
            )
        }

        if (recipients.isEmpty()) {
            logger.warn("Account finding-age report: no ADMIN users with a valid email")
            return ReportResult(0, 0, 0, ExecutionStatus.FAILURE, emptyList(), emptyList(), accounts.size)
        }

        if (dryRun) {
            return ReportResult(
                recipientCount = recipients.size,
                emailsSent = 0,
                emailsFailed = 0,
                status = ExecutionStatus.DRY_RUN,
                recipients = recipients.map { it.email },
                failedRecipients = emptyList(),
                accountCount = accounts.size
            )
        }

        val executionDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val reportUrl = appConfig.backend.baseUrl.trimEnd('/') + "/account-finding-age"

        val htmlContent = render("/email-templates/account-finding-age-report.html", accounts, executionDate, reportUrl, html = true)
        val textContent = render("/email-templates/account-finding-age-report.txt", accounts, executionDate, reportUrl, html = false)

        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()

        recipients.forEach { user ->
            try {
                if (verbose) logger.info("Sending account finding-age report to {}", user.email)
                val ok = emailService.sendEmailWithInlineImages(
                    to = user.email,
                    subject = "SecMan — Accounts With The Longest-Open Findings",
                    textContent = textContent,
                    htmlContent = htmlContent,
                    inlineImages = emptyMap()
                ).get()
                if (ok) sent.add(user.email) else failed.add(user.email)
            } catch (e: Exception) {
                logger.error("Error sending account finding-age report to {}: {}", user.email, e.message)
                failed.add(user.email)
            }
        }

        val status = when {
            failed.isEmpty() -> ExecutionStatus.SUCCESS
            sent.isEmpty() -> ExecutionStatus.FAILURE
            else -> ExecutionStatus.PARTIAL_FAILURE
        }

        return ReportResult(recipients.size, sent.size, failed.size, status, sent, failed, accounts.size)
    }

    private fun render(
        resource: String,
        accounts: List<AccountFindingAgeService.AccountFindingAge>,
        executionDate: String,
        reportUrl: String,
        html: Boolean
    ): String {
        val body = if (html) renderAccountsHtml(accounts) else renderAccountsText(accounts)
        val placeholder = if (html) "\${accountsHtml}" else "\${accountsText}"

        return try {
            javaClass.getResourceAsStream(resource)!!.bufferedReader().use { it.readText() }
                .replace("\${executionDate}", executionDate)
                .replace("\${accountCount}", accounts.size.toString())
                .replace("\${reportUrl}", reportUrl)
                .replace(placeholder, body)
        } catch (e: Exception) {
            logger.error("Failed to render {}: {}", resource, e.message)
            "SecMan — Accounts With The Longest-Open Findings\nGenerated on: $executionDate\n\n$body\n\n$reportUrl"
        }
    }

    private fun renderAccountsHtml(accounts: List<AccountFindingAgeService.AccountFindingAge>): String {
        val rows = accounts.mapIndexed { index, a ->
            """            <tr>
                <td style="padding: 8px 12px; border-bottom: 1px solid #e9ecef;">${index + 1}</td>
                <td style="padding: 8px 12px; border-bottom: 1px solid #e9ecef;">${escape(a.accountName)}</td>
                <td style="padding: 8px 12px; border-bottom: 1px solid #e9ecef;">${escape(a.awsAccountId)}</td>
                <td style="padding: 8px 12px; border-bottom: 1px solid #e9ecef; text-align: right; font-weight: bold; color: #dc3545;">${a.oldestFindingDaysOpen}</td>
                <td style="padding: 8px 12px; border-bottom: 1px solid #e9ecef;">${escape(a.oldestFindingCve ?: "-")}</td>
                <td style="padding: 8px 12px; border-bottom: 1px solid #e9ecef;">${escape(a.oldestFindingSeverity ?: "-")}</td>
                <td style="padding: 8px 12px; border-bottom: 1px solid #e9ecef;">${escape(a.oldestFindingAssetName ?: "-")}</td>
                <td style="padding: 8px 12px; border-bottom: 1px solid #e9ecef; text-align: right;">${a.openFindingCount}</td>
            </tr>"""
        }.joinToString("\n")

        return """<table style="width: 100%; border-collapse: collapse;">
            <tr style="background-color: #e9ecef;">
                <th style="padding: 8px 12px; text-align: left;">#</th>
                <th style="padding: 8px 12px; text-align: left;">Account name</th>
                <th style="padding: 8px 12px; text-align: left;">Account ID</th>
                <th style="padding: 8px 12px; text-align: right;">Days open</th>
                <th style="padding: 8px 12px; text-align: left;">CVE</th>
                <th style="padding: 8px 12px; text-align: left;">Severity</th>
                <th style="padding: 8px 12px; text-align: left;">Asset</th>
                <th style="padding: 8px 12px; text-align: right;">Open findings</th>
            </tr>
$rows
        </table>"""
    }

    private fun renderAccountsText(accounts: List<AccountFindingAgeService.AccountFindingAge>): String =
        accounts.mapIndexed { index, a ->
            "${index + 1}. ${a.accountName} (${a.awsAccountId}) — ${a.oldestFindingDaysOpen} days open, " +
                "${a.oldestFindingCve ?: "-"} [${a.oldestFindingSeverity ?: "-"}] on ${a.oldestFindingAssetName ?: "-"}, " +
                "${a.openFindingCount} open findings"
        }.joinToString("\n")

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
