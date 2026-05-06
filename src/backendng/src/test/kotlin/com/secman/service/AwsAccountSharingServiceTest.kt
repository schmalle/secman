package com.secman.service

import com.secman.domain.AwsAccountSharing
import com.secman.domain.AwsAccountSharingCreatedEvent
import com.secman.domain.User
import com.secman.dto.CreateAwsAccountSharingRequest
import com.secman.repository.AwsAccountSharingRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import io.micronaut.context.event.ApplicationEventPublisher
import io.mockk.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
}
