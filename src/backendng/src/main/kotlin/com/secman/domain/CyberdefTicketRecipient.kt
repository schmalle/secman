package com.secman.domain

import jakarta.persistence.*

/**
 * Who was told about a ticket at creation and why. An audit record only: it grants no
 * access. Visibility follows the caller's live account access instead.
 */
@Entity
@Table(
    name = "cyberdef_ticket_recipient",
    uniqueConstraints = [UniqueConstraint(name = "uk_cyberdef_recipient", columnNames = ["ticket_id", "email"])]
)
// Columns mirror db/migration/V310__cyberdef_tickets.sql; change both together.
class CyberdefTicketRecipient(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "ticket_id", nullable = false)
    var ticketId: Long = 0,

    @Column(nullable = false, length = 255)
    var email: String = "",

    @Column(name = "user_id")
    var userId: Long? = null,

    /** MAPPING (personal AWS-account mapping) or WORKGROUP (member of a granted workgroup). */
    @Column(nullable = false, length = 16)
    var source: String = ""
)
