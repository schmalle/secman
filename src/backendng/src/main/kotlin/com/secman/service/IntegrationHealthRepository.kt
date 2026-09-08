package com.secman.service

import com.secman.domain.IntegrationScanner
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.Instant

@Singleton
@Transactional
open class IntegrationHealthRepository(private val em: EntityManager) {
    open fun scannerIdsAfter(after: Long): List<Long> = em.createQuery(
        "SELECT c.id FROM IntegrationScanner c WHERE c.id > :after ORDER BY c.id", Long::class.javaObjectType
    ).setParameter("after", after).setMaxResults(100).resultList

    /** The scanner lock makes each stale incident claim exclusive across backend nodes. */
    open fun claimStaleTransition(id: Long, now: Instant): Boolean {
        val scanner = em.find(IntegrationScanner::class.java, id, LockModeType.PESSIMISTIC_WRITE) ?: return false
        val stale = scanner.enabled && em.createQuery(
            "SELECT COUNT(s) FROM IntegrationSubject s WHERE s.scannerId = :scanner AND " +
                "coalesce(s.lastSuccessfulScanAt, s.createdAt) < :cutoff", Long::class.javaObjectType
        ).setParameter("scanner", id).setParameter("cutoff", now.minusSeconds(scanner.staleAfterHours * 3600L)).singleResult > 0
        if (!stale) {
            scanner.lastStaleNotifiedAt = null
            return false
        }
        if (scanner.lastStaleNotifiedAt != null) return false
        scanner.lastStaleNotifiedAt = now
        return true
    }
}
