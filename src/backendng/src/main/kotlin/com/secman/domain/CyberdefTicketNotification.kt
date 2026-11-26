package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * Mail outbox for tickets. Rows are written in the same transaction as the change that
 * caused them and sent afterwards, outside any transaction, so a slow SMTP server never
 * holds a database transaction open and a rollback never leaves a sent mail behind.
 *
 * Status values: PENDING → SENDING → SENT | FAILED | UNCERTAIN. UNCERTAIN means the
 * transport broke after the provider may have accepted the message; it is never retried
 * automatically, to avoid duplicate mail.
 */
@Entity
@Table(
    name = "cyberdef_ticket_notification",
    uniqueConstraints = [UniqueConstraint(name = "uk_cyberdef_notification_key", columnNames = ["event_key"])],
    indexes = [
        Index(name = "idx_cyberdef_notification_ticket", columnList = "ticket_id"),
        Index(name = "idx_cyberdef_notification_status", columnList = "status,created_at")
    ]
)
// Columns mirror db/migration/V310__cyberdef_tickets.sql; change both together.
class CyberdefTicketNotification(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "ticket_id", nullable = false)
    var ticketId: Long = 0,

    @Column(name = "recipient_email", nullable = false, length = 255)
    var recipientEmail: String = "",

    /** CREATED, STATUS_CHANGED or DEFENDER_UPDATE. Selects the mail template. */
    @Column(nullable = false, length = 24)
    var kind: String = "",

    /** The event that caused this mail, so the template can name the new status. */
    @Column(name = "event_id")
    var eventId: Long? = null,

    @Column(name = "event_key", nullable = false, length = 64)
    var eventKey: String = "",

    @Column(nullable = false, length = 16)
    var status: String = STATUS_PENDING,

    @Column(nullable = false)
    var attempts: Int = 0,

    @Column(name = "error_code", length = 64)
    var errorCode: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "sent_at")
    var sentAt: Instant? = null
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SENDING = "SENDING"
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_UNCERTAIN = "UNCERTAIN"
    }
}
