package com.secman.repository

import com.secman.domain.CyberdefTicketRecipient
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository

/** Who was mailed about a ticket at creation (audit only, grants no access). */
@Repository
interface CyberdefTicketRecipientRepository : JpaRepository<CyberdefTicketRecipient, Long> {
    fun findByTicketIdOrderByEmailAsc(ticketId: Long): List<CyberdefTicketRecipient>
}
