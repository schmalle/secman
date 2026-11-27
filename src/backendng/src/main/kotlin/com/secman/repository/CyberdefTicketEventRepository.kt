package com.secman.repository

import com.secman.domain.CyberdefTicketEvent
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import io.micronaut.data.model.Pageable

/** Append-only ticket activity, read per ticket or as a cursor feed. */
@Repository
interface CyberdefTicketEventRepository : JpaRepository<CyberdefTicketEvent, Long> {

    fun findByTicketIdOrderByIdAsc(ticketId: Long, pageable: Pageable): List<CyberdefTicketEvent>

    fun findByIdIn(ids: Collection<Long>): List<CyberdefTicketEvent>

    /**
     * The polling cursor: every event after [sinceId], oldest first, bounded by [pageable].
     *
     * Auto-increment ids are allocated at insert but become visible at commit, so a later id
     * can be readable before an earlier one. Events younger than [settledBefore] are held
     * back until every transaction that could own a lower id has committed; otherwise a
     * poller would advance its cursor past an event it never saw.
     */
    @Query("SELECT e FROM CyberdefTicketEvent e WHERE e.id > :sinceId AND e.createdAt < :settledBefore ORDER BY e.id ASC")
    fun findAfter(sinceId: Long, settledBefore: java.time.Instant, pageable: Pageable): List<CyberdefTicketEvent>
}
