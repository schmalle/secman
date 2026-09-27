package com.secman.service

import com.secman.domain.AnswerType
import com.secman.domain.AssessmentBasisType
import com.secman.domain.Asset
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
import com.secman.repository.AssetRepository
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
    private val assets = mockk<AssetRepository>(relaxed = true)
    private val users = mockk<UserRepository>(relaxed = true)
    private val releaseScope = mockk<ReleaseRequirementScopeService>(relaxed = true)
    private val assignments = mockk<com.secman.repository.AssessmentAssignmentRepository>(relaxed = true)
    private val filter = mockk<AssetFilterService>(relaxed = true)
    private val access = RiskAssessmentAccessService(assignments, filter)
    private val entityManager = mockk<jakarta.persistence.EntityManager>(relaxed = true)
    private val keys = mockk<com.secman.repository.McpApiKeyRepository>(relaxed = true)
    private val workflow = AssessmentWorkflowService(entityManager, assessments, assignments, mockk(relaxed = true), mockk(relaxed = true),
        responses, users, keys, requirements, releaseScope, access)
    private val recommendationService = RiskAssessmentRecommendationService(responses, workflow)
    private val service = RiskAssessmentMcpService(
        assessments, responses, requirements, useCases, awsAccounts, assets, users, filter, workflow, access, releaseScope,
        recommendationService
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
        every { assignments.save(any()) } answers { firstArg() }
        every { assignments.update(any()) } answers { firstArg() }
        every { keys.findById(1) } returns Optional.of(com.secman.domain.McpApiKey(id = 1, keyId = "test",
            keyHash = "hash", name = "test", userId = 1, permissions = "ASSESSMENTS_EXECUTE", delegationEnabled = true,
            allowedDelegateUserIds = "1,2,3", allowedDelegationDomains = "@example.test"))
        assessment.status = "STARTED"
        every { users.findById(any()) } answers { Optional.ofNullable(listOf(assessor, respondent, outsider).find { it.id == firstArg<Long>() }) }
        every { entityManager.find(RiskAssessment::class.java, assessment.id!!, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE) } returns assessment
        every { entityManager.find(RiskAssessment::class.java, assessment.id!!, jakarta.persistence.LockModeType.PESSIMISTIC_READ) } returns assessment
        every { assignments.findByAssessmentId(assessment.id!!) } returns listOf(
            com.secman.domain.AssessmentAssignment(id = 4, assessmentId = assessment.id!!, userId = assessor.id, email = assessor.email, role = "ASSESSOR"),
            com.secman.domain.AssessmentAssignment(id = 5, assessmentId = assessment.id!!, userId = respondent.id, email = respondent.email, role = "RESPONDENT"))
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
            context(assessor, isAdmin = true), awsAccount.awsAccountId, null, listOf(useCase.id!!),
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
    fun `create uses an accessible supplier asset as basis`() {
        val supplier = Asset(id = 31, name = "Example SaaS", type = "SUPPLIER", owner = assessor.username,
            uri = "https://supplier.example.test")
        every { assets.findById(supplier.id!!) } returns Optional.of(supplier)
        every { useCases.findById(useCase.id!!) } returns Optional.of(useCase)
        every { users.findByEmailIgnoreCase(assessor.email) } returns Optional.of(assessor)
        every { users.findByEmailIgnoreCase(respondent.email) } returns Optional.of(respondent)
        every { users.findById(assessor.id!!) } returns Optional.of(assessor)
        every { assessments.save(any()) } answers { firstArg<RiskAssessment>().apply { id = 43 } }

        val result = service.create(
            context(assessor, isAdmin = true), null, supplier.id, listOf(useCase.id!!),
            assessor.email, respondent.email, LocalDate.now().plusDays(7), null
        )

        assertThat(result["basisType"]).isEqualTo("ASSET")
        assertThat((result["asset"] as Map<*, *>)["type"]).isEqualTo("SUPPLIER")
        verify { assessments.save(match { it.asset == supplier && it.awsAccount == null }) }
    }

    @Test
    fun `create rejects assessments without an active requirements release`() {
        every { awsAccounts.findByAwsAccountId(awsAccount.awsAccountId) } returns Optional.of(awsAccount)
        every { useCases.findById(useCase.id!!) } returns Optional.of(useCase)
        every { releaseScope.findActiveRelease() } returns null

        assertThatThrownBy {
            service.create(
                context(assessor, isAdmin = true), awsAccount.awsAccountId, null, listOf(useCase.id!!),
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
            context(assessor, isAdmin = true), awsAccount.awsAccountId, null,
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
            .isInstanceOf(io.micronaut.http.exceptions.HttpStatusException::class.java)
    }

    @Test
    fun `list forwards open status and use case filter and returns account identity`() {
        every {
            assessments.findForMcp(null, "STARTED", useCase.name, respondent.id!!, false, any(), any(), Pageable.from(0, 20))
        } returns Page.of(listOf(assessment), Pageable.from(0, 20), 1L)

        val result = service.list(context(respondent), "started", useCase.name, false, null, 0, 20)

        @Suppress("UNCHECKED_CAST")
        val rows = result["assessments"] as List<Map<String, Any?>>
        assertThat(rows).hasSize(1)
        assertThat(rows.single()["awsAccountId"]).isEqualTo(awsAccount.awsAccountId)
        assertThat(result["totalElements"]).isEqualTo(1L)
        @Suppress("UNCHECKED_CAST")
        val filters = result["filtersApplied"] as Map<String, Any?>
        assertThat(filters["status"]).isEqualTo("STARTED")
        assertThat(filters["openOnly"]).isEqualTo(false)
        assertThat(filters["assessmentType"]).isNull()
        assertThat(filters["useCaseName"]).isEqualTo(useCase.name)
    }

    @Test
    fun `list without filters reports empty filtersApplied`() {
        every {
            assessments.findForMcp(null, null, null, respondent.id!!, false, any(), any(), Pageable.from(0, 20))
        } returns Page.of(listOf(assessment), Pageable.from(0, 20), 1L)

        val result = service.list(context(respondent), null, null, false, null, 0, 20)

        assertThat(result["filtersApplied"]).isEqualTo(
            mapOf("status" to null, "openOnly" to false, "assessmentType" to null, "useCaseName" to null)
        )
    }

    @Test
    fun `openOnly alone forwards and reports the STARTED status`() {
        every {
            assessments.findForMcp(null, "STARTED", null, respondent.id!!, false, any(), any(), Pageable.from(0, 20))
        } returns Page.of(listOf(assessment), Pageable.from(0, 20), 1L)

        val result = service.list(context(respondent), null, null, true, null, 0, 20)

        @Suppress("UNCHECKED_CAST")
        val filters = result["filtersApplied"] as Map<String, Any?>
        assertThat(filters["status"]).isEqualTo("STARTED")
        assertThat(filters["openOnly"]).isEqualTo(true)
        verify { assessments.findForMcp(null, "STARTED", null, respondent.id!!, false, any(), any(), Pageable.from(0, 20)) }
    }

    @Test
    fun `openOnly tolerates the equivalent STARTED status`() {
        every {
            assessments.findForMcp(null, "STARTED", null, respondent.id!!, false, any(), any(), Pageable.from(0, 20))
        } returns Page.of(listOf(assessment), Pageable.from(0, 20), 1L)

        val result = service.list(context(respondent), "STARTED", null, true, null, 0, 20)

        @Suppress("UNCHECKED_CAST")
        assertThat((result["filtersApplied"] as Map<String, Any?>)["status"]).isEqualTo("STARTED")
    }

    @Test
    fun `list forwards each supported status normalized to uppercase`() {
        listOf("started" to "STARTED", "completed" to "COMPLETED").forEach { (input, expected) ->
            every {
                assessments.findForMcp(null, expected, null, respondent.id!!, false, any(), any(), Pageable.from(0, 20))
            } returns Page.of(emptyList(), Pageable.from(0, 20), 0L)

            val result = service.list(context(respondent), input, null, false, null, 0, 20)

            @Suppress("UNCHECKED_CAST")
            assertThat((result["filtersApplied"] as Map<String, Any?>)["status"]).isEqualTo(expected)
        }
    }

    @Test
    fun `list normalizes and forwards each assessment type to the repository`() {
        AssessmentBasisType.entries.forEach { type ->
            every {
                assessments.findForMcp(type, null, null, respondent.id!!, false, any(), any(), Pageable.from(0, 20))
            } returns Page.of(emptyList(), Pageable.from(0, 20), 0L)

            val result = service.list(context(respondent), null, null, false, type.name.lowercase(), 0, 20)

            @Suppress("UNCHECKED_CAST")
            assertThat((result["filtersApplied"] as Map<String, Any?>)["assessmentType"]).isEqualTo(type.name)
            verify { assessments.findForMcp(type, null, null, respondent.id!!, false, any(), any(), Pageable.from(0, 20)) }
        }
    }

    @Test
    fun `openOnly combined with a non-open status is rejected`() {
        assertThatThrownBy { service.list(context(respondent), "COMPLETED", null, true, null, 0, 20) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("openOnly")

        verify(exactly = 0) { assessments.findForMcp(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `unknown assessment type is rejected before repository access`() {
        assertThatThrownBy { service.list(context(respondent), null, null, false, "SERVER", 0, 20) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("assessmentType")

        verify(exactly = 0) { assessments.findForMcp(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `summaries expose assessmentType as an alias of basisType`() {
        every {
            assessments.findForMcp(null, null, null, respondent.id!!, false, any(), any(), Pageable.from(0, 20))
        } returns Page.of(listOf(assessment), Pageable.from(0, 20), 1L)

        val result = service.list(context(respondent), null, null, false, null, 0, 20)

        @Suppress("UNCHECKED_CAST")
        val row = (result["assessments"] as List<Map<String, Any?>>).single()
        assertThat(row["assessmentType"]).isEqualTo("AWS_ACCOUNT")
        assertThat(row["basisType"]).isEqualTo(row["assessmentType"])
    }

    @Test
    fun `mixed basis types keep repository paging and totals`() {
        val supplier = Asset(id = 32, name = "Example SaaS", type = "SUPPLIER", owner = assessor.username,
            uri = "https://supplier.example.test")
        val assetAssessment = RiskAssessment(
            id = 41,
            startDate = LocalDate.now(),
            endDate = LocalDate.now().plusDays(7),
            assessmentBasisType = AssessmentBasisType.ASSET,
            assessmentBasisId = supplier.id!!,
            assessor = assessor,
            requestor = assessor,
            respondent = respondent,
            asset = supplier,
            useCases = mutableSetOf(useCase)
        )
        every {
            assessments.findForMcp(null, null, null, assessor.id!!, true, setOf(-1L), setOf(""), Pageable.from(0, 20))
        } returns Page.of(listOf(assessment, assetAssessment), Pageable.from(0, 20), 21L)

        val result = service.list(context(assessor, isAdmin = true), null, null, false, null, 0, 20)

        @Suppress("UNCHECKED_CAST")
        val rows = result["assessments"] as List<Map<String, Any?>>
        assertThat(rows.map { it["assessmentType"] }).containsExactly("AWS_ACCOUNT", "ASSET")
        assertThat(result["totalElements"]).isEqualTo(21L)
        assertThat(result["totalPages"]).isEqualTo(2)
    }

    @Test
    fun `privileged viewers pass the global visibility flag and sentinel sets`() {
        every {
            assessments.findForMcp(null, null, null, assessor.id!!, true, setOf(-1L), setOf(""), Pageable.from(0, 20))
        } returns Page.of(listOf(assessment), Pageable.from(0, 20), 1L)

        service.list(context(assessor, isAdmin = true), null, null, false, null, 0, 20)

        verify { assessments.findForMcp(null, null, null, assessor.id!!, true, setOf(-1L), setOf(""), Pageable.from(0, 20)) }
    }

    @Test
    fun `delegated viewers keep asset and account scoping`() {
        every { filter.getAccessibleAwsAccountIds(any()) } returns emptySet()
        every {
            assessments.findForMcp(null, "STARTED", useCase.name, respondent.id!!, false, setOf(-1L), setOf(""), Pageable.from(0, 20))
        } returns Page.of(listOf(assessment), Pageable.from(0, 20), 1L)

        service.list(context(respondent), "started", useCase.name, false, null, 0, 20)

        verify { assessments.findForMcp(null, "STARTED", useCase.name, respondent.id!!, false, setOf(-1L), setOf(""), Pageable.from(0, 20)) }
    }

    @Test
    fun `only assigned respondent may save answers`() {
        assertThatThrownBy {
            service.saveAnswers(
                context(outsider), assessment.id!!,
                listOf(RiskAssessmentMcpService.AnswerInput(requirement.id!!, AnswerType.YES, null))
            )
        }.isInstanceOf(io.micronaut.http.exceptions.HttpStatusException::class.java)

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
            .hasMessageContaining("assignment scope")
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
        assessment.answerRevision = 5
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
        assertThat(result["recommendation"]).isEqualTo("NOT_OK")
        assertThat(result["answerRevision"]).isEqualTo(5L)
        assertThat(result["missingAnswerCount"]).isEqualTo(0)
        assertThat(result["summary"]).isEqualTo("1 requirement is not met.")
        assertThat(result["advisory"]).isEqualTo(true)
        assertThat(result["policyVersion"]).isEqualTo(RiskAssessmentRecommendationService.POLICY_VERSION)
        java.time.Instant.parse(result["generatedAt"] as String)
        @Suppress("UNCHECKED_CAST")
        val findings = result["findings"] as List<Map<String, Any?>>
        val finding = findings.single()
        assertThat(finding["requirementId"]).isEqualTo(requirement.id)
        assertThat(finding["internalId"]).isEqualTo(requirement.internalId)
        assertThat(finding["shortreq"]).isEqualTo(requirement.shortreq)
        assertThat(finding["answerType"]).isEqualTo("NO")
        assertThat(finding["reason"]).isEqualTo("Requirement is explicitly not met.")
        assertThat(finding["comment"]).isEqualTo("Gap confirmed")
    }

    @Test
    fun `evaluation keeps every legacy key and adds the recommendation metadata`() {
        assessment.status = "COMPLETED"
        val response = Response(
            answerType = AnswerType.YES,
            respondentEmail = respondent.email,
            riskAssessment = assessment,
            requirement = requirement
        )
        every { responses.findByRiskAssessmentId(assessment.id!!) } returns listOf(response)

        val result = service.evaluate(context(assessor), assessment.id!!)

        assertThat(result.keys).containsExactlyInAnyOrder(
            "assessment", "verdict", "answerCounts", "requirementCount", "findings",
            "recommendation", "answerRevision", "summary", "missingAnswerCount", "generatedAt",
            "advisory", "policyVersion", "assessmentId"
        )
        assertThat(result["verdict"]).isEqualTo("COMPLIANT")
        assertThat(result["recommendation"]).isEqualTo("OK")
        assertThat(result["answerCounts"]).isEqualTo(mapOf("YES" to 1, "NO" to 0, "N_A" to 0))
        assertThat(result["requirementCount"]).isEqualTo(1)
        assertThat(result["findings"]).isEqualTo(emptyList<Map<String, Any?>>())
    }

    @Test
    fun `evaluation rejects an unsubmitted assessment`() {
        assertThatThrownBy { service.evaluate(context(assessor), assessment.id!!) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("completed")
    }

    @Test
    fun `completed assessment with a blank answer is NEEDS_REVIEW and lists the missing answer`() {
        assessment.status = "COMPLETED"

        val result = service.evaluate(context(assessor), assessment.id!!)

        assertThat(result["verdict"]).isEqualTo("COMPLIANT")
        assertThat(result["recommendation"]).isEqualTo("NEEDS_REVIEW")
        assertThat(result["missingAnswerCount"]).isEqualTo(1)
        @Suppress("UNCHECKED_CAST")
        val findings = result["findings"] as List<Map<String, Any?>>
        val finding = findings.single()
        assertThat(finding["requirementId"]).isEqualTo(requirement.id)
        assertThat(finding["answerType"]).isNull()
        assertThat(finding["reason"]).isEqualTo("No answer submitted.")
    }

    @Test
    fun `MCP evaluation matches the shared recommendation service`() {
        assessment.status = "COMPLETED"
        assessment.answerRevision = 9
        val response = Response(
            answerType = AnswerType.N_A,
            comment = "Out of scope",
            respondentEmail = respondent.email,
            riskAssessment = assessment,
            requirement = requirement
        )
        every { responses.findByRiskAssessmentId(assessment.id!!) } returns listOf(response)

        val viaMcp = service.evaluate(context(assessor), assessment.id!!)
        val direct = recommendationService.recommend(assessment)

        assertThat(viaMcp["recommendation"]).isEqualTo(direct.recommendation.name)
        assertThat(viaMcp["verdict"]).isEqualTo(direct.verdict)
        assertThat(viaMcp["answerCounts"]).isEqualTo(direct.answerCounts)
        assertThat(viaMcp["requirementCount"]).isEqualTo(direct.requirementCount)
        assertThat(viaMcp["answerRevision"]).isEqualTo(direct.answerRevision)
        assertThat(viaMcp["missingAnswerCount"]).isEqualTo(direct.missingAnswerCount)
        @Suppress("UNCHECKED_CAST")
        val mcpFindings = viaMcp["findings"] as List<Map<String, Any?>>
        assertThat(mcpFindings.map { it["requirementId"] }).isEqualTo(direct.findings.map { it.requirementId })
        assertThat(mcpFindings.map { it["answerType"] }).isEqualTo(direct.findings.map { it.answerType })
        assertThat(mcpFindings.map { it["reason"] }).isEqualTo(direct.findings.map { it.reason })
        assertThat(mcpFindings.map { it["comment"] }).isEqualTo(direct.findings.map { it.comment })
    }

    @Test
    fun `respondent cannot perform evaluator action`() {
        assessment.status = "COMPLETED"

        assertThatThrownBy { service.evaluate(context(respondent), assessment.id!!) }
            .isInstanceOf(SecurityException::class.java)
    }
}
