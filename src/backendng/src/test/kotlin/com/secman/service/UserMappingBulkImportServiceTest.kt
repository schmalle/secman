package com.secman.service

import com.secman.dto.AccountRiskAssessmentInfo
import com.secman.dto.BulkUserMappingEntry
import com.secman.dto.BulkUserMappingRequest
import com.secman.dto.BulkUserMappingResponse
import com.secman.dto.NewAccountImportInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Guards the contract REST `POST /api/user-mappings/bulk` and MCP
 * `import_user_mappings` share: validate up front, import, then run the
 * side effects only after the import has committed.
 */
class UserMappingBulkImportServiceTest {

    private val userMappingService = mockk<UserMappingService>(relaxed = true)
    private val newAccountNotificationService = mockk<NewAccountNotificationService>(relaxed = true)
    private val riskAssessmentService = mockk<AwsAccountRiskAssessmentService>(relaxed = true)

    private val service = UserMappingBulkImportService(
        userMappingService, newAccountNotificationService, riskAssessmentService
    )

    private val newAccount = NewAccountImportInfo("111111111111", listOf("alice@corp.com"))

    private fun request(
        dryRun: Boolean = false,
        notify: Boolean = false,
        notifyAddress: String? = null,
        startRiskAssessment: Boolean = false,
        useCase: String? = null,
        deadlineDays: Int? = null
    ) = BulkUserMappingRequest(
        mappings = listOf(BulkUserMappingEntry("alice@corp.com", "111111111111", null)),
        dryRun = dryRun,
        notifyNewAccounts = notify,
        notifyAddress = notifyAddress,
        startRiskAssessment = startRiskAssessment,
        riskAssessmentUseCase = useCase,
        riskAssessmentDeadlineDays = deadlineDays
    )

    private fun response(newAccounts: List<NewAccountImportInfo> = listOf(newAccount)) =
        BulkUserMappingResponse(
            totalProcessed = 1, created = 1, createdPending = 0, skipped = 0,
            errors = emptyList(), newAccounts = newAccounts
        )

    @BeforeEach
    fun setup() {
        every { userMappingService.bulkCreateMappings(any()) } returns response()
        every { riskAssessmentService.validateStartRequest(any(), any()) } returns null
        every { riskAssessmentService.startAssessmentsForNewAccounts(any(), any(), any(), any()) } returns
            listOf(
                AccountRiskAssessmentInfo(
                    awsAccountId = "111111111111", ownerEmail = "alice@corp.com",
                    riskAssessmentId = 1000L, releaseVersion = "2.3.0", useCase = "Cloud Onboarding"
                )
            )
        every { newAccountNotificationService.sendImportNotification(any(), any()) } returns true
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
    fun `risk assessment validation is delegated to the risk assessment service`() {
        every { riskAssessmentService.validateStartRequest("Cloud Onboarding", 7) } returns
            "No ACTIVE release exists to base the risk assessment on"

        assertThat(
            service.validate(
                request(startRiskAssessment = true, useCase = "Cloud Onboarding", deadlineDays = 7)
            )
        ).contains("No ACTIVE release")
    }

    @Test
    fun `risk assessment validation is skipped when the option is off`() {
        service.validate(request(startRiskAssessment = false, useCase = "Cloud Onboarding"))

        verify(exactly = 0) { riskAssessmentService.validateStartRequest(any(), any()) }
    }

    // --- execute --------------------------------------------------------------

    @Test
    fun `side effects run only after the import returns`() {
        // Load-bearing ordering: the notification and the assessments must not run
        // inside the import transaction, or a slow SMTP send holds a pooled DB
        // connection and a failure rolls back mappings that should have persisted.
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
            riskAssessmentService.startAssessmentsForNewAccounts(any(), any(), any(), any())
        }
    }

    @Test
    fun `assessments are started with the requestor and the default deadline`() {
        service.execute(
            request(startRiskAssessment = true, useCase = "Cloud Onboarding", deadlineDays = null),
            requestorUserId = 9L
        )

        verify {
            riskAssessmentService.startAssessmentsForNewAccounts(
                listOf(newAccount),
                "Cloud Onboarding",
                AwsAccountRiskAssessmentService.DEFAULT_DEADLINE_DAYS,
                9L
            )
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
    }

    @Test
    fun `dry run imports nothing and triggers no side effects`() {
        service.execute(
            request(
                dryRun = true, notify = true, notifyAddress = "ops@corp.com",
                startRiskAssessment = true, useCase = "Cloud Onboarding"
            ),
            requestorUserId = 9L
        )

        verify(exactly = 0) { newAccountNotificationService.sendImportNotification(any(), any()) }
        verify(exactly = 0) { riskAssessmentService.startAssessmentsForNewAccounts(any(), any(), any(), any()) }
    }

    @Test
    fun `no new accounts means no notification and no assessments`() {
        every { userMappingService.bulkCreateMappings(any()) } returns response(newAccounts = emptyList())

        service.execute(
            request(
                notify = true, notifyAddress = "ops@corp.com",
                startRiskAssessment = true, useCase = "Cloud Onboarding"
            ),
            requestorUserId = 9L
        )

        verify(exactly = 0) { newAccountNotificationService.sendImportNotification(any(), any()) }
        verify(exactly = 0) { riskAssessmentService.startAssessmentsForNewAccounts(any(), any(), any(), any()) }
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
}
