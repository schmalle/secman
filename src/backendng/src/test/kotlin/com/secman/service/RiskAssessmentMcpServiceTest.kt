package com.secman.service

import com.secman.domain.AnswerType
import com.secman.domain.AssessmentBasisType
import com.secman.domain.AwsAccount
import com.secman.domain.McpPermission
import com.secman.domain.Release
import com.secman.domain.Requirement
import com.secman.domain.Response
import com.secman.domain.RiskAssessment
import com.secman.domain.UseCase
import com.secman.domain.User
import com.secman.dto.mcp.McpExecutionContext
import com.secman.repository.AwsAccountRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.ResponseRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UseCaseRepository
import com.secman.repository.UserRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Optional

class RiskAssessmentMcpServiceTest {
    private val assessments = mockk<RiskAssessmentRepository>(relaxed = true)
    private val responses = mockk<ResponseRepository>(relaxed = true)
    private val requirements = mockk<RequirementRepository>(relaxed = true)
    private val useCases = mockk<UseCaseRepository>(relaxed = true)
    private val awsAccounts = mockk<AwsAccountRepository>(relaxed = true)
    private val users = mockk<UserRepository>(relaxed = true)
    private val releaseScope = mockk<ReleaseRequirementScopeService>(relaxed = true)
    private val service = RiskAssessmentMcpService(
        assessments, responses, requirements, useCases, awsAccounts, users, releaseScope
    )

    private val assessor = user(1, "champ", "champ@example.test", User.Role.SECCHAMPION)
    private val respondent = user(2, "owner", "owner@example.test", User.Role.RISK)
    private val outsider = user(3, "other", "other@example.test", User.Role.RISK)
    private val useCase = UseCase(id = 10, name = "One requirement")
    private val requirement = Requirement(id = 20, internalId = "REQ-20", shortreq = "Encrypt data")
    private val awsAccount = AwsAccount(id = 30, awsAccountId = "123456789012")
    private val activeRelease = Release(
        id = 50,
        version = "2026.09",
        name = "September 2026",
        status = Release.ReleaseStatus.ACTIVE
    )
    private val assessment = RiskAssessment(
        id = 40,
        startDate = LocalDate.now(),
        endDate = LocalDate.now().plusDays(7),
        assessmentBasisType = AssessmentBasisType.AWS_ACCOUNT,
        assessmentBasisId = awsAccount.id!!,
        assessor = assessor,
        requestor = assessor,
        respondent = respondent,
        awsAccount = awsAccount,
        useCases = mutableSetOf(useCase)
    )

    private fun user(id: Long, username: String, email: String, role: User.Role) =
        User(id = id, username = username, email = email, passwordHash = "hash", roles = mutableSetOf(role))

    private fun context(user: User, isAdmin: Boolean = false) = McpExecutionContext.forDelegatedUser(
        apiKeyId = 1,
        apiKeyName = "test",
        delegatedUserId = user.id!!,
        delegatedUserEmail = user.email,
        delegatedUsername = user.username,
        delegatedUserRoles = user.roles.map { it.name }.toSet(),
        effectivePermissions = McpPermission.entries.toSet(),
        isAdmin = isAdmin,
        accessibleAssetIds = emptySet(),
        accessibleWorkgroupIds = emptySet()
    )

    @BeforeEach
    fun setUp() {
        assessment.status = "STARTED"
        every { assessments.findById(assessment.id!!) } returns Optional.of(assessment)
        every { assessments.update(assessment) } returns assessment
        every { requirements.findByUsecaseId(useCase.id!!) } returns listOf(requirement)
        every { responses.findByRiskAssessmentId(assessment.id!!) } returns emptyList()
        every { releaseScope.findActiveRelease() } returns activeRelease
        every { releaseScope.requirementsForRelease(activeRelease.id!!, any<Long>()) } returns listOf(requirement)
    }

    @Test
    fun `questionnaire returns exactly the use case requirement`() {
        val result = service.questionnaire(context(respondent), assessment.id!!)

        @Suppress("UNCHECKED_CAST")
        val rows = result["requirements"] as List<Map<String, Any?>>
        assertThat(rows).hasSize(1)
        assertThat(rows.single()["id"]).isEqualTo(requirement.id)
        assertThat(result["requirementCount"]).isEqualTo(1)
    }

    @Test
    fun `dedicated answer read returns saved response metadata`() {
        val response = Response(
            answerType = AnswerType.NO,
            comment = "Gap confirmed",
            respondentEmail = respondent.email,
            riskAssessment = assessment,
            requirement = requirement
        )
        every { responses.findByRiskAssessmentId(assessment.id!!) } returns listOf(response)

        val result = service.answers(context(respondent), assessment.id!!)

        @Suppress("UNCHECKED_CAST")
        val rows = result["answers"] as List<Map<String, Any?>>
        assertThat(rows).hasSize(1)
        assertThat(rows.single()["requirementId"]).isEqualTo(requirement.id)
        assertThat(rows.single()["answerType"]).isEqualTo("NO")
        assertThat(rows.single()["comment"]).isEqualTo("Gap confirmed")
    }

    @Test
    fun `create uses AWS account as basis without an asset`() {
        every { awsAccounts.findByAwsAccountId(awsAccount.awsAccountId) } returns Optional.of(awsAccount)
        every { useCases.findById(useCase.id!!) } returns Optional.of(useCase)
        every { users.findByEmailIgnoreCase(assessor.email) } returns Optional.of(assessor)
        every { users.findByEmailIgnoreCase(respondent.email) } returns Optional.of(respondent)
        every { users.findById(assessor.id!!) } returns Optional.of(assessor)
        every { assessments.save(any()) } answers { firstArg<RiskAssessment>().apply { id = 41 } }

        val result = service.create(
            context(assessor, isAdmin = true), awsAccount.awsAccountId, listOf(useCase.id!!),
            assessor.email, respondent.email, LocalDate.now().plusDays(7), null
        )

        assertThat(result["basisType"]).isEqualTo("AWS_ACCOUNT")
        assertThat(result["awsAccountId"]).isEqualTo(awsAccount.awsAccountId)
        verify {
            assessments.save(match {
                it.assessmentBasisType == AssessmentBasisType.AWS_ACCOUNT &&
                    it.awsAccount == awsAccount && it.asset == null &&
                    it.lockedRelease == activeRelease && it.isReleaseLocked && it.contentSnapshotTaken
            })
        }
    }

    @Test
    fun `create rejects assessments without an active requirements release`() {
        every { awsAccounts.findByAwsAccountId(awsAccount.awsAccountId) } returns Optional.of(awsAccount)
        every { useCases.findById(useCase.id!!) } returns Optional.of(useCase)
        every { releaseScope.findActiveRelease() } returns null

        assertThatThrownBy {
            service.create(
                context(assessor, isAdmin = true), awsAccount.awsAccountId, listOf(useCase.id!!),
                assessor.email, respondent.email, LocalDate.now().plusDays(7), null
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No ACTIVE release")

        verify(exactly = 0) { assessments.save(any()) }
    }

    @Test
    fun `create scopes questionnaire to every selected use case`() {
        val secondUseCase = UseCase(id = 11, name = "Second use case")
        val secondRequirement = Requirement(id = 21, internalId = "REQ-21", shortreq = "Log access")
        every { awsAccounts.findByAwsAccountId(awsAccount.awsAccountId) } returns Optional.of(awsAccount)
        every { useCases.findById(useCase.id!!) } returns Optional.of(useCase)
        every { useCases.findById(secondUseCase.id!!) } returns Optional.of(secondUseCase)
        every { requirements.findByUsecaseId(secondUseCase.id!!) } returns listOf(secondRequirement)
        every { users.findByEmailIgnoreCase(assessor.email) } returns Optional.of(assessor)
        every { users.findByEmailIgnoreCase(respondent.email) } returns Optional.of(respondent)
        every { users.findById(assessor.id!!) } returns Optional.of(assessor)
        every { assessments.save(any()) } answers { firstArg<RiskAssessment>().apply { id = 42 } }

        service.create(
            context(assessor, isAdmin = true), awsAccount.awsAccountId,
            listOf(useCase.id!!, secondUseCase.id!!), assessor.email, respondent.email,
            LocalDate.now().plusDays(7), null
        )

        verify {
            assessments.save(match {
                it.useCases.mapNotNull(UseCase::id).toSet() == setOf(useCase.id, secondUseCase.id)
            })
        }
    }

    @Test
    fun `assessor can prepare reminder with exact outstanding answer count`() {
        val answered = Response(
            answerType = AnswerType.YES,
            respondentEmail = respondent.email,
            riskAssessment = assessment,
            requirement = requirement
        )
        val secondRequirement = Requirement(id = 21, internalId = "REQ-21", shortreq = "Log access")
        every { requirements.findByUsecaseId(useCase.id!!) } returns listOf(requirement, secondRequirement)
        every { responses.findByRiskAssessmentId(assessment.id!!) } returns listOf(answered)

        val reminder = service.prepareOutstandingReminder(context(assessor), assessment.id!!)

        assertThat(reminder.recipientEmail).isEqualTo(respondent.email)
        assertThat(reminder.requirementCount).isEqualTo(2)
        assertThat(reminder.unansweredCount).isEqualTo(1)
    }

    @Test
    fun `respondent cannot prepare their own reminder`() {
        assertThatThrownBy { service.prepareOutstandingReminder(context(respondent), assessment.id!!) }
            .isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun `list forwards open status and use case filter and returns account identity`() {
        every {
            assessments.findForMcp("STARTED", useCase.name, respondent.id!!, false, Pageable.from(0, 20))
        } returns Page.of(listOf(assessment), Pageable.from(0, 20), 1L)

        val result = service.list(context(respondent), "started", useCase.name, 0, 20)

        @Suppress("UNCHECKED_CAST")
        val rows = result["assessments"] as List<Map<String, Any?>>
        assertThat(rows).hasSize(1)
        assertThat(rows.single()["awsAccountId"]).isEqualTo(awsAccount.awsAccountId)
        assertThat(result["totalElements"]).isEqualTo(1L)
    }

    @Test
    fun `only assigned respondent may save answers`() {
        assertThatThrownBy {
            service.saveAnswers(
                context(outsider), assessment.id!!,
                listOf(RiskAssessmentMcpService.AnswerInput(requirement.id!!, AnswerType.YES, null))
            )
        }.isInstanceOf(NoSuchElementException::class.java)

        verify(exactly = 0) { responses.save(any()) }
    }

    @Test
    fun `answer for a requirement outside the questionnaire is rejected`() {
        assertThatThrownBy {
            service.saveAnswers(
                context(respondent), assessment.id!!,
                listOf(RiskAssessmentMcpService.AnswerInput(999, AnswerType.YES, null))
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not part of this assessment")
    }

    @Test
    fun `complete questionnaire can be submitted by respondent`() {
        val response = Response(
            answerType = AnswerType.NO,
            comment = "Control is planned",
            respondentEmail = respondent.email,
            riskAssessment = assessment,
            requirement = requirement
        )
        every { responses.findByRiskAssessmentId(assessment.id!!) } returns listOf(response)

        val result = service.submit(context(respondent), assessment.id!!)

        assertThat(result["status"]).isEqualTo("COMPLETED")
        verify { assessments.update(assessment) }
    }

    @Test
    fun `incomplete questionnaire cannot be submitted`() {
        assertThatThrownBy { service.submit(context(respondent), assessment.id!!) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("incomplete")
    }

    @Test
    fun `assessor evaluates completed answers and receives non-compliant finding`() {
        assessment.status = "COMPLETED"
        val response = Response(
            answerType = AnswerType.NO,
            comment = "Gap confirmed",
            respondentEmail = respondent.email,
            riskAssessment = assessment,
            requirement = requirement
        )
        every { responses.findByRiskAssessmentId(assessment.id!!) } returns listOf(response)

        val result = service.evaluate(context(assessor), assessment.id!!)

        assertThat(result["verdict"]).isEqualTo("NON_COMPLIANT")
        @Suppress("UNCHECKED_CAST")
        assertThat(result["findings"] as List<Map<String, Any?>>).singleElement()
            .extracting("requirementId").isEqualTo(requirement.id)
    }

    @Test
    fun `respondent cannot perform evaluator action`() {
        assessment.status = "COMPLETED"

        assertThatThrownBy { service.evaluate(context(respondent), assessment.id!!) }
            .isInstanceOf(SecurityException::class.java)
    }
}
