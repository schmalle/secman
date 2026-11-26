package com.secman.service.cyberdef

import com.secman.domain.CyberdefActorKind
import com.secman.domain.CyberdefTicket
import com.secman.repository.CyberdefTicketRepository
import com.secman.service.AssetFilterService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

/** Unit tests for CyberdefTicketAccess; no database, collaborators are mocked. */
class CyberdefTicketAccessTest {
    private val tickets = mockk<CyberdefTicketRepository>()
    private val assetFilter = mockk<AssetFilterService>()
    private val access = CyberdefTicketAccess(tickets, assetFilter)
    private val ticket = CyberdefTicket(id = 10, awsAccountId = "123456789012", title = "t", description = "d",
        createdByUserId = 1, createdByUsername = "soc")

    @Test
    fun `account members see their account's tickets and nothing else`() {
        every { tickets.findById(10) } returns Optional.of(ticket)
        every { assetFilter.getAccessibleAwsAccountIds(owner.userId, owner.email) } returns setOf("123456789012")
        assertThat(access.loadVisible(owner, 10)).isSameAs(ticket)

        every { assetFilter.getAccessibleAwsAccountIds(owner.userId, owner.email) } returns setOf("999999999999")
        assertThat(access.loadVisible(owner, 10)).isNull()
        assertThat(access.scope(owner)).isEqualTo(CyberdefAccountScope(false, setOf("999999999999")))
    }

    @Test
    fun `defence and champions have the global view without an account lookup`() {
        every { tickets.findById(10) } returns Optional.of(ticket)
        assertThat(access.scope(defender).all).isTrue()
        assertThat(access.loadVisible(champion, 10)).isSameAs(ticket)
        verify(exactly = 0) { assetFilter.getAccessibleAwsAccountIds(any<Long>(), any()) }
    }

    @Test
    fun `workflow sides follow role and account access`() {
        every { assetFilter.getAccessibleAwsAccountIds(any<Long>(), any()) } returns emptySet()
        assertThat(access.kinds(defender, ticket)).containsExactly(CyberdefActorKind.DEFENDER)
        assertThat(access.kinds(champion, ticket)).containsExactly(CyberdefActorKind.OWNER)
        assertThat(access.kinds(owner, ticket)).isEmpty()

        every { assetFilter.getAccessibleAwsAccountIds(defender.userId, any()) } returns setOf("123456789012")
        assertThat(access.kinds(defender, ticket)).containsExactlyInAnyOrder(CyberdefActorKind.OWNER, CyberdefActorKind.DEFENDER)
    }

    @Test
    fun `an empty scope still produces a valid IN list`() {
        assertThat(CyberdefAccountScope(false, emptySet()).queryIds).isNotEmpty()
    }
}
