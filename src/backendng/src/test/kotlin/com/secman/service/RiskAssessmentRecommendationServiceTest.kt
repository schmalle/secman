package com.secman.service

import com.secman.domain.AnswerType
import com.secman.domain.AssessmentBasisType
import com.secman.domain.AwsAccount
import com.secman.domain.Requirement
import com.secman.domain.Response
import com.secman.domain.RiskAssessment
import com.secman.domain.UseCase
import com.secman.domain.User
import com.secman.dto.Recommendation
import com.secman.repository.ResponseRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class RiskAssessmentRecommendationServiceTest {
    private val responses = mockk<ResponseRepository>()
    private val workflow = mockk<AssessmentWorkflowService>(relaxed = true)
    private val service = RiskAssessmentRecommendationService(responses, workflow)

    private val assessor = User(id = 1, username = "champ", email = "champ@example.test",
        passwordHash = "hash", roles = mutableSetOf(User.Role.SECCHAMPION))
    private val useCase = UseCase(id = 10, name = "Scope")
    private val awsAccount = AwsAccount(id = 30, awsAccountId = "123456789012")
    private val requirementA = Requirement(id = 20, internalId = "REQ-20", shortreq = "Encrypt data")
    private val requirementB = Requirement(id = 21, internalId = "REQ-21", shortreq = "Log access")
    private val requirementC = Requirement(id = 22, internalId = "REQ-22", shortreq = "Patch systems")

    private val template = RiskAssessment(
        id = 40,
        startDate = LocalDate.now(),
        endDate = LocalDate.now().plusDays(7),
        assessmentBasisType = AssessmentBasisType.AWS_ACCOUNT,
        assessmentBasisId = awsAccount.id!!,
        assessor = assessor,
        requestor = assessor,
        awsAccount = awsAccount,
        useCases = mutableSetOf(useCase)
    )

    private fun response(requirement: Requirement, answerType: AnswerType?, comment: String? = null) =
        Response(answerType = answerType, comment = comment, respondentEmail = "owner@example.test",
            riskAssessment = template, requirement = requirement)

    private fun fixture(
        requirements: List<Requirement>,
        answers: List<Response>,
        status: String = "COMPLETED",
        answerRevision: Long = 7
    ): RiskAssessment {
        val assessment = template.copy(status = status, answerRevision = answerRevision)
        every { workflow.requirementsFor(assessment) } returns requirements
        every { responses.findByRiskAssessmentId(assessment.id!!) } returns answers
        return assessment
    }

    @Test
    fun `all YES answers on a completed assessment yield OK and COMPLIANT`() {
        val assessment = fixture(
            listOf(requirementA, requirementB),
            listOf(response(requirementA, AnswerType.YES), response(requirementB, AnswerType.YES))
        )

        val result = service.recommend(assessment)

        assertThat(result.recommendation).isEqualTo(Recommendation.OK)
        assertThat(result.verdict).isEqualTo("COMPLIANT")
        assertThat(result.findings).isEmpty()
        assertThat(result.missingAnswerCount).isEqualTo(0)
        assertThat(result.requirementCount).isEqualTo(2)
        assertThat(result.answerCounts).containsExactlyEntriesOf(mapOf("YES" to 2, "NO" to 0, "N_A" to 0))
        assertThat(result.summary).isEqualTo("All 2 requirements are answered and none are unmet.")
    }

    @Test
    fun `a single NO answer yields NOT_OK and NON_COMPLIANT with a finding`() {
        val assessment = fixture(
            listOf(requirementA),
            listOf(response(requirementA, AnswerType.NO, "Gap confirmed"))
        )

        val result = service.recommend(assessment)

        assertThat(result.recommendation).isEqualTo(Recommendation.NOT_OK)
        assertThat(result.verdict).isEqualTo("NON_COMPLIANT")
        assertThat(result.summary).isEqualTo("1 requirement is not met.")
        val finding = result.findings.single()
        assertThat(finding.requirementId).isEqualTo(requirementA.id)
        assertThat(finding.internalId).isEqualTo("REQ-20")
        assertThat(finding.shortreq).isEqualTo("Encrypt data")
        assertThat(finding.answerType).isEqualTo("NO")
        assertThat(finding.reason).isEqualTo("Requirement is explicitly not met.")
        assertThat(finding.comment).isEqualTo("Gap confirmed")
    }

    @Test
    fun `N_A requires review while the legacy verdict remains COMPLIANT`() {
        val assessment = fixture(
            listOf(requirementA),
            listOf(response(requirementA, AnswerType.N_A))
        )

        val result = service.recommend(assessment)

        assertThat(result.recommendation).isEqualTo(Recommendation.NEEDS_REVIEW)
        assertThat(result.verdict).isEqualTo("COMPLIANT")
        assertThat(result.summary).isEqualTo("1 not-applicable answer requires reviewer confirmation.")
        val finding = result.findings.single()
        assertThat(finding.answerType).isEqualTo("N_A")
        assertThat(finding.reason).isEqualTo("Marked not applicable - reviewer should confirm scope.")
    }

    @Test
    fun `YES and N_A mixed requires review`() {
        val assessment = fixture(
            listOf(requirementA, requirementB),
            listOf(response(requirementA, AnswerType.YES), response(requirementB, AnswerType.N_A))
        )

        val result = service.recommend(assessment)

        assertThat(result.recommendation).isEqualTo(Recommendation.NEEDS_REVIEW)
        assertThat(result.verdict).isEqualTo("COMPLIANT")
        assertThat(result.answerCounts).containsExactlyEntriesOf(mapOf("YES" to 1, "NO" to 0, "N_A" to 1))
        assertThat(result.findings).singleElement()
            .extracting("requirementId").isEqualTo(requirementB.id)
    }

    @Test
    fun `NO combined with YES and N_A yields NOT_OK and never lists YES as a finding`() {
        val assessment = fixture(
            listOf(requirementA, requirementB, requirementC),
            listOf(
                response(requirementA, AnswerType.YES),
                response(requirementB, AnswerType.NO),
                response(requirementC, AnswerType.N_A)
            )
        )

        val result = service.recommend(assessment)

        assertThat(result.recommendation).isEqualTo(Recommendation.NOT_OK)
        assertThat(result.verdict).isEqualTo("NON_COMPLIANT")
        assertThat(result.summary).isEqualTo("1 requirement is not met; 1 marked not applicable.")
        assertThat(result.findings.map { it.requirementId }).containsExactly(requirementB.id, requirementC.id)
        assertThat(result.findings.map { it.answerType }).containsExactly("NO", "N_A")
    }

    @Test
    fun `a missing answer on a completed assessment yields NEEDS_REVIEW with a null-answer finding`() {
        val assessment = fixture(
            listOf(requirementA, requirementB),
            listOf(response(requirementA, AnswerType.YES))
        )

        val result = service.recommend(assessment)

        assertThat(result.recommendation).isEqualTo(Recommendation.NEEDS_REVIEW)
        assertThat(result.verdict).isEqualTo("COMPLIANT")
        assertThat(result.missingAnswerCount).isEqualTo(1)
        assertThat(result.summary).isEqualTo("Questionnaire is submitted but incomplete; 1 of 2 requirements answered.")
        val finding = result.findings.single()
        assertThat(finding.requirementId).isEqualTo(requirementB.id)
        assertThat(finding.answerType).isNull()
        assertThat(finding.reason).isEqualTo("No answer submitted.")
        assertThat(finding.comment).isNull()
    }

    @Test
    fun `a response row without an answer type counts as missing`() {
        val assessment = fixture(
            listOf(requirementA),
            listOf(response(requirementA, null))
        )

        val result = service.recommend(assessment)

        assertThat(result.recommendation).isEqualTo(Recommendation.NEEDS_REVIEW)
        assertThat(result.missingAnswerCount).isEqualTo(1)
        assertThat(result.findings).singleElement()
            .extracting("answerType").isNull()
    }

    @Test
    fun `an unsubmitted questionnaire yields NEEDS_REVIEW with completeness details`() {
        val assessment = fixture(
            listOf(requirementA, requirementB),
            listOf(response(requirementA, AnswerType.YES)),
            status = "STARTED"
        )

        val result = service.recommend(assessment)

        assertThat(result.recommendation).isEqualTo(Recommendation.NEEDS_REVIEW)
        assertThat(result.verdict).isEqualTo("COMPLIANT")
        assertThat(result.summary).isEqualTo("Questionnaire is not yet submitted; 1 of 2 requirements answered.")
    }

    @Test
    fun `a NO answer on an unsubmitted questionnaire needs review`() {
        val assessment = fixture(
            listOf(requirementA, requirementB),
            listOf(response(requirementA, AnswerType.NO)),
            status = "STARTED"
        )

        val result = service.recommend(assessment)

        assertThat(result.recommendation).isEqualTo(Recommendation.NEEDS_REVIEW)
        assertThat(result.verdict).isEqualTo("NON_COMPLIANT")
        assertThat(result.summary).isEqualTo("Questionnaire is not yet submitted; 1 of 2 requirements answered.")
    }

    @Test
    fun `an assessment without requirements in scope yields NEEDS_REVIEW`() {
        val assessment = fixture(emptyList(), emptyList())

        val result = service.recommend(assessment)

        assertThat(result.recommendation).isEqualTo(Recommendation.NEEDS_REVIEW)
        assertThat(result.verdict).isEqualTo("COMPLIANT")
        assertThat(result.requirementCount).isEqualTo(0)
        assertThat(result.missingAnswerCount).isEqualTo(0)
        assertThat(result.findings).isEmpty()
        assertThat(result.summary).isEqualTo("No requirements are in scope for this assessment.")
    }

    @Test
    fun `metadata echoes the assessment and policy version`() {
        val assessment = fixture(
            listOf(requirementA),
            listOf(response(requirementA, AnswerType.YES)),
            answerRevision = 11
        )
        val before = Instant.now()

        val result = service.recommend(assessment)

        assertThat(result.assessmentId).isEqualTo(assessment.id)
        assertThat(result.answerRevision).isEqualTo(11)
        assertThat(result.advisory).isTrue()
        assertThat(result.policyVersion).isEqualTo(RiskAssessmentRecommendationService.POLICY_VERSION)
        assertThat(result.generatedAt).isBetween(before, Instant.now())
    }

    @Test
    fun `findings are ordered by requirement id regardless of requirement order`() {
        val assessment = fixture(
            listOf(requirementC, requirementA),
            listOf(response(requirementC, AnswerType.NO), response(requirementA, AnswerType.NO))
        )

        val result = service.recommend(assessment)

        assertThat(result.findings.map { it.requirementId }).containsExactly(requirementA.id, requirementC.id)
        assertThat(result.summary).isEqualTo("2 requirements are not met.")
    }

    @Test
    fun `answers outside the pinned questionnaire cannot alter the recommendation`() {
        val assessment = fixture(
            listOf(requirementA),
            listOf(response(requirementA, AnswerType.YES), response(requirementB, AnswerType.NO))
        )

        val result = service.recommend(assessment)

        assertThat(result.answerCounts).containsExactlyEntriesOf(mapOf("YES" to 1, "NO" to 0, "N_A" to 0))
        assertThat(result.verdict).isEqualTo("COMPLIANT")
        assertThat(result.recommendation).isEqualTo(Recommendation.OK)
        assertThat(result.findings).isEmpty()
    }
}
