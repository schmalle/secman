package com.secman.service

import com.secman.domain.EolFinding
import com.secman.domain.EolStatus
import com.secman.domain.EolSubjectType
import com.secman.repository.EolFindingRepository
import com.secman.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.concurrent.CompletableFuture

/**
 * `--dry-run` and `--only-email` are the two safety valves an operator reaches
 * for before a real notification run: preview who would be mailed, or narrow a
 * run to one address while validating recipient resolution. Both are covered
 * here because [EolNotificationBoundaryTest] only exercises address validation,
 * not the run itself.
 *
 * ID prefix: ENS-*
 */
class EolNotificationServiceTest {

    private val eolFindingRepository = mockk<EolFindingRepository>()
    private val awsAccountRecipientResolver = mockk<AwsAccountRecipientResolver>(relaxed = true)
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val emailService = mockk<EmailService>()

    private val service = EolNotificationService(
        eolFindingRepository = eolFindingRepository,
        awsAccountRecipientResolver = awsAccountRecipientResolver,
        userRepository = userRepository,
        emailService = emailService,
        eolFindingTableRenderer = EolFindingTableRenderer()
    )

    private fun finding(assetId: Long, assetName: String, owner: String, component: String) = EolFinding(
        id = assetId,
        subjectType = EolSubjectType.ASSET_PRODUCT,
        assetId = assetId,
        assetName = assetName,
        assetOwner = owner,
        componentName = component,
        eolProductId = 1,
        eolProductKey = component.lowercase(),
        eolReleaseId = 1,
        eolCycle = "1.0",
        eolDate = LocalDate.now().plusMonths(3),
        status = EolStatus.APPROACHING_EOL,
        scanRunId = "run-1"
    )

    @Test
    @DisplayName("ENS-001: dry-run resolves every recipient but sends no mail")
    fun dryRunSendsNothing() {
        every {
            eolFindingRepository.findAssetFindingsWithEolBetween(any(), any(), any())
        } returns listOf(
            finding(1, "host-a", "alice@example.com", "OpenSSL"),
            finding(2, "host-b", "bob@example.com", "Java")
        )

        val response = service.sendEolNotifications(months = 12, dryRun = true)

        assertThat(response.dryRun).isTrue()
        assertThat(response.status).isEqualTo("SUCCESS")
        assertThat(response.recipientsResolved).isEqualTo(2)
        assertThat(response.emailsSent).isZero()
        assertThat(response.recipients).allSatisfy { assertThat(it.sent).isFalse() }
        verify(exactly = 0) { emailService.sendEmail(any(), any(), any(), any()) }
    }

    @Test
    @DisplayName("ENS-002: only-email restricts delivery to that address, case-insensitively")
    fun onlyEmailRestrictsDelivery() {
        every {
            eolFindingRepository.findAssetFindingsWithEolBetween(any(), any(), any())
        } returns listOf(
            finding(1, "host-a", "alice@example.com", "OpenSSL"),
            finding(2, "host-b", "bob@example.com", "Java")
        )
        every {
            emailService.sendEmail(any(), any(), any(), any())
        } returns CompletableFuture.completedFuture(true)

        val response = service.sendEolNotifications(months = 12, onlyEmail = "ALICE@Example.com")

        assertThat(response.recipientsResolved).isEqualTo(1)
        assertThat(response.recipients).hasSize(1)
        assertThat(response.recipients.single().email).isEqualTo("alice@example.com")
        assertThat(response.emailsSent).isEqualTo(1)
        verify(exactly = 1) { emailService.sendEmail("alice@example.com", any(), any(), any()) }
        verify(exactly = 0) { emailService.sendEmail("bob@example.com", any(), any(), any()) }
    }

    @Test
    @DisplayName("ENS-003: only-email combined with dry-run neither sends nor filters anything out silently")
    fun onlyEmailDryRunCombination() {
        every {
            eolFindingRepository.findAssetFindingsWithEolBetween(any(), any(), any())
        } returns listOf(
            finding(1, "host-a", "alice@example.com", "OpenSSL"),
            finding(2, "host-b", "bob@example.com", "Java")
        )

        val response = service.sendEolNotifications(months = 12, dryRun = true, onlyEmail = "bob@example.com")

        assertThat(response.dryRun).isTrue()
        assertThat(response.recipientsResolved).isEqualTo(1)
        assertThat(response.recipients.single().email).isEqualTo("bob@example.com")
        assertThat(response.emailsSent).isZero()
        verify(exactly = 0) { emailService.sendEmail(any(), any(), any(), any()) }
    }
}
