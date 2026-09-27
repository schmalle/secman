package com.secman.service

import com.secman.domain.OwnerMailNotification
import com.secman.repository.OwnerMailNotificationRepository
import com.secman.testutil.BaseIntegrationTest
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@MicronautTest(environments = ["test"], transactional = false, startApplication = false)
class OwnerMailDeliveryIntegrationTest : BaseIntegrationTest() {
    @Inject lateinit var repository: OwnerMailNotificationRepository
    @Inject lateinit var service: OwnerMailDeliveryService

    @Test fun `concurrent event reservations and retries have exactly one winner`() {
        val now = LocalDateTime.now()
        val key = OwnerMailDeliveryService.eventKey("123456789012", "owner-${System.nanoTime()}@example.test")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val reservations = (1..4).map { pool.submit<Int> {
                repository.reserve(key, "123456789012", "owner@example.test", now)
            } }
            assertEquals(1, reservations.sumOf { it.get(20, TimeUnit.SECONDS) })
            val id = repository.findByEventKey(key).orElseThrow().id!!
            val claims = (1..4).map { pool.submit<Int> { repository.claim(id, false, now, now.minusDays(90)) } }
            assertEquals(1, claims.sumOf { it.get(20, TimeUnit.SECONDS) })
            repository.finish(id, "FAILED", "NO_ACTIVE_PROVIDER", null, now)
            val retries = (1..4).map { pool.submit<Int> { repository.claim(id, true, now, now.minusDays(90)) } }
            assertEquals(1, retries.sumOf { it.get(20, TimeUnit.SECONDS) })
            repository.finish(id, "SENT", null, "provider-id", now)
            assertEquals(0, repository.claim(id, true, now, now.minusDays(90)))
            assertEquals(0, repository.claim(id, false, now, now.minusDays(90)))
        } finally { pool.shutdownNow() }
    }

    @Test fun `retention removes recipient metadata but keeps the deduplication tombstone`() {
        val created = LocalDateTime.now().minusDays(91)
        val key = OwnerMailDeliveryService.eventKey("123456789012", "old-${System.nanoTime()}@example.test")
        val row = repository.save(OwnerMailNotification(eventKey = key, awsAccountId = "123456789012",
            ownerEmail = "old@example.test", status = "SENT", providerMessageId = "message", errorCode = "old",
            createdAt = created, updatedAt = created))
        service.redactExpired()
        val redacted = repository.findById(row.id!!).orElseThrow()
        assertNull(redacted.ownerEmail)
        assertNull(redacted.awsAccountId)
        assertNull(redacted.providerMessageId)
        assertNull(redacted.errorCode)
        assertEquals("SENT", redacted.status)
        assertEquals(0, repository.reserve(key, "123456789012", "old@example.test", LocalDateTime.now()))
        assertThrows(IllegalStateException::class.java) { service.retry(row.id!!, 1) }
    }

    @Test fun `dry run and opt out create no delivery records`() {
        val before = repository.count()
        assertEquals("WOULD_SEND", service.welcome("123456789012", "owner@example.test", true, true).status)
        assertEquals("SKIPPED", service.welcome("123456789012", "owner@example.test", false, false).status)
        assertEquals(before, repository.count())
    }
}
