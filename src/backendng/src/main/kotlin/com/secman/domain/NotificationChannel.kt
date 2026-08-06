package com.secman.domain

/**
 * A chat transport a user can have events delivered over.
 *
 * Subscriptions are per (user, channel, event type), so a user can have CrowdStrike
 * imports land in Slack and AWS account imports in Telegram — or both in both.
 */
enum class NotificationChannel(val displayName: String) {
    SLACK("Slack"),
    TELEGRAM("Telegram");

    companion object {
        fun fromNameOrNull(raw: String?): NotificationChannel? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        }
    }
}
