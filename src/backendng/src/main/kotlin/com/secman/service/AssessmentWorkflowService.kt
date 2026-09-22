package com.secman.service

import com.secman.domain.*
import com.secman.repository.*
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType

/** Serializes authorization, answer revisions, submission and human acceptance on the assessment row. */
@Singleton
open class AssessmentWorkflowService(
    private val entityManager: EntityManager,
    private val assessments: RiskAssessmentRepository,
    private val assignments: AssessmentAssignmentRepository,
    private val contributions: AssessmentContributionRepository,
    private val acceptances: AssessmentAcceptanceRepository,
    private val responses: ResponseRepository,
    private val users: UserRepository,
    private val apiKeys: McpApiKeyRepository,
    private val requirements: RequirementRepository,
    private val releases: ReleaseRequirementScopeService,
    private val access: RiskAssessmentAccessService
) {
    /** Input is validated as a complete batch before persistence. */
    data class Answer(val requirementId: Long, val answerType: AnswerType, val comment: String?)

    /** Keep every adapter on the pinned questionnaire; legacy records never expose unrelated requirements. */
    fun requirementsFor(assessment: RiskAssessment): List<Requirement> {
        val useCases = assessment.useCases.mapNotNull { it.id }
        if (useCases.isEmpty() && !assessment.authorshipComplete) return responses.findByRiskAssessmentId(assessment.id!!).map { it.requirement }.distinctBy { it.id }
        val release = assessment.lockedRelease?.id
        return if (release != null) releases.requirementsForRelease(release, useCases)
        else useCases.flatMap(requirements::findByUsecaseId).distinctBy { it.id }
    }

    private fun locked(id: Long): RiskAssessment =
        entityManager.find(RiskAssessment::class.java, id, LockModeType.PESSIMISTIC_WRITE)
            ?.also { entityManager.refresh(it, LockModeType.PESSIMISTIC_WRITE) }
            ?: throw HttpStatusException(HttpStatus.NOT_FOUND, "Assessment not found")

    private fun current(authentication: Authentication): Authentication {
        val id = access.actorId(authentication) ?: denied()
        val user = users.findById(id).orElse(null)?.takeIf { it.enabled } ?: denied()
        return Authentication.build(user.username, user.roles.map { it.name }, mapOf("userId" to id, "email" to user.email))
    }

    private fun denied(): Nothing = throw HttpStatusException(HttpStatus.FORBIDDEN, "Assessment action not permitted")

    /** Seed explicit assignments when an assessment is created, without granting resource access. */
    @Transactional
    open fun initialize(assessment: RiskAssessment) {
        check(assignments.findByAssessmentId(assessment.id!!).isEmpty()) { "Assignments already initialized" }
        assignments.save(AssessmentAssignment(assessmentId = assessment.id!!, userId = assessment.assessor.id,
            email = assessment.assessor.email, role = "ASSESSOR"))
        assessment.respondent?.let {
            assignments.save(AssessmentAssignment(assessmentId = assessment.id!!, userId = it.id, email = it.email, role = "RESPONDENT"))
        }
    }

    /** Expose only the current independent decision to authorized reviewers. */
    @Transactional(readOnly = true)
    open fun reviewDecision(assessment: RiskAssessment, authentication: Authentication): AssessmentAcceptance? {
        if (!access.canReview(assessment, current(authentication))) return null
        return acceptances.findByAssessmentId(assessment.id!!)
            .filter { !it.invalidated && it.answerRevision == assessment.answerRevision }.maxByOrNull { it.createdAt }
    }

    /** Keep recipient identities and assignment administration restricted to managers. */
    @Transactional(readOnly = true)
    open fun listAssignments(id: Long, authentication: Authentication): List<AssessmentAssignment> {
        if (!access.isGlobal(current(authentication))) denied()
        return assignments.findByAssessmentId(id)
    }

    @Transactional
    open fun assign(id: Long, authentication: Authentication, userId: Long?, email: String,
                    role: String, requirementIds: Set<Long>): AssessmentAssignment {
        val assessment = locked(id)
        if (!access.isGlobal(current(authentication))) denied()
        require(role in setOf("ASSESSOR", "RESPONDENT")) { "Invalid assignment role" }
        require(requirementIds.size <= 1000 && requirementsFor(assessment).mapNotNull { it.id }.containsAll(requirementIds)) {
            "Requirements must belong to the pinned assessment"
        }
        val user = userId?.let { users.findById(it).orElseThrow { IllegalArgumentException("Unknown user") } }
            ?: email.trim().takeIf { it.isNotBlank() }?.let { users.findByEmailIgnoreCase(it).orElse(null) }
        require(user?.enabled != false) { "Cannot assign a disabled user" }
        require(user != null || (role == "RESPONDENT" && email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")))) {
            "Accountless assignments require a respondent email"
        }
        val recipient = user?.email ?: email.trim().lowercase()
        assignments.findByAssessmentId(id).filter { !it.revoked && (it.userId == userId && userId != null || it.email.equals(recipient, true)) && it.role == role }
            .forEach { it.revoked = true; it.version++; assignments.update(it) }
        assessment.assignmentVersion++
        if (user != null && role == "ASSESSOR") assessment.assessor = user
        if (role == "RESPONDENT" && requirementIds.isEmpty()) assessment.respondent = user
        assessments.update(assessment)
        org.slf4j.LoggerFactory.getLogger(javaClass).info("Assessment assignment actor={} target={} role={} outcome=assigned", access.actorId(authentication), id, role)
        return assignments.save(AssessmentAssignment(assessmentId = id, userId = user?.id, email = recipient,
            role = role, requirementIds = requirementIds.sorted().joinToString(",")))
    }

    /** Invalidate a specific assignment and queued work without erasing contribution history. */
    @Transactional
    open fun revoke(id: Long, assignmentId: Long, authentication: Authentication) {
        val assessment = locked(id)
        if (!access.isGlobal(current(authentication))) denied()
        val assignment = assignments.findById(assignmentId).orElse(null) ?: denied()
        if (assignment.assessmentId != id) denied()
        assignment.revoked = true
        assignment.version++
        if (assignment.role == "RESPONDENT" && assessment.respondent?.id == assignment.userId) assessment.respondent = null
        org.slf4j.LoggerFactory.getLogger(javaClass).info("Assessment assignment actor={} target={} assignment={} outcome=revoked", access.actorId(authentication), id, assignmentId)
        assignments.update(assignment)
        assessment.assignmentVersion++
        assessments.update(assessment)
    }

    /** Validate the capability against current assignment state, including user suspension. */
    fun tokenAssignment(token: AssessmentToken, write: Boolean = false): AssessmentAssignment {
        if (!token.isValid()) denied()
        val assignment = token.assignmentId?.let { assignments.findById(it).orElse(null) } ?: denied()
        if (assignment.userId?.let { users.findById(it).orElse(null)?.enabled != true } == true) denied()
        if (assignment.revoked || assignment.assessmentId != token.riskAssessment.id ||
            assignment.version != token.assignmentVersion || !assignment.email.equals(token.email, true) ||
            assignment.role != "RESPONDENT" || (write && (assignment.submitted || token.riskAssessment.status != "STARTED"))) denied()
        return assignment
    }

    /** Bind new email links to a reviewed assignment rather than an arbitrary recipient. */
    @Transactional
    open fun issueToken(id: Long, email: String, authentication: Authentication): AssessmentToken {
        val assessment = locked(id)
        if (!access.isGlobal(current(authentication))) denied()
        val assignment = assignments.findByAssessmentId(id).singleOrNull {
            !it.revoked && !it.submitted && it.role == "RESPONDENT" && it.email.equals(email, true)
        } ?: throw IllegalArgumentException("Create a respondent assignment for this recipient first")
        check(assessment.status == "STARTED") { "Assessment is not open" }
        return AssessmentToken.create(assignment.email, assessment).also {
            it.assignmentId = assignment.id; it.assignmentVersion = assignment.version
        }
    }

    /** Reload delegation authority before attributing automated writes to a responsible identity. */
    @Transactional(readOnly = true)
    open fun mcpInitiator(apiKeyId: Long, delegateId: Long): Long {
        val key = apiKeys.findById(apiKeyId).orElse(null) ?: denied()
        val delegate = users.findById(delegateId).orElse(null)?.takeIf { it.enabled } ?: denied()
        if (!key.isValid() || !key.delegationEnabled || !key.permitsDelegate(delegateId) ||
            !key.isDelegationAllowedForEmail(delegate.email) || !key.hasPermission(McpPermission.ASSESSMENTS_EXECUTE) ||
            users.findById(key.userId).orElse(null)?.enabled != true) denied()
        return key.userId
    }

    /** Authorize the whole answer batch before recording any response or contribution. */
    @Transactional
    open fun save(id: Long, authentication: Authentication, answers: List<Answer>, initiatingUserId: Long? = null, apiKeyId: Long? = null): List<Response> {
        val assessment = locked(id)
        val actor = current(authentication)
        val assigned = access.activeAssignments(assessment, actor).filter { it.role == "RESPONDENT" && !it.submitted }
        if (assigned.isEmpty()) denied()
        return write(assessment, answers, assigned, access.actorId(actor), actor.attributes["email"].toString(), initiatingUserId, if (apiKeyId == null) "HUMAN" else "MCP", apiKeyId)
    }

    /** Use the same locked answer transaction for accountless email capabilities. */
    @Transactional
    open fun saveToken(token: AssessmentToken, answer: Answer): Response {
        val assessment = locked(token.riskAssessment.id!!)
        token.riskAssessment = assessment
        val assignment = tokenAssignment(token, true)
        return write(assessment, listOf(answer), listOf(assignment), assignment.userId, assignment.email, null, "EMAIL_LINK").single()
    }

    private fun requireUnsubmitted(id: Long, requirementIds: Collection<Long>) {
        if (assignments.findByAssessmentId(id).any { assignment ->
                assignment.role == "RESPONDENT" && assignment.submitted &&
                    requirementIds.any { access.permitsRequirement(assignment, it) }
            }) denied()
    }

    private fun write(assessment: RiskAssessment, answers: List<Answer>, assigned: List<AssessmentAssignment>,
                      actorId: Long?, email: String, initiator: Long?, source: String, apiKeyId: Long? = null): List<Response> {
        check(assessment.status == "STARTED") { "Assessment is not open for editing" }
        require(answers.isNotEmpty() && answers.size <= 200 && answers.map { it.requirementId }.distinct().size == answers.size)
        val scope = requirementsFor(assessment).mapNotNull { it.id }.toSet()
        require(answers.all { it.requirementId in scope && it.comment.orEmpty().length <= 4000 &&
            assigned.any { a -> access.permitsRequirement(a, it.requirementId) } }) { "Answer outside assignment scope" }
        requireUnsubmitted(assessment.id!!, answers.map { it.requirementId })
        assessment.answerRevision++
        val saved = answers.map { input ->
            val existing = responses.findByRiskAssessmentIdAndRequirementId(assessment.id!!, input.requirementId)
            val response = existing ?: Response(answerType = input.answerType, riskAssessment = assessment,
                requirement = requirements.findById(input.requirementId).orElseThrow())
            if (response.source == ResponseSource.AI_GENERATED) response.source = ResponseSource.AI_EDITED
            response.answerType = input.answerType
            response.comment = input.comment?.trim()?.takeIf(String::isNotBlank)
            response.respondentEmail = email
            contributions.save(AssessmentContribution(assessmentId = assessment.id!!, requirementId = input.requirementId,
                actorUserId = actorId, actorEmail = email, initiatingUserId = initiator,
                assignmentId = assigned.first { access.permitsRequirement(it, input.requirementId) }.id, apiKeyId = apiKeyId,
                source = source, revision = assessment.answerRevision))
            if (existing == null) responses.save(response) else responses.update(response)
        }
        assessments.update(assessment)
        return saved
    }

    /** Freeze the current respondent sections without creating an acceptance decision. */
    @Transactional
    open fun submit(id: Long, authentication: Authentication): RiskAssessment {
        val assessment = locked(id)
        val actor = current(authentication)
        val assigned = access.activeAssignments(assessment, actor).filter { it.role == "RESPONDENT" && !it.submitted }
        if (assigned.isEmpty()) denied()
        return submitAssignments(assessment, assigned)
    }

    /** Apply the same submission boundary to accountless respondents. */
    @Transactional
    open fun submitToken(token: AssessmentToken): RiskAssessment {
        val assessment = locked(token.riskAssessment.id!!)
        token.riskAssessment = assessment
        return submitAssignments(assessment, listOf(tokenAssignment(token, true)))
    }

    private fun submitAssignments(assessment: RiskAssessment, assigned: List<AssessmentAssignment>): RiskAssessment {
        check(assessment.status == "STARTED") { "Assessment is not open for submission" }
        val scope = requirementsFor(assessment).mapNotNull { it.id }.toSet()
        val answered = responses.findByRiskAssessmentId(assessment.id!!).filter { it.answerType != null }.mapNotNull { it.requirement.id }.toSet()
        val required = scope.filter { id -> assigned.any { access.permitsRequirement(it, id) } }
        require(required.isNotEmpty() && answered.containsAll(required)) { "Assigned questions are incomplete" }
        assigned.forEach { it.submitted = true; assignments.update(it) }
        val outstanding = assignments.findByAssessmentId(assessment.id!!).any { !it.revoked && it.role == "RESPONDENT" && !it.submitted }
        if (!outstanding && scope.isNotEmpty() && answered.containsAll(scope)) assessment.status = "COMPLETED"
        return assessments.update(assessment)
    }

    /** Explicit reopening invalidates decisions and capabilities while retaining contributor history. */
    @Transactional
    open fun reopen(id: Long, authentication: Authentication): RiskAssessment {
        val assessment = locked(id)
        if (!access.isGlobal(current(authentication))) denied()
        assessment.status = "STARTED"
        assessment.assignmentVersion++
        assignments.findByAssessmentId(id).forEach {
            it.submitted = false; it.reminderSentAt = null; it.version++; assignments.update(it)
        }
        acceptances.findByAssessmentId(id).filter { !it.invalidated }.forEach { it.invalidated = true; acceptances.update(it) }
        return assessments.update(assessment)
    }

    /** Reject queued work when its actor, key, assignment version, or assessment state changed. */
    @Transactional
    open fun authorizeAi(job: AiSuggestionJob): RiskAssessment {
        val assessment = locked(job.riskAssessmentId)
        val actor = users.findById(job.triggeredByUserId).orElse(null)?.takeIf { it.enabled } ?: denied()
        if (actor.roles.none { it == User.Role.ADMIN || it == User.Role.SECCHAMPION } ||
            assessment.status != "STARTED" || assessment.assignmentVersion != job.assignmentVersion) denied()
        job.apiKeyId?.let { keyId ->
            val key = apiKeys.findById(keyId).orElse(null) ?: denied()
            if (!key.isValid() || !key.permitsDelegate(actor.id!!) || !key.hasPermission(McpPermission.ASSESSMENTS_WRITE) ||
                !key.isDelegationAllowedForEmail(actor.email) || users.findById(key.userId).orElse(null)?.enabled != true) denied()
        }
        return assessment
    }

    /** Record responsible AI identities and preserve submitted or human-edited answers. */
    @Transactional
    open fun saveAi(job: AiSuggestionJob, requirementId: Long, answer: AnswerType, rationale: String, suggestionId: Long?) {
        val assessment = authorizeAi(job)
        require(requirementId in requirementsFor(assessment).mapNotNull { it.id })
        requireUnsubmitted(assessment.id!!, listOf(requirementId))
        val existing = responses.findByRiskAssessmentIdAndRequirementId(assessment.id!!, requirementId)
        if (existing != null && existing.source != ResponseSource.AI_GENERATED) return
        val actor = users.findById(job.triggeredByUserId).orElseThrow()
        assessment.answerRevision++
        val response = existing ?: Response(answerType = answer, riskAssessment = assessment,
            requirement = requirements.findById(requirementId).orElseThrow())
        response.answerType = answer
        response.comment = rationale.take(4000)
        response.respondentEmail = actor.email
        response.source = ResponseSource.AI_GENERATED
        response.aiSuggestionId = suggestionId
        contributions.save(AssessmentContribution(assessmentId = assessment.id!!, requirementId = requirementId,
            actorUserId = actor.id, actorEmail = actor.email, initiatingUserId = job.initiatingUserId,
            source = "AI", revision = assessment.answerRevision, jobId = job.id, apiKeyId = job.apiKeyId))
        if (existing == null) responses.save(response) else responses.update(response)
        assessments.update(assessment)
    }

    /** Treat evidence changes as contributions so uploaders cannot approve their own work. */
    @Transactional
    open fun recordEvidenceChange(id: Long, requirementId: Long, user: User) {
        val assessment = locked(id)
        val actor = current(Authentication.build(user.username, user.roles.map { it.name }, mapOf("userId" to user.id!!)))
        val assigned = access.activeAssignments(assessment, actor).filter { it.role == "RESPONDENT" && !it.submitted }
        if (assessment.status != "STARTED" || assigned.none { access.permitsRequirement(it, requirementId) } ||
            requirementId !in requirementsFor(assessment).mapNotNull { it.id }) denied()
        requireUnsubmitted(id, listOf(requirementId))
        assessment.answerRevision++
        contributions.save(AssessmentContribution(assessmentId = id, requirementId = requirementId,
            actorUserId = user.id, actorEmail = user.email,
            assignmentId = assigned.first { access.permitsRequirement(it, requirementId) }.id,
            source = "EVIDENCE", revision = assessment.answerRevision))
        assessments.update(assessment)
    }

    /** Deleting drafts is an authored change and cannot bypass a submitted section. */
    @Transactional
    open fun clearAiDrafts(id: Long, authentication: Authentication): Long {
        val assessment = locked(id)
        val actor = current(authentication)
        if (!access.isGlobal(actor) || assessment.status != "STARTED") denied()
        if (assignments.findByAssessmentId(id).any { it.role == "RESPONDENT" && it.submitted }) denied()
        val deleted = responses.deleteLowConfidenceAiResponses(id)
        if (deleted > 0) {
            assessment.answerRevision++
            contributions.save(AssessmentContribution(assessmentId = id, requirementId = 0,
                actorUserId = access.actorId(actor), actorEmail = actor.attributes["email"].toString(),
                source = "AI_DRAFT_REMOVAL", revision = assessment.answerRevision))
            assessments.update(assessment)
        }
        return deleted
    }

    /** Recheck both recipient scope and sending identity at the delivery boundary. */
    @Transactional(readOnly = true)
    open fun authorizeReminder(id: Long, actorId: Long, email: String, apiKeyId: Long? = null, scheduled: Boolean = false) {
        val assessment = assessments.findById(id).orElse(null) ?: denied()
        val actor = users.findById(actorId).orElse(null)?.takeIf { it.enabled } ?: denied()
        val auth = Authentication.build(actor.username, actor.roles.map { it.name }, mapOf("userId" to actorId))
        if (assessment.status != "STARTED" || !access.canReview(assessment, auth) || (scheduled && !actor.serviceAccount)) denied()
        if (assignments.findByAssessmentId(id).none { !it.revoked && !it.submitted && it.role == "RESPONDENT" && it.email.equals(email, true) }) denied()
        apiKeyId?.let {
            val key = apiKeys.findById(it).orElse(null) ?: denied()
            if (!key.isValid() || !key.permitsDelegate(actorId) || !key.hasPermission(McpPermission.NOTIFICATIONS_SEND) ||
                !key.isDelegationAllowedForEmail(actor.email) || users.findById(key.userId).orElse(null)?.enabled != true) denied()
        }
    }

    /** Prepare one respondent section so reminders never disclose other sections or recipients. */
    @Transactional(readOnly = true)
    open fun prepareReminder(id: Long, authentication: Authentication, email: String? = null): RiskAssessmentMcpService.OutstandingReminder {
        val assessment = assessments.findById(id).orElseThrow { NoSuchElementException("Assessment not found") }
        val actor = current(authentication)
        if (!access.canReview(assessment, actor)) denied()
        check(assessment.status == "STARTED") { "Only an ongoing assessment can be notified" }
        val recipients = assignments.findByAssessmentId(id).filter { !it.revoked && !it.submitted && it.role == "RESPONDENT" }
        val assignment = if (email.isNullOrBlank()) recipients.singleOrNull()
            else recipients.singleOrNull { it.email.equals(email.trim(), true) }
        requireNotNull(assignment) { "Select one active respondent email" }
        val scope = requirementsFor(assessment).filter { access.permitsRequirement(assignment, it.id!!) }
        val answered = responses.findByRiskAssessmentId(id).filter { it.answerType != null }.map { it.requirement.id }.toSet()
        return RiskAssessmentMcpService.OutstandingReminder(id, assignment.email, assessment.awsAccount?.awsAccountId,
            assessment.useCases.map { it.name }.sorted(), assessment.endDate, scope.count { it.id !in answered }, scope.size,
            assignment.id!!, assignment.version, assignment.userId == null)
    }

    /** Only an existing authorized accountless assignment can receive an emailed capability. */
    @Transactional
    open fun issueReminderToken(reminder: RiskAssessmentMcpService.OutstandingReminder, actorId: Long, apiKeyId: Long?): AssessmentToken {
        val assessment = locked(reminder.assessmentId)
        authorizeReminder(assessment.id!!, actorId, reminder.recipientEmail, apiKeyId)
        val assignment = assignments.findById(reminder.assignmentId).orElse(null) ?: denied()
        if (assignment.revoked || assignment.submitted || assignment.assessmentId != assessment.id ||
            assignment.version != reminder.assignmentVersion || !assignment.email.equals(reminder.recipientEmail, true)) denied()
        return AssessmentToken.create(assignment.email, assessment).also {
            it.assignmentId = assignment.id; it.assignmentVersion = assignment.version
        }
    }

    /** Serialize independent human acceptance against answer changes and reopening. */
    @Transactional
    open fun accept(id: Long, authentication: Authentication, expectedRevision: Long, rationale: String): AssessmentAcceptance {
        val assessment = locked(id)
        val actor = current(authentication)
        val actorId = access.actorId(actor) ?: denied()
        if (users.findById(actorId).orElseThrow().serviceAccount || !access.canReview(assessment, actor)) denied()
        check(assessment.status == "COMPLETED" && assessment.authorshipComplete && assessment.answerRevision == expectedRevision) {
            "Acceptance requires submitted answers, complete authorship and the current revision"
        }
        require(rationale.isNotBlank() && rationale.length <= 4000)
        if (contributions.findByAssessmentId(id).any {
            it.actorUserId == actorId || it.initiatingUserId == actorId || it.actorEmail.equals(actor.attributes["email"].toString(), true)
        }) denied()
        check(acceptances.findByAssessmentId(id).none { !it.invalidated && it.answerRevision == expectedRevision }) { "Revision already accepted" }
        org.slf4j.LoggerFactory.getLogger(javaClass).info("Assessment acceptance actor={} target={} revision={} outcome=accepted", actorId, id, expectedRevision)
        return acceptances.save(AssessmentAcceptance(assessmentId = id, reviewerUserId = actorId,
            answerRevision = expectedRevision, rationale = rationale.trim()))
    }
}
