package com.secman.service.cyberdef

/**
 * Outcome of a ticket operation. Error codes are a stable contract: MCP clients and the
 * scripts under scripts/cyberdef/ match on them, so never reword or rename one.
 */
sealed class CyberdefTicketResult<out T> {
    /** The operation succeeded with [value]. */
    data class Ok<T>(val value: T) : CyberdefTicketResult<T>()
    /** The operation was refused; [code] is one of the constants below or a workflow code. */
    data class Failed(val code: String, val message: String) : CyberdefTicketResult<Nothing>()

    companion object {
        const val NOT_FOUND = "NOT_FOUND"
        const val FORBIDDEN = "FORBIDDEN"
        const val VALIDATION_ERROR = "VALIDATION_ERROR"
        const val VERSION_CONFLICT = "VERSION_CONFLICT"
        const val UNROUTABLE_ACCOUNT = "UNROUTABLE_ACCOUNT"
        const val RECIPIENT_NOT_ALLOWED = "RECIPIENT_NOT_ALLOWED"
        const val RATE_LIMITED = "RATE_LIMITED"
        const val INTERNAL_ERROR = "INTERNAL_ERROR"

        /** Same message whether the ticket does not exist or is outside the caller's accounts. */
        val notFound = Failed(NOT_FOUND, "Ticket not found")
        val defendersOnly = Failed(FORBIDDEN, "Only Cyber Defence (CYBERDEF) or ADMIN may do this")
    }
}
