package com.secman.service

import com.secman.domain.SupplierAssessmentInvitation
import com.secman.dto.SupplierQuestionnaire
import com.secman.repository.SupplierAssessmentInvitationRepository
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Value
import io.micronaut.data.model.Pageable
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.scheduling.annotation.Scheduled
import io.micronaut.security.authentication.Authentication
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

@Singleton
open class SupplierInvitationOutbox(
    private val repository: SupplierAssessmentInvitationRepository,
    private val workflow: AssessmentWorkflowService,
    private val suppliers: SupplierAssessmentService,
    private val portal: SupplierPortalClient,
    @Value("\${secman.supplier.enabled:false}") private val enabled: Boolean
) {
    private val random = SecureRandom()
    private val encoder = BCryptPasswordEncoder()
    private val log = LoggerFactory.getLogger(javaClass)

    class Job(
        val rowId: Long,
        val assessmentId: Long,
        val invitationId: String,
        val lease: String,
        val revoke: Boolean,
        val questionnaire: SupplierQuestionnaire?,
        val previousId: String?,
        val retainDraft: Boolean,
        val credential: String?,
        val verifier: String?,
        val expiresAt: Instant?
    )

    class Mail(val recipient: String, val link: String, val scope: String, val requester: String,
               val deadline: Instant, val expiresAt: Instant)

    @Transactional
    open fun enqueue(assessmentId: Long, externalId: String, authentication: Authentication) {
        workflow.locked(assessmentId)
        val actor = workflow.requireAssessmentManager(authentication)
        val row = repository.findFirstByAssessmentIdOrderByGenerationDesc(assessmentId)
            ?.takeIf { it.externalId == externalId }
            ?: throw HttpStatusException(HttpStatus.NOT_FOUND, "Supplier invitation unavailable")
        if (!enabled || !portal.ready()) throw HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Supplier delivery is unavailable")
        val now = Instant.now()
        if (row.revokedAt != null || row.importedAt != null || row.deadline <= now ||
            row.deliveryState in setOf("FAILED", "UNCERTAIN")
        ) throw HttpStatusException(HttpStatus.CONFLICT, "A new supplier invitation is required")
        if (row.deliveryState != null) return
        val credential = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        row.deliveryCredential = credential
        row.deliveryVerifier = encoder.encode(credential)
        row.deliveryState = "PROVISION_PENDING"
        row.deliveryDueAt = now
        row.deliveryExpiresAt = minOf(row.deadline, now.plus(Duration.ofDays(14))).truncatedTo(ChronoUnit.MICROS)
        log.info("Supplier delivery actor={} target={} outcome=queued", actor.attributes["userId"], row.externalId)
    }

    @Transactional
    open fun claim(rowId: Long, assessmentId: Long): Job? {
        if (workflow.findLocked(assessmentId) == null) return null
        val row = repository.findById(rowId).orElse(null) ?: return null
        val now = Instant.now()
        if (row.deliveryDueAt?.let { it <= now } != true) return null
        if (row.deliveryState == "MAIL_SENDING") {
            finish(row, "UNCERTAIN")
            return null
        }
        val revoke = row.revokedAt != null
        if (!revoke && (row.deliveryExpiresAt?.let { it <= now } != false || row.deliveryCredential == null ||
                row.deliveryVerifier == null || row.importedAt != null)) {
            finish(row, "FAILED")
            return null
        }
        val lease = UUID.randomUUID().toString()
        row.deliveryLease = lease
        row.deliveryState = if (revoke) "REVOKING" else "PROVISIONING"
        row.deliveryDueAt = now.plusSeconds(120)
        return Job(rowId, assessmentId, row.externalId, lease, revoke,
            if (revoke) null else suppliers.publicQuestionnaire(row), row.previousExternalId,
            row.retainPreviousDraft, row.deliveryCredential, row.deliveryVerifier, row.deliveryExpiresAt)
    }

    @Transactional
    open fun provisioned(job: Job, success: Boolean): Mail? {
        val row = claimed(job) ?: return null
        if (job.revoke) {
            if (success) finish(row, "REVOKED") else retry(row, "REVOKE_PENDING")
            return null
        }
        if (!success) {
            retry(row, "PROVISION_PENDING")
            return null
        }
        val now = Instant.now()
        val expiresAt = requireNotNull(row.deliveryExpiresAt)
        if (expiresAt <= now || row.importedAt != null) {
            finish(row, "FAILED")
            return null
        }
        val link = portal.invitationLink(row.externalId, requireNotNull(job.credential))
        row.deliveryState = "MAIL_SENDING"
        row.deliveryDueAt = now.plusSeconds(120)
        row.pollDueAt = now
        // A crash after this point leaves an uncertain mail result. No automatic resend.
        row.deliveryCredential = null
        row.deliveryVerifier = null
        return Mail(row.recipientEmail, link, row.serviceScope, row.requesterLabel, row.deadline, expiresAt)
    }

    @Transactional
    open fun mailed(job: Job, result: String) {
        val row = claimed(job) ?: return
        if (row.deliveryState != "MAIL_SENDING") return
        finish(row, when (result) {
            "SENT" -> "SENT"
            "FAILED" -> "FAILED"
            else -> "UNCERTAIN"
        })
    }

    private fun claimed(job: Job): SupplierAssessmentInvitation? {
        if (workflow.findLocked(job.assessmentId) == null) return null
        return repository.findById(job.rowId).orElse(null)?.takeIf { it.deliveryLease == job.lease }
    }

    private fun retry(row: SupplierAssessmentInvitation, state: String) {
        row.deliveryState = state
        row.deliveryLease = null
        row.deliveryDueAt = Instant.now().plusSeconds(30)
        log.warn("Supplier delivery actor=internal target={} outcome={}", row.externalId, state)
    }

    private fun finish(row: SupplierAssessmentInvitation, state: String) {
        row.deliveryState = state
        row.deliveryDueAt = null
        row.deliveryLease = null
        row.deliveryCredential = null
        row.deliveryVerifier = null
        log.info("Supplier delivery actor=internal target={} outcome={}", row.externalId, state)
    }
}

@Singleton
@Requires(property = "secman.supplier.enabled", value = "true")
open class SupplierInvitationDelivery(
    private val repository: SupplierAssessmentInvitationRepository,
    private val outbox: SupplierInvitationOutbox,
    private val portal: SupplierPortalClient,
    private val email: EmailService,
    private val templates: EmailTemplateRenderer
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = "15s", initialDelay = "15s")
    open fun deliverPending() {
        repository.findByDeliveryDueAtLessThanEqualOrderByDeliveryDueAt(Instant.now(), Pageable.from(0, 10))
            .forEach { row ->
                try {
                    outbox.claim(row.id!!, row.assessmentId)?.let(::deliver)
                } catch (e: Exception) {
                    // A lease recovers interrupted work. Never serialize exception messages or job contents.
                    log.warn("Supplier delivery actor=internal target={} outcome=interrupted type={}",
                        row.externalId, e.javaClass.simpleName)
                }
            }
    }

    internal fun deliver(job: SupplierInvitationOutbox.Job) {
        val success = portal.ready() && if (job.revoke) portal.revoke(job.invitationId) else {
            portal.provision(requireNotNull(job.questionnaire), requireNotNull(job.verifier),
                job.previousId, job.retainDraft, requireNotNull(job.expiresAt))
        }
        val mail = outbox.provisioned(job, success) ?: return
        val subject = "Supplier security assessment invitation"
        val body = "${mail.requester} requests a security assessment for ${mail.scope}.\n" +
            "Deadline (UTC): ${mail.deadline}\nLink expires (UTC): ${mail.expiresAt}\n\n" +
            "Open the questionnaire: ${mail.link}\n\nKeep this link private."
        val html = "<p>${templates.escapeHtml(mail.requester)} requests a security assessment for " +
            "${templates.escapeHtml(mail.scope)}.</p><p>Deadline (UTC): ${mail.deadline}<br>Link expires (UTC): ${mail.expiresAt}</p>" +
            "<p><a href=\"${templates.escapeHtml(mail.link)}\">Open the questionnaire</a></p><p>Keep this link private.</p>"
        val result = email.sendTrackedSupplierInvitation(mail.recipient, subject, body, html)
        outbox.mailed(job, result.status)
    }
}
