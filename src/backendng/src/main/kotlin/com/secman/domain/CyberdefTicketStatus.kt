package com.secman.domain

/**
 * Lifecycle of a cyber-defence ticket: OPEN until someone closes it, CLOSED afterwards.
 * A closed ticket can be reopened. Who may make which move lives in
 * [com.secman.service.cyberdef.CyberdefTicketWorkflow]; nothing else may change a status.
 */
enum class CyberdefTicketStatus {
    OPEN,
    CLOSED;

    val isOpen: Boolean get() = this == OPEN
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
