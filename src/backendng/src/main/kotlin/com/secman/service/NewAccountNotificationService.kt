package com.secman.service

import com.secman.domain.ExecutionStatus
import com.secman.domain.MappingStatus
import com.secman.repository.UserMappingRepository
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Notifies users about new AWS account mappings created within a recent time window
 * (default: last 24 hours). "New account" means a UserMapping row with a non-null
 * aws_account_id whose created_at falls inside the look-back period.
 *
 * The notification body text is caller-supplied (read from a file by the CLI command)
 * so operators can customize the message without redeploying. The service appends the
 * list of new account IDs below the custom text.
 */
@Singleton
open class NewAccountNotificationService(
    private val userMappingRepository: UserMappingRepository,
    private val emailService: EmailService
) {
    private val logger = LoggerFactory.getLogger(NewAccountNotificationService::class.java)

    @Serdeable
    data class NewAccountNotificationResult(
        val status: ExecutionStatus,
        val accountMappingsFound: Int,
        val usersNotified: Int,
        val emailsSent: Int,
        val emailsFailed: Int,
        val recipients: List<String>,
        val failedRecipients: List<String>,
        val hours: Int
    )

    @Transactional
    open fun sendNewAccountNotifications(
        hours: Int = 24,
        dryRun: Boolean = false,
        verbose: Boolean = false,
        notificationText: String
    ): NewAccountNotificationResult {
        val since = Instant.now().minusSeconds(hours * 3600L)
        val newMappings = userMappingRepository.findRecentAwsAccountMappings(since)
            .filter { it.status == MappingStatus.ACTIVE }

        logger.info("Found {} new AWS account mapping(s) in the last {} hour(s)", newMappings.size, hours)

        if (newMappings.isEmpty()) {
            return NewAccountNotificationResult(
                status = if (dryRun) ExecutionStatus.DRY_RUN else ExecutionStatus.SUCCESS,
                accountMappingsFound = 0,
                usersNotified = 0,
                emailsSent = 0,
                emailsFailed = 0,
                recipients = emptyList(),
                failedRecipients = emptyList(),
                hours = hours
            )
        }

        // Group by email → list of new AWS account IDs
        val byEmail: Map<String, List<String>> = newMappings
            .groupBy { it.email }
            .mapValues { (_, mappings) -> mappings.mapNotNull { it.awsAccountId }.distinct().sorted() }

        if (dryRun) {
            logger.info("DRY-RUN: would notify {} user(s)", byEmail.size)
            return NewAccountNotificationResult(
                status = ExecutionStatus.DRY_RUN,
                accountMappingsFound = newMappings.size,
                usersNotified = byEmail.size,
                emailsSent = 0,
                emailsFailed = 0,
                recipients = byEmail.keys.sorted(),
                failedRecipients = emptyList(),
                hours = hours
            )
        }

        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for ((email, accounts) in byEmail) {
            val subject = "New AWS Account Access in SecMan"
            val textBody = buildTextBody(notificationText, accounts)
            val htmlBody = buildHtmlBody(notificationText, accounts)

            val success = try {
                emailService.sendEmail(email, subject, textBody, htmlBody).get()
            } catch (e: Exception) {
                logger.error("Failed to send new-account notification to {}: {}", email, e.message, e)
                false
            }

            if (success) {
                sent += email
                if (verbose) logger.info("Sent new-account notification to {}", email)
            } else {
                failed += email
                logger.warn("Failed to send new-account notification to {}", email)
            }
        }

        val status = when {
            failed.isEmpty() -> ExecutionStatus.SUCCESS
            sent.isEmpty() -> ExecutionStatus.FAILURE
            else -> ExecutionStatus.PARTIAL_FAILURE
        }

        return NewAccountNotificationResult(
            status = status,
            accountMappingsFound = newMappings.size,
            usersNotified = byEmail.size,
            emailsSent = sent.size,
            emailsFailed = failed.size,
            recipients = sent,
            failedRecipients = failed,
            hours = hours
        )
    }

    private fun buildTextBody(notificationText: String, accounts: List<String>): String {
        val accountLines = accounts.joinToString("\n") { "  - $it" }
        return """
            |$notificationText
            |
            |New AWS Account(s) now mapped to your user:
            |$accountLines
            |
            |-- SecMan
        """.trimMargin()
    }

    private fun buildHtmlBody(notificationText: String, accounts: List<String>): String {
        val accountRows = accounts.joinToString("") { accountId ->
            "<tr><td style=\"padding:4px 8px;font-family:monospace;\">${escapeHtml(accountId)}</td></tr>"
        }
        val textHtml = escapeHtml(notificationText).replace("\n", "<br>")
        return """
            <!DOCTYPE html>
            <html>
            <body style="font-family:Arial,sans-serif;color:#333;max-width:600px;margin:0 auto;padding:20px;">
              <h2 style="color:#2c3e50;">New AWS Account Access in SecMan</h2>
              <p>$textHtml</p>
              <h3 style="color:#495057;">New AWS Account(s) mapped to your user:</h3>
              <table style="border-collapse:collapse;width:100%;margin-top:8px;">
                $accountRows
              </table>
              <hr style="margin:30px 0;border:none;border-top:1px solid #dee2e6;">
              <p style="font-size:0.85em;color:#6c757d;">This message was sent by SecMan.</p>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
