package com.secman.service

import com.secman.domain.EmailBroadcastJob
import com.secman.domain.EmailBroadcastStatus
import com.secman.repository.EmailBroadcastJobRepository
import com.secman.repository.UserRepository
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

/**
 * Async broadcast service. Each broadcast renders the admin-supplied HTML inside a
 * SecMan branded shell (logo + footer) and sends to every user with `lastLogin != null`.
 */
@Singleton
@ExecuteOn(TaskExecutors.IO)
open class EmailBroadcastService(
    private val emailBroadcastJobRepository: EmailBroadcastJobRepository,
    private val userRepository: UserRepository,
    private val emailService: EmailService
) {
    private val log = LoggerFactory.getLogger(EmailBroadcastService::class.java)

    @Transactional
    open fun createJob(subject: String, htmlContent: String, createdBy: String): EmailBroadcastJob {
        val total = userRepository.countByLastLoginIsNotNull().toInt()
        val job = EmailBroadcastJob(
            status = EmailBroadcastStatus.PENDING,
            subject = subject.trim(),
            htmlContent = htmlContent,
            totalRecipients = total,
            createdBy = createdBy,
            createdAt = LocalDateTime.now()
        )
        return emailBroadcastJobRepository.save(job)
    }

    /**
     * Kicks off the send loop. Returns a CompletableFuture so callers may join during tests.
     */
    fun runJobAsync(jobId: Long): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                runJob(jobId)
            } catch (e: Exception) {
                log.error("Email broadcast job {} crashed: {}", jobId, e.message, e)
                markFailed(jobId, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun runJob(jobId: Long) {
        val job = emailBroadcastJobRepository.findById(jobId).orElse(null) ?: run {
            log.warn("Broadcast job {} not found", jobId)
            return
        }

        markProcessing(jobId)

        val recipients = userRepository.findByLastLoginIsNotNull()
        log.info("Broadcast job {}: dispatching to {} recipients", jobId, recipients.size)

        val wrappedHtml = wrapWithBrand(job.subject, job.htmlContent)
        val textContent = htmlToText(job.htmlContent)
        val logo = loadLogoInlineImage()

        var sent = 0
        var failed = 0
        recipients.forEach { user ->
            val ok = try {
                emailService.sendEmailWithInlineImages(
                    to = user.email,
                    subject = job.subject,
                    textContent = textContent,
                    htmlContent = wrappedHtml,
                    inlineImages = logo
                ).get()
            } catch (e: Exception) {
                log.warn("Broadcast job {}: send to {} failed: {}", jobId, user.email, e.message)
                false
            }
            if (ok) sent++ else failed++

            // Persist progress every 25 messages so the UI poller has fresh data.
            if ((sent + failed) % 25 == 0) {
                updateProgress(jobId, sent, failed)
            }
        }

        finalize(jobId, sent, failed)
        log.info("Broadcast job {} complete: {} sent, {} failed", jobId, sent, failed)
    }

    @Transactional
    open fun markProcessing(jobId: Long) {
        emailBroadcastJobRepository.findById(jobId).ifPresent { job ->
            job.status = EmailBroadcastStatus.PROCESSING
            job.startedAt = LocalDateTime.now()
            emailBroadcastJobRepository.update(job)
        }
    }

    @Transactional
    open fun updateProgress(jobId: Long, sent: Int, failed: Int) {
        emailBroadcastJobRepository.findById(jobId).ifPresent { job ->
            job.sentCount = sent
            job.failedCount = failed
            emailBroadcastJobRepository.update(job)
        }
    }

    @Transactional
    open fun finalize(jobId: Long, sent: Int, failed: Int) {
        emailBroadcastJobRepository.findById(jobId).ifPresent { job ->
            job.sentCount = sent
            job.failedCount = failed
            // FAILED only when nothing got through; partial success stays COMPLETED with failedCount > 0.
            job.status = if (sent == 0 && failed > 0) EmailBroadcastStatus.FAILED else EmailBroadcastStatus.COMPLETED
            job.completedAt = LocalDateTime.now()
            emailBroadcastJobRepository.update(job)
        }
    }

    @Transactional
    open fun markFailed(jobId: Long, message: String) {
        emailBroadcastJobRepository.findById(jobId).ifPresent { job ->
            job.status = EmailBroadcastStatus.FAILED
            job.errorMessage = message.take(2000)
            job.completedAt = LocalDateTime.now()
            emailBroadcastJobRepository.update(job)
        }
    }

    fun listRecentJobs(limit: Int = 50): List<EmailBroadcastJob> =
        emailBroadcastJobRepository.listRecent().take(limit)

    fun getJob(id: Long): EmailBroadcastJob? = emailBroadcastJobRepository.findById(id).orElse(null)

    fun recipientCount(): Long = userRepository.countByLastLoginIsNotNull()

    private fun loadLogoInlineImage(): Map<String, Pair<ByteArray, String>> {
        return try {
            val bytes = javaClass.getResourceAsStream("/email-templates/SecManLogo.png")?.readAllBytes()
            if (bytes != null) mapOf("secman-logo" to (bytes to "image/png")) else emptyMap()
        } catch (e: Exception) {
            log.warn("Failed to load SecManLogo.png: {}", e.message)
            emptyMap()
        }
    }

    private fun wrapWithBrand(subject: String, body: String): String {
        val safeSubject = Jsoup.parse(subject).text()
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8" />
                <title>${safeSubject}</title>
            </head>
            <body style="margin:0;padding:0;background-color:#f4f4f4;font-family:Arial,sans-serif;color:#333;">
                <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color:#f4f4f4;padding:20px 0;">
                    <tr>
                        <td align="center">
                            <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="600" style="background-color:#ffffff;border-radius:6px;overflow:hidden;">
                                <tr>
                                    <td style="padding:24px 24px 0 24px;text-align:center;">
                                        <img src="cid:secman-logo" alt="SecMan" style="max-width:180px;height:auto;" />
                                    </td>
                                </tr>
                                <tr>
                                    <td style="padding:24px;line-height:1.6;font-size:14px;color:#333;">
                                        ${body}
                                    </td>
                                </tr>
                                <tr>
                                    <td style="padding:16px 24px;border-top:1px solid #e5e5e5;font-size:12px;color:#888;text-align:center;">
                                        This is an automated notification from SecMan. Please do not reply to this email.
                                    </td>
                                </tr>
                            </table>
                        </td>
                    </tr>
                </table>
            </body>
            </html>
        """.trimIndent()
    }

    private fun htmlToText(html: String): String = try {
        Jsoup.parse(html).text()
    } catch (_: Exception) {
        html.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()
    }
}
