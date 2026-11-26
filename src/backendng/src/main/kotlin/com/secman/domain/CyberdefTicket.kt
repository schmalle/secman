package com.secman.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * A vulnerability report about one AWS account, submitted by the cyber-defence team
 * (role CYBERDEF) and worked on by the people allocated to that account.
 *
 * Users are referenced by id plus a username snapshot rather than by a foreign key:
 * the nightly user cleanup deletes accounts, and a ticket's history must survive that.
 *
 * Visibility is never stored here. It is evaluated live from the caller's account
 * access, see `CyberdefTicketService` and docs/CYBERDEF_TICKETS.md.
 */
@Entity
@Table(
    name = "cyberdef_ticket",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_cyberdef_ticket_key", columnNames = ["ticket_key"]),
        UniqueConstraint(name = "uk_cyberdef_ticket_external_ref", columnNames = ["created_by_user_id", "external_reference"])
    ],
    indexes = [
        Index(name = "idx_cyberdef_ticket_account_status", columnList = "aws_account_id,status"),
        Index(name = "idx_cyberdef_ticket_status_due", columnList = "status,due_at"),
        Index(name = "idx_cyberdef_ticket_updated", columnList = "updated_at")
    ]
)
// Columns mirror db/migration/V310__cyberdef_tickets.sql; change both together.
class CyberdefTicket(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "ticket_key", length = 20)
    var ticketKey: String? = null,

    @Column(name = "target_type", nullable = false, length = 16)
    var targetType: String = TARGET_AWS_ACCOUNT,

    @Column(name = "aws_account_id", nullable = false, length = 12)
    var awsAccountId: String = "",

    @Column(name = "aws_account_name", length = 255)
    var awsAccountName: String? = null,

    @Column(nullable = false, length = 200)
    var title: String = "",

    @Column(nullable = false, columnDefinition = "TEXT")
    var description: String = "",

    @Column(name = "recommended_action", columnDefinition = "TEXT")
    var recommendedAction: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var severity: CyberdefSeverity = CyberdefSeverity.MEDIUM,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: CyberdefTicketStatus = CyberdefTicketStatus.OPEN,

    /** Comma-separated, each value already matched against the CVE pattern on input. */
    @Column(name = "cve_ids", columnDefinition = "TEXT")
    var cveIds: String? = null,

    /** Newline-separated https URLs, display only. The backend never fetches them. */
    @Column(name = "reference_urls", columnDefinition = "TEXT")
    var referenceUrls: String? = null,

    @Column(name = "due_at")
    var dueAt: Instant? = null,

    @Column(name = "external_reference", length = 128)
    var externalReference: String? = null,

    @Column(name = "source_system", length = 64)
    var sourceSystem: String? = null,

    @Column(name = "created_by_user_id", nullable = false)
    var createdByUserId: Long = 0,

    @Column(name = "created_by_username", nullable = false, length = 255)
    var createdByUsername: String = "",

    @Column(name = "recipient_count", nullable = false)
    var recipientCount: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    /** Set when the ticket last moved to CLOSED; cleared again on reopen. */
    @Column(name = "closed_at")
    var closedAt: Instant? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0
) {
    fun cveList(): List<String> = cveIds?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    fun referenceList(): List<String> = referenceUrls?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    companion object {
        const val TARGET_AWS_ACCOUNT = "AWS_ACCOUNT"
    }
}
