package com.secman.dto

import com.secman.domain.NotificationEventType
import io.micronaut.serde.annotation.Serdeable

/**
 * The subscribable-event catalogue as sent to the UI.
 *
 * Shared by every channel's settings endpoint so the frontend renders the same list
 * everywhere, and so a new [NotificationEventType] appears in all of them at once with no
 * frontend change.
 */
@Serdeable
data class NotificationEventTypeDto(
    val name: String,
    val displayName: String,
    val description: String
) {
    companion object {
        fun catalogue(): List<NotificationEventTypeDto> =
            NotificationEventType.entries.map {
                NotificationEventTypeDto(it.name, it.displayName, it.description)
            }
    }
}
