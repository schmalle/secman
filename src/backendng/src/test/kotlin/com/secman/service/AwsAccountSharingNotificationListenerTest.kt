package com.secman.service

import com.secman.domain.AwsAccountSharingCreatedEvent
import io.mockk.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class AwsAccountSharingNotificationListenerTest {

    private fun sampleEvent() = AwsAccountSharingCreatedEvent(
        sharingId = 1L,
        sourceUserEmail = "alice@example.com",
        targetUserId = 2L,
        targetUserEmail = "bob@example.com",
        targetUsername = "bob",
        createdByEmail = "admin@example.com",
        createdAtIso = "2026-05-06T10:15:00Z",
        sharedAwsAccountCount = 1,
    )

    @Test
    fun `forwards event to notification service`() {
        val service = mockk<AwsAccountSharingNotificationService>()
        every { service.notifyTargetOfNewShare(any()) } returns CompletableFuture.completedFuture(true)

        val listener = AwsAccountSharingNotificationListener(service)
        listener.onCreated(sampleEvent())

        verify(exactly = 1) { service.notifyTargetOfNewShare(any()) }
    }

    @Test
    fun `swallows exceptions thrown by notification service`() {
        val service = mockk<AwsAccountSharingNotificationService>()
        every { service.notifyTargetOfNewShare(any()) } throws RuntimeException("boom")

        val listener = AwsAccountSharingNotificationListener(service)
        // Must not throw
        listener.onCreated(sampleEvent())

        verify(exactly = 1) { service.notifyTargetOfNewShare(any()) }
    }
}
