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
    @Value("\${secman.account-onboarding.welcome-template:account-welcome}") private val welcomeTemplate: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun welcome(accountId: String, ownerEmail: String, requested: Boolean, dryRun: Boolean, actorId: Long? = null): OwnerMailDelivery {
        if (!requested) return OwnerMailDelivery()
        val recipient = ownerEmail.trim().lowercase(Locale.ROOT)
        require(accountId.matches(Regex("[0-9]{12}")) && EmailAddressValidator.isValidRecipient(recipient)) {
            "Invalid account or recipient"
        }
        if (dryRun) return OwnerMailDelivery(true, "WOULD_SEND")
        val key = eventKey(accountId, recipient)
        notifications.reserve(key, accountId, recipient, LocalDateTime.now())
        val notification = notifications.findByEventKey(key).orElseThrow()
        return deliver(notification, retry = false, actorId = actorId)
    }

    fun list(page: Int, pageSize: Int): Map<String, Any> {
        require(page >= 0 && pageSize in 1..100) { "Invalid page or pageSize" }
        redactExpired()
        val result = notifications.findByOwnerEmailIsNotNullOrderByCreatedAtDesc(Pageable.from(page, pageSize))
        return mapOf("notifications" to result.content.map { row ->
            mapOf("id" to row.id, "awsAccountId" to row.awsAccountId, "ownerEmail" to row.ownerEmail,
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
        return OwnerMailDelivery(true, result.status, row.id, result.status == "FAILED", result.errorCode)
    }

    private fun send(row: OwnerMailNotification): EmailService.TrackedDelivery {
        val release = releases.findActiveRelease()
        val values = mapOf("awsAccountId" to row.awsAccountId!!, "ownerEmail" to row.ownerEmail!!,
            "portalUrl" to appConfig.backend.baseUrl.trimEnd('/') + "/",
            "requirementsVersion" to (release?.let { "${it.version} (${it.name})" } ?: ""), "simulatedBy" to "")
        val template = templates.requireAllowed(welcomeTemplate)
        fun prepare(raw: String) = templates.renderConditionalBlock(
            templates.renderConditionalBlock(raw, "ifVersion", release != null), "ifSimulated", false)
        return email.sendTrackedEmailWithInlineImages(row.ownerEmail!!,
            "Welcome - your AWS account ${row.awsAccountId} is registered in SecMan",
            templates.render(prepare(templates.readText(template)), values, escape = false),
            templates.render(prepare(templates.readHtml(template)), values, escape = true),
            templates.loadLogoInlineImage())
    }

    @Scheduled(fixedDelay = "1h", initialDelay = "1m")
    open fun redactExpired() {
        notifications.redactExpired(cutoff())
    }

    private fun cutoff() = LocalDateTime.now().minusDays(90)
    private fun outcome(row: OwnerMailNotification) = OwnerMailDelivery(true, row.status, row.id,
        row.status == "FAILED" && row.ownerEmail != null && row.createdAt.isAfter(cutoff()), row.errorCode)

    companion object {
        fun eventKey(accountId: String, email: String): String = MessageDigest.getInstance("SHA-256")
            .digest("AWS_ACCOUNT_WELCOME:v1:$accountId:${email.trim().lowercase(Locale.ROOT)}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
