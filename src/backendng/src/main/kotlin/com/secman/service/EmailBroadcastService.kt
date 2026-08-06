package com.secman.service

import com.secman.domain.EmailBroadcastJob
import com.secman.domain.EmailBroadcastStatus
import com.secman.domain.EmailBroadcastTargetGroup
import com.secman.domain.User
import com.secman.repository.EmailBroadcastJobRepository
import com.secman.repository.UserRepository
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
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
    private val emailService: EmailService,
    private val productBroadcastRecipientResolver: ProductBroadcastRecipientResolver
) {
    private val log = LoggerFactory.getLogger(EmailBroadcastService::class.java)
    private val broadcastHtmlSafelist = Safelist()
        .addTags(
            "p", "br", "strong", "b", "em", "i", "u",
            "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "blockquote", "code", "pre",
            "table", "thead", "tbody", "tr", "th", "td", "a"
        )
        .addAttributes("a", "href", "title")
        .addProtocols("a", "href", "http", "https", "mailto")

    @Transactional
    open fun createJob(
        subject: String,
        htmlContent: String,
        createdBy: String,
        targetGroup: EmailBroadcastTargetGroup
    ): EmailBroadcastJob {
        val total = resolveRecipients(targetGroup, createdBy).size
        val sanitizedHtml = sanitizeBroadcastHtml(htmlContent)
        val job = EmailBroadcastJob(
            status = EmailBroadcastStatus.PENDING,
            subject = subject.trim(),
            htmlContent = sanitizedHtml,
            totalRecipients = total,
            createdBy = createdBy,
            createdAt = LocalDateTime.now(),
            targetGroup = targetGroup
        )
        return emailBroadcastJobRepository.save(job)
    }

    @Transactional
    open fun createProductJob(
        subject: String,
        htmlContent: String,
        createdBy: String,
        productName: String,
        authentication: Authentication
    ): EmailBroadcastJob {
        val normalizedProduct = productName.trim()
        val total = productBroadcastRecipientResolver.resolve(normalizedProduct, authentication).size
        val sanitizedHtml = sanitizeBroadcastHtml(htmlContent)
        val job = EmailBroadcastJob(
            status = EmailBroadcastStatus.PENDING,
            subject = subject.trim(),
            htmlContent = sanitizedHtml,
            totalRecipients = total,
            createdBy = createdBy,
            createdAt = LocalDateTime.now(),
            targetGroup = EmailBroadcastTargetGroup.PRODUCT_USERS,
            targetProduct = normalizedProduct
        )
        return emailBroadcastJobRepository.save(job)
    }

    /**
     * Kicks off the send loop. Returns a CompletableFuture so callers may join during tests.
     */
    fun runJobAsync(jobId: Long): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                runJob(jobId, productAuthentication = null)
            } catch (e: Exception) {
                log.error("Email broadcast job {} crashed: {}", jobId, e.message, e)
                markFailed(jobId, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun runProductJobAsync(jobId: Long, authentication: Authentication): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                runJob(jobId, productAuthentication = authentication)
            } catch (e: Exception) {
                log.error("Product email broadcast job {} crashed: {}", jobId, e.message, e)
                markFailed(jobId, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun runJob(jobId: Long, productAuthentication: Authentication?) {
        val job = emailBroadcastJobRepository.findById(jobId).orElse(null) ?: run {
            log.warn("Broadcast job {} not found", jobId)
            return
        }

        markProcessing(jobId)

        val recipients = resolveRecipients(job.targetGroup, job.createdBy, job.targetProduct, productAuthentication)
        log.info(
            "Broadcast job {}: dispatching to {} recipients (targetGroup={})",
            jobId, recipients.size, job.targetGroup
        )

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

    fun recipientCount(targetGroup: EmailBroadcastTargetGroup, requester: String): Long =
        resolveRecipients(targetGroup, requester).size.toLong()

    fun productRecipientCount(productName: String, authentication: Authentication): Long =
        productBroadcastRecipientResolver.resolve(productName.trim(), authentication).size.toLong()

    /**
     * Single source of truth for "who receives this broadcast?".
     *
     * - ALL_USERS / ADMINS_ONLY / ADMINS_AND_SECCHAMPIONS: only users with lastLogin != null
     *   (we never email accounts that have never been activated).
     * - SELF: just the admin who triggered the broadcast — useful for previewing the rendered
     *   email against a real inbox before sending to the wider audience.
     */
    internal fun resolveRecipients(
        targetGroup: EmailBroadcastTargetGroup,
        requester: String,
        targetProduct: String? = null,
        productAuthentication: Authentication? = null
    ): List<User> {
        return when (targetGroup) {
            EmailBroadcastTargetGroup.ALL_USERS ->
                userRepository.findByLastLoginIsNotNull()
            EmailBroadcastTargetGroup.ADMINS_ONLY ->
                userRepository.findByLastLoginIsNotNull()
                    .filter { it.hasRole(User.Role.ADMIN) }
            EmailBroadcastTargetGroup.ADMINS_AND_SECCHAMPIONS ->
                userRepository.findByLastLoginIsNotNull()
                    .filter { it.hasRole(User.Role.ADMIN) || it.hasRole(User.Role.SECCHAMPION) }
            EmailBroadcastTargetGroup.SELF ->
                userRepository.findByUsername(requester)
                    .map { listOf(it) }
                    .orElse(emptyList())
            EmailBroadcastTargetGroup.PRODUCT_USERS ->
                if (targetProduct != null && productAuthentication != null) {
                    productBroadcastRecipientResolver.resolve(targetProduct, productAuthentication)
                } else {
                    emptyList()
                }
        }
    }

    internal fun sanitizeBroadcastHtml(html: String): String =
        Jsoup.clean(html, broadcastHtmlSafelist)

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
