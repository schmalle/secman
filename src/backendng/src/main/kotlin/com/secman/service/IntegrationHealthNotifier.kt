package com.secman.service

import com.secman.domain.NotificationEventType
import com.secman.dto.IntegrationRunAck
import com.secman.event.ChatNotificationEvent
import io.micronaut.context.event.ApplicationEventPublisher
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant

@Singleton
open class IntegrationHealthNotifier(
    private val repository: IntegrationHealthRepository,
    private val publisher: ApplicationEventPublisher<ChatNotificationEvent>,
    private val settings: AppSettingsService,
    private val views: MaterializedViewRefreshService
) {
    private val log = LoggerFactory.getLogger(IntegrationHealthNotifier::class.java)

    /** Called only after ingestion commits. Replays never deliver a duplicate failure alert. */
    open fun completed(ack: IntegrationRunAck) {
        if (ack.replayed) return
        views.requestDeferredRefresh("Integration run accepted")
        if (ack.status in setOf("FAILED", "PARTIAL")) {
            publish(NotificationEventType.INTEGRATION_SCAN_FAILED, "An integration scan needs attention")
        }
    }

    @Scheduled(fixedDelay = "5m", initialDelay = "1m")
    open fun sweep() {
        var after = 0L
        while (true) {
            val ids = repository.scannerIdsAfter(after)
            if (ids.isEmpty()) return
            for (id in ids) {
                if (repository.claimStaleTransition(id, Instant.now())) {
                    publish(NotificationEventType.INTEGRATION_STALE, "Integration coverage is stale")
                }
            }
            after = ids.last()
        }
    }

    private fun publish(type: NotificationEventType, title: String) {
        try {
            // Subscribers have different asset scopes: evidence and identities stay behind the authenticated UI.
            publisher.publishEvent(ChatNotificationEvent(type, title,
                "Open Integration Results in SecMan to review coverage within your access scope.",
                listOf(ChatNotificationEvent.ChatField("Integration Results", settings.getBaseUrl() + "/integrations"))))
        } catch (e: Exception) {
            log.warn("Integration health notification could not be queued ({})", e.javaClass.simpleName)
        }
    }
}
