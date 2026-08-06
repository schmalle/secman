package com.secman.service

/**
 * Outcome of one chat delivery attempt, shared by every transport.
 *
 * Failures are values rather than exceptions: delivery is best-effort and per-recipient
 * isolated, so the dispatcher needs to record an outcome and carry on rather than unwind.
 * [error] is truncated because it is persisted on the user's settings row and rendered
 * back into the UI.
 */
data class ChatDeliveryResult(val success: Boolean, val error: String? = null) {
    companion object {
        const val MAX_ERROR_LENGTH = 300

        fun ok() = ChatDeliveryResult(true)
        fun failed(message: String) = ChatDeliveryResult(false, message.take(MAX_ERROR_LENGTH))
    }
}
