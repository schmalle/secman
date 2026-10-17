package com.secman.service

import com.secman.config.AppConfig
import com.secman.domain.OwnerMailNotification
import com.secman.dto.OwnerMailDelivery
import com.secman.repository.OwnerMailNotificationRepository
import com.secman.util.EmailAddressValidator
import io.micronaut.context.annotation.Value
import io.micronaut.data.model.Pageable
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Locale

/** Called after import commit. Each repository write commits before SMTP starts. */
@Singleton
open class OwnerMailDeliveryService(
    private val notifications: OwnerMailNotificationRepository,
    private val email: EmailService,
    private val templates: EmailTemplateRenderer,
    private val releases: ReleaseRequirementScopeService,
    private val appConfig: AppConfig,
    @Value("\${secman.account-onboarding.welcome-template:account-welcome}") private val welcomeTemplate: String,
    private val settings: AccountOnboardingSettingsService? = null
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun welcome(accountId: String, ownerEmail: String, requested: Boolean, dryRun: Boolean, actorId: Long? = null): OwnerMailDelivery =
        welcome(listOf(accountId), ownerEmail, requested, dryRun, actorId)

    fun welcome(accountIds: List<String>, ownerEmail: String, requested: Boolean, dryRun: Boolean, actorId: Long? = null): OwnerMailDelivery {
        if (!requested) return OwnerMailDelivery()
        val recipient = ownerEmail.trim().lowercase(Locale.ROOT)
        val accounts = accountIds.distinct().sorted()
        require(accounts.isNotEmpty() && accounts.all { it.matches(Regex("[0-9]{12}")) } &&
            EmailAddressValidator.isValidRecipient(recipient)) { "Invalid account or recipient" }
        if (dryRun) return OwnerMailDelivery(true, "WOULD_SEND", accountIds = accounts)
        val key = eventKey(accounts, recipient)
        if (accounts.size == 1) {
            notifications.reserve(key, accounts.first(), recipient, LocalDateTime.now())
        } else {
            notifications.reserveGroup(key, accounts.first(), accounts.joinToString(","), recipient, LocalDateTime.now())
        }
        val notification = notifications.findByEventKey(key).orElseThrow()
        return deliver(notification, retry = false, actorId = actorId)
    }

    fun testWelcome(accountIds: List<String>, recipient: String, actor: String): OwnerMailDelivery {
        require(accountIds.size in 1..200 && accountIds.all { it.matches(Regex("[0-9]{12}")) } &&
            EmailAddressValidator.isValidRecipient(recipient)) { "Invalid account or recipient" }
        val accounts = accountIds.distinct().sorted()
        val row = OwnerMailNotification(awsAccountId = accounts.first(), awsAccountIds = accounts.joinToString(","),
            ownerEmail = recipient.trim().lowercase(Locale.ROOT))
        val result = try {
            send(row, actor)
        } catch (e: Exception) {
            log.warn("Test welcome failed exceptionType={}", e.javaClass.simpleName)
            EmailService.TrackedDelivery("PENDING", "DELIVERY_UNCERTAIN")
        }
        log.info("AUDIT operation=OWNER_MAIL_TEST actor={} recipient={} outcome={}",
            EmailAddressValidator.sanitizeForEcho(actor), EmailAddressValidator.sanitizeForEcho(row.ownerEmail!!), result.status)
        return OwnerMailDelivery(true, result.status, retryable = false, errorCode = result.errorCode, accountIds = accounts)
    }

    fun list(page: Int, pageSize: Int): Map<String, Any> {
        require(page >= 0 && pageSize in 1..100) { "Invalid page or pageSize" }
        redactExpired()
        val result = notifications.findByOwnerEmailIsNotNullOrderByCreatedAtDesc(Pageable.from(page, pageSize))
        return mapOf("notifications" to result.content.map { row ->
            mapOf("id" to row.id, "awsAccountId" to row.awsAccountId, "accountIds" to accounts(row), "ownerEmail" to row.ownerEmail,
                "createdAt" to row.createdAt.toString(), "updatedAt" to row.updatedAt.toString(),
                "welcomeEmail" to outcome(row))
        }, "totalElements" to result.totalSize, "page" to page, "pageSize" to pageSize)
    }

    fun retry(id: Long, actorId: Long): OwnerMailDelivery {
        val row = notifications.findById(id).orElseThrow { NoSuchElementException("Notification not found") }
        check(row.status == "FAILED" && row.ownerEmail != null && row.createdAt.isAfter(cutoff())) {
            "Only retained, failed notifications can be retried"
        }
        log.info("AUDIT operation=OWNER_MAIL_RETRY actorId={} notificationId={}", actorId, id)
        return deliver(row, retry = true, actorId = actorId)
    }

    private fun deliver(row: OwnerMailNotification, retry: Boolean, actorId: Long?): OwnerMailDelivery {
        if (notifications.claim(row.id!!, retry, LocalDateTime.now(), cutoff()) != 1) {
            return outcome(notifications.findById(row.id!!).orElseThrow())
        }
        val result = try {
            send(row)
        } catch (e: Exception) {
            // The claimed row stays pending if an unexpected failure makes delivery uncertain.
            log.warn("Owner mail failed notificationId={} exceptionType={}", row.id, e.javaClass.simpleName)
            EmailService.TrackedDelivery("PENDING", "DELIVERY_UNCERTAIN")
        }
        notifications.finish(row.id!!, result.status, result.errorCode, result.messageId?.take(255), LocalDateTime.now())
        log.info("AUDIT operation=OWNER_MAIL actorId={} notificationId={} outcome={}", actorId, row.id, result.status)
        return OwnerMailDelivery(true, result.status, row.id, result.status == "FAILED", result.errorCode, accounts(row))
    }

    private fun send(row: OwnerMailNotification, testActor: String? = null): EmailService.TrackedDelivery {
        val release = releases.findActiveRelease()
        val accountIds = accounts(row)
        val values = mapOf("awsAccountIds" to accountIds.joinToString(", "), "accountCount" to accountIds.size.toString(),
            "awsAccountId" to row.awsAccountId!!, "ownerEmail" to row.ownerEmail!!,
            "portalUrl" to appConfig.backend.baseUrl.trimEnd('/') + "/",
            "requirementsVersion" to (release?.let { "${it.version} (${it.name})" } ?: ""), "simulatedBy" to (testActor ?: ""))
        if (settings != null) {
            val mail = settings.renderWelcome(values)
            return email.sendTrackedEmailWithInlineImages(row.ownerEmail!!, testSubject(mail.subject, testActor), testBody(withAccountSummary(mail.text, row, false), testActor, false),
                testBody(withAccountSummary(mail.html, row, true), testActor, true),
                templates.loadLogoInlineImage())
        }
        val template = templates.requireAllowed(welcomeTemplate)
        fun prepare(raw: String) = templates.renderConditionalBlock(
            templates.renderConditionalBlock(raw, "ifVersion", release != null), "ifSimulated", false)
        return email.sendTrackedEmailWithInlineImages(row.ownerEmail!!,
            testSubject(if (accountIds.size == 1) "Welcome - your AWS account ${row.awsAccountId} is registered in SecMan"
            else "Welcome - your ${accountIds.size} AWS accounts are registered in SecMan", testActor),
            testBody(withAccountSummary(templates.render(prepare(templates.readText(template)), values, escape = false), row, false), testActor, false),
            testBody(withAccountSummary(templates.render(prepare(templates.readHtml(template)), values, escape = true), row, true), testActor, true),
            templates.loadLogoInlineImage())
    }

    private fun testSubject(subject: String, actor: String?) = if (actor == null) subject else "[TEST] $subject"

    private fun testBody(body: String, actor: String?, html: Boolean): String {
        if (actor == null) return body
        val notice = "TEST EMAIL requested by $actor. No mappings or production notifications were changed."
        if (!html) return "$notice\n\n$body"
        val banner = "<p><strong>${templates.escapeHtml(notice)}</strong></p>"
        val openingBody = Regex("<body[^>]*>", RegexOption.IGNORE_CASE).find(body)
        return if (openingBody == null) banner + body
            else body.substring(0, openingBody.range.last + 1) + banner + body.substring(openingBody.range.last + 1)
    }

    private fun accounts(row: OwnerMailNotification): List<String> =
        row.awsAccountIds?.split(",") ?: listOfNotNull(row.awsAccountId)

    // Also append to customized templates which may not contain the new account-list placeholder.
    private fun withAccountSummary(body: String, row: OwnerMailNotification, html: Boolean): String {
        val accountIds = accounts(row)
        if (accountIds.size <= 1) return body
        val summary = "New AWS accounts registered to you: " + accountIds.joinToString(", ")
        if (!html) return "$body\n\n$summary"
        val paragraph = "<p>${templates.escapeHtml(summary)}</p>"
        val closingBody = Regex("</body>", RegexOption.IGNORE_CASE).find(body)
        return if (closingBody == null) body + paragraph
            else body.substring(0, closingBody.range.first) + paragraph + body.substring(closingBody.range.first)
    }

    @Scheduled(fixedDelay = "1h", initialDelay = "1m")
    open fun redactExpired() {
        notifications.redactExpired(cutoff())
    }

    private fun cutoff() = LocalDateTime.now().minusDays(90)
    private fun outcome(row: OwnerMailNotification) = OwnerMailDelivery(true, row.status, row.id,
        row.status == "FAILED" && row.ownerEmail != null && row.createdAt.isAfter(cutoff()), row.errorCode, accounts(row))

    companion object {
        fun eventKey(accountIds: List<String>, email: String): String {
            val accounts = accountIds.distinct().sorted()
            // Preserve existing single-account tombstones and their retry identity.
            if (accounts.size == 1) return eventKey(accounts.first(), email)
            return MessageDigest.getInstance("SHA-256")
                .digest("AWS_ACCOUNT_WELCOME:v2:${accounts.joinToString(",")}:${email.trim().lowercase(Locale.ROOT)}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        fun eventKey(accountId: String, email: String): String = MessageDigest.getInstance("SHA-256")
            .digest("AWS_ACCOUNT_WELCOME:v1:$accountId:${email.trim().lowercase(Locale.ROOT)}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
