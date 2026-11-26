package com.secman.service.cyberdef

import com.secman.domain.CyberdefTicketNotification
import com.secman.domain.CyberdefTicketStatus
import com.secman.domain.User
import com.secman.domain.UserMapping
import com.secman.repository.CyberdefTicketEventRepository
import com.secman.repository.CyberdefTicketNotificationRepository
import com.secman.repository.CyberdefTicketRecipientRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.testutil.BaseIntegrationTest
import com.secman.testutil.TestDataFactory
import io.micronaut.data.model.Pageable
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * End-to-end through the real repositories: JPQL filters, account scoping via the same
 * rule the asset inventory uses, the outbox, and the event cursor.
 */
@MicronautTest(environments = ["test"], transactional = false, startApplication = false)
class CyberdefTicketIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var service: CyberdefTicketService
    @Inject lateinit var queries: CyberdefTicketQueryService
    @Inject lateinit var users: UserRepository
    @Inject lateinit var mappings: UserMappingRepository
    @Inject lateinit var events: CyberdefTicketEventRepository
    @Inject lateinit var recipients: CyberdefTicketRecipientRepository
    @Inject lateinit var notifications: CyberdefTicketNotificationRepository

    private val suffix = System.nanoTime().toString().takeLast(8)
    private val ownedAccount = "1" + suffix.padStart(11, '0')
    private val foreignAccount = "2" + suffix.padStart(11, '0')
    private lateinit var soc: CyberdefTicketActor
    private lateinit var accountOwner: CyberdefTicketActor
    private lateinit var outsider: CyberdefTicketActor

    @BeforeAll
    fun seed() {
        fun save(name: String, vararg roles: User.Role) =
            users.save(TestDataFactory.createUserWithRoles("$name$suffix", "$name$suffix@example.test", *roles))
        fun actorOf(user: User) = CyberdefTicketActor(user.id!!, user.username, user.email, user.roles.map { it.name }.toSet())
        val socUser = save("soc", User.Role.USER, User.Role.CYBERDEF)
        val ownerUser = save("owner", User.Role.USER)
        val outsiderUser = save("outsider", User.Role.USER)
        mappings.save(UserMapping(email = ownerUser.email, user = ownerUser, awsAccountId = ownedAccount,
            awsAccountName = "payments-prod", domain = null))
        mappings.save(UserMapping(email = outsiderUser.email, user = outsiderUser, awsAccountId = foreignAccount, domain = null))
        soc = actorOf(socUser)
        accountOwner = actorOf(ownerUser)
        outsider = actorOf(outsiderUser)
    }

    private fun submit(reference: String, account: String = ownedAccount, severity: String = "HIGH") =
        (service.create(soc, CyberdefTicketCreateRequest(
            externalReference = reference, awsAccountId = account, title = "Finding $reference",
            severity = severity, description = "Details for $reference"
        )) as CyberdefTicketResult.Ok).value

    @Test
    fun `a submission is routed, mailed, scoped and idempotent`() {
        val created = submit("IT-ROUTE-$suffix")
        assertThat(created.outcome).isEqualTo("CREATED")
        assertThat(created.recipientCount).isEqualTo(1)
        assertThat(created.ticketKey).matches("CD-\\d{4}-\\d{6}")
        val id = created.ticketId!!
        assertThat(recipients.findByTicketIdOrderByEmailAsc(id).map { it.email }).containsExactly(accountOwner.email)
        assertThat(notifications.findByTicketIdOrderByIdAsc(id)).hasSize(1)

        assertThat(submit("IT-ROUTE-$suffix").outcome).isEqualTo("DUPLICATE")
        assertThat(notifications.findByTicketIdOrderByIdAsc(id)).hasSize(1)

        assertThat(queries.detail(accountOwner, id)).isInstanceOf(CyberdefTicketResult.Ok::class.java)
        assertThat(queries.detail(outsider, id)).isEqualTo(CyberdefTicketResult.notFound)
        assertThat(queries.list(outsider, CyberdefTicketFilter(accountId = ownedAccount)).content).isEmpty()
        assertThat(queries.list(accountOwner, CyberdefTicketFilter(search = "IT-ROUTE-$suffix")).content.map { it.id })
            .containsExactly(id)
    }

    @Test
    fun `an unowned account is refused`() {
        val refused = (service.create(soc, CyberdefTicketCreateRequest(awsAccountId = "3" + suffix.padStart(11, '0'),
            title = "t", severity = "LOW", description = "d")) as CyberdefTicketResult.Ok).value
        assertThat(refused.errorCode).isEqualTo(CyberdefTicketResult.UNROUTABLE_ACCOUNT)
    }

    @Test
    fun `the full conversation is visible through the update cursor`() {
        val id = submit("IT-FLOW-$suffix").ticketId!!
        val start = events.findByTicketIdOrderByIdAsc(id, Pageable.from(0, 1)).single().id!! - 1

        fun version() = (queries.detail(accountOwner, id) as CyberdefTicketResult.Ok).value.ticket.version
        fun move(actor: CyberdefTicketActor, to: String, comment: String? = null) =
            service.transition(actor, id, to, comment, if (actor == soc) null else version())

        assertThat(move(accountOwner, "NEEDS_INFO", "Which snapshot?")).isInstanceOf(CyberdefTicketResult.Ok::class.java)
        assertThat((move(accountOwner, "RESOLVED", "done") as CyberdefTicketResult.Failed).code)
            .isEqualTo(CyberdefTicketWorkflow.INVALID_TRANSITION)
        assertThat(move(soc, "IN_PROGRESS", "db-prod-2026-11-20")).isInstanceOf(CyberdefTicketResult.Ok::class.java)
        assertThat(move(accountOwner, "RESOLVED", "Public restore removed")).isInstanceOf(CyberdefTicketResult.Ok::class.java)
        assertThat((service.transition(accountOwner, id, "ACKNOWLEDGED", null, 0) as CyberdefTicketResult.Failed).code)
            .isEqualTo(CyberdefTicketResult.VERSION_CONFLICT)
        val closed = move(soc, "CLOSED") as CyberdefTicketResult.Ok
        assertThat(closed.value.ticket.status).isEqualTo(CyberdefTicketStatus.CLOSED.name)
        assertThat(closed.value.allowedTransitions).isEmpty()

        val feed = events.findAfter(start, Instant.now().plusSeconds(60), Pageable.from(0, 100)).filter { it.ticketId == id }
        assertThat(feed.map { it.toStatus }).containsExactly(
            CyberdefTicketStatus.NEW, CyberdefTicketStatus.NEEDS_INFO, CyberdefTicketStatus.IN_PROGRESS,
            CyberdefTicketStatus.RESOLVED, CyberdefTicketStatus.CLOSED)
        // The defender's answer was mailed back to the account team.
        assertThat(notifications.findByTicketIdOrderByIdAsc(id).map { it.kind })
            .containsExactly(CyberdefTicketWriter.KIND_CREATED, CyberdefTicketWriter.KIND_DEFENDER_UPDATE)
    }

    @Test
    fun `counts and overdue filters run against the database`() {
        submit("IT-COUNT-$suffix", severity = "CRITICAL")
        val counts = queries.counts(accountOwner)
        assertThat(counts.open).isGreaterThanOrEqualTo(1)
        assertThat(counts.needsAction).isGreaterThanOrEqualTo(1)
        assertThat(queries.counts(outsider).byStatus.keys).doesNotContain("CLOSED")
        assertThat(queries.list(soc, CyberdefTicketFilter(overdueOnly = true, accountId = ownedAccount)).content).isEmpty()
    }

    @Test
    fun `outbox rows are claimed exactly once`() {
        val id = submit("IT-CLAIM-$suffix").ticketId!!
        val row = notifications.findByTicketIdOrderByIdAsc(id).single()
        // The async dispatcher may already own it; either way a second claim from PENDING must fail.
        notifications.claim(row.id!!, CyberdefTicketNotification.STATUS_PENDING, Instant.now())
        assertThat(notifications.claim(row.id!!, CyberdefTicketNotification.STATUS_PENDING, Instant.now())).isZero()
    }
}
