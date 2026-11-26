package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * Append-only activity log of a ticket. Rows are never updated or deleted.
 *
 * The auto-increment id is the cursor `get_cyberdef_ticket_updates` pages by, so a
 * poller that remembers the last id it processed sees every later event exactly once.
 */
@Entity
@Table(
    name = "cyberdef_ticket_event",
    indexes = [Index(name = "idx_cyberdef_event_ticket", columnList = "ticket_id,id")]
)
// Columns mirror db/migration/V310__cyberdef_tickets.sql; change both together.
class CyberdefTicketEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "ticket_id", nullable = false)
    var ticketId: Long = 0,

    @Column(name = "actor_user_id")
    var actorUserId: Long? = null,

    @Column(name = "actor_username", length = 255)
    var actorUsername: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", nullable = false, length = 16)
    var actorKind: CyberdefActorKind = CyberdefActorKind.SYSTEM,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 24)
    var eventType: CyberdefTicketEventType = CyberdefTicketEventType.COMMENT,

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    var fromStatus: CyberdefTicketStatus? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 20)
    var toStatus: CyberdefTicketStatus? = null,

    @Column(columnDefinition = "TEXT")
    var comment: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)
