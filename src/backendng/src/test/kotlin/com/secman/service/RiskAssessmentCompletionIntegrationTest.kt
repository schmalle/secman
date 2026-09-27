package com.secman.service

import com.secman.controller.RiskAssessmentController
import com.secman.domain.*
import com.secman.dto.RiskAssessmentRecommendation
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.*
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.data.model.Pageable
import io.micronaut.security.authentication.Authentication
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

@MicronautTest(environments = ["test"], transactional = false, startApplication = false)
class RiskAssessmentCompletionIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var users: UserRepository
    @Inject lateinit var assessments: RiskAssessmentRepository
    @Inject lateinit var assets: AssetRepository
    @Inject lateinit var demands: DemandRepository
    @Inject lateinit var requirements: RequirementRepository
    @Inject lateinit var useCases: UseCaseRepository
    @Inject lateinit var assignments: AssessmentAssignmentRepository
    @Inject lateinit var workflow: AssessmentWorkflowService
    @Inject lateinit var controller: RiskAssessmentController
    @Inject lateinit var mcp: RiskAssessmentMcpService

    @Test fun `REST and MCP expose identical policy fields on the submitted revision`() {
        val suffix = System.nanoTime()
        val actor = users.save(TestDataFactory.createAdminUser(username = "recommend-$suffix", email = "recommend-$suffix@example.test"))
        val useCase = useCases.save(UseCase(name = "Recommendation $suffix"))
        val requirement = requirements.save(Requirement(internalId = "RC-$suffix", shortreq = "Control", usecases = mutableSetOf(useCase)))
        val asset = assets.save(TestDataFactory.createAsset(name = "Recommend $suffix"))
        val assessment = assessments.save(RiskAssessment(startDate = LocalDate.now(), endDate = LocalDate.now(),
            assessmentBasisType = AssessmentBasisType.ASSET, assessmentBasisId = asset.id!!, asset = asset,
            assessor = actor, requestor = actor, respondent = actor, useCases = mutableSetOf(useCase)))
        workflow.initialize(assessment)
        val auth = Authentication.build(actor.username, listOf("ADMIN"), mapOf("userId" to actor.id!!, "email" to actor.email))
        workflow.save(assessment.id!!, auth, listOf(AssessmentWorkflowService.Answer(requirement.id!!, AnswerType.N_A, "Scope confirmation needed")))
        workflow.submit(assessment.id!!, auth)
        val rest = controller.getRiskAssessmentRecommendation(assessment.id!!, auth).body() as RiskAssessmentRecommendation
        val context = McpExecutionContext.forDelegatedUser(1, "test", actor.id!!, actor.email, actor.username,
            setOf("ADMIN"), setOf(McpPermission.ASSESSMENTS_READ), true, null, null)
        val result = mcp.evaluate(context, assessment.id!!)
        assertEquals(rest.recommendation.name, result["recommendation"])
        assertEquals("NEEDS_REVIEW", result["recommendation"])
        assertEquals(rest.verdict, result["verdict"])
        assertEquals(rest.answerCounts, result["answerCounts"])
        assertEquals(rest.answerRevision, result["answerRevision"])
        assertEquals(rest.summary, result["summary"])
        assertEquals(rest.policyVersion, result["policyVersion"])
        assertEquals(rest.findings.map { it.requirementId }, (result["findings"] as List<*>).map { (it as Map<*, *>)["requirementId"] })
        assertEquals("COMPLETED", assessments.findById(assessment.id!!).orElseThrow().status)
    }

    @Test fun `database type filters and revoked assignments preserve exact page counts`() {
        val suffix = System.nanoTime()
        val user = users.save(TestDataFactory.createRegularUser(username = "discovery-$suffix", email = "discovery-$suffix@example.test"))
        val useCase = useCases.save(UseCase(name = "Discovery $suffix"))
        val asset = assets.save(TestDataFactory.createAsset(name = "Discovery $suffix"))
        val demand = demands.save(Demand(title = "Discovery $suffix", demandType = DemandType.CHANGE, existingAsset = asset, requestor = user))
        val rows = (0..2).map { index -> assessments.save(RiskAssessment(startDate = LocalDate.now(), endDate = LocalDate.now(),
            assessmentBasisType = if (index == 2) AssessmentBasisType.DEMAND else AssessmentBasisType.ASSET,
            assessmentBasisId = if (index == 2) demand.id!! else asset.id!!,
            asset = if (index == 2) null else asset, demand = if (index == 2) demand else null,
            assessor = user, requestor = user, useCases = mutableSetOf(useCase))) }
        val assigned = assignments.save(AssessmentAssignment(assessmentId = rows.first().id!!, userId = user.id,
            email = user.email, role = "ASSESSOR"))
        fun page(type: AssessmentBasisType?, resource: Set<Long>, privileged: Boolean, index: Int = 0) =
            assessments.findForMcp(type, "STARTED", useCase.name, user.id!!, privileged, resource, setOf(""), Pageable.from(index, 1))
        assertEquals(3, page(null, setOf(asset.id!!), false).totalSize)
        assertEquals(1, page(AssessmentBasisType.DEMAND, setOf(asset.id!!), false).totalSize)
        assertEquals(2, page(AssessmentBasisType.ASSET, setOf(asset.id!!), false).totalSize)
        assertNotEquals(page(null, setOf(asset.id!!), false).content[0].id, page(null, setOf(asset.id!!), false, 1).content[0].id)
        assertEquals(1, page(null, setOf(-1), false).totalSize)
        assigned.revoked = true
        assignments.update(assigned)
        assertEquals(0, page(null, setOf(-1), false).totalSize)
        assertEquals(3, page(null, setOf(-1), true).totalSize)
    }
    @Test fun `concurrent answer writes never mix recommendation counts with another revision`() {
        val suffix = System.nanoTime()
        val actor = users.save(TestDataFactory.createAdminUser(username = "revision-$suffix", email = "revision-$suffix@example.test"))
        val useCase = useCases.save(UseCase(name = "Revision $suffix"))
        val requirement = requirements.save(Requirement(internalId = "RV-$suffix", shortreq = "Control", usecases = mutableSetOf(useCase)))
        val asset = assets.save(TestDataFactory.createAsset(name = "Revision $suffix"))
        val assessment = assessments.save(RiskAssessment(startDate = LocalDate.now(), endDate = LocalDate.now(),
            assessmentBasisType = AssessmentBasisType.ASSET, assessmentBasisId = asset.id!!, asset = asset,
            assessor = actor, requestor = actor, respondent = actor, useCases = mutableSetOf(useCase)))
        workflow.initialize(assessment)
        val auth = Authentication.build(actor.username, listOf("ADMIN"), mapOf("userId" to actor.id!!, "email" to actor.email))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val writer = pool.submit {
                start.await()
                for (revision in 1..12) workflow.save(assessment.id!!, auth, listOf(AssessmentWorkflowService.Answer(
                    requirement.id!!, if (revision % 2 == 1) AnswerType.YES else AnswerType.NO, null)))
            }
            val reader = pool.submit {
                start.await()
                repeat(12) {
                    val result = controller.getRiskAssessmentRecommendation(assessment.id!!, auth).body() as RiskAssessmentRecommendation
                    if (result.answerRevision == 0L) assertEquals(1, result.missingAnswerCount)
                    else {
                        val expected = if (result.answerRevision % 2 == 1L) "YES" else "NO"
                        assertEquals(1, result.answerCounts[expected])
                        assertEquals(0, result.missingAnswerCount)
                    }
                }
            }
            start.countDown()
            writer.get(40, java.util.concurrent.TimeUnit.SECONDS)
            reader.get(40, java.util.concurrent.TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
    }

}
