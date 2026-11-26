package com.secman.service.cyberdef

import com.secman.domain.User
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupAwsAccountRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

/** Unit tests for CyberdefRecipientResolver; no database, collaborators are mocked. */
class CyberdefRecipientResolverTest {
    private val mappings = mockk<UserMappingRepository>()
    private val workgroups = mockk<WorkgroupAwsAccountRepository>()
    private val users = mockk<UserRepository>()

    private fun resolver(cap: Int = 50, domains: String = "") =
        CyberdefRecipientResolver(mappings, workgroups, users, testSettings(maxRecipients = cap, overrideDomains = domains))

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

    @Test
    fun `an override may name known SecMan addresses and allowed domains only`() {
        val known = User(id = 4, username = "known", email = "known@corp.example", passwordHash = "x")
        every { users.findByEmailIgnoreCase(any()) } returns Optional.empty()
        every { users.findByEmailIgnoreCase("known@corp.example") } returns Optional.of(known)
        every { mappings.existsByEmail(any()) } returns false
        every { mappings.existsByEmail("future@corp.example") } returns true
        every { mappings.findRecipientRowsByAwsAccountId(any()) } returns listOf(arrayOf<Any?>("x@corp.example", null, "prod"))

        val accepted = resolver(domains = "soc.example").resolveOverride("123456789012",
            listOf("known@corp.example", "future@corp.example", "analyst@soc.example"))
        val routing = (accepted as CyberdefRecipientResolver.Override.Accepted).routing
        assertThat(routing.recipients).containsExactly(
            CyberdefRecipient("known@corp.example", 4L, CyberdefRecipientResolver.SOURCE_OVERRIDE),
            CyberdefRecipient("future@corp.example", null, CyberdefRecipientResolver.SOURCE_OVERRIDE),
            CyberdefRecipient("analyst@soc.example", null, CyberdefRecipientResolver.SOURCE_OVERRIDE)
        )
        assertThat(routing.accountName).isEqualTo("prod")

        val refused = resolver().resolveOverride("123456789012", listOf("known@corp.example", "stranger@elsewhere.example"))
        assertThat((refused as CyberdefRecipientResolver.Override.Refused).addresses).containsExactly("stranger@elsewhere.example")
    }

    @Test
    fun `an override never consults the account's owners`() {
        every { users.findByEmailIgnoreCase(any()) } returns Optional.empty()
        every { mappings.findRecipientRowsByAwsAccountId(any()) } returns emptyList()
        resolver(domains = "@SOC.example").resolveOverride("123456789012", listOf("a@soc.example"))
        io.mockk.verify(exactly = 0) { workgroups.findMemberContactsByAwsAccountId(any()) }
    }
}
