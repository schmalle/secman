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
    @Inject lateinit var submissions: CyberdefTicketSubmissionService
    @Inject lateinit var queries: CyberdefTicketQueryService
    @Inject lateinit var users: UserRepository
    @Inject lateinit var mappings: UserMappingRepository
    @Inject lateinit var events: CyberdefTicketEventRepository
    @Inject lateinit var recipients: CyberdefTicketRecipientRepository
    @Inject lateinit var notifications: CyberdefTicketNotificationRepository
    @Inject lateinit var directory: CyberdefAccountDirectory

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
        create(CyberdefTicketFields(externalReference = reference, awsAccountId = account, title = "Finding $reference",
            severity = severity, description = "Details for $reference"))

    private fun create(fields: CyberdefTicketFields, delivery: CyberdefDelivery? = null) =
        (submissions.create(soc, CyberdefTicketCreateRequest(fields, delivery)) as CyberdefTicketResult.Ok).value

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
        val refused = create(CyberdefTicketFields(awsAccountId = "3" + suffix.padStart(11, '0'),
            title = "t", severity = "LOW", description = "d"))
        assertThat(refused.errorCode).isEqualTo(CyberdefTicketResult.UNROUTABLE_ACCOUNT)
    }

    @Test
    fun `the full conversation is visible through the update cursor`() {
        val id = submit("IT-FLOW-$suffix").ticketId!!
        val start = events.findByTicketIdOrderByIdAsc(id, Pageable.from(0, 1)).single().id!! - 1

        fun version() = (queries.detail(accountOwner, id) as CyberdefTicketResult.Ok).value.ticket.version
        fun move(actor: CyberdefTicketActor, to: String, comment: String? = null) =
            service.transition(actor, id, to, comment, if (actor == soc) null else version())

        assertThat(service.comment(accountOwner, id, "Which snapshot?")).isInstanceOf(CyberdefTicketResult.Ok::class.java)
        assertThat((move(accountOwner, "CLOSED") as CyberdefTicketResult.Failed).code)
            .isEqualTo(CyberdefTicketWorkflow.COMMENT_REQUIRED)
        assertThat(move(accountOwner, "CLOSED", "Public restore removed")).isInstanceOf(CyberdefTicketResult.Ok::class.java)
        assertThat((service.transition(accountOwner, id, "OPEN", "again", 0) as CyberdefTicketResult.Failed).code)
            .isEqualTo(CyberdefTicketResult.VERSION_CONFLICT)
        val preview = service.previewTransition(soc, id, "OPEN", "Snapshot still public", null) as CyberdefTicketResult.Ok
        assertThat(preview.value.wouldNotify).containsExactly(accountOwner.email)
        assertThat(move(soc, "OPEN", "Snapshot still public")).isInstanceOf(CyberdefTicketResult.Ok::class.java)
        val closed = move(soc, "CLOSED") as CyberdefTicketResult.Ok
        assertThat(closed.value.ticket.status).isEqualTo(CyberdefTicketStatus.CLOSED.name)
        assertThat(closed.value.allowedTransitions).containsExactly("OPEN")

        val feed = events.findAfter(start, Instant.now().plusSeconds(60), Pageable.from(0, 100)).filter { it.ticketId == id }
        assertThat(feed.map { it.eventType.name to it.toStatus }).containsExactly(
            "CREATED" to CyberdefTicketStatus.OPEN, "COMMENT" to null, "STATUS_CHANGED" to CyberdefTicketStatus.CLOSED,
            "STATUS_CHANGED" to CyberdefTicketStatus.OPEN, "STATUS_CHANGED" to CyberdefTicketStatus.CLOSED)
        // Each Cyber Defence change was mailed to the account team; the owner's close was not (notify-submitter off).
        assertThat(notifications.findByTicketIdOrderByIdAsc(id).map { it.kind }).containsExactly(
            CyberdefTicketWriter.KIND_CREATED, CyberdefTicketWriter.KIND_DEFENDER_UPDATE, CyberdefTicketWriter.KIND_DEFENDER_UPDATE)
    }

    @Test
    fun `a dry run leaves no trace and an override mails only the named known address`() {
        val before = events.count()
        val preview = create(CyberdefTicketFields(externalReference = "IT-DRY-$suffix",
            awsAccountId = ownedAccount, title = "t", severity = "LOW", description = "d"), CyberdefDelivery(dryRun = true))
        assertThat(preview.outcome).isEqualTo("WOULD_CREATE")
        assertThat(preview.recipients).containsExactly(accountOwner.email)
        assertThat(events.count()).isEqualTo(before)

        val overridden = create(CyberdefTicketFields(externalReference = "IT-OVR-$suffix",
            awsAccountId = ownedAccount, title = "t", severity = "LOW", description = "d"),
            CyberdefDelivery(recipients = listOf(outsider.email!!)))
        assertThat(overridden.outcome).isEqualTo("CREATED")
        assertThat(recipients.findByTicketIdOrderByEmailAsc(overridden.ticketId!!).map { it.email to it.source })
            .containsExactly(outsider.email!! to CyberdefRecipientResolver.SOURCE_OVERRIDE)
        // Being mailed grants no access: the outsider still cannot open the ticket.
        assertThat(queries.detail(outsider, overridden.ticketId)).isEqualTo(CyberdefTicketResult.notFound)

        val refused = create(CyberdefTicketFields(awsAccountId = ownedAccount, title = "t",
            severity = "LOW", description = "d"), CyberdefDelivery(recipients = listOf("nobody-$suffix@unknown.example")))
        assertThat(refused.errorCode).isEqualTo(CyberdefTicketResult.RECIPIENT_NOT_ALLOWED)
    }

    @Test
    fun `counts and overdue filters run against the database`() {
        submit("IT-COUNT-$suffix", severity = "CRITICAL")
        val counts = queries.counts(accountOwner)
        assertThat(counts.open).isGreaterThanOrEqualTo(1)
        assertThat(counts.total).isEqualTo(counts.open + counts.closed)
        assertThat(queries.counts(outsider).total).isZero()
        assertThat(queries.list(soc, CyberdefTicketFilter(overdueOnly = true, accountId = ownedAccount)).content).isEmpty()
    }

    @Test
    fun `open ticket pages use the default most recently updated sort`() {
        val search = "IT-LIST-$suffix"
        val older = submit("$search-older").ticketId!!
        val newer = submit("$search-newer").ticketId!!
        val closed = submit("$search-closed").ticketId!!
        assertThat(service.transition(soc, closed, "CLOSED", null, null))
            .isInstanceOf(CyberdefTicketResult.Ok::class.java)

        val filter = CyberdefTicketFilter(statuses = setOf(CyberdefTicketStatus.OPEN), search = search, size = 1)
        val first = queries.list(accountOwner, filter)
        val second = queries.list(accountOwner, filter.copy(page = 1))
        assertThat(first.totalElements).isEqualTo(2)
        assertThat(first.totalPages).isEqualTo(2)
        assertThat(first.content.map { it.id }).containsExactly(newer)
        assertThat(second.content.map { it.id }).containsExactly(older)
    }

    @Test
    fun `outbox rows are claimed exactly once`() {
        val id = submit("IT-CLAIM-$suffix").ticketId!!
        val row = notifications.findByTicketIdOrderByIdAsc(id).single()
        // The async dispatcher may already own it; either way a second claim from PENDING must fail.
        notifications.claim(row.id!!, CyberdefTicketNotification.STATUS_PENDING, Instant.now())
        assertThat(notifications.claim(row.id!!, CyberdefTicketNotification.STATUS_PENDING, Instant.now())).isZero()
    }

    @Test
    fun `a batch for one account queues one digest row group and the account directory sees it`() {
        val batch = (submissions.createBatch(soc, CyberdefBatchCreateRequest(listOf(
            CyberdefTicketFields(externalReference = "IT-B1-$suffix", awsAccountId = ownedAccount, title = "a", severity = "LOW", description = "d"),
            CyberdefTicketFields(externalReference = "IT-B2-$suffix", awsAccountId = ownedAccount, title = "b", severity = "LOW", description = "d")
        ))) as CyberdefTicketResult.Ok).value
        val rows = batch.results.flatMap { notifications.findByTicketIdOrderByIdAsc(it.ticketId!!) }
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.batchKey }.distinct()).hasSize(1).doesNotContainNull()

        val found = directory.search(ownedAccount.take(6), 20).first { it.awsAccountId == ownedAccount }
        assertThat(found.accountName).isEqualTo("payments-prod")
        assertThat(found.recipientCount).isEqualTo(1)
    }

    @Test
    fun `a ticket is found by its derived key`() {
        val created = submit("IT-KEY-$suffix")
        val byKey = queries.find(soc, created.ticketKey, null) as CyberdefTicketResult.Ok
        assertThat(byKey.value.ticket.id).isEqualTo(created.ticketId)
        assertThat(queries.find(soc, "CD-1999-%06d".format(created.ticketId), null)).isEqualTo(CyberdefTicketResult.notFound)
    }
}
