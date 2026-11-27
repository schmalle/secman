package com.secman.repository

import com.secman.domain.CyberdefSeverity
import com.secman.domain.CyberdefTicket
import com.secman.domain.CyberdefTicketStatus
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import java.time.Instant
import java.util.Optional

/**
 * Every list and count takes the caller's account scope as `allAccounts` + `accountIds`.
 * Callers pass `allAccounts = true` only for roles with a global ticket view and must never
 * pass an empty `accountIds` (an empty IN list is invalid SQL); a scoped caller without any
 * account short-circuits before reaching the repository.
 */
@Repository
interface CyberdefTicketRepository : JpaRepository<CyberdefTicket, Long> {

    fun findByCreatedByUserIdAndExternalReference(createdByUserId: Long, externalReference: String): Optional<CyberdefTicket>

    /** Batch idempotency check in one query instead of one per ticket. */
    fun findByCreatedByUserIdAndExternalReferenceIn(createdByUserId: Long, externalReferences: Collection<String>): List<CyberdefTicket>

    fun findByIdIn(ids: Collection<Long>): List<CyberdefTicket>

    fun countByCreatedByUserIdAndCreatedAtAfter(createdByUserId: Long, createdAt: Instant): Long

    @Query(
        value = """
            SELECT t FROM CyberdefTicket t
            WHERE (:allAccounts = true OR t.awsAccountId IN (:accountIds))
              AND t.status IN (:statuses)
              AND t.severity IN (:severities)
              AND (:accountId IS NULL OR t.awsAccountId = :accountId)
              AND (:createdBy IS NULL OR t.createdByUserId = :createdBy)
              AND (:dueBefore IS NULL OR (t.dueAt IS NOT NULL AND t.dueAt < :dueBefore))
              AND (:updatedSince IS NULL OR t.updatedAt >= :updatedSince)
              AND (:search IS NULL OR LOWER(t.title) LIKE :search OR t.id = :searchId)
        """,
        countQuery = """
            SELECT COUNT(t) FROM CyberdefTicket t
            WHERE (:allAccounts = true OR t.awsAccountId IN (:accountIds))
              AND t.status IN (:statuses)
              AND t.severity IN (:severities)
              AND (:accountId IS NULL OR t.awsAccountId = :accountId)
              AND (:createdBy IS NULL OR t.createdByUserId = :createdBy)
              AND (:dueBefore IS NULL OR (t.dueAt IS NOT NULL AND t.dueAt < :dueBefore))
              AND (:updatedSince IS NULL OR t.updatedAt >= :updatedSince)
              AND (:search IS NULL OR LOWER(t.title) LIKE :search OR t.id = :searchId)
        """
    )
    fun search(
        allAccounts: Boolean,
        accountIds: Collection<String>,
        statuses: Collection<CyberdefTicketStatus>,
        severities: Collection<CyberdefSeverity>,
        accountId: String?,
        createdBy: Long?,
        dueBefore: Instant?,
        updatedSince: Instant?,
        search: String?,
        searchId: Long?,
        pageable: Pageable
    ): Page<CyberdefTicket>

    /** Rows of [status, count] for the summary tiles and the sidebar badge. */
    @Query(
        """
        SELECT t.status, COUNT(t) FROM CyberdefTicket t
        WHERE (:allAccounts = true OR t.awsAccountId IN (:accountIds))
        GROUP BY t.status
        """
    )
    fun countByStatus(allAccounts: Boolean, accountIds: Collection<String>): List<Array<Any>>

    @Query(
        """
        SELECT COUNT(t) FROM CyberdefTicket t
        WHERE (:allAccounts = true OR t.awsAccountId IN (:accountIds))
          AND t.status IN (:openStatuses) AND t.dueAt IS NOT NULL AND t.dueAt < :now
        """
    )
    fun countOverdue(
        allAccounts: Boolean,
        accountIds: Collection<String>,
        openStatuses: Collection<CyberdefTicketStatus>,
        now: Instant
    ): Long
}
