package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.AssessmentBasisType
import com.secman.domain.AwsAccountRiskAssessment
import com.secman.domain.Release
import com.secman.domain.Requirement
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
    private val releaseRequirementScopeService = mockk<ReleaseRequirementScopeService>(relaxed = true)

    // Real config, not a mock: it is a plain data class, and the assessment link the
    // templates render is asserted below — a relaxed mock would yield "" and hide a break.
    private val appConfig = com.secman.config.AppConfig(
        backend = com.secman.config.BackendConfig(baseUrl = "https://secman.test")
    )

    // Constructed in setup(); selfProvider returns this same instance (the AOP proxy is a
    // no-op under a plain unit test, so createAssessment runs directly with REQUIRES_NEW inert).
    private lateinit var service: AwsAccountRiskAssessmentService

    private val useCase = UseCase(id = 5L, name = "Cloud Onboarding")
    private val activeRelease = Release(id = 42L, version = "2.3.0", name = "Q3 baseline")
    private val releaseRequirements = listOf(
        Requirement(id = 901L, shortreq = "Encrypt data at rest"),
        Requirement(id = 902L, shortreq = "Enable CloudTrail")
    )
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
            appConfig = appConfig,
            releaseRequirementScopeService = releaseRequirementScopeService,
            // Real renderer, not a mock. These helpers used to be private to the service and
            // were extracted so guided onboarding could reuse them; the point of leaving this
            // test untouched otherwise is that it proves the extraction changed no behaviour.
            // A mock would render "" and hide exactly the break it exists to catch.
            templateRenderer = EmailTemplateRenderer(),
            selfProvider = Provider { service }
        )
        every { releaseRequirementScopeService.findActiveRelease() } returns activeRelease
        every { releaseRequirementScopeService.requirementsForRelease(42L, 5L) } returns releaseRequirements
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
        every { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(true)
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
    fun `validation rejects a deadline beyond the 10 year cap`() {
        // Unbounded, an endDate past year 9999 fails the SQL DATE insert — and it fails per
        // pair, AFTER the mappings have committed. A typo two keystrokes wide (100000 for
        // 1000) also produces an assessment no reminder will ever fire for.
        val error = service.validateStartRequest(
            "Cloud Onboarding", AwsAccountRiskAssessmentService.MAX_DEADLINE_DAYS + 1
        )

        assertThat(error).contains("at most ${AwsAccountRiskAssessmentService.MAX_DEADLINE_DAYS}")
        assertThat(service.validateStartRequest("Cloud Onboarding", Int.MAX_VALUE)).isNotNull()
        // The cap itself is accepted — the boundary is inclusive.
        assertThat(
            service.validateStartRequest("Cloud Onboarding", AwsAccountRiskAssessmentService.MAX_DEADLINE_DAYS)
        ).isNull()
    }

    @Test
    fun `an out-of-range deadline reaching the service directly is clamped, not persisted`() {
        // validateStartRequest guards both entry points, so this can only come from a direct
        // service call. We are past the commit point here: clamp rather than throw.
        val results = service.startAssessmentsForNewAccounts(
            listOf(NewAccountImportInfo("111111111111", listOf("alice@corp.com"))),
            "Cloud Onboarding", Int.MAX_VALUE, null
        )

        assertThat(results.single().error).isNull()
        val saved = slot<RiskAssessment>()
        verify { riskAssessmentRepository.save(capture(saved)) }
        assertThat(saved.captured.endDate)
            .isEqualTo(LocalDate.now().plusDays(AwsAccountRiskAssessmentService.MAX_DEADLINE_DAYS.toLong()))
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

    @Test
    fun `validation rejects when no ACTIVE release exists`() {
        // The assessment is measured against the current version of the security
        // requirements; without an ACTIVE release there is nothing to measure against.
        every { releaseRequirementScopeService.findActiveRelease() } returns null

        assertThat(service.validateStartRequest("Cloud Onboarding", 7)).contains("No ACTIVE release")
    }

    @Test
    fun `validation rejects when the ACTIVE release has no requirements for the use case`() {
        every { releaseRequirementScopeService.requirementsForRelease(42L, 5L) } returns emptyList()

        val error = service.validateStartRequest("Cloud Onboarding", 7)

        assertThat(error).contains("2.3.0")
        assertThat(error).contains("no requirements")
        assertThat(error).contains("Cloud Onboarding")
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
        verify(exactly = 2) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `assessments are pinned to the ACTIVE release and report it back`() {
        val results = service.startAssessmentsForNewAccounts(
            listOf(
                NewAccountImportInfo("111111111111", listOf("alice@corp.com")),
                NewAccountImportInfo("222222222222", listOf("bob@corp.com"))
            ),
            "Cloud Onboarding", 7, 9L
        )

        assertThat(results).allSatisfy {
            assertThat(it.releaseVersion).isEqualTo("2.3.0")
            assertThat(it.useCase).isEqualTo("Cloud Onboarding")
            assertThat(it.requirementCount).isEqualTo(2)
        }

        val saved = mutableListOf<RiskAssessment>()
        verify(exactly = 2) { riskAssessmentRepository.save(capture(saved)) }
        assertThat(saved).allSatisfy {
            assertThat(it.lockedRelease).isEqualTo(activeRelease)
            assertThat(it.isReleaseLocked).isTrue()
            assertThat(it.contentSnapshotTaken).isTrue()
        }
    }

    @Test
    fun `the ACTIVE release is resolved once so every account in one import pins to the same version`() {
        service.startAssessmentsForNewAccounts(
            listOf(
                NewAccountImportInfo("111111111111", listOf("alice@corp.com", "carol@corp.com")),
                NewAccountImportInfo("222222222222", listOf("bob@corp.com"))
            ),
            "Cloud Onboarding", 7, 9L
        )

        // Three (account, owner) pairs, but a single release lookup — activating a new
        // release mid-run must not split one import across two requirement versions.
        verify(exactly = 1) { releaseRequirementScopeService.findActiveRelease() }
    }

    @Test
    fun `no assessment is created when the ACTIVE release disappeared after validation`() {
        every { releaseRequirementScopeService.findActiveRelease() } returns null

        val results = service.startAssessmentsForNewAccounts(
            listOf(NewAccountImportInfo("111111111111", listOf("alice@corp.com"))),
            "Cloud Onboarding", 7, 9L
        )

        assertThat(results.single().error).contains("No ACTIVE release")
        assertThat(results.single().riskAssessmentId).isNull()
        verify(exactly = 0) { riskAssessmentRepository.save(any()) }
        verify(exactly = 0) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `start notification names the requirements version`() {
        val body = slot<String>()
        every { emailService.sendEmailWithInlineImages(any(), any(), capture(body), any(), any()) } returns
            CompletableFuture.completedFuture(true)

        service.startAssessmentsForNewAccounts(
            listOf(NewAccountImportInfo("111111111111", listOf("alice@corp.com"))),
            "Cloud Onboarding", 7, 9L
        )

        assertThat(body.captured).contains("2.3.0")
        assertThat(body.captured).contains("Q3 baseline")
    }

    @Test
    fun `start notification deep-links to the assessment that was just created`() {
        // The owner should land on their questionnaire, not the assessment list — the link
        // must carry the id of the assessment this very mail is about. Pointing into the
        // authenticated app (not /respond/{token}) is deliberate: the app forces a login,
        // whereas a token link would let anyone holding the mail answer for the owner.
        val body = slot<String>()
        every { emailService.sendEmailWithInlineImages(any(), any(), capture(body), any(), any()) } returns
            CompletableFuture.completedFuture(true)

        val results = service.startAssessmentsForNewAccounts(
            listOf(NewAccountImportInfo("111111111111", listOf("alice@corp.com"))),
            "Cloud Onboarding", 7, 9L
        )

        val assessmentId = results.single().riskAssessmentId
        assertThat(assessmentId).isNotNull()
        assertThat(body.captured).contains("https://secman.test/risk-assessments?assessmentId=$assessmentId")
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
            emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any())
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

        // A skip is an idempotent no-op, not a failure: `error` must stay null so neither CLI
        // renders it as ❌/FAILED nor exits 1 on a re-import.
        assertThat(results.single().skipped).isTrue()
        assertThat(results.single().skipReason).contains("already exists")
        assertThat(results.single().error).isNull()
        assertThat(results.single().riskAssessmentId).isEqualTo(77L)
        verify(exactly = 0) { riskAssessmentRepository.save(any()) }
        verify(exactly = 0) { trackingRepository.save(any()) }
        // And the owner is not mailed a second time about an assessment they were already told
        // about. This is what the `!info.skipped` half of the notification gate protects — with
        // `error` no longer set, testing `error` alone would re-notify on every re-import.
        verify(exactly = 0) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
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
        every { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) } throws RuntimeException("SMTP down")

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
        verify { emailService.sendEmailWithInlineImages("alice@corp.com", match { it.contains("2 days") }, any(), any(), any()) }
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
        verify { emailService.sendEmailWithInlineImages("alice@corp.com", match { it.contains("1 day") }, any(), any(), any()) }
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
        verify(exactly = 1) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { trackingRepository.claimTwoDayReminder(any(), any()) }
    }

    @Test
    fun `already-sent reminders are not repeated`() {
        val today = LocalDate.now()
        val t = tracking(endDate = today.plusDays(2), twoSent = true)
        every { trackingRepository.findPendingDeadlineReminders(today, today.plusDays(2)) } returns listOf(t)

        val sent = service.processDeadlineReminders(today)

        assertThat(sent).isEqualTo(0)
        verify(exactly = 0) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
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
        verify(exactly = 0) { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `failed reminder email releases the claim for retry on next run`() {
        val today = LocalDate.now()
        val t = tracking(endDate = today.plusDays(2))
        every { trackingRepository.findPendingDeadlineReminders(today, today.plusDays(2)) } returns listOf(t)
        every { trackingRepository.claimTwoDayReminder(300L, any()) } returns 1
        every { emailService.sendEmailWithInlineImages(any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(false)

        val sent = service.processDeadlineReminders(today)

        assertThat(sent).isEqualTo(0)
        verify { trackingRepository.releaseTwoDayReminderClaim(300L, any()) }
    }
}
