package com.secman.service

import com.secman.domain.*
import com.secman.dto.*
import com.secman.repository.*
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.mockk.every
import io.mockk.mockk
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@MicronautTest(environments = ["test"], transactional = false, startApplication = false)
class SupplierAssessmentIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var service: SupplierAssessmentService
    @Inject lateinit var workflow: AssessmentWorkflowService
    @Inject lateinit var users: UserRepository
    @Inject lateinit var assessments: RiskAssessmentRepository
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var useCases: UseCaseRepository
    @Inject lateinit var requirements: RequirementRepository
    @Inject lateinit var releases: ReleaseRepository
    @Inject lateinit var snapshots: RequirementSnapshotRepository
    @Inject lateinit var responses: ResponseRepository
    @Inject lateinit var contributions: AssessmentContributionRepository
    @Inject lateinit var invitations: SupplierAssessmentInvitationRepository
    @Inject lateinit var pollState: SupplierSubmissionPollState

    @Test
    fun `incident hold invalidates a prior acceptance and preserves the exact imported answers`() {
        val fixture = fixture()
        val invitation = service.create(fixture.id, fixture.manager, fixture.request).questionnaire
        assertEquals(SupplierImportOutcome.IMPORTED, service.importSubmission(fixture.id, invitation.invitationId, submission(invitation)))
        workflow.accept(fixture.id, fixture.manager, 1, "Original review")

        val denied = assertThrows(HttpStatusException::class.java) {
            service.placeIncidentHold(fixture.id, invitation.invitationId, 1, fixture.contactWithoutManagerRole)
        }
        assertEquals(HttpStatus.FORBIDDEN, denied.status)
        val stale = assertThrows(HttpStatusException::class.java) {
            service.placeIncidentHold(fixture.id, invitation.invitationId, 2, fixture.manager)
        }
        assertEquals(HttpStatus.CONFLICT, stale.status)
        service.placeIncidentHold(fixture.id, invitation.invitationId, 1, fixture.manager)
        val heldAt = pollState.status(fixture.id, fixture.manager)!!.incidentHeldAt
        assertNotNull(heldAt)
        service.placeIncidentHold(fixture.id, invitation.invitationId, 1, fixture.manager)
        assertEquals(heldAt, pollState.status(fixture.id, fixture.manager)!!.incidentHeldAt)
        assertNull(workflow.reviewDecision(assessments.findById(fixture.id).orElseThrow(), fixture.manager))
        assertEquals(2, responses.findByRiskAssessmentId(fixture.id).size)
        assertEquals(HttpStatus.CONFLICT, assertThrows(HttpStatusException::class.java) {
            workflow.accept(fixture.id, fixture.manager, 1, "Another review")
        }.status)
    }

    @Test
    fun `paused intake does not claim pending work`() {
        val repository = mockk<SupplierAssessmentInvitationRepository>()
        val state = SupplierSubmissionPollState(repository, workflow, enabled = true, intakePaused = true)
        assertNull(state.claim(1L, 2L))
    }

    @Test
    fun `one bad portal response does not block another invitation and conflicts preserve imported answers`() {
        val bad = fixture()
        val good = fixture()
        val badInvite = preparePoll(bad)
        val goodInvite = preparePoll(good)
        val portal = mockk<SupplierPortalClient>()
        every { portal.ready() } returns true
        every { portal.readSubmission(badInvite.invitationId) } returns SupplierPortalRead.Invalid
        val original = submission(goodInvite)
        every { portal.readSubmission(goodInvite.invitationId) } returns SupplierPortalRead.Completed(original)
        val worker = SupplierSubmissionPoller(invitations, pollState, service, portal)

        worker.pollDue()
        val invalid = pollState.status(bad.id, bad.manager)!!
        assertEquals("INVALID", invalid.pollState)
        assertNull(invalid.observedAt)
        assertEquals(0, responses.findByRiskAssessmentId(bad.id).size)
        val imported = pollState.status(good.id, good.manager)!!
        assertEquals("IMPORTED", imported.pollState)
        assertNotNull(imported.observedAt)
        assertEquals(1L, imported.importedRevision)
        assertNull(imported.nextCheckAt)

        markDue(good.id)
        val changed = original.copy(answers = original.answers.map { it.copy(comment = "Different version") })
        every { portal.readSubmission(goodInvite.invitationId) } returns SupplierPortalRead.Completed(changed)
        worker.pollDue()
        val conflict = pollState.status(good.id, good.manager)!!
        assertEquals("CONFLICT", conflict.pollState)
        assertTrue(conflict.integrityConflict)
        assertEquals(imported.importedAt, conflict.importedAt)
        assertTrue(responses.findByRiskAssessmentId(good.id).all { it.comment == "  Supplier evidence  " })
        val blocked = assertThrows(HttpStatusException::class.java) {
            workflow.accept(good.id, good.manager, 1, "Review the conflicting response")
        }
        assertEquals(HttpStatus.CONFLICT, blocked.status)
    }

    @Test
    fun `a portal outage schedules another check and a late claim is held`() {
        val fixture = fixture()
        val invite = preparePoll(fixture)
        val portal = mockk<SupplierPortalClient>()
        every { portal.ready() } returns true
        every { portal.readSubmission(invite.invitationId) } returns SupplierPortalRead.Unavailable
        val worker = SupplierSubmissionPoller(invitations, pollState, service, portal)
        worker.pollDue()
        val unavailable = pollState.status(fixture.id, fixture.manager)!!
        assertEquals("UNAVAILABLE", unavailable.pollState)
        assertNotNull(unavailable.nextCheckAt)
        assertNull(unavailable.observedAt)

        markDue(fixture.id)
        val late = submission(invite).copy(submittedAt = invite.deadline)
        every { portal.readSubmission(invite.invitationId) } returns SupplierPortalRead.Completed(late)
        worker.pollDue()
        val held = pollState.status(fixture.id, fixture.manager)!!
        assertEquals("HELD", held.pollState)
        assertNull(held.nextCheckAt)
        assertNotNull(held.observedAt)
        assertNull(held.importedAt)
    }

    @Test
    fun `an assigned reviewer can read collection status and other users cannot`() {
        val fixture = fixture()
        val invite = preparePoll(fixture)
        val viewer = fixture.contactWithoutManagerRole
        val denied = assertThrows(HttpStatusException::class.java) { pollState.status(fixture.id, viewer) }
        assertEquals(HttpStatus.NOT_FOUND, denied.status)
        val userId = viewer.attributes.getValue("userId") as Long
        workflow.assign(fixture.id, fixture.manager, userId, "", "ASSESSOR", emptySet())
        val status = pollState.status(fixture.id, viewer)!!
        assertEquals(invite.invitationId, status.invitationId)
        assertFalse(status.canRetry)
        val deniedRetry = assertThrows(HttpStatusException::class.java) {
            pollState.requestRetry(fixture.id, invite.invitationId, viewer)
        }
        assertEquals(HttpStatus.FORBIDDEN, deniedRetry.status)
        service.revoke(fixture.id, invite.invitationId, fixture.manager)
    }

    @Test
    fun `lease recovery retains the original observation for an interrupted import`() {
        val fixture = fixture()
        val invitation = preparePoll(fixture)
        val rowId = invitations.findFirstByAssessmentIdOrderByGenerationDesc(fixture.id)!!.id!!
        val first = requireNotNull(pollState.claim(rowId, fixture.id))
        val payload = submission(invitation)
        assertEquals(SupplierObservation.READY, service.observeSubmission(first, payload))
        val observed = pollState.status(fixture.id, fixture.manager)!!
        assertEquals("OBSERVED", observed.pollState)
        assertNotNull(observed.observedAt)
        assertNull(observed.importedAt)

        markDue(fixture.id)
        val restarted = requireNotNull(pollState.claim(rowId, fixture.id))
        assertNotEquals(first.lease, restarted.lease)
        assertEquals(SupplierImportOutcome.REJECTED, service.importSubmission(fixture.id, invitation.invitationId, payload, first.lease))
        val portal = mockk<SupplierPortalClient>()
        every { portal.ready() } returns true
        every { portal.readSubmission(invitation.invitationId) } returns SupplierPortalRead.Completed(payload)
        SupplierSubmissionPoller(invitations, pollState, service, portal).poll(restarted)
        val imported = pollState.status(fixture.id, fixture.manager)!!
        assertEquals("IMPORTED", imported.pollState)
        assertEquals(observed.observedAt, imported.observedAt)
        assertEquals(1L, imported.importedRevision)
    }

    private fun preparePoll(fixture: Fixture): SupplierQuestionnaire {
        val invitation = service.create(fixture.id, fixture.manager, fixture.request).questionnaire
        val row = invitations.findFirstByAssessmentIdOrderByGenerationDesc(fixture.id)!!
        row.deliveryState = "SENT"
        row.pollDueAt = Instant.EPOCH
        invitations.update(row)
        return invitation
    }

    private fun markDue(assessmentId: Long) {
        val row = invitations.findFirstByAssessmentIdOrderByGenerationDesc(assessmentId)!!
        row.pollDueAt = Instant.EPOCH
        invitations.update(row)
    }

    @Test
    fun `pin approved questions and import a single reviewed revision under concurrent retries`() {
        val fixture = fixture(pinRelease = false)
        val invitation = service.create(fixture.id, fixture.manager, fixture.request).questionnaire
        assertEquals(fixture.releaseId, assessments.findById(fixture.id).orElseThrow().lockedRelease?.id)
        assertEquals(fixture.request.questions.map { it.text }, invitation.questions.map { it.text })
        assertEquals(invitation, service.latest(fixture.id, fixture.manager).questionnaire)
        val submission = submission(invitation)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = listOf(submission, submission.copy(answers = submission.answers.reversed())).map { attempt ->
                executor.submit<SupplierImportOutcome> {
                    check(start.await(10, TimeUnit.SECONDS))
                    service.importSubmission(fixture.id, invitation.invitationId, attempt)
                }
            }
            start.countDown()
            assertEquals(
                setOf(SupplierImportOutcome.IMPORTED, SupplierImportOutcome.ALREADY_IMPORTED),
                attempts.map { it.get(30, TimeUnit.SECONDS) }.toSet()
            )
        } finally {
            executor.shutdownNow()
        }

        val changed = submission.copy(answers = submission.answers.map { it.copy(comment = "Different text") })
        assertEquals(SupplierImportOutcome.CONFLICT, service.importSubmission(fixture.id, invitation.invitationId, changed))
        val persisted = responses.findByRiskAssessmentId(fixture.id)
        assertEquals(2, persisted.size)
        assertTrue(persisted.all { it.source == ResponseSource.SUPPLIER_PORTAL && it.comment == "  Supplier evidence  " })
        assertTrue(contributions.findByAssessmentId(fixture.id).all {
            it.source == "SUPPLIER_PORTAL" && it.revision == 1L && it.actorEmail == fixture.request.recipientEmail
        })
        val completed = assessments.findById(fixture.id).orElseThrow()
        assertEquals("COMPLETED", completed.status)
        assertEquals(1L, completed.answerRevision)
        assertEquals("IMPORTED", service.latest(fixture.id, fixture.manager).state)
        assertNull(workflow.reviewDecision(completed, fixture.manager))
        assertThrows(HttpStatusException::class.java) {
            workflow.accept(fixture.id, fixture.contact, 1, "The contributor cannot accept")
        }
        assertEquals(1L, workflow.accept(fixture.id, fixture.manager, 1, "Independent review").answerRevision)
    }

    @Test
    fun `manager authorization and generations protect a supplier assessment`() {
        val fixture = fixture()
        val forbidden = assertThrows(HttpStatusException::class.java) {
            service.create(fixture.id, fixture.contactWithoutManagerRole, fixture.request)
        }
        assertEquals(HttpStatus.FORBIDDEN, forbidden.status)
        val first = service.create(fixture.id, fixture.manager, fixture.request).questionnaire
        assertThrows(HttpStatusException::class.java) {
            workflow.assign(fixture.id, fixture.manager, null, fixture.request.recipientEmail, "RESPONDENT", emptySet())
        }
        assertThrows(HttpStatusException::class.java) { workflow.reopen(fixture.id, fixture.manager) }
        service.revoke(fixture.id, first.invitationId, fixture.manager)
        assertEquals("REVOKED", service.latest(fixture.id, fixture.manager).state)
        assertEquals(
            SupplierImportOutcome.REJECTED,
            service.importSubmission(fixture.id, first.invitationId, submission(first))
        )
        val next = service.create(fixture.id, fixture.manager, fixture.request).questionnaire
        assertEquals(first.generation + 1, next.generation)
        assertNotEquals(first.invitationId, next.invitationId)
        assertEquals(first.snapshotHash, next.snapshotHash)
        assertEquals(first.questions, next.questions)
        assertThrows(HttpStatusException::class.java) { service.revoke(fixture.id, first.invitationId, fixture.manager) }
        assertEquals(SupplierImportOutcome.REJECTED, service.importSubmission(fixture.id, next.invitationId, submission(first)))
        assertEquals("PREPARED", service.latest(fixture.id, fixture.manager).state)
    }

    @Test
    fun `invalid snapshots and unsafe submissions leave the assessment untouched`() {
        val fixture = fixture()
        assertThrows(HttpStatusException::class.java) {
            service.create(fixture.id, fixture.manager, fixture.request.copy(questions = fixture.request.questions.take(1)))
        }
        val invitation = service.create(fixture.id, fixture.manager, fixture.request).questionnaire
        val submission = submission(invitation)
        val duplicate = submission.answers.first()
        val invalid = listOf(
            submission.copy(snapshotHash = "wrong"),
            submission.copy(generation = invitation.generation + 1),
            submission.copy(answers = listOf(duplicate, duplicate)),
            submission.copy(answers = listOf(duplicate.copy(answer = AnswerType.N_A, comment = null), submission.answers.last())),
            submission.copy(answers = listOf(duplicate.copy(comment = "x".repeat(4001)), submission.answers.last()))
        )
        invalid.forEach {
            assertEquals(SupplierImportOutcome.REJECTED, service.importSubmission(fixture.id, invitation.invitationId, it))
        }
        assertEquals(
            SupplierImportOutcome.HELD,
            service.importSubmission(fixture.id, invitation.invitationId, submission.copy(submittedAt = invitation.deadline))
        )
        val other = fixture()
        val otherInvitation = service.create(other.id, other.manager, other.request).questionnaire
        assertEquals(
            SupplierImportOutcome.REJECTED,
            service.importSubmission(other.id, otherInvitation.invitationId, submission)
        )
        assertEquals(0L, responses.countByRiskAssessmentId(fixture.id))
        assertEquals(0L, responses.countByRiskAssessmentId(other.id))
        assertEquals(0L, assessments.findById(fixture.id).orElseThrow().answerRevision)
    }

    private fun fixture(pinRelease: Boolean = true): Fixture {
        val suffix = UUID.randomUUID().toString().take(8)
        val manager = users.save(TestDataFactory.createAdminUser("manager-$suffix", "manager-$suffix@example.test"))
        val contact = users.save(TestDataFactory.createAdminUser("contact-$suffix", "contact-$suffix@example.test"))
        val unprivileged = users.save(TestDataFactory.createRegularUser("other-$suffix", "other-$suffix@example.test"))
        val useCase = useCases.save(UseCase(name = "Supplier $suffix"))
        val requirements = (1..2).map { number ->
            requirements.save(Requirement(
                internalId = "SUP-$number-$suffix", shortreq = "Internal control $number", usecases = mutableSetOf(useCase)
            ))
        }
        val release = releases.save(Release(
            version = "supplier-$suffix", name = "Supplier testing",
            status = if (pinRelease) Release.ReleaseStatus.ARCHIVED else Release.ReleaseStatus.ACTIVE
        ))
        requirements.forEach { snapshots.save(RequirementSnapshot.fromRequirement(it, release)) }
        val asset = assets.save(TestDataFactory.createAsset(name = "Supplier $suffix", type = "SUPPLIER"))
        val assessment = assessments.save(RiskAssessment(
            startDate = LocalDate.now(), endDate = LocalDate.now().plusDays(1),
            assessmentBasisType = AssessmentBasisType.ASSET, assessmentBasisId = asset.id!!, asset = asset,
            assessor = manager, requestor = manager, useCases = mutableSetOf(useCase),
            lockedRelease = if (pinRelease) release else null
        ))
        workflow.initialize(assessment)
        val request = CreateSupplierInvitation(
            contact.email, "Procurement", "Payroll platform", Instant.now().plusSeconds(3600),
            requirements.mapIndexed { index, requirement -> ApprovedSupplierQuestion(requirement.id!!, "Public question ${index + 1}") }
        )
        return Fixture(assessment.id!!, release.id!!, authentication(manager), authentication(contact), authentication(unprivileged), request)
    }

    private fun submission(questionnaire: SupplierQuestionnaire) = SupplierSubmission(
        questionnaire.schemaVersion, questionnaire.invitationId, questionnaire.generation, questionnaire.snapshotHash,
        UUID.randomUUID().toString(), Instant.now(),
        questionnaire.questions.map { SupplierAnswer(it.key, AnswerType.YES, "  Supplier evidence  ") }
    )

    private fun authentication(user: User) =
        Authentication.build(user.username, user.roles.map { it.name }, mapOf("userId" to user.id!!, "email" to user.email))

    private data class Fixture(
        val id: Long,
        val releaseId: Long,
        val manager: Authentication,
        val contact: Authentication,
        val contactWithoutManagerRole: Authentication,
        val request: CreateSupplierInvitation
    )
}
