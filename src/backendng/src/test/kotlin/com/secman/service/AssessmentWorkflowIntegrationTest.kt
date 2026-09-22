package com.secman.service

import com.secman.domain.*
import com.secman.repository.*
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.security.authentication.Authentication
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Uses the disposable MariaDB schema to exercise actual transactions and row locks. */
@MicronautTest(environments = ["test"], transactional = false, startApplication = false)
class AssessmentWorkflowIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var workflow: AssessmentWorkflowService
    @Inject lateinit var users: UserRepository
    @Inject lateinit var assessments: RiskAssessmentRepository
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var requirements: RequirementRepository
    @Inject lateinit var useCases: UseCaseRepository
    @Inject lateinit var contributions: AssessmentContributionRepository
    @Inject lateinit var responses: ResponseRepository

    @Test fun `concurrent answers serialize revisions and submitted answers remain frozen`() {
        val suffix = System.nanoTime()
        val actor = users.save(TestDataFactory.createRegularUser(username = "workflow-$suffix", email = "workflow-$suffix@test.com"))
        val useCase = useCases.save(UseCase(name = "Workflow $suffix"))
        val first = requirements.save(Requirement(internalId = "WF1-$suffix", shortreq = "First $suffix", usecases = mutableSetOf(useCase)))
        val second = requirements.save(Requirement(internalId = "WF2-$suffix", shortreq = "Second $suffix", usecases = mutableSetOf(useCase)))
        val asset = assets.save(TestDataFactory.createAsset(name = "Workflow asset $suffix"))
        val assessment = assessments.save(RiskAssessment(startDate = LocalDate.now(), endDate = LocalDate.now(),
            assessmentBasisType = AssessmentBasisType.ASSET, assessmentBasisId = asset.id!!, asset = asset,
            assessor = actor, requestor = actor, respondent = actor, useCases = mutableSetOf(useCase)))
        workflow.initialize(assessment)
        val assessmentId = assessment.id!!
        val auth = Authentication.build(actor.username, listOf("USER"), mapOf("userId" to actor.id!!, "email" to actor.email))
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val futures = listOf(first, second).map { requirement ->
                executor.submit {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    workflow.save(assessmentId, auth, listOf(AssessmentWorkflowService.Answer(requirement.id!!, AnswerType.YES, "Evidence")))
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(2L, assessments.findById(assessmentId).orElseThrow().answerRevision)
        assertEquals(setOf(1L, 2L), contributions.findByAssessmentId(assessmentId).map { it.revision }.toSet())
        assertEquals(2, responses.findByRiskAssessmentId(assessmentId).size)
        assertEquals("COMPLETED", workflow.submit(assessmentId, auth).status)
        assertThrows(Exception::class.java) {
            workflow.save(assessmentId, auth, listOf(AssessmentWorkflowService.Answer(first.id!!, AnswerType.NO, "Late edit")))
        }
        assertEquals(2L, assessments.findById(assessmentId).orElseThrow().answerRevision)
    }
}
