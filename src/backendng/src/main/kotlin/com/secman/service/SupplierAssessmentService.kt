package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.AnswerType
import com.secman.domain.AssessmentBasisType
import com.secman.domain.RiskAssessment
import com.secman.domain.SupplierAssessmentInvitation
import com.secman.dto.*
import com.secman.repository.AssetRepository
import com.secman.repository.ResponseRepository
import com.secman.repository.SupplierAssessmentInvitationRepository
import com.secman.repository.UserRepository
import com.secman.util.EmailAddressValidator
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@Singleton
open class SupplierAssessmentService(
    private val invitations: SupplierAssessmentInvitationRepository,
    private val assets: AssetRepository,
    private val assetAccess: AssetFilterService,
    private val users: UserRepository,
    private val responses: ResponseRepository,
    private val releases: ReleaseRequirementScopeService,
    private val workflow: AssessmentWorkflowService,
    private val mapper: ObjectMapper,
    @Value("\${secman.supplier.intake-paused:false}") private val intakePaused: Boolean
) {
    private data class MappedQuestion(val requirementId: Long, val key: String, val text: String) {
        fun publicQuestion() = SupplierQuestion(key, text)
    }
    private data class ValidatedSubmission(val answers: List<AssessmentWorkflowService.Answer>, val digest: String)

    @Transactional
    open fun create(
        assessmentId: Long, authentication: Authentication, request: CreateSupplierInvitation
    ): SupplierInvitationStatus {
        val assessment = workflow.locked(assessmentId)
        val actor = workflow.requireAssessmentManager(authentication)
        if (!assetAccess.canAccessAsset(assessment.assessmentBasisId, actor) || !isSupplier(assessment)) notFound()
        val previous = invitations.findFirstByAssessmentIdOrderByGenerationDesc(assessmentId)
        if (previous != null && previous.revokedAt == null) conflict("Revoke the open invitation before replacing it")
        if (previous?.deliveryDueAt != null) conflict("Wait for the previous invitation revocation")
        workflow.requireUnusedSupplierAssessment(assessment)

        val now = now()
        val deadline = request.deadline.truncatedTo(ChronoUnit.MICROS)
        if (deadline <= now) invalid("Choose a future deadline")
        val email = request.recipientEmail.trim()
        if (!EmailAddressValidator.isValidRecipient(email)) invalid("Enter a valid supplier contact")
        val label = approvedText(request.requesterLabel, 255)
        val scope = approvedText(request.serviceScope, 1000)
        val release = assessment.lockedRelease ?: releases.findActiveRelease()
            ?: conflict("An active questionnaire release is required")
        val requirements = releases.requirementsForRelease(release.id!!, assessment.useCases.mapNotNull { it.id })
        val requested = approvedQuestions(request.questions, requirements.mapNotNull { it.id }.toSet())
        val previousQuestions = previous?.let(::questions).orEmpty()
        val keepDraft = previous != null && previous.recipientEmail.equals(email, true) &&
            previousQuestions.map { it.requirementId to it.text } == requested.map { it.requirementId to it.text }
        val questions = if (keepDraft) previousQuestions else requested
        assessment.lockedRelease = release
        assessment.isReleaseLocked = true
        assessment.contentSnapshotTaken = true
        assessment.releaseLockedAt = assessment.releaseLockedAt ?: LocalDateTime.now()
        // Invalidate AI work queued before supplier collection was selected.
        assessment.assignmentVersion++

        val row = invitations.save(SupplierAssessmentInvitation(
            externalId = UUID.randomUUID().toString(), assessmentId = assessmentId, releaseId = release.id!!,
            generation = (previous?.generation ?: 0) + 1, recipientEmail = email,
            recipientUserId = users.findByEmailIgnoreCase(email).orElse(null)?.id,
            requesterLabel = label, serviceScope = scope, snapshotJson = mapper.writeValueAsString(questions),
            snapshotHash = digest(questions.map { it.publicQuestion() }), createdAt = now, deadline = deadline,
            previousExternalId = previous?.externalId, retainPreviousDraft = keepDraft
        ))
        log.info("Supplier invitation actor={} target={} outcome=created", actor.attributes["userId"], row.externalId)
        return view(row, questions, now)
    }

    @Transactional
    open fun latest(assessmentId: Long, authentication: Authentication): SupplierInvitationStatus {
        workflow.requireAssessmentManager(authentication)
        val row = invitations.findFirstByAssessmentIdOrderByGenerationDesc(assessmentId) ?: notFound()
        return view(row, questions(row), now())
    }

    @Transactional
    open fun revoke(assessmentId: Long, invitationId: String, authentication: Authentication) {
        workflow.locked(assessmentId)
        val actor = workflow.requireAssessmentManager(authentication)
        val row = invitations.findFirstByAssessmentIdOrderByGenerationDesc(assessmentId) ?: notFound()
        if (row.externalId != invitationId) notFound()
        if (row.importedAt != null) conflict("An imported submission cannot be revoked")
        if (row.revokedAt != null) return
        row.revokedAt = now()
        row.pollDueAt = null
        row.pollLease = null
        if (row.deliveryState != null) {
            row.deliveryState = "REVOKE_PENDING"
            row.deliveryDueAt = row.revokedAt
            row.deliveryLease = null
            row.deliveryCredential = null
            row.deliveryVerifier = null
        }
        log.info("Supplier invitation actor={} target={} outcome=revoked", actor.attributes["userId"], row.externalId)
    }

    /** Quarantine the exact imported revision without discarding its answers or acceptance history. */
    @Transactional
    open fun placeIncidentHold(assessmentId: Long, invitationId: String, revision: Long, authentication: Authentication) {
        val assessment = workflow.locked(assessmentId)
        val actorId = workflow.requireSupplierIncidentAdmin(authentication)
        val row = invitations.findFirstByAssessmentIdOrderByGenerationDesc(assessmentId)
            ?.takeIf { it.externalId == invitationId } ?: notFound()
        if (row.importedAt == null || row.importedRevision != revision || assessment.answerRevision != revision) {
            conflict("The supplier submission revision has changed")
        }
        if (row.incidentHeldAt != null) return
        row.incidentHeldAt = now()
        row.pollDueAt = null
        row.pollLease = null
        workflow.invalidateSupplierAcceptance(assessmentId, revision)
        log.warn("Supplier incident hold actor={} target={} revision={} outcome=held", actorId, row.externalId, revision)
    }

    /** The caller supplies the invitation it polled. The portal payload cannot select an assessment. */
    @Transactional
    open fun importSubmission(
        assessmentId: Long, expectedInvitationId: String, submission: SupplierSubmission, pollLease: String? = null
    ): SupplierImportOutcome {
        if (intakePaused) return SupplierImportOutcome.HELD
        val assessment = workflow.locked(assessmentId)
        val row = invitations.findFirstByAssessmentIdOrderByGenerationDesc(assessmentId)
            ?: return SupplierImportOutcome.REJECTED
        if (pollLease != null && row.pollLease != pollLease) return SupplierImportOutcome.REJECTED
        val validated = validateSubmission(row, expectedInvitationId, submission) ?: return SupplierImportOutcome.REJECTED
        val outcome = applySubmission(assessment, row, submission, validated)
        log.info("Supplier import actor=internal-poller target={} outcome={}", row.externalId, outcome)
        return outcome
    }

    /** Persist only the verified payload fingerprint and a trusted receipt time before the import transaction. */
    @Transactional
    open fun observeSubmission(job: SupplierSubmissionPollJob, submission: SupplierSubmission): SupplierObservation {
        if (intakePaused) return SupplierObservation.STALE
        if (workflow.findLocked(job.assessmentId) == null) return SupplierObservation.STALE
        val row = invitations.findById(job.rowId).orElse(null)
            ?.takeIf { it.pollLease == job.lease && it.revokedAt == null }
            ?: return SupplierObservation.STALE
        val validated = validateSubmission(row, job.invitationId, submission) ?: return SupplierObservation.INVALID
        val original = row.submissionDigest ?: row.pollObservedDigest
        if (original != null && original != validated.digest) {
            row.pollIntegrityConflict = true
            return SupplierObservation.CONFLICT
        }
        if (row.pollIntegrityConflict) return SupplierObservation.CONFLICT
        row.pollObservedAt = row.pollObservedAt ?: row.importedAt ?: now()
        row.pollObservedDigest = validated.digest
        row.pollCheckedAt = now()
        row.pollState = "OBSERVED"
        return SupplierObservation.READY
    }

    private fun validateSubmission(
        row: SupplierAssessmentInvitation, expectedId: String, submission: SupplierSubmission
    ): ValidatedSubmission? {
        if (row.externalId != expectedId || row.externalId != submission.invitationId ||
            row.generation != submission.generation || row.snapshotHash != submission.snapshotHash ||
            submission.schemaVersion != SCHEMA_VERSION || row.revokedAt != null ||
            !OPAQUE_ID.matches(submission.submissionId)
        ) return null
        val answers = validatedAnswers(questions(row), submission.answers) ?: return null
        return ValidatedSubmission(answers, digest(submission.copy(answers = submission.answers.sortedBy { it.questionKey })))
    }

    private fun applySubmission(
        assessment: RiskAssessment, row: SupplierAssessmentInvitation,
        submission: SupplierSubmission, validated: ValidatedSubmission
    ): SupplierImportOutcome {
        val (answers, digest) = validated
        if (row.incidentHeldAt != null) return SupplierImportOutcome.HELD
        if (row.pollIntegrityConflict || (row.pollObservedDigest != null && row.pollObservedDigest != digest)) {
            return SupplierImportOutcome.CONFLICT
        }
        if (row.importedAt != null) {
            return if (row.submissionDigest == digest) SupplierImportOutcome.ALREADY_IMPORTED else SupplierImportOutcome.CONFLICT
        }
        val observedAt = row.pollObservedAt ?: now()
        val cutoff = minOf(row.deadline, row.deliveryExpiresAt ?: row.deadline)
        if (submission.submittedAt < row.createdAt || submission.submittedAt > observedAt ||
            submission.submittedAt >= cutoff || observedAt >= row.deadline
        ) return SupplierImportOutcome.HELD
        if (assessment.status != "STARTED" || assessment.answerRevision != 0L ||
            assessment.lockedRelease?.id != row.releaseId || !isSupplier(assessment) ||
            responses.countByRiskAssessmentId(row.assessmentId) != 0L ||
            workflow.requirementsFor(assessment).mapNotNull { it.id }.toSet() != answers.map { it.requirementId }.toSet()
        ) return SupplierImportOutcome.CONFLICT

        workflow.recordSupplierSubmission(assessment, row.recipientEmail, row.recipientUserId, answers)
        row.submissionId = submission.submissionId
        row.submissionDigest = digest
        row.submittedAt = submission.submittedAt
        row.importedAt = now()
        row.importedRevision = assessment.answerRevision
        return SupplierImportOutcome.IMPORTED
    }

    private fun approvedQuestions(
        requested: List<ApprovedSupplierQuestion>, requirementIds: Set<Long>
    ): List<MappedQuestion> {
        if (requested.size !in 1..SUPPLIER_MAX_QUESTIONS || requested.size != requirementIds.size ||
            requested.map { it.requirementId }.toSet() != requirementIds
        ) invalid("Approve exactly one public question for each scoped requirement")
        return requested.map {
            MappedQuestion(it.requirementId, UUID.randomUUID().toString(), approvedText(it.text, SUPPLIER_MAX_COMMENT))
        }
    }

    private fun validatedAnswers(
        questions: List<MappedQuestion>, answers: List<SupplierAnswer>
    ): List<AssessmentWorkflowService.Answer>? {
        if (answers.size != questions.size || answers.any {
                !OPAQUE_ID.matches(it.questionKey) || it.comment.orEmpty().length > SUPPLIER_MAX_COMMENT ||
                    it.comment?.contains('\u0000') == true || (it.answer == AnswerType.N_A && it.comment.isNullOrBlank())
            }
        ) return null
        val byKey = answers.associateBy { it.questionKey }
        if (byKey.size != questions.size || questions.any { it.key !in byKey }) return null
        return questions.map { question ->
            val answer = byKey.getValue(question.key)
            AssessmentWorkflowService.Answer(question.requirementId, answer.answer, answer.comment)
        }
    }

    internal fun publicQuestionnaire(row: SupplierAssessmentInvitation) = publicQuestionnaire(row, questions(row))

    private fun publicQuestionnaire(row: SupplierAssessmentInvitation, questions: List<MappedQuestion>) =
        SupplierQuestionnaire(
            SCHEMA_VERSION, row.externalId, row.generation, row.requesterLabel, row.serviceScope,
            row.deadline, row.snapshotHash, questions.map { it.publicQuestion() }
        )

    private fun view(row: SupplierAssessmentInvitation, questions: List<MappedQuestion>, now: Instant) =
        SupplierInvitationStatus(
            publicQuestionnaire(row, questions),
            row.recipientEmail,
            when {
                row.importedAt != null -> "IMPORTED"
                row.revokedAt != null -> "REVOKED"
                now >= row.deadline -> "EXPIRED"
                else -> "PREPARED"
            },
            row.submissionId, row.submittedAt, row.importedAt, row.importedRevision, row.deliveryState, row.deliveryExpiresAt
        )

    private fun questions(row: SupplierAssessmentInvitation) =
        mapper.readValue(row.snapshotJson, Array<MappedQuestion>::class.java).asList()

    private fun approvedText(text: String, maximum: Int): String = text.trim().also {
        if (it.isBlank() || it.length > maximum || '\u0000' in it) invalid("Invalid supplier-facing text")
    }

    private fun isSupplier(assessment: RiskAssessment): Boolean =
        assessment.assessmentBasisType == AssessmentBasisType.ASSET &&
            assets.findById(assessment.assessmentBasisId).orElse(null)?.type.equals("SUPPLIER", ignoreCase = true)

    private fun digest(value: Any): String = supplierPayloadFingerprint(mapper.writeValueAsBytes(value))

    private fun now() = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private fun notFound(): Nothing = throw HttpStatusException(HttpStatus.NOT_FOUND, "Supplier invitation unavailable")
    private fun conflict(message: String): Nothing = throw HttpStatusException(HttpStatus.CONFLICT, message)
    private fun invalid(message: String): Nothing = throw HttpStatusException(HttpStatus.BAD_REQUEST, message)

    companion object {
        private const val SCHEMA_VERSION = 1
        private val OPAQUE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val log = LoggerFactory.getLogger(SupplierAssessmentService::class.java)
    }
}
