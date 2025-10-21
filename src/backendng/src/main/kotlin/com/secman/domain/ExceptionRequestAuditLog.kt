package com.secman.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * ExceptionRequestAuditLog entity for permanent audit trail of exception request lifecycle events.
 *
 * Immutable audit records - no UPDATE or DELETE operations allowed after creation.
 * Provides complete traceability for compliance and debugging.
 *
 * Retention policy: Permanent (manual cleanup after 7 years per compliance requirements)
 *
 * Related to: Feature 031-vuln-exception-approval (FR-026b - Audit logging)
 *
 * @property id Unique identifier
 * @property requestId ID of the exception request (not FK to allow orphaned audit logs)
 * @property eventType Type of event (REQUEST_CREATED, APPROVED, REJECTED, etc.)
 * @property timestamp When the event occurred (indexed for time-based queries)
 * @property oldState Previous status before transition (nullable for creation events)
 * @property newState New status after transition
 * @property actorUsername Username of user who performed the action
 * @property actorUser User who performed the action (nullable after user deletion)
 * @property contextData JSON context data (reason, comments, additional info)
 * @property severity Event severity (INFO, WARN, ERROR)
 * @property clientIp Client IP address (IPv6 compatible)
 */
@Entity
@Table(
    name = "exception_request_audit",
    indexes = [
        Index(name = "idx_audit_request", columnList = "request_id"),
        Index(name = "idx_audit_timestamp", columnList = "timestamp"),
        Index(name = "idx_audit_event_type", columnList = "event_type"),
        Index(name = "idx_audit_actor", columnList = "actor_user_id"),
        Index(name = "idx_audit_composite", columnList = "request_id,timestamp,event_type")
    ]
)
@Serdeable
data class ExceptionRequestAuditLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /**
     * ID of the exception request this audit log refers to
     * Not a foreign key to allow audit logs to survive request deletion
     */
    @Column(name = "request_id", nullable = false)
    @NotNull
    var requestId: Long,

    /**
     * Type of audit event
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    @NotNull
    var eventType: AuditEventType,

    /**
     * When the event occurred (indexed for compliance reporting)
     */
    @Column(name = "timestamp", nullable = false)
    @NotNull
    var timestamp: LocalDateTime = LocalDateTime.now(),

    /**
     * Previous status (for state transitions)
     * Nullable for creation events
     */
    @Column(name = "old_state", length = 20)
    @Size(max = 20)
    var oldState: String? = null,

    /**
     * New status after transition
     */
    @Column(name = "new_state", nullable = false, length = 20)
    @NotBlank
    @Size(max = 20)
    var newState: String,

    /**
     * Username of user who performed the action (denormalized for audit trail)
     * Preserved even if user account deleted
     */
    @Column(name = "actor_username", nullable = false, length = 255)
    @NotBlank
    @Size(max = 255)
    var actorUsername: String,

    /**
     * User who performed the action
     * Nullable: If user deleted, preserve username for audit trail
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    @JsonIgnore
    var actorUser: User? = null,

    /**
     * JSON context data with event-specific details
     * Examples:
     * - { "reason": "Legacy system...", "comment": "Approved due to...", "ticketId": "SEC-1234" }
     * - { "reviewerNotes": "Compensating controls in place" }
     */
    @Column(name = "context_data", columnDefinition = "TEXT")
    var contextData: String? = null,

    /**
     * Event severity for filtering and alerting
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    @NotNull
    var severity: AuditSeverity = AuditSeverity.INFO,

    /**
     * Client IP address (IPv4 or IPv6)
     * Max 45 characters for IPv6 (8 groups of 4 hex digits + 7 colons)
     */
    @Column(name = "client_ip", length = 45)
    @Size(max = 45)
    var clientIp: String? = null
) {
    @PrePersist
    fun onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now()
        }
    }

    override fun toString(): String {
        return "ExceptionRequestAuditLog(id=$id, requestId=$requestId, eventType=$eventType, " +
                "actor='$actorUsername', oldState='$oldState', newState='$newState', " +
                "timestamp=$timestamp, severity=$severity)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExceptionRequestAuditLog) return false
        return id != null && id == other.id
    }

    override fun hashCode(): Int {
        return id?.hashCode() ?: 0
    }
}
