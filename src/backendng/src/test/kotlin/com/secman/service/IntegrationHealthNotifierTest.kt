package com.secman.service

import com.secman.dto.IntegrationRunAck
import com.secman.domain.NotificationEventType
import com.secman.event.ChatNotificationEvent
import io.micronaut.context.event.ApplicationEventPublisher
import io.mockk.*
import org.junit.jupiter.api.Test

class IntegrationHealthNotifierTest {
    private val publisher = mockk<ApplicationEventPublisher<ChatNotificationEvent>>(relaxed = true)
    private val repository = mockk<IntegrationHealthRepository>()
    private val settings = mockk<AppSettingsService> { every { getBaseUrl() } returns "https://secman.example.test" }
    private val views = mockk<MaterializedViewRefreshService>(relaxed = true)
    private val notifier = IntegrationHealthNotifier(repository, publisher, settings, views)

    @Test fun `only new unsuccessful runs notify without exposing evidence or identities`() {
        notifier.completed(IntegrationRunAck(1, 42, 63, "FAILED", 0, 0, false))
        verify(exactly = 1) { publisher.publishEvent(match {
            it.eventType == NotificationEventType.INTEGRATION_SCAN_FAILED &&
                it.fields == listOf(ChatNotificationEvent.ChatField("Integration Results", "https://secman.example.test/integrations"))
        }) }
        notifier.completed(IntegrationRunAck(1, 42, 63, "FAILED", 0, 0, true))
        notifier.completed(IntegrationRunAck(2, 42, 63, "SUCCESS", 0, 0, false))
        verify(exactly = 1) { publisher.publishEvent(any()) }
        verify(exactly = 2) { views.requestDeferredRefresh("Integration run accepted") }
    }

    @Test fun `stale sweep notifies only claimed transitions`() {
        every { repository.scannerIdsAfter(0) } returns listOf(1L, 2L)
        every { repository.scannerIdsAfter(2) } returns emptyList()
        every { repository.claimStaleTransition(1, any()) } returns true
        every { repository.claimStaleTransition(2, any()) } returns false
        notifier.sweep()
        verify(exactly = 1) { publisher.publishEvent(match { it.eventType == NotificationEventType.INTEGRATION_STALE }) }
    }

    @Test fun `notification transport failure does not reject committed ingestion`() {
        every { publisher.publishEvent(any()) } throws IllegalStateException("transport unavailable")
        notifier.completed(IntegrationRunAck(1, 42, 63, "PARTIAL", 1, 0, false))
    }
}
