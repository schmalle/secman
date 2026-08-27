package com.secman.service

import com.secman.domain.AccountOnboardingMode
import com.secman.dto.AccountOnboardingInfo
import com.secman.dto.AccountRiskAssessmentInfo
import com.secman.dto.BulkUserMappingEntry
import com.secman.dto.BulkUserMappingRequest
import com.secman.dto.BulkUserMappingResponse
import com.secman.dto.NewAccountImportInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Guards the contract REST `POST /api/user-mappings/bulk` and MCP
 * `import_user_mappings` share: validate up front, import, then run the
 * side effects only after the import has committed.
 *
 * Since onboarding modes were added, the risk-assessment side effect is reached through
 * [AccountOnboardingService] rather than directly. The tests below therefore also pin the
 * **backward-compatibility contract**: a caller that sets only `startRiskAssessment=true` must
 * still resolve to DIRECT with no welcome mail, because the `extensions/` clients and both
 * existing E2E drivers do exactly that and nothing in this build compiles against them.
 */
class UserMappingBulkImportServiceTest {

    private val userMappingService = mockk<UserMappingService>(relaxed = true)
    private val newAccountNotificationService = mockk<NewAccountNotificationService>(relaxed = true)
    private val onboardingService = mockk<AccountOnboardingService>(relaxed = true)

    private val workgroupAccountLinkService = mockk<WorkgroupAccountLinkService>(relaxed = true)

    private val service = UserMappingBulkImportService(
        userMappingService, newAccountNotificationService, onboardingService,
        mockk(relaxed = true), workgroupAccountLinkService
    )

    private val newAccount = NewAccountImportInfo("111111111111", listOf("alice@corp.com"))

    private fun request(
        dryRun: Boolean = false,
        notify: Boolean = false,
        notifyAddress: String? = null,
        startRiskAssessment: Boolean = false,
        useCase: String? = null,
        deadlineDays: Int? = null,
        mode: AccountOnboardingMode? = null,
        sendWelcomeEmail: Boolean? = null,
        expiryDays: Int? = null
    ) = BulkUserMappingRequest(
        mappings = listOf(BulkUserMappingEntry("alice@corp.com", "111111111111", null)),
        dryRun = dryRun,
        notifyNewAccounts = notify,
        notifyAddress = notifyAddress,
        startRiskAssessment = startRiskAssessment,
        riskAssessmentUseCase = useCase,
        riskAssessmentDeadlineDays = deadlineDays,
        onboardingMode = mode,
        sendWelcomeEmail = sendWelcomeEmail,
        questionnaireExpiryDays = expiryDays
    )

    private fun response(newAccounts: List<NewAccountImportInfo> = listOf(newAccount)) =
        BulkUserMappingResponse(
            totalProcessed = 1, created = 1, createdPending = 0, skipped = 0,
            errors = emptyList(), newAccounts = newAccounts
        )

    private fun plan(
        mode: AccountOnboardingMode = AccountOnboardingMode.DIRECT,
        welcome: Boolean = false,
        useCase: String? = "Cloud Onboarding"
    ) = AccountOnboardingService.OnboardingPlan(
        mode = mode, sendWelcomeEmail = welcome, useCaseName = useCase
    )

    private val startedAssessment = AccountRiskAssessmentInfo(
        awsAccountId = "111111111111", ownerEmail = "alice@corp.com",
        riskAssessmentId = 1000L, releaseVersion = "2.3.0", useCase = "Cloud Onboarding"
    )

    @BeforeEach
    fun setup() {
        every { userMappingService.bulkCreateMappings(any()) } returns response()
        every { newAccountNotificationService.sendImportNotification(any(), any()) } returns true

        // planFrom is real logic living on the collaborator, so stub it to mirror what the real
        // one does rather than letting a relaxed mock return null and silently disable onboarding.
        every { onboardingService.planFrom(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val explicit = firstArg<AccountOnboardingMode?>()
            val legacyFlag = secondArg<Boolean>()
            val mode = AccountOnboardingMode.resolve(explicit, legacyFlag)
            if (mode == null) null
            else AccountOnboardingService.OnboardingPlan(
                mode = mode,
                sendWelcomeEmail = thirdArg<Boolean?>() ?: (explicit != null),
                useCaseName = arg<String?>(3)
            )
        }
        every { onboardingService.validateRequest(any(), any()) } returns null
        every { onboardingService.onboardNewAccounts(any(), any(), any(), any()) } returns
            AccountOnboardingService.OnboardingOutcome(
                onboarding = listOf(
                    AccountOnboardingInfo(
                        awsAccountId = "111111111111", ownerEmail = "alice@corp.com", mode = "DIRECT",
                        riskAssessmentId = 1000L
                    )
                ),
                riskAssessments = listOf(startedAssessment)
            )
    }

    // --- validate -------------------------------------------------------------

    @Test
    fun `notify without a valid address is rejected`() {
        assertThat(service.validate(request(notify = true, notifyAddress = null)))
            .contains("notifyAddress")
        assertThat(service.validate(request(notify = true, notifyAddress = "not-an-email")))
            .contains("notifyAddress")
        assertThat(service.validate(request(notify = true, notifyAddress = "ops@corp.com")))
            .isNull()
    }

    @Test
    fun `an address that would split into two recipients is rejected`() {
        // The whole reason the shared EmailAddressValidator exists: InternetAddress.parse would
        // treat this as two recipients, and a CR would reach a mail header.
        assertThat(service.validate(request(notify = true, notifyAddress = "ops@corp.com,evil@bad.com")))
            .contains("notifyAddress")
        assertThat(service.validate(request(notify = true, notifyAddress = "ops@corp.com\nBcc: evil@bad.com")))
            .contains("notifyAddress")
    }

    @Test
    fun `onboarding validation is delegated to the onboarding service`() {
        every { onboardingService.validateRequest(any(), any()) } returns
            "No ACTIVE release exists to base the risk assessment on"

        assertThat(
            service.validate(
                request(startRiskAssessment = true, useCase = "Cloud Onboarding", deadlineDays = 7)
            )
        ).contains("No ACTIVE release")
    }

    @Test
    fun `onboarding validation is skipped when nothing was requested`() {
        service.validate(request(startRiskAssessment = false, useCase = "Cloud Onboarding"))

        verify(exactly = 0) { onboardingService.validateRequest(any(), any()) }
    }

    @Test
    fun `a non-DIRECT mode combined with startRiskAssessment is refused, not guessed`() {
        for (mode in listOf(AccountOnboardingMode.WELCOME_ONLY, AccountOnboardingMode.GUIDED)) {
            assertThat(service.validate(request(startRiskAssessment = true, mode = mode)))
                .describedAs("mode %s", mode)
                .contains("startRiskAssessment")
        }
        // DIRECT plus the legacy flag is coherent and stays accepted.
        assertThat(
            service.validate(
                request(startRiskAssessment = true, mode = AccountOnboardingMode.DIRECT, useCase = "Cloud Onboarding")
            )
        ).isNull()
    }

    // --- execute --------------------------------------------------------------

    @Test
    fun `side effects run only after the import returns`() {
        // Load-bearing ordering: the notification and the onboarding must not run inside the
        // import transaction, or a slow SMTP send holds a pooled DB connection and a failure
        // rolls back mappings that should have persisted.
        service.execute(
            request(
                notify = true, notifyAddress = "ops@corp.com",
                startRiskAssessment = true, useCase = "Cloud Onboarding"
            ),
            requestorUserId = 9L
        )

        verifyOrder {
            userMappingService.bulkCreateMappings(any())
            newAccountNotificationService.sendImportNotification(any(), any())
            onboardingService.onboardNewAccounts(any(), any(), any(), any())
        }
    }

    @Test
    fun `the legacy flag alone resolves to DIRECT with no welcome mail`() {
        // The backward-compatibility contract, asserted directly. Every existing client sends
        // only this, and must keep getting exactly what it got before onboarding modes existed.
        service.execute(
            request(startRiskAssessment = true, useCase = "Cloud Onboarding"),
            requestorUserId = 9L
        )

        verify {
            onboardingService.onboardNewAccounts(
                listOf(newAccount),
                match { it.mode == AccountOnboardingMode.DIRECT && !it.sendWelcomeEmail },
                9L,
                false
            )
        }
    }

    @Test
    fun `an explicit mode opts into the welcome mail`() {
        service.execute(request(mode = AccountOnboardingMode.GUIDED), requestorUserId = 9L)

        verify {
            onboardingService.onboardNewAccounts(
                any(),
                match { it.mode == AccountOnboardingMode.GUIDED && it.sendWelcomeEmail },
                9L,
                false
            )
        }
    }

    @Test
    fun `the welcome mail can be forced off on an explicit mode`() {
        service.execute(
            request(mode = AccountOnboardingMode.GUIDED, sendWelcomeEmail = false),
            requestorUserId = 9L
        )

        verify {
            onboardingService.onboardNewAccounts(any(), match { !it.sendWelcomeEmail }, 9L, false)
        }
    }

    @Test
    fun `the assessment outcome is returned to the caller`() {
        val result = service.execute(
            request(startRiskAssessment = true, useCase = "Cloud Onboarding"),
            requestorUserId = 9L
        )

        assertThat(result.riskAssessments).hasSize(1)
        assertThat(result.riskAssessments.single().releaseVersion).isEqualTo("2.3.0")
        assertThat(result.onboarding).hasSize(1)
    }

    @Test
    fun `a dry run still reports what onboarding would do, but sends no operator mail`() {
        // Deliberately different from the other two side effects: the notification is skipped
        // outright, while onboarding is invoked *with* dryRun so it can report a preview. That
        // is the whole point of asking for a dry run.
        service.execute(
            request(
                dryRun = true, notify = true, notifyAddress = "ops@corp.com",
                startRiskAssessment = true, useCase = "Cloud Onboarding"
            ),
            requestorUserId = 9L
        )

        verify(exactly = 0) { newAccountNotificationService.sendImportNotification(any(), any()) }
        verify { onboardingService.onboardNewAccounts(any(), any(), 9L, true) }
    }

    @Test
    fun `no new accounts means no notification and no onboarding`() {
        every { userMappingService.bulkCreateMappings(any()) } returns response(newAccounts = emptyList())

        service.execute(
            request(
                notify = true, notifyAddress = "ops@corp.com",
                startRiskAssessment = true, useCase = "Cloud Onboarding"
            ),
            requestorUserId = 9L
        )

        verify(exactly = 0) { newAccountNotificationService.sendImportNotification(any(), any()) }
        verify(exactly = 0) { onboardingService.onboardNewAccounts(any(), any(), any(), any()) }
    }

    @Test
    fun `an ordinary import with no onboarding requested does nothing extra`() {
        service.execute(request(), requestorUserId = 9L)

        verify(exactly = 0) { onboardingService.onboardNewAccounts(any(), any(), any(), any()) }
    }

    @Test
    fun `a failed notification is reported without hiding the imported mappings`() {
        every { newAccountNotificationService.sendImportNotification(any(), any()) } returns false

        val result = service.execute(
            request(notify = true, notifyAddress = "ops@corp.com"),
            requestorUserId = 9L
        )

        assertThat(result.created).isEqualTo(1)
        assertThat(result.notificationSent).isFalse()
        assertThat(result.notificationError).isNotNull()
        assertThat(result.notificationRecipient).isEqualTo("ops@corp.com")
    }

    // --- Workgroup linking (display_name) ---

    @Test
    fun `an entry with no display name links nothing`() {
        every { userMappingService.bulkCreateMappings(any()) } returns response()

        val result = service.execute(request(), requestorUserId = 7L)

        // Not "linked zero accounts" — not called at all, which is what keeps every
        // pre-existing Excel/CSV import byte-identical.
        verify(exactly = 0) { workgroupAccountLinkService.link(any(), any(), any()) }
        assertThat(result.workgroupLinks).isNull()
    }

    @Test
    fun `a display name is linked to its workgroup after the import committed`() {
        every { userMappingService.bulkCreateMappings(any()) } returns response()
        every { workgroupAccountLinkService.link(any(), any(), any()) } returns
            com.secman.dto.WorkgroupAccountLinkSummary(processed = 1, linked = 1)

        val request = BulkUserMappingRequest(
            mappings = listOf(
                BulkUserMappingEntry("alice@corp.com", "111111111111", null, "DevOps-x")
            )
        )

        val result = service.execute(request, requestorUserId = 7L)

        val pairs = slot<List<WorkgroupAccountLinkService.AccountDisplayName>>()
        verify { workgroupAccountLinkService.link(capture(pairs), 7L, false) }
        assertThat(pairs.captured).containsExactly(
            WorkgroupAccountLinkService.AccountDisplayName("111111111111", "DevOps-x")
        )
        assertThat(result.workgroupLinks?.linked).isEqualTo(1)

        // Ordering is the contract: mappings first, linking after they committed.
        verifyOrder {
            userMappingService.bulkCreateMappings(any())
            workgroupAccountLinkService.link(any(), any(), any())
        }
    }

    @Test
    fun `a dry run asks for a dry-run linking too`() {
        every { userMappingService.bulkCreateMappings(any()) } returns response()

        val request = BulkUserMappingRequest(
            mappings = listOf(
                BulkUserMappingEntry("alice@corp.com", "111111111111", null, "DevOps-x")
            ),
            dryRun = true
        )

        service.execute(request, requestorUserId = 7L)

        verify { workgroupAccountLinkService.link(any(), 7L, true) }
    }

    @Test
    fun `a linking failure never loses the import result`() {
        every { userMappingService.bulkCreateMappings(any()) } returns response()
        every { workgroupAccountLinkService.link(any(), any(), any()) } throws
            RuntimeException("workgroup table is on fire")

        val request = BulkUserMappingRequest(
            mappings = listOf(
                BulkUserMappingEntry("alice@corp.com", "111111111111", null, "DevOps-x")
            )
        )

        val result = service.execute(request, requestorUserId = 7L)

        // The mappings are already committed at this point; the failure is reported.
        assertThat(result.created).isEqualTo(1)
        assertThat(result.workgroupLinks?.failed).isEqualTo(1)
    }

    @Test
    fun `an entry with a display name but no account id is not a linking candidate`() {
        every { userMappingService.bulkCreateMappings(any()) } returns response()

        val request = BulkUserMappingRequest(
            mappings = listOf(
                BulkUserMappingEntry("alice@corp.com", null, "corp.com", "DevOps-x")
            )
        )

        service.execute(request, requestorUserId = 7L)

        verify(exactly = 0) { workgroupAccountLinkService.link(any(), any(), any()) }
    }
}
