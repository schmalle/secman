package com.secman.service

import com.secman.domain.IntegrationScanner
import io.mockk.*
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.TypedQuery
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class IntegrationHealthRepositoryTest {
    @Test fun `stale incident is claimed once and rearmed after recovery`() {
        val em = mockk<EntityManager>()
        val query = mockk<TypedQuery<Long>>()
        val scanner = IntegrationScanner(id = 1)
        val now = Instant.parse("2026-09-06T12:00:00Z")
        every { em.find(IntegrationScanner::class.java, 1L, LockModeType.PESSIMISTIC_WRITE) } returns scanner
        every { em.createQuery(any<String>(), Long::class.javaObjectType) } returns query
        every { query.setParameter(any<String>(), any()) } returns query
        every { query.singleResult } returnsMany listOf(1L, 1L, 0L, 1L)
        val repository = IntegrationHealthRepository(em)
        assertTrue(repository.claimStaleTransition(1, now))
        assertEquals(now, scanner.lastStaleNotifiedAt)
        assertFalse(repository.claimStaleTransition(1, now))
        assertFalse(repository.claimStaleTransition(1, now))
        assertNull(scanner.lastStaleNotifiedAt)
        assertTrue(repository.claimStaleTransition(1, now))
        verify { query.setParameter("cutoff", now.minusSeconds(24 * 3600L)) }
    }
}
