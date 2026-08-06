package com.secman.domain

/**
 * Catalogue of events a user can subscribe to for chat delivery (Slack, Telegram, …).
 *
 * Deliberately a closed enum rather than free-form strings: the per-user subscription
 * table stores the enum name, the UI renders the catalogue generically from [entries],
 * and an unknown value coming in over the API is rejected instead of silently persisted
 * as a subscription that never fires.
 *
 * Adding a new reportable event is a two-step change: add a constant here, and publish a
 * [com.secman.event.ChatNotificationEvent] with it from wherever the event actually
 * completes. Nothing else — the settings APIs, the settings UI and the dispatcher all
 * derive from this enum, for every channel.
 */
enum class NotificationEventType(
    val displayName: String,
    val description: String
) {
    CROWDSTRIKE_REPORT_COMPLETED(
        "New CrowdStrike report completed",
        "A CrowdStrike vulnerability import run finished. Reports how many servers were " +
            "processed and how many vulnerabilities were imported."
    ),

    AWS_ACCOUNT_IMPORT_COMPLETED(
        "New AWS account import completed",
        "An AWS account / user-mapping import finished. Reports how many mappings were " +
            "processed and which AWS accounts SecMan had never seen before."
    );

    companion object {
        /**
         * Parse an API-supplied event type name. Returns null for unknown or blank
         * values so callers can turn it into a 400 rather than a silent no-op.
         */
        fun fromNameOrNull(raw: String?): NotificationEventType? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        }
    }
}
