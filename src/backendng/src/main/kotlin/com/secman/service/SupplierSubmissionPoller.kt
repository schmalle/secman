package com.secman.service

import com.secman.domain.SupplierAssessmentInvitation
import com.secman.dto.SupplierCollectionStatus
import com.secman.dto.SupplierImportOutcome
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class SupplierSubmissionPollJob(val rowId: Long, val assessmentId: Long, val invitationId: String, val lease: String)

enum class SupplierObservation { READY, INVALID, CONFLICT, STALE }

enum class SupplierPollState(val retryDelay: Duration? = null) {
    WAITING(Duration.ofMinutes(1)),
    MISSING(Duration.ofMinutes(5)),
    UNAVAILABLE(Duration.ofMinutes(5)),
    IMPORT_FAILED(Duration.ofMinutes(5)),
    INVALID, HELD, CONFLICT, IMPORTED, WINDOW_CLOSED
}

@Singleton
open class SupplierSubmissionPollState(
    private val repository: SupplierAssessmentInvitationRepository,
    private val workflow: AssessmentWorkflowService,
    @Value("\${secman.supplier.enabled:false}") private val enabled: Boolean,
    @Value("\${secman.supplier.intake-paused:false}") private val intakePaused: Boolean
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    open fun status(assessmentId: Long, authentication: Authentication): SupplierCollectionStatus? {
        if (!workflow.canReviewSupplierCollection(assessmentId, authentication)) notFound()
        val row = repository.findFirstByAssessmentIdOrderByGenerationDesc(assessmentId) ?: return null
        val now = Instant.now()
        return SupplierCollectionStatus(
            row.externalId, row.serviceScope,
            when {
                row.revokedAt != null -> "REVOKED"
                row.deliveryExpiresAt?.let { it <= now } == true || row.deadline <= now -> "EXPIRED"
                else -> "OPEN"
            },
            row.deliveryState, row.pollState, row.pollCheckedAt, row.pollDueAt.takeIf { row.pollLease == null }, row.pollObservedAt,
            row.importedAt, row.importedRevision, row.pollIntegrityConflict, row.incidentHeldAt,
            enabled && !intakePaused && row.canPoll() && workflow.managesSupplierCollection(authentication)
        )
    }

    @Transactional
    open fun requestRetry(assessmentId: Long, invitationId: String, authentication: Authentication) {
        workflow.locked(assessmentId)
        val actor = workflow.requireAssessmentManager(authentication)
        val row = repository.findFirstByAssessmentIdOrderByGenerationDesc(assessmentId)
            ?.takeIf { it.externalId == invitationId } ?: notFound()
        if (!enabled || intakePaused) throw HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Supplier polling is disabled")
        if (!row.canPoll()) throw HttpStatusException(HttpStatus.CONFLICT, "This supplier invitation cannot be checked")
        val now = Instant.now()
        if (row.pollLease != null && row.pollDueAt?.isAfter(now) == true) return
        row.pollLease = null
        row.pollDueAt = now
        log.info("Supplier submission actor={} target={} outcome=check-requested", actor.attributes["userId"], row.externalId)
    }

    @Transactional
    open fun claim(rowId: Long, assessmentId: Long): SupplierSubmissionPollJob? {
        if (intakePaused) return null
        if (workflow.findLocked(assessmentId) == null) return null
        val row = repository.findById(rowId).orElse(null) ?: return null
        val now = Instant.now()
        if (!row.canPoll() || row.pollDueAt?.let { it <= now } != true) return null
        val lease = UUID.randomUUID().toString()
        row.pollLease = lease
        row.pollDueAt = now.plusSeconds(60)
        return SupplierSubmissionPollJob(rowId, assessmentId, row.externalId, lease)
    }

    @Transactional
    open fun finish(job: SupplierSubmissionPollJob, outcome: SupplierPollState) {
        if (workflow.findLocked(job.assessmentId) == null) return
        val row = repository.findById(job.rowId).orElse(null)
            ?.takeIf { it.pollLease == job.lease && it.revokedAt == null } ?: return
        val now = Instant.now()
        val next = outcome.retryDelay?.let(now::plus)
        val endOfWindow = (row.deliveryExpiresAt ?: row.deadline).plus(Duration.ofDays(7))
        row.pollState = if (outcome == SupplierPollState.WAITING && next != null && next > endOfWindow) {
            SupplierPollState.WINDOW_CLOSED.name
        } else outcome.name
        row.pollCheckedAt = now
        row.pollDueAt = next?.takeIf { it <= endOfWindow && (row.importedAt == null || outcome == SupplierPollState.IMPORT_FAILED) }
        row.pollLease = null
        log.info("Supplier submission actor=internal-poller target={} outcome={}", row.externalId, row.pollState)
    }

    private fun SupplierAssessmentInvitation.canPoll(): Boolean =
        revokedAt == null && incidentHeldAt == null && (pollDueAt != null || pollState != null ||
            deliveryState in setOf("MAIL_SENDING", "SENT", "UNCERTAIN"))

    private fun notFound(): Nothing = throw HttpStatusException(HttpStatus.NOT_FOUND, "Supplier collection unavailable")
}

@Singleton
@Requires(property = "secman.supplier.enabled", value = "true")
@Requires(property = "secman.supplier.intake-paused", notEquals = "true")
open class SupplierSubmissionPoller(
    private val repository: SupplierAssessmentInvitationRepository,
    private val pollState: SupplierSubmissionPollState,
    private val suppliers: SupplierAssessmentService,
    private val portal: SupplierPortalClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = "15s", initialDelay = "20s")
    open fun pollDue() {
        repository.findByPollDueAtLessThanEqualOrderByPollDueAt(Instant.now(), Pageable.from(0, 10))
            .forEach { row ->
                try {
                    pollState.claim(row.id!!, row.assessmentId)?.let(::poll)
                } catch (e: Exception) {
                    log.warn("Supplier submission actor=internal-poller target={} outcome=interrupted type={}",
                        row.externalId, e.javaClass.simpleName)
                }
            }
    }

    internal fun poll(job: SupplierSubmissionPollJob) {
        val outcome = try {
            when (val response = if (portal.ready()) portal.readSubmission(job.invitationId) else SupplierPortalRead.Unavailable) {
                SupplierPortalRead.Waiting -> SupplierPollState.WAITING
                SupplierPortalRead.Missing -> SupplierPollState.MISSING
                SupplierPortalRead.Invalid -> SupplierPollState.INVALID
                SupplierPortalRead.Unavailable -> SupplierPollState.UNAVAILABLE
                is SupplierPortalRead.Completed -> when (suppliers.observeSubmission(job, response.submission)) {
                    SupplierObservation.STALE -> return
                    SupplierObservation.CONFLICT -> SupplierPollState.CONFLICT
                    SupplierObservation.INVALID -> SupplierPollState.INVALID
                    SupplierObservation.READY -> when (suppliers.importSubmission(job.assessmentId, job.invitationId, response.submission, job.lease)) {
                        SupplierImportOutcome.IMPORTED, SupplierImportOutcome.ALREADY_IMPORTED -> SupplierPollState.IMPORTED
                        SupplierImportOutcome.HELD -> SupplierPollState.HELD
                        SupplierImportOutcome.CONFLICT -> SupplierPollState.CONFLICT
                        SupplierImportOutcome.REJECTED -> SupplierPollState.INVALID
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Supplier submission actor=internal-poller target={} outcome=failed type={}",
                job.invitationId, e.javaClass.simpleName)
            SupplierPollState.IMPORT_FAILED
        }
        pollState.finish(job, outcome)
    }
}
