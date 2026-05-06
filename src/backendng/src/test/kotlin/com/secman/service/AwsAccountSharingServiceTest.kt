package com.secman.service

import com.secman.domain.AwsAccountSharing
import com.secman.domain.AwsAccountSharingCreatedEvent
import com.secman.domain.User
import com.secman.dto.CreateAwsAccountSharingRequest
import com.secman.dto.UpdateAwsAccountSharingRequest
import com.secman.repository.AwsAccountSharingRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import io.micronaut.context.event.ApplicationEventPublisher
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional

class AwsAccountSharingServiceTest {

    private lateinit var repo: AwsAccountSharingRepository
    private lateinit var userRepo: UserRepository
    private lateinit var mappingRepo: UserMappingRepository
    private lateinit var resolver: UserResolutionService
    private lateinit var publisher: ApplicationEventPublisher<AwsAccountSharingCreatedEvent>
    private lateinit var service: AwsAccountSharingService

    private fun user(id: Long, email: String, username: String = email.substringBefore('@')) =
        User(id = id, username = username, email = email, passwordHash = "x")

    @BeforeEach
    fun setUp() {
        repo = mockk()
        userRepo = mockk()
        mappingRepo = mockk()
        resolver = mockk()
        publisher = mockk(relaxed = true)
        service = AwsAccountSharingService(repo, userRepo, mappingRepo, resolver, publisher)
    }

    @Test
    fun `publishes AwsAccountSharingCreatedEvent after successful save`() {
        val source = user(1L, "alice@example.com")
        val target = user(2L, "bob@example.com", "bob")
        val admin  = user(3L, "admin@example.com")

        every { resolver.resolveByIdOrEmail(any(), any(), "source") } returns source
        every { resolver.resolveByIdOrEmail(any(), any(), "target") } returns target
        every { userRepo.findById(3L) } returns Optional.of(admin)
        every { repo.existsBySourceUserIdAndTargetUserId(1L, 2L) } returns false
        every { mappingRepo.findDistinctAwsAccountIdByEmail("alice@example.com") } returns
            listOf("111111111111", "222222222222")

        val savedSharing = AwsAccountSharing(
            id = 99L, sourceUser = source, targetUser = target, createdBy = admin,
            createdAt = Instant.parse("2026-05-06T10:15:00Z"),
            updatedAt = Instant.parse("2026-05-06T10:15:00Z"),
        )
        every { repo.save(any()) } returns savedSharing

        val req = CreateAwsAccountSharingRequest(
            sourceUserId = 1L, sourceUserEmail = null,
            targetUserId = 2L, targetUserEmail = null,
        )

        service.createSharingRule(req, adminUserId = 3L)

        val eventSlot = slot<AwsAccountSharingCreatedEvent>()
        verify(exactly = 1) { publisher.publishEvent(capture(eventSlot)) }

        val ev = eventSlot.captured
        assertEquals(99L, ev.sharingId)
        assertEquals("alice@example.com", ev.sourceUserEmail)
        assertEquals(2L, ev.targetUserId)
        assertEquals("bob@example.com", ev.targetUserEmail)
        assertEquals("bob", ev.targetUsername)
        assertEquals("admin@example.com", ev.createdByEmail)
        assertEquals(2, ev.sharedAwsAccountCount)
        assertEquals("2026-05-06T10:15:00Z", ev.createdAtIso)
    }

    @Test
    fun `does not publish when source equals target`() {
        val same = user(1L, "alice@example.com")
        every { resolver.resolveByIdOrEmail(any(), any(), "source") } returns same
        every { resolver.resolveByIdOrEmail(any(), any(), "target") } returns same

        val req = CreateAwsAccountSharingRequest(
            sourceUserId = 1L, sourceUserEmail = null,
            targetUserId = 1L, targetUserEmail = null,
        )

        assertThrows(IllegalArgumentException::class.java) {
            service.createSharingRule(req, adminUserId = 1L)
        }
        verify(exactly = 0) { publisher.publishEvent(any()) }
    }

    @Test
    fun `does not publish when duplicate sharing exists`() {
        val source = user(1L, "alice@example.com")
        val target = user(2L, "bob@example.com")
        val admin  = user(3L, "admin@example.com")

        every { resolver.resolveByIdOrEmail(any(), any(), "source") } returns source
        every { resolver.resolveByIdOrEmail(any(), any(), "target") } returns target
        every { userRepo.findById(3L) } returns Optional.of(admin)
        every { repo.existsBySourceUserIdAndTargetUserId(1L, 2L) } returns true

        val req = CreateAwsAccountSharingRequest(
            sourceUserId = 1L, sourceUserEmail = null,
            targetUserId = 2L, targetUserEmail = null,
        )

        assertThrows(DuplicateSharingException::class.java) {
            service.createSharingRule(req, adminUserId = 3L)
        }
        verify(exactly = 0) { publisher.publishEvent(any()) }
    }

    @Test
    fun `does not publish when source has no AWS mappings`() {
        val source = user(1L, "alice@example.com")
        val target = user(2L, "bob@example.com")
        val admin  = user(3L, "admin@example.com")

        every { resolver.resolveByIdOrEmail(any(), any(), "source") } returns source
        every { resolver.resolveByIdOrEmail(any(), any(), "target") } returns target
        every { userRepo.findById(3L) } returns Optional.of(admin)
        every { repo.existsBySourceUserIdAndTargetUserId(1L, 2L) } returns false
        every { mappingRepo.findDistinctAwsAccountIdByEmail("alice@example.com") } returns emptyList()

        val req = CreateAwsAccountSharingRequest(
            sourceUserId = 1L, sourceUserEmail = null,
            targetUserId = 2L, targetUserEmail = null,
        )

        assertThrows(IllegalArgumentException::class.java) {
            service.createSharingRule(req, adminUserId = 3L)
        }
        verify(exactly = 0) { publisher.publishEvent(any()) }
    }

    @Test
    fun `create with awsAccountIds subset persists only intersection`() {
        val source = user(1L, "alice@example.com")
        val target = user(2L, "bob@example.com")
        val admin  = user(3L, "admin@example.com")

        every { resolver.resolveByIdOrEmail(any(), any(), "source") } returns source
        every { resolver.resolveByIdOrEmail(any(), any(), "target") } returns target
        every { userRepo.findById(3L) } returns Optional.of(admin)
        every { repo.existsBySourceUserIdAndTargetUserId(1L, 2L) } returns false
        // Source has 3 mapped accounts; user requests 2 of them plus an unknown one.
        every { mappingRepo.findDistinctAwsAccountIdByEmail("alice@example.com") } returns
            listOf("111111111111", "222222222222", "333333333333")

        val savedSlot = slot<AwsAccountSharing>()
        every { repo.save(capture(savedSlot)) } answers {
            savedSlot.captured.copy(
                id = 99L,
                createdAt = Instant.parse("2026-05-06T10:15:00Z"),
                updatedAt = Instant.parse("2026-05-06T10:15:00Z"),
            )
        }

        val req = CreateAwsAccountSharingRequest(
            sourceUserId = 1L, targetUserId = 2L,
            awsAccountIds = listOf("111111111111", "333333333333", "999999999999"), // last one is unknown
        )

        val result = service.createSharingRule(req, adminUserId = 3L)

        assertEquals(setOf("111111111111", "333333333333"), savedSlot.captured.selectedAwsAccountIds)
        assertEquals(2, result.sharedAwsAccountCount)
        assertFalse(result.shareAllAccounts)
        assertEquals(listOf("111111111111", "333333333333"), result.selectedAwsAccountIds)

        // Event reflects effective scope, not the size of the source's full mapping set.
        val ev = slot<AwsAccountSharingCreatedEvent>()
        verify(exactly = 1) { publisher.publishEvent(capture(ev)) }
        assertEquals(2, ev.captured.sharedAwsAccountCount)
    }

    @Test
    fun `create with awsAccountIds where none match source mappings throws`() {
        val source = user(1L, "alice@example.com")
        val target = user(2L, "bob@example.com")
        val admin  = user(3L, "admin@example.com")

        every { resolver.resolveByIdOrEmail(any(), any(), "source") } returns source
        every { resolver.resolveByIdOrEmail(any(), any(), "target") } returns target
        every { userRepo.findById(3L) } returns Optional.of(admin)
        every { repo.existsBySourceUserIdAndTargetUserId(1L, 2L) } returns false
        every { mappingRepo.findDistinctAwsAccountIdByEmail("alice@example.com") } returns
            listOf("111111111111")

        val req = CreateAwsAccountSharingRequest(
            sourceUserId = 1L, targetUserId = 2L,
            awsAccountIds = listOf("999999999999"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            service.createSharingRule(req, adminUserId = 3L)
        }
        verify(exactly = 0) { publisher.publishEvent(any()) }
        verify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `create with empty awsAccountIds shares all (legacy default)`() {
        val source = user(1L, "alice@example.com")
        val target = user(2L, "bob@example.com")
        val admin  = user(3L, "admin@example.com")

        every { resolver.resolveByIdOrEmail(any(), any(), "source") } returns source
        every { resolver.resolveByIdOrEmail(any(), any(), "target") } returns target
        every { userRepo.findById(3L) } returns Optional.of(admin)
        every { repo.existsBySourceUserIdAndTargetUserId(1L, 2L) } returns false
        every { mappingRepo.findDistinctAwsAccountIdByEmail("alice@example.com") } returns
            listOf("111111111111", "222222222222")

        val savedSlot = slot<AwsAccountSharing>()
        every { repo.save(capture(savedSlot)) } answers {
            savedSlot.captured.copy(
                id = 100L,
                createdAt = Instant.parse("2026-05-06T10:15:00Z"),
                updatedAt = Instant.parse("2026-05-06T10:15:00Z"),
            )
        }

        val req = CreateAwsAccountSharingRequest(
            sourceUserId = 1L, targetUserId = 2L,
            awsAccountIds = emptyList(),
        )

        val result = service.createSharingRule(req, adminUserId = 3L)
        assertTrue(savedSlot.captured.selectedAwsAccountIds.isEmpty(), "empty awsAccountIds should map to share-all")
        assertTrue(result.shareAllAccounts)
        assertEquals(2, result.sharedAwsAccountCount)
        assertEquals(emptyList<String>(), result.selectedAwsAccountIds)
    }

    @Test
    fun `update replaces selection in place`() {
        val source = user(1L, "alice@example.com")
        val target = user(2L, "bob@example.com")
        val admin  = user(3L, "admin@example.com")

        val existing = AwsAccountSharing(
            id = 50L, sourceUser = source, targetUser = target, createdBy = admin,
            selectedAwsAccountIds = mutableSetOf("111111111111"),
            createdAt = Instant.parse("2026-05-06T10:15:00Z"),
            updatedAt = Instant.parse("2026-05-06T10:15:00Z"),
        )

        every { repo.findById(50L) } returns Optional.of(existing)
        every { mappingRepo.findDistinctAwsAccountIdByEmail("alice@example.com") } returns
            listOf("111111111111", "222222222222", "333333333333")
        every { repo.update(any()) } answers { firstArg() }

        val result = service.updateSharingRule(50L, UpdateAwsAccountSharingRequest(
            awsAccountIds = listOf("222222222222", "333333333333"),
        ))

        assertEquals(setOf("222222222222", "333333333333"), existing.selectedAwsAccountIds)
        assertEquals(2, result.sharedAwsAccountCount)
        assertFalse(result.shareAllAccounts)
    }

    @Test
    fun `update with empty awsAccountIds resets to share-all`() {
        val source = user(1L, "alice@example.com")
        val target = user(2L, "bob@example.com")
        val admin  = user(3L, "admin@example.com")

        val existing = AwsAccountSharing(
            id = 50L, sourceUser = source, targetUser = target, createdBy = admin,
            selectedAwsAccountIds = mutableSetOf("111111111111", "222222222222"),
            createdAt = Instant.parse("2026-05-06T10:15:00Z"),
            updatedAt = Instant.parse("2026-05-06T10:15:00Z"),
        )

        every { repo.findById(50L) } returns Optional.of(existing)
        every { mappingRepo.findDistinctAwsAccountIdByEmail("alice@example.com") } returns
            listOf("111111111111", "222222222222", "333333333333")
        every { repo.update(any()) } answers { firstArg() }

        val result = service.updateSharingRule(50L, UpdateAwsAccountSharingRequest(awsAccountIds = null))

        assertTrue(existing.selectedAwsAccountIds.isEmpty())
        assertTrue(result.shareAllAccounts)
        // share-all → effective count is the source's full mapping set size (3).
        assertEquals(3, result.sharedAwsAccountCount)
    }
}
