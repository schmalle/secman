package com.secman.service

import com.secman.domain.EmailBroadcastJob
import com.secman.domain.EmailBroadcastStatus
import com.secman.domain.EmailBroadcastTargetGroup
import com.secman.domain.User
import com.secman.repository.EmailBroadcastJobRepository
import com.secman.repository.EolFindingRepository
import com.secman.repository.UserRepository
import io.micronaut.data.model.Pageable
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
    private val productBroadcastRecipientResolver: ProductBroadcastRecipientResolver,
    private val eolBroadcastRecipientResolver: EolBroadcastRecipientResolver,
    private val eolFindingRepository: EolFindingRepository,
    private val eolFindingTableRenderer: EolFindingTableRenderer
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

    @Transactional
    open fun createEolProductJob(
        subject: String,
        htmlContent: String,
        createdBy: String,
        productName: String,
        authentication: Authentication,
        ccRecipients: List<String> = emptyList()
    ): EmailBroadcastJob {
        val normalizedProduct = productName.trim()
        val total = eolBroadcastRecipientResolver.resolve(normalizedProduct, authentication).size
        val sanitizedHtml = sanitizeBroadcastHtml(htmlContent)
        val job = EmailBroadcastJob(
            status = EmailBroadcastStatus.PENDING,
            subject = subject.trim(),
            htmlContent = sanitizedHtml,
            totalRecipients = total,
            createdBy = createdBy,
            createdAt = LocalDateTime.now(),
            targetGroup = EmailBroadcastTargetGroup.EOL_PRODUCT_USERS,
            targetProduct = normalizedProduct,
            ccRecipients = serializeCcRecipients(ccRecipients)
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

    fun runEolProductJobAsync(jobId: Long, authentication: Authentication): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                runJob(jobId, productAuthentication = authentication)
            } catch (e: Exception) {
                log.error("EOL product email broadcast job {} crashed: {}", jobId, e.message, e)
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

        // An EOL product broadcast resolves recipients through its own resolver so the
        // asset linkage survives: each recipient's copy of the mail lists only the
        // affected systems that made *them* a recipient (§A01). Every other target
        // group has no per-recipient data and shares one rendered body.
        val eolRecipients = job.targetProduct
            ?.takeIf { job.targetGroup == EmailBroadcastTargetGroup.EOL_PRODUCT_USERS }
            ?.let { product ->
                productAuthentication?.let { eolBroadcastRecipientResolver.resolve(product, it) }
            }
        val assetIdsByUserId = eolRecipients
            ?.mapNotNull { recipient -> recipient.user.id?.let { it to recipient.assetIds } }
            ?.toMap()
            ?: emptyMap()

        val recipients = eolRecipients?.map { it.user }
            ?: resolveRecipients(job.targetGroup, job.createdBy, job.targetProduct, productAuthentication)
        log.info(
            "Broadcast job {}: dispatching to {} recipients (targetGroup={})",
            jobId, recipients.size, job.targetGroup
        )

        val wrappedHtml = wrapWithBrand(job.subject, job.htmlContent)
        val textContent = htmlToText(job.htmlContent)
        val logo = loadLogoInlineImage()
        val ccRecipients = parseCcRecipients(job.ccRecipients)

        var sent = 0
        var failed = 0
        recipients.forEach { user ->
            val table = assetIdsByUserId[user.id]
                ?.let { renderAffectedSystems(job.targetProduct, it) }
            val ok = try {
                emailService.sendEmailWithInlineImages(
                    to = user.email,
                    subject = job.subject,
                    textContent = table?.let { textContent + it.text } ?: textContent,
                    htmlContent = table?.let { wrapWithBrand(job.subject, job.htmlContent + it.html) } ?: wrappedHtml,
                    inlineImages = logo,
                    cc = ccRecipients
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

    fun eolProductRecipientCount(productName: String, authentication: Authentication): Long =
        eolBroadcastRecipientResolver.resolve(productName.trim(), authentication).size.toLong()

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
            EmailBroadcastTargetGroup.EOL_PRODUCT_USERS ->
                if (targetProduct != null && productAuthentication != null) {
                    eolBroadcastRecipientResolver.resolve(targetProduct, productAuthentication).map { it.user }
                } else {
                    emptyList()
                }
        }
    }

    /**
     * Renders the affected-systems table for one recipient of an EOL product
     * broadcast, or null when there is nothing to show.
     *
     * Both the row page and the count are bounded at the query
     * (`findByComponentNameForAssets` / `countByComponentNameForAssets`) rather
     * than fetched whole and sliced in Kotlin — a recipient mapped to a large AWS
     * account can be linked to thousands of findings (§A04). The count is read
     * separately so the renderer's overflow line can name the real remainder
     * instead of understating it.
     *
     * A failure here must not lose the message: the mail still goes out with the
     * admin's text, and the failure is logged rather than swallowed (§A09).
     */
    private fun renderAffectedSystems(product: String?, assetIds: Set<Long>): RenderedTable? {
        if (product.isNullOrBlank() || assetIds.isEmpty()) return null
        return try {
            val findings = eolFindingRepository.findByComponentNameForAssets(
                product, assetIds, Pageable.from(0, EolFindingTableRenderer.MAX_ROWS)
            )
            if (findings.isEmpty()) return null
            val total = eolFindingRepository.countByComponentNameForAssets(product, assetIds).toInt()
            val html = eolFindingTableRenderer.renderHtml(findings, totalCount = total)
            val text = eolFindingTableRenderer.renderText(findings, totalCount = total)
            RenderedTable(
                html = "<h3 style=\"font-size:15px;margin:24px 0 8px 0;\">Affected systems</h3>$html",
                text = "\n\nAffected systems:\n\n$text"
            )
        } catch (e: Exception) {
            log.warn("Failed to render affected-systems table for product broadcast: {}", e.message, e)
            null
        }
    }

    /**
     * The affected-systems table in both bodies one mail carries. Rendered from a
     * single query so the HTML and plain-text copies cannot drift apart — a reader
     * comparing the two would otherwise see different rows for the same product.
     */
    private data class RenderedTable(val html: String, val text: String)

    internal fun sanitizeBroadcastHtml(html: String): String =
        Jsoup.clean(html, broadcastHtmlSafelist)

    /**
     * Manually-added CC addresses are stored comma-joined on the job row (already
     * trimmed/deduped/validated by the controller) and re-split when the job runs.
     */
    private fun serializeCcRecipients(ccRecipients: List<String>): String? =
        ccRecipients.map { it.trim() }.filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() }
            ?.joinToString(",")

    private fun parseCcRecipients(raw: String?): List<String> =
        raw?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

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
