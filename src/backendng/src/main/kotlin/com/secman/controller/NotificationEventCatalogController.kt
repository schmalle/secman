package com.secman.controller

import com.secman.domain.NotificationChannel
import com.secman.dto.NotificationEventTypeDto
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton

/**
 * Channel-independent catalogue of what can be reported over chat and where.
 *
 * Exists so the UI never hardcodes the event list or the channel list: adding a
 * [com.secman.domain.NotificationEventType] or a [NotificationChannel] surfaces here
 * automatically. Read-only and exposes no per-user data, so plain authentication is
 * sufficient.
 */
@Singleton
@Controller("/api/notification-events")
@Secured(SecurityRule.IS_AUTHENTICATED)
open class NotificationEventCatalogController {

    @Serdeable
    data class NotificationChannelDto(val name: String, val displayName: String)

    @Serdeable
    data class CatalogResponse(
        val eventTypes: List<NotificationEventTypeDto>,
        val channels: List<NotificationChannelDto>
    )

    @Get
    open fun getCatalog(): CatalogResponse = CatalogResponse(
        eventTypes = NotificationEventTypeDto.catalogue(),
        channels = NotificationChannel.entries.map { NotificationChannelDto(it.name, it.displayName) }
    )
}
