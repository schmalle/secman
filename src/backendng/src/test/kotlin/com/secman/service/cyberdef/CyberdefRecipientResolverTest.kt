package com.secman.service.cyberdef

import com.secman.repository.UserMappingRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for CyberdefRecipientResolver; no database, collaborators are mocked. */
class CyberdefRecipientResolverTest {
    private val mappings = mockk<UserMappingRepository>()
    private val workgroups = mockk<WorkgroupAwsAccountRepository>()

    private fun resolver(cap: Int = 50) = CyberdefRecipientResolver(mappings, workgroups, testSettings(maxRecipients = cap))

    @Test
    fun `mapping and workgroup members are merged, normalised and deduplicated`() {
        every { mappings.findRecipientRowsByAwsAccountId("123456789012") } returns listOf(
            arrayOf<Any?>(" Owner@Example.test ", 2L, "prod-payments"),
            arrayOf<Any?>("future@example.test", null, null)
        )
        every { workgroups.findMemberContactsByAwsAccountId("123456789012") } returns listOf(
            arrayOf<Any?>(2L, "owner@example.test"),
            arrayOf<Any?>(7L, "member@example.test")
        )
        val routing = resolver().resolve("123456789012")
        assertThat(routing.recipients).containsExactly(
            CyberdefRecipient("owner@example.test", 2L, CyberdefRecipientResolver.SOURCE_MAPPING),
            CyberdefRecipient("future@example.test", null, CyberdefRecipientResolver.SOURCE_MAPPING),
            CyberdefRecipient("member@example.test", 7L, CyberdefRecipientResolver.SOURCE_WORKGROUP)
        )
        assertThat(routing.accountName).isEqualTo("prod-payments")
        assertThat(routing.truncated).isFalse()
    }

    @Test
    fun `invalid addresses are dropped and the cap is reported`() {
        every { mappings.findRecipientRowsByAwsAccountId(any()) } returns listOf(
            arrayOf<Any?>("not-an-address", 1L, null),
            arrayOf<Any?>("a@example.test", 1L, null),
            arrayOf<Any?>("b@example.test", 2L, null)
        )
        every { workgroups.findMemberContactsByAwsAccountId(any()) } returns emptyList()
        val routing = resolver(cap = 1).resolve("123456789012")
        assertThat(routing.recipients.map { it.email }).containsExactly("a@example.test")
        assertThat(routing.truncated).isTrue()
    }

    @Test
    fun `an account nobody owns has no recipients`() {
        every { mappings.findRecipientRowsByAwsAccountId(any()) } returns emptyList()
        every { workgroups.findMemberContactsByAwsAccountId(any()) } returns emptyList()
        assertThat(resolver().resolve("123456789012").recipients).isEmpty()
    }
}
