package com.secman.repository

import com.secman.domain.CyberdefTicketResource
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/** Affected resources of a ticket, in submission order. */
@Repository
interface CyberdefTicketResourceRepository : JpaRepository<CyberdefTicketResource, Long> {
    fun findByTicketIdOrderByIdAsc(ticketId: Long): List<CyberdefTicketResource>
}
