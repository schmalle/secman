package com.secman.domain

/**
 * Values stored in `last_delivery_status` on the per-user chat settings entities.
 *
 * A plain string column rather than an enum: this is diagnostic bookkeeping shown back in
 * the UI, not something the application branches on, and it is shared verbatim by every
 * channel so the frontend can colour it the same way regardless of transport.
 */
object ChatDeliveryStatus {
    /** Delivered successfully. */
    const val SENT = "SENT"

    /** The transport rejected the message, or the request failed. */
    const val FAILED = "FAILED"

    /** Subscribed and enabled, but there was nowhere to send — a configuration gap. */
    const val SKIPPED = "SKIPPED"
}
