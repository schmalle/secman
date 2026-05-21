package com.secman.service

import com.secman.repository.ApplicationRegisterRepository
import jakarta.inject.Singleton
import java.time.LocalDate

@Singleton
class ApplicationRegisterReminderService(
    private val applicationRegisterRepository: ApplicationRegisterRepository,
    private val emailService: EmailService
) {
    enum class Status { SUCCESS, DRY_RUN, PARTIAL_FAILURE, FAILURE }

    data class Result(
        val status: Status,
        val thresholdDays: Int,
        val recipientCount: Int,
        val entriesOverdue: Int,
        val emailsSent: Int,
        val emailsFailed: Int,
        val recipients: List<String>,
        val failedRecipients: List<String>
    )

    fun sendReminderEmails(thresholdDays: Int, dryRun: Boolean, verbose: Boolean): Result {
        val cutoff = LocalDate.now().minusDays(thresholdDays.toLong())
        val overdueEntries = applicationRegisterRepository.findEntriesOverdueForQualityCheck(cutoff)

        val recipientToEntries = overdueEntries
            .flatMap { entry -> listOf(entry.applicationManager, entry.businessOwner).map { it.trim().lowercase() to entry.name } }
            .filter { (mail, _) -> mail.contains("@") }
            .groupBy({ it.first }, { it.second })

        if (recipientToEntries.isEmpty()) {
            return Result(Status.FAILURE, thresholdDays, 0, overdueEntries.size, 0, 0, emptyList(), emptyList())
        }
        if (dryRun) {
            return Result(Status.DRY_RUN, thresholdDays, recipientToEntries.size, overdueEntries.size, 0, 0, recipientToEntries.keys.sorted(), emptyList())
        }

        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()
        recipientToEntries.forEach { (recipient, names) ->
            val uniqueNames = names.distinct().sorted()
            val subject = "SecMan reminder: application register entries overdue for quality check"
            val bodyText = buildString {
                appendLine("Hello,")
                appendLine()
                appendLine("The following application register entries were not quality-checked in the last $thresholdDays days:")
                uniqueNames.forEach { appendLine("- $it") }
            }
            val ok = emailService.sendEmail(recipient, subject, bodyText, renderHtmlBody(bodyText)).get()
            if (ok) sent += recipient else failed += recipient
            if (verbose) {
                // no-op, method kept for parity with other send services
            }
        }

        val status = when {
            failed.isEmpty() -> Status.SUCCESS
            sent.isEmpty() -> Status.FAILURE
            else -> Status.PARTIAL_FAILURE
        }

        return Result(status, thresholdDays, recipientToEntries.size, overdueEntries.size, sent.size, failed.size, sent.sorted(), failed.sorted())
    }

    private fun renderHtmlBody(bodyText: String): String =
        "<pre>${escapeHtml(bodyText)}</pre>"

    private fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
