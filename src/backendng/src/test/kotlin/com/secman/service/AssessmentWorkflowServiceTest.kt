package com.secman.service

import com.secman.domain.*
import com.secman.repository.*
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.mockk.*
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional

/** Exercises assignment, authorship, capability, and submission denial boundaries. */
class AssessmentWorkflowServiceTest {
    private val entityManager = mockk<EntityManager>(relaxed = true)
    private val assessments = mockk<RiskAssessmentRepository>(relaxed = true)
    private val assignments = mockk<AssessmentAssignmentRepository>(relaxed = true)
    private val contributions = mockk<AssessmentContributionRepository>(relaxed = true)
    private val acceptances = mockk<AssessmentAcceptanceRepository>(relaxed = true)
    private val responses = mockk<ResponseRepository>(relaxed = true)
    private val users = mockk<UserRepository>(relaxed = true)
    private val keys = mockk<McpApiKeyRepository>(relaxed = true)
    private val requirements = mockk<RequirementRepository>(relaxed = true)
    private val releases = mockk<ReleaseRequirementScopeService>(relaxed = true)
    private val access = RiskAssessmentAccessService(assignments, mockk(relaxed = true))
    private val service = AssessmentWorkflowService(entityManager, assessments, assignments, contributions, acceptances,
        responses, users, keys, requirements, releases, access)
    private val user = User(id = 1, username = "person", email = "person@example.test", passwordHash = "x")
    private val assessment = RiskAssessment(id = 10, startDate = LocalDate.now(), endDate = LocalDate.now(),
        assessmentBasisType = AssessmentBasisType.ASSET, assessmentBasisId = 20, assessor = user, requestor = user,
        useCases = mutableSetOf(UseCase(id = 2, name = "SaaS")))
    private val assignment = AssessmentAssignment(id = 3, assessmentId = 10, userId = 1, email = user.email,
        role = "RESPONDENT", requirementIds = "11")
    private val requirement = Requirement(id = 11, shortreq = "Question")
    private val auth = Authentication.build(user.username, listOf("USER"), mapOf("userId" to 1L, "email" to user.email))
    private fun answer(id: Long = 11) = AssessmentWorkflowService.Answer(id, AnswerType.YES, "Evidence")

    /** Keep repository fixtures explicit so denied paths cannot succeed through relaxed mocks. */
    @BeforeEach fun setup() {
        every { entityManager.find(RiskAssessment::class.java, 10L, LockModeType.PESSIMISTIC_WRITE) } returns assessment
        every { users.findById(1) } returns Optional.of(user)
        every { assignments.findByAssessmentId(10) } returns listOf(assignment)
        every { assignments.findById(3) } returns Optional.of(assignment)
        every { requirements.findByUsecaseId(2) } returns listOf(requirement, Requirement(id = 12, shortreq = "Other"))
        every { requirements.findById(11) } returns Optional.of(requirement)
        every { responses.findByRiskAssessmentIdAndRequirementId(any(), any()) } returns null
        every { responses.save(any()) } answers { firstArg() }
        every { assessments.update(any()) } answers { firstArg() }
        every { acceptances.save(any()) } answers { firstArg() }
        every { acceptances.update(any()) } answers { firstArg() }
        every { assignments.update(any()) } answers { firstArg() }
        every { contributions.save(any()) } answers { firstArg() }
    }

    @Test fun `mixed authorized and unauthorized batch writes nothing`() {
        assertThrows(IllegalArgumentException::class.java) { service.save(10, auth, listOf(answer(), answer(12))) }
        verify(exactly = 0) { responses.save(any()); contributions.save(any()) }
        assertEquals(0, assessment.answerRevision)
    }
    @Test fun `answer records stable contributor and revision`() {
        service.save(10, auth, listOf(answer()), 7, 5)
        verify { contributions.save(match { it.actorUserId == 1L && it.initiatingUserId == 7L && it.assignmentId == 3L && it.apiKeyId == 5L && it.source == "MCP" && it.revision == 1L }) }
        assertEquals(1, assessment.answerRevision)
    }
    @Test fun `submitted assessment rejects all answer writes`() {
        assessment.status = "COMPLETED"
        assertThrows(IllegalStateException::class.java) { service.save(10, auth, listOf(answer())) }
        verify(exactly = 0) { responses.save(any()) }
    }
    @Test fun `revoked assignment rejects writes even for global role`() {
        user.roles.add(User.Role.ADMIN)
        assignment.revoked = true
        assertThrows(HttpStatusException::class.java) { service.save(10, auth, listOf(answer())) }
    }
    @Test fun `token fails after assignment version changes`() {
        val token = AssessmentToken(token = "capability", email = user.email, expiresAt = LocalDateTime.now().plusDays(1),
            riskAssessment = assessment, assignmentId = 3, assignmentVersion = 1)
        assertEquals(assignment, service.tokenAssignment(token))
        assignment.version++
        assertThrows(HttpStatusException::class.java) { service.tokenAssignment(token) }
    }
    @Test fun `unbound legacy token fails closed`() {
        val token = AssessmentToken.create(user.email, assessment)
        assertThrows(HttpStatusException::class.java) { service.tokenAssignment(token) }
    }
    @Test fun `admin who contributed cannot accept after reassignment`() {
        user.roles.add(User.Role.ADMIN)
        assessment.status = "COMPLETED"
        every { contributions.findByAssessmentId(10) } returns listOf(AssessmentContribution(assessmentId = 10,
            requirementId = 11, actorUserId = 1, actorEmail = "old@example.test", source = "HUMAN", revision = 0))
        assertThrows(HttpStatusException::class.java) { service.accept(10, auth, 0, "Reviewed") }
        verify(exactly = 0) { acceptances.save(any()) }
    }
    @Test fun `initiator cannot accept delegated AI contribution`() {
        user.roles.add(User.Role.SECCHAMPION)
        assessment.status = "COMPLETED"
        every { contributions.findByAssessmentId(10) } returns listOf(AssessmentContribution(assessmentId = 10,
            requirementId = 11, actorUserId = 7, initiatingUserId = 1, actorEmail = "bot@example.test", source = "AI", revision = 0))
        assertThrows(HttpStatusException::class.java) { service.accept(10, auth, 0, "Reviewed") }
    }
    @Test fun `legacy incomplete authorship prevents acceptance`() {
        user.roles.add(User.Role.ADMIN)
        assessment.status = "COMPLETED"
        assessment.authorshipComplete = false
        assertThrows(IllegalStateException::class.java) { service.accept(10, auth, 0, "Reviewed") }
    }
    @Test fun `stale answer revision prevents acceptance`() {
        user.roles.add(User.Role.ADMIN)
        assessment.status = "COMPLETED"
        assessment.answerRevision = 2
        assertThrows(IllegalStateException::class.java) { service.accept(10, auth, 1, "Reviewed") }
    }
    @Test fun `independent reviewer accepts only submitted revision`() {
        user.roles.add(User.Role.SECCHAMPION)
        assessment.status = "COMPLETED"
        val accepted = service.accept(10, auth, 0, "Evidence checked")
        assertEquals(1L, accepted.reviewerUserId)
        assertEquals(0L, accepted.answerRevision)
    }
    @Test fun `reopening revokes old tokens and invalidates acceptance`() {
        user.roles.add(User.Role.ADMIN)
        assessment.status = "COMPLETED"
        assignment.submitted = true
        val accepted = AssessmentAcceptance(assessmentId = 10, reviewerUserId = 8, answerRevision = 0, rationale = "Reviewed")
        every { acceptances.findByAssessmentId(10) } returns listOf(accepted)
        service.reopen(10, auth)
        assertEquals("STARTED", assessment.status)
        assertFalse(assignment.submitted)
        assertEquals(2L, assignment.version)
        assertTrue(accepted.invalidated)
    }
    @Test fun `removed global role prevents queued AI work`() {
        val job = AiSuggestionJob(riskAssessmentId = 10, triggeredByUserId = 1, model = "model",
            scope = AiSuggestionScope.WHOLE_ASSESSMENT, assignmentVersion = 0)
        assertThrows(HttpStatusException::class.java) { service.authorizeAi(job) }
    }
    @Test fun `assignment changes invalidate in flight AI result`() {
        user.roles.add(User.Role.ADMIN)
        val job = AiSuggestionJob(riskAssessmentId = 10, triggeredByUserId = 1, model = "model",
            scope = AiSuggestionScope.WHOLE_ASSESSMENT, assignmentVersion = 0)
        assessment.assignmentVersion = 1
        assertThrows(HttpStatusException::class.java) { service.authorizeAi(job) }
    }
    @Test fun `queued reminder rejects changed delegation domain`() {
        user.roles.add(User.Role.ADMIN)
        every { assessments.findById(10) } returns Optional.of(assessment)
        every { keys.findById(5) } returns Optional.of(McpApiKey(id = 5, keyId = "test-reminder-key", keyHash = "x",
            name = "reminder", userId = 1, permissions = "NOTIFICATIONS_SEND", delegationEnabled = true,
            allowedDelegationDomains = "@other.test"))
        assertThrows(HttpStatusException::class.java) { service.authorizeReminder(10, 1, user.email, 5) }
    }
    @Test fun `queued reminder rejects completed recipient assignment`() {
        user.roles.add(User.Role.ADMIN)
        assignment.submitted = true
        every { assessments.findById(10) } returns Optional.of(assessment)
        assertThrows(HttpStatusException::class.java) { service.authorizeReminder(10, 1, user.email) }
    }
    @Test fun `scheduler cannot impersonate an ordinary human account`() {
        user.roles.add(User.Role.ADMIN)
        every { assessments.findById(10) } returns Optional.of(assessment)
        assertThrows(HttpStatusException::class.java) { service.authorizeReminder(10, 1, user.email, scheduled = true) }
    }

    @Test fun `AI cannot alter a submitted section while other sections remain open`() {
        user.roles.add(User.Role.ADMIN)
        assignment.submitted = true
        val job = AiSuggestionJob(riskAssessmentId = 10, triggeredByUserId = 1, model = "model",
            scope = AiSuggestionScope.WHOLE_ASSESSMENT, assignmentVersion = 0)
        assertThrows(HttpStatusException::class.java) { service.saveAi(job, 11, AnswerType.YES, "draft", null) }
        verify(exactly = 0) { responses.save(any()); responses.update(any()) }
        assertThrows(HttpStatusException::class.java) { service.clearAiDrafts(10, auth) }
        verify(exactly = 0) { responses.deleteLowConfidenceAiResponses(any()) }
    }
    @Test fun `bound email capability is invalid when assigned user is disabled`() {
        user.enabled = false
        val token = AssessmentToken(token = "capability", email = user.email, expiresAt = LocalDateTime.now().plusDays(1),
            riskAssessment = assessment, assignmentId = 3, assignmentVersion = 1)
        assertThrows(HttpStatusException::class.java) { service.tokenAssignment(token) }
    }

    @Test fun `overlapping new assignment cannot edit a submitted or revoked submitted section`() {
        val frozen = AssessmentAssignment(id = 9, assessmentId = 10, userId = 2, email = "other@example.test",
            role = "RESPONDENT", requirementIds = "11", submitted = true, revoked = true)
        every { assignments.findByAssessmentId(10) } returns listOf(assignment, frozen)
        assertThrows(HttpStatusException::class.java) { service.save(10, auth, listOf(answer())) }
        assertThrows(HttpStatusException::class.java) { service.recordEvidenceChange(10, 11, user) }
        verify(exactly = 0) { responses.save(any()); contributions.save(any()) }
    }

}
