package com.secman.service

import com.secman.domain.UserMapping
import com.secman.repository.AssetRepository
import com.secman.repository.UserMappingRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AwsAccountRecipientResolverTest {

    private val userMappingRepository = mockk<UserMappingRepository>(relaxed = true)
    private val assetRepository = mockk<AssetRepository>(relaxed = true)
    private val awsAccountSharingService = mockk<AwsAccountSharingService>(relaxed = true)
    private val resolver = AwsAccountRecipientResolver(
        userMappingRepository = userMappingRepository,
        assetRepository = assetRepository,
        awsAccountSharingService = awsAccountSharingService
    )

    @Test
    fun `unions owners, workgroup members and sharing targets, deduped case-insensitively`() {
        every { userMappingRepository.findByAwsAccountId("123456789012") } returns listOf(
            UserMapping(email = "Owner@example.com", awsAccountId = "123456789012", domain = null)
        )
        every { assetRepository.findDistinctWorkgroupMemberEmailsByCloudAccountId("123456789012") } returns
            listOf("wg-member@example.com", "OWNER@example.com")
        every { awsAccountSharingService.getTargetUserEmailsForAwsAccount("123456789012") } returns
            listOf("Shared-User@example.com")

        val recipients = resolver.resolveAwsAccountRecipients("123456789012")

        assertThat(recipients).containsExactlyInAnyOrder(
            "owner@example.com", "wg-member@example.com", "shared-user@example.com"
        )
    }

    @Test
    fun `returns workgroup members even without an owner mapping`() {
        every { userMappingRepository.findByAwsAccountId("444444444444") } returns emptyList()
        every { assetRepository.findDistinctWorkgroupMemberEmailsByCloudAccountId("444444444444") } returns
            listOf("wg-only@example.com")
        every { awsAccountSharingService.getTargetUserEmailsForAwsAccount("444444444444") } returns emptyList()

        val recipients = resolver.resolveAwsAccountRecipients("444444444444")

        assertThat(recipients).containsExactly("wg-only@example.com")
    }

    @Test
    fun `blank account id yields no recipients and skips all lookups`() {
        val recipients = resolver.resolveAwsAccountRecipients("")

        assertThat(recipients).isEmpty()
        verify(exactly = 0) { userMappingRepository.findByAwsAccountId(any()) }
        verify(exactly = 0) { assetRepository.findDistinctWorkgroupMemberEmailsByCloudAccountId(any()) }
        verify(exactly = 0) { awsAccountSharingService.getTargetUserEmailsForAwsAccount(any()) }
    }
}
