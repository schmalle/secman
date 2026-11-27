package com.secman.domain

import jakarta.persistence.*

/** One affected cloud resource (ARN, instance id, hostname, bucket ...) named in a ticket. */
@Entity
@Table(
    name = "cyberdef_ticket_resource",
    indexes = [Index(name = "idx_cyberdef_resource_ticket", columnList = "ticket_id")]
)
// Columns mirror db/migration/V310__cyberdef_tickets.sql; change both together.
class CyberdefTicketResource(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "ticket_id", nullable = false)
    var ticketId: Long = 0,

    @Column(name = "resource_type", nullable = false, length = 32)
    var resourceType: String = "",

    @Column(nullable = false, length = 512)
    var identifier: String = "",

    @Column(length = 32)
    var region: String? = null
)
