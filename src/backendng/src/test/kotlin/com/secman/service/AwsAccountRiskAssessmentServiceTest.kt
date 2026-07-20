package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.AssessmentBasisType
import com.secman.domain.AwsAccountRiskAssessment
import com.secman.domain.RiskAssessment
import com.secman.domain.UseCase
import com.secman.domain.User
import com.secman.dto.NewAccountImportInfo
import com.secman.repository.AssetRepository
import com.secman.repository.AwsAccountRiskAssessmentRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.UseCaseRepository
import com.secman.repository.UserRepository
import jakarta.inject.Provider
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Optional
import java.util.concurrent.CompletableFuture

class AwsAccountRiskAssessmentServiceTest {

    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val useCaseRepository = mockk<UseCaseRepository>(relaxed = true)
    private val assetRepository = mockk<AssetRepository>(relaxed = true)
    private val riskAssessmentRepository = mockk<RiskAssessmentRepository>(relaxed = true)
    private val trackingRepository = mockk<AwsAccountRiskAssessmentRepository>(relaxed = true)
    private val emailService = mockk<EmailService>(relaxed = true)

    // Constructed in setup(); selfProvider returns this same instance (the AOP proxy is a
    // no-op under a plain unit test, so createAssessment runs directly with REQUIRES_NEW inert).
    private lateinit var service: AwsAccountRiskAssessmentService

    private val useCase = UseCase(id = 5L, name = "Cloud Onboarding")
    private val champion1 = user(1L, "champ1", "champ1@corp.com", User.Role.SECCHAMPION)
    private val champion2 = user(2L, "champ2", "champ2@corp.com", User.Role.SECCHAMPION)
    private val admin = user(9L, "admin", "admin@corp.com", User.Role.ADMIN)

    private fun user(id: Long, name: String, email: String, role: User.Role) =
        User(id = id, username = name, email = email, passwordHash = "x", roles = mutableSetOf(role))

    @BeforeEach
    fun setup() {
        service = AwsAccountRiskAssessmentService(
            userRepository = userRepository,
            useCaseRepository = useCaseRepository,
            assetRepository = assetRepository,
            riskAssessmentRepository = riskAssessmentRepository,
            trackingRepository = trackingRepository,
            emailService = emailService,
            selfProvider = Provider { service }
        )
        every { useCaseRepository.findByNameIgnoreCase("Cloud Onboarding") } returns Optional.of(useCase)
        every { userRepository.findByRolesContaining(User.Role.SECCHAMPION) } returns listOf(champion1, champion2)
        every { userRepository.findById(9L) } returns Optional.of(admin)
        every { userRepository.findByEmailIgnoreCase(any()) } returns Optional.empty()
        every { assetRepository.findByName(any()) } returns Optional.empty()
        every { assetRepository.save(any()) } answers { firstArg<Asset>().also { it.id = it.id ?: 100L } }
        var nextId = 1000L
        every { riskAssessmentRepository.save(any()) } answers { firstArg<RiskAssessment>().apply { id = nextId++ } }
        every { trackingRepository.save(any()) } answers { firstArg() }
        every { trackingRepository.update(any<AwsAccountRiskAssessment>()) } answers { firstArg() }
        every { emailService.sendEmail(any(), any(), any(), any()) } returns CompletableFuture.completedFuture(true)
    }

    // --- validateStartRequest ------------------------------------------------

    @Test
    fun `validation rejects missing use case name`() {
        assertThat(service.validateStartRequest(null, 7)).contains("riskAssessmentUseCase")
        assertThat(service.validateStartRequest("  ", 7)).contains("riskAssessmentUseCase")
    }

    @Test
    fun `validation rejects deadline below 1 day`() {
        assertThat(service.validateStartRequest("Cloud Onboarding", 0)).contains("at least 1")
    }

    @Test
    fun `validation rejects unknown use case`() {
        every { useCaseRepository.findByNameIgnoreCase("Nope") } returns Optional.empty()
        assertThat(service.validateStartRequest("Nope", 7)).contains("not found")
    }

    @Test
    fun `validation rejects when no SECCHAMPION user exists`() {
        every { userRepository.findByRolesContaining(User.Role.SECCHAMPION) } returns emptyList()
        assertThat(service.validateStartRequest("Cloud Onboarding", 7)).contains("SECCHAMPION")
    }

    @Test
    fun `validation passes for valid parameters, with and without explicit deadline`() {
        assertThat(service.validateStartRequest("Cloud Onboarding", 7)).isNull()
        assertThat(service.validateStartRequest("Cloud Onboarding", null)).isNull()
    }

    // --- startAssessmentsForNewAccounts --------------------------------------

    @Test
    fun `creates one assessment per account-owner pair with deadline and SECCHAMPION assessor`() {
        val newAccounts = listOf(
            NewAccountImportInfo("111111111111", listOf("alice@corp.com")),
            NewAccountImportInfo("222222222222", listOf("bob@corp.com"))
        )

        val results = service.startAssessmentsForNewAccounts(newAccounts, "Cloud Onboarding", 7, 9L)

        assertThat(results).hasSize(2)
        assertThat(results).allSatisfy {
            assertThat(it.error).isNull()
            assertThat(it.riskAssessmentId).isNotNull()
            assertThat(it.endDate).isEqualTo(LocalDate.now().plusDays(7).toString())
        }
        // Round-robin over the two SECCHAMPION users
        assertThat(results.map { it.assessor }).containsExactly("champ1@corp.com", "champ2@corp.com")

        val savedAssessments = mutableListOf<RiskAssessment>()
        verify(exactly = 2) { riskAssessmentRepository.save(capture(savedAssessments)) }
        assertThat(savedAssessments).allSatisfy {
            assertThat(it.assessmentBasisType).isEqualTo(AssessmentBasisType.ASSET)
            assertThat(it.endDate).isEqualTo(LocalDate.now().plusDays(7))
            assertThat(it.requestor).isEqualTo(admin)
            assertThat(it.useCases).containsExactly(useCase)
            assertThat(it.status).isEqualTo("STARTED")
        }
        assertThat(savedAssessments.map { it.assessor }).containsExactly(champion1, champion2)

        verify(exactly = 2) { trackingRepository.save(any()) }
        verify(exactly = 2) { emailService.sendEmail(any(), any(), any(), any()) }
    }

    @Test
    fun `owner email is sent only after the assessment is persisted`() {
        // Locks the pool-safety refactor: the blocking SMTP send must happen AFTER the persist
        // transaction (createAssessment / REQUIRES_NEW) commits, never while its connection is held.
        service.startAssessmentsForNewAccounts(
            listOf(NewAccountImportInfo("111111111111", listOf("alice@corp.com"))),
            "Cloud Onboarding", 7, 9L
        )

        io.mockk.verifyOrder {
            riskAssessmentRepository.save(any())
            trackingRepository.save(any())
            emailService.sendEmail(any(), any(), any(), any())
        }
    }

    @Test
    fun `skips creation when an open assessment is already tracked for the account-owner pair`() {
        val openAssessment = RiskAssessment(
            id = 77L,
            startDate = LocalDate.now().minusDays(3),
            endDate = LocalDate.now().plusDays(4),
            assessmentBasisType = AssessmentBasisType.ASSET,
            assessmentBasisId = 1L,
            assessor = champion1,
            requestor = admin
        )
        every { trackingRepository.findByAwsAccountId("111111111111") } returns listOf(
            AwsAccountRiskAssessment(
                id = 300L,
                awsAccountId = "111111111111",
                ownerEmail = "ALICE@corp.com", // case-insensitive match
                riskAssessment = openAssessment,
                useCaseName = "Cloud Onboarding"
            )
        )

        val results = service.startAssessmentsForNewAccounts(
            listOf(NewAccountImportInfo("111111111111", listOf("alice@corp.com"))),
            "Cloud Onboarding", 7, 9L
        )

        assertThat(results.single().error).contains("already exists")
        assertThat(results.single().riskAssessmentId).isEqualTo(77L)
        verify(exactly = 0) { riskAssessmentRepository.save(any()) }
        verify(exactly = 0) { trackingRepository.save(any()) }
        verify(exactly = 0) { emailService.sendEmail(any(), any(), any(), any()) }
    }

    @Test
    fun `creates dedicated AWS account asset when none exists`() {
        val assetSlot = slot<Asset>()
        every { assetRepository.save(capture(assetSlot)) } answers { firstArg<Asset>().apply { id = 100L } }

        service.startAssessmentsForNewAccounts(
            listOf(NewAccountImportInfo("111111111111", listOf("alice@corp.com"))),
            "Cloud Onboarding", 7, null
        )

        assertThat(assetSlot.captured.name).isEqualTo("AWS Account 111111111111")
        assertThat(assetSlot.captured.type).isEqualTo("AWS_ACCOUNT")
        assertThat(assetSlot.captured.owner).isEqualTo("alice@corp.com")
        assertThat(assetSlot.captured.cloudAccountId).isEqualTo("111111111111")
    }

    @Test
    fun `reuses existing account asset by name`() {
        val existing = Asset(id = 55L, name = "AWS Account 111111111111", type = "AWS_ACCOUNT", owner = "someone")
        every { assetRepository.findByName("AWS Account 111111111111") } returns Optional.of(existing)

        val results = service.startAssessmentsForNewAccounts(
            listOf(NewAccountImportInfo("111111111111", listOf("alice@corp.com"))),
            "Cloud Onboarding", 7, null
        )

        assertThat(results.single().error).isNull()
        verify(exactly = 0) { assetRepository.save(any()) }
        val saved = slot<RiskAssessment>()
        verify { riskAssessmentRepository.save(capture(saved)) }
        assertThat(saved.captured.assessmentBasisId).isEqualTo(55L)
    }

    @Test
    fun `sets owner user as respondent when a matching account exists`() {
        val owner = user(7L, "alice", "alice@corp.com", User.Role.USER)
        every { userRepository.findByEmailIgnoreCase("alice@corp.com") } returns Optional.of(owner)

        service.startAssessmentsForNewAccounts(
            listOf(NewAccountImportInfo("111111111111", listOf("alice@corp.com"))),
            "Cloud Onboarding", 3, null
        )

        val saved = slot<RiskAssessment>()
        verify { riskAssessmentRepository.save(capture(saved)) }
        assertThat(saved.captured.respondent).isEqualTo(owner)
    }

    @Test
    fun `owner notification failure does not fail assessment creation`() {
        every { emailService.sendEmail(any(), any(), any(), any()) } throws RuntimeException("SMTP down")

        val results = service.startAssessmentsForNewAccounts(
            listOf(NewAccountImportInfo("111111111111", listOf("alice@corp.com"))),
            "Cloud Onboarding", 7, null
        )

        assertThat(results.single().error).isNull()
        assertThat(results.single().riskAssessmentId).isNotNull()
    }

    @Test
    fun `per-item failure is reported without aborting remaining accounts`() {
        every { assetRepository.findByName("AWS Account 111111111111") } throws RuntimeException("boom")

        val results = service.startAssessmentsForNewAccounts(
            listOf(
                NewAccountImportInfo("111111111111", listOf("alice@corp.com")),
                NewAccountImportInfo("222222222222", listOf("bob@corp.com"))
            ),
            "Cloud Onboarding", 7, null
        )

        assertThat(results).hasSize(2)
        assertThat(results[0].error).contains("boom")
        assertThat(results[1].error).isNull()
    }

    // --- processDeadlineReminders ---------------------------------------------

    private fun tracking(endDate: LocalDate, twoSent: Boolean = false, oneSent: Boolean = false): AwsAccountRiskAssessment {
        val assessment = RiskAssessment(
            id = 77L,
            startDate = endDate.minusDays(7),
            endDate = endDate,
            assessmentBasisType = AssessmentBasisType.ASSET,
            assessmentBasisId = 1L,
            assessor = champion1,
            requestor = admin
        )
        return AwsAccountRiskAssessment(
            id = 300L,
            awsAccountId = "111111111111",
            ownerEmail = "alice@corp.com",
            riskAssessment = assessment,
            useCaseName = "Cloud Onboarding",
            reminderTwoDaysSentAt = if (twoSent) java.time.LocalDateTime.now().minusDays(1) else null,
            reminderOneDaySentAt = if (oneSent) java.time.LocalDateTime.now().minusDays(1) else null
        )
    }

    @Test
    fun `sends 2-day reminder when deadline is 2 days away`() {
        val today = LocalDate.now()
        val t = tracking(endDate = today.plusDays(2))
        every { trackingRepository.findPendingDeadlineReminders(today, today.plusDays(2)) } returns listOf(t)
        every { trackingRepository.claimTwoDayReminder(300L, any()) } returns 1

        val sent = service.processDeadlineReminders(today)

        assertThat(sent).isEqualTo(1)
        verify { trackingRepository.claimTwoDayReminder(300L, any()) }
        verify { emailService.sendEmail("alice@corp.com", match { it.contains("2 days") }, any(), any()) }
        verify(exactly = 0) { trackingRepository.claimOneDayReminder(any(), any()) }
    }

    @Test
    fun `sends 1-day reminder when deadline is 1 day away and 2-day reminder was already sent`() {
        val today = LocalDate.now()
        val t = tracking(endDate = today.plusDays(1), twoSent = true)
        every { trackingRepository.findPendingDeadlineReminders(today, today.plusDays(2)) } returns listOf(t)
        every { trackingRepository.claimOneDayReminder(300L, any()) } returns 1

        val sent = service.processDeadlineReminders(today)

        assertThat(sent).isEqualTo(1)
        verify { trackingRepository.claimOneDayReminder(300L, any()) }
        verify { emailService.sendEmail("alice@corp.com", match { it.contains("1 day") }, any(), any()) }
    }

    @Test
    fun `missed 2-day reminder collapses into single 1-day catch-up send`() {
        val today = LocalDate.now()
        val t = tracking(endDate = today.plusDays(1))
        every { trackingRepository.findPendingDeadlineReminders(today, today.plusDays(2)) } returns listOf(t)
        // claimOneDayReminder stamps BOTH slots in one atomic UPDATE (catch-up collapse).
        every { trackingRepository.claimOneDayReminder(300L, any()) } returns 1

        val sent = service.processDeadlineReminders(today)

        assertThat(sent).isEqualTo(1)
        verify(exactly = 1) { emailService.sendEmail(any(), any(), any(), any()) }
        verify(exactly = 0) { trackingRepository.claimTwoDayReminder(any(), any()) }
    }

    @Test
    fun `already-sent reminders are not repeated`() {
        val today = LocalDate.now()
        val t = tracking(endDate = today.plusDays(2), twoSent = true)
        every { trackingRepository.findPendingDeadlineReminders(today, today.plusDays(2)) } returns listOf(t)

        val sent = service.processDeadlineReminders(today)

        assertThat(sent).isEqualTo(0)
        verify(exactly = 0) { emailService.sendEmail(any(), any(), any(), any()) }
    }

    @Test
    fun `lost reminder claim means a concurrent run already sent - no duplicate email`() {
        val today = LocalDate.now()
        val t = tracking(endDate = today.plusDays(2))
        every { trackingRepository.findPendingDeadlineReminders(today, today.plusDays(2)) } returns listOf(t)
        // Another instance won the guarded UPDATE: 0 rows affected here.
        every { trackingRepository.claimTwoDayReminder(300L, any()) } returns 0

        val sent = service.processDeadlineReminders(today)

        assertThat(sent).isEqualTo(0)
        verify(exactly = 0) { emailService.sendEmail(any(), any(), any(), any()) }
    }

    @Test
    fun `failed reminder email releases the claim for retry on next run`() {
        val today = LocalDate.now()
        val t = tracking(endDate = today.plusDays(2))
        every { trackingRepository.findPendingDeadlineReminders(today, today.plusDays(2)) } returns listOf(t)
        every { trackingRepository.claimTwoDayReminder(300L, any()) } returns 1
        every { emailService.sendEmail(any(), any(), any(), any()) } returns CompletableFuture.completedFuture(false)

        val sent = service.processDeadlineReminders(today)

        assertThat(sent).isEqualTo(0)
        verify { trackingRepository.releaseTwoDayReminderClaim(300L, any()) }
    }
}
