package com.secman.domain

/**
 * Lifecycle of a cyber-defence ticket. The allowed moves between these values live in
 * [com.secman.service.cyberdef.CyberdefTicketWorkflow]; nothing else may change a status.
 */
enum class CyberdefTicketStatus {
    NEW,
    ACKNOWLEDGED,
    IN_PROGRESS,
    NEEDS_INFO,
    RESOLVED,
    RISK_ACCEPTED,
    FALSE_POSITIVE,
    REOPENED,
    CLOSED,
    CANCELLED;

    val isTerminal: Boolean get() = this == CLOSED || this == CANCELLED
}

/** Severity the submitter assigns; selects the default due window (secman.cyberdef.sla.*). */
enum class CyberdefSeverity {
    CRITICAL, HIGH, MEDIUM, LOW, INFO
}

/**
 * What a row in `cyberdef_ticket_event` records. The event id doubles as the polling cursor.
 * Mail delivery is tracked in `cyberdef_ticket_notification`, not here, so pollers only see
 * changes people made.
 */
enum class CyberdefTicketEventType {
    CREATED, STATUS_CHANGED, COMMENT
}

/** Which side of the workflow an actor acted for when the event was written. */
enum class CyberdefActorKind {
    OWNER, DEFENDER, SYSTEM
}
