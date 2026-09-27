package com.secman.controller

import com.secman.domain.AssessmentAssignment
import com.secman.domain.AssessmentBasisType
import com.secman.domain.AnswerType
import com.secman.domain.AwsAccount
import com.secman.domain.Requirement
import com.secman.domain.Response
import com.secman.domain.RiskAssessment
import com.secman.domain.UseCase
import com.secman.domain.User
import com.secman.dto.RiskAssessmentRecommendation
import com.secman.dto.Recommendation
import com.secman.repository.AssessmentAssignmentRepository
import com.secman.repository.ResponseRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.service.AssetFilterService
import com.secman.service.AssessmentWorkflowService
import com.secman.service.RiskAssessmentAccessService
import com.secman.service.RiskAssessmentRecommendationService
import io.micronaut.http.HttpStatus
import io.micronaut.security.authentication.Authentication
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RiskAssessmentControllerRecommendationTest {
    private val assessments = mockk<RiskAssessmentRepository>()
    private val assignments = mockk<AssessmentAssignmentRepository>()
    private val assetFilter = mockk<AssetFilterService>()
    private val responses = mockk<ResponseRepository>()
    private val workflow = mockk<AssessmentWorkflowService>(relaxed = true)
    private val access = RiskAssessmentAccessService(assignments, assetFilter)
    private val recommendationService = RiskAssessmentRecommendationService(responses, workflow)
    private val controller = RiskAssessmentController(
        assessments,
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        responses,
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        assetFilter,
        workflow,
        mockk(relaxed = true),
        access,
        recommendationService
    )

    private val assessor = user(1, "assessor", "assessor@example.test", User.Role.RISK)
    private val respondent = user(2, "owner", "owner@example.test", User.Role.RISK)
    private val requestor = user(3, "requestor", "requestor@example.test", User.Role.RISK)
    private val outsider = user(4, "other", "other@example.test", User.Role.RISK)
    private val champion = user(5, "champ", "champ@example.test", User.Role.SECCHAMPION)
    private val admin = user(6, "admin", "admin@example.test", User.Role.ADMIN)
    private val useCase = UseCase(id = 10, name = "One requirement")
    private val requirement = Requirement(id = 20, internalId = "REQ-20", shortreq = "Encrypt data")
    private val awsAccount = AwsAccount(id = 30, awsAccountId = "123456789012")
    private val assessment = RiskAssessment(
        id = 40,
        startDate = LocalDate.now(),
        endDate = LocalDate.now().plusDays(7),
        status = "COMPLETED",
        answerRevision = 3,
        assessmentBasisType = AssessmentBasisType.AWS_ACCOUNT,
        assessmentBasisId = awsAccount.id!!,
        assessor = assessor,
        requestor = requestor,
        respondent = respondent,
        awsAccount = awsAccount,
        useCases = mutableSetOf(useCase)
    )

    private fun user(id: Long, username: String, email: String, role: User.Role) =
        User(id = id, username = username, email = email, passwordHash = "hash", roles = mutableSetOf(role))

    private fun auth(user: User): Authentication = Authentication.build(
        user.username, user.roles.map { it.name }, mapOf("userId" to user.id!!, "email" to user.email)
    )

    @BeforeEach
    fun setUp() {
        every { workflow.findForRecommendation(assessment.id!!) } returns assessment
        every { workflow.findForRecommendation(999) } returns null
        every { workflow.requirementsFor(assessment) } returns listOf(requirement)
        every { responses.findByRiskAssessmentId(assessment.id!!) } returns listOf(
            Response(answerType = AnswerType.YES, respondentEmail = respondent.email,
                riskAssessment = assessment, requirement = requirement)
        )
        every { assignments.findByAssessmentId(assessment.id!!) } returns listOf(
            AssessmentAssignment(id = 4, assessmentId = assessment.id!!, userId = assessor.id,
                email = assessor.email, role = "ASSESSOR"),
            AssessmentAssignment(id = 5, assessmentId = assessment.id!!, userId = respondent.id,
                email = respondent.email, role = "RESPONDENT")
        )
        every { assetFilter.canAccessAwsAccount(any(), any()) } answers {
            (secondArg<Authentication>().attributes["userId"] as? Number)?.toLong() == requestor.id
        }
    }

    @Test
    fun `ADMIN receives the recommendation`() {
        val response = controller.getRiskAssessmentRecommendation(assessment.id!!, auth(admin))

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        val body = response.body() as RiskAssessmentRecommendation
        assertThat(body.recommendation).isEqualTo(Recommendation.OK)
        assertThat(body.answerRevision).isEqualTo(3)
        assertThat(body.policyVersion).isEqualTo(RiskAssessmentRecommendationService.POLICY_VERSION)
        assertThat(body.advisory).isTrue()
    }

    @Test
    fun `SECCHAMPION receives the recommendation`() {
        val response = controller.getRiskAssessmentRecommendation(assessment.id!!, auth(champion))

        assertThat(response.status).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `assigned assessor receives the recommendation`() {
        val response = controller.getRiskAssessmentRecommendation(assessment.id!!, auth(assessor))

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        assertThat((response.body() as RiskAssessmentRecommendation).recommendation).isEqualTo(Recommendation.OK)
    }

    @Test
    fun `respondent without assessor assignment is forbidden`() {
        val response = controller.getRiskAssessmentRecommendation(assessment.id!!, auth(respondent))

        assertThat(response.status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `visible requestor may analyze without acceptance authority`() {
        val response = controller.getRiskAssessmentRecommendation(assessment.id!!, auth(requestor))

        assertThat(response.status).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `unrelated user gets the same not found as a missing assessment`() {
        val response = controller.getRiskAssessmentRecommendation(assessment.id!!, auth(outsider))

        assertThat(response.status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `missing assessment is not found`() {
        val response = controller.getRiskAssessmentRecommendation(999, auth(admin))

        assertThat(response.status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `started assessment yields NEEDS_REVIEW instead of an error`() {
        assessment.status = "STARTED"

        val response = controller.getRiskAssessmentRecommendation(assessment.id!!, auth(assessor))

        assertThat(response.status).isEqualTo(HttpStatus.OK)
        val body = response.body() as RiskAssessmentRecommendation
        assertThat(body.recommendation).isEqualTo(Recommendation.NEEDS_REVIEW)
        assertThat(body.summary).isEqualTo("Questionnaire is not yet submitted; 1 of 1 requirements answered.")
    }
}
