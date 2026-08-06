package com.secman.service

import com.secman.domain.ChatDeliveryStatus
import com.secman.domain.NotificationChannel
import com.secman.domain.NotificationEventType
import com.secman.domain.SlackConfig
import com.secman.domain.TelegramConfig
import com.secman.domain.UserNotificationSubscription
import com.secman.domain.UserSlackSettings
import com.secman.domain.UserTelegramSettings
import com.secman.event.ChatNotificationEvent
import com.secman.repository.UserNotificationSubscriptionRepository
import com.secman.repository.UserSlackSettingsRepository
import com.secman.repository.UserTelegramSettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * Covers per-user, per-channel fan-out: only subscribers get a message, an unsubscribed or
 * disabled user gets nothing, destination resolution follows the documented precedence on
 * both channels, one recipient's failure does not stop the others, and a subscription on
 * one channel never leaks onto the other.
 */
class ChatNotificationServiceTest {

    private val subscriptionRepository = mockk<UserNotificationSubscriptionRepository>(relaxed = true)
    private val slackSettingsRepository = mockk<UserSlackSettingsRepository>(relaxed = true)
    private val telegramSettingsRepository = mockk<UserTelegramSettingsRepository>(relaxed = true)
    private val slackConfigService = mockk<SlackConfigService>()
    private val telegramConfigService = mockk<TelegramConfigService>()
    private val slackClient = mockk<SlackClient>()
    private val telegramClient = mockk<TelegramClient>()

    private val service = ChatNotificationService(
        subscriptionRepository = subscriptionRepository,
        slackSettingsRepository = slackSettingsRepository,
        telegramSettingsRepository = telegramSettingsRepository,
        slackConfigService = slackConfigService,
        telegramConfigService = telegramConfigService,
        slackClient = slackClient,
        telegramClient = telegramClient,
        renderer = ChatMessageRenderer()
    )

    private val eventType = NotificationEventType.CROWDSTRIKE_REPORT_COMPLETED

    private val event = ChatNotificationEvent(
        eventType = eventType,
        title = "New CrowdStrike report completed",
        summary = "done"
    )

    @BeforeEach
    fun setUp() {
        every { slackConfigService.find() } returns null
        every { telegramConfigService.find() } returns null
        // Default: nobody subscribed on either channel. Tests opt in per channel.
        every { subscriptionRepository.findByChannelAndEventType(any(), any()) } returns emptyList()
        // Explicit empties rather than relying on relaxed mocking: a relaxed repository
        // hands back a mock Optional whose orElse(null) is another mock, not null — which
        // would make an unconfigured user look configured.
        every { slackSettingsRepository.findByUserId(any()) } returns Optional.empty()
        every { telegramSettingsRepository.findByUserId(any()) } returns Optional.empty()
    }

    private fun subscribeSlack(vararg userIds: Long) {
        every { subscriptionRepository.findByChannelAndEventType(NotificationChannel.SLACK, eventType) } returns
            userIds.map {
                UserNotificationSubscription(id = it, userId = it, channel = NotificationChannel.SLACK, eventType = eventType)
            }
    }

    private fun subscribeTelegram(vararg userIds: Long) {
        every { subscriptionRepository.findByChannelAndEventType(NotificationChannel.TELEGRAM, eventType) } returns
            userIds.map {
                UserNotificationSubscription(id = it, userId = it, channel = NotificationChannel.TELEGRAM, eventType = eventType)
            }
    }

    private fun slackSettings(
        userId: Long,
        enabled: Boolean = true,
        webhookUrl: String? = "https://hooks.slack.com/services/T/B/X",
        channel: String? = null
    ) = UserSlackSettings(id = userId, userId = userId, enabled = enabled, webhookUrl = webhookUrl, channel = channel)
        .also { every { slackSettingsRepository.findByUserId(userId) } returns Optional.of(it) }

    private fun telegramSettings(
        userId: Long,
        enabled: Boolean = true,
        chatId: String? = "123456789",
        botToken: String? = null
    ) = UserTelegramSettings(id = userId, userId = userId, enabled = enabled, chatId = chatId, botToken = botToken)
        .also { every { telegramSettingsRepository.findByUserId(userId) } returns Optional.of(it) }

    private fun resultFor(results: List<ChatNotificationService.DispatchResult>, channel: NotificationChannel) =
        results.first { it.channel == channel }

    // --- Slack ---------------------------------------------------------------------

    @Test
    fun `sends to a Slack subscriber with a personal webhook`() {
        subscribeSlack(1)
        slackSettings(1)
        every { slackClient.postWebhook(any(), any()) } returns ChatDeliveryResult.ok()

        val slack = resultFor(service.dispatch(event), NotificationChannel.SLACK)

        assertThat(slack.sent).isEqualTo(1)
        assertThat(slack.failed).isZero()
        verify(exactly = 1) { slackClient.postWebhook("https://hooks.slack.com/services/T/B/X", any()) }
    }

    @Test
    fun `sends nothing when nobody subscribed to the event type`() {
        val results = service.dispatch(event)

        assertThat(results.sumOf { it.subscribers }).isZero()
        verify(exactly = 0) { slackClient.postWebhook(any(), any()) }
        verify(exactly = 0) { telegramClient.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `skips a Slack subscriber whose delivery is switched off`() {
        subscribeSlack(1)
        slackSettings(1, enabled = false)

        val slack = resultFor(service.dispatch(event), NotificationChannel.SLACK)

        assertThat(slack.sent).isZero()
        assertThat(slack.skipped).isEqualTo(1)
        verify(exactly = 0) { slackClient.postWebhook(any(), any()) }
    }

    @Test
    fun `skips a Slack subscriber with no destination rather than failing`() {
        subscribeSlack(1)
        slackSettings(1, webhookUrl = null, channel = null)

        val slack = resultFor(service.dispatch(event), NotificationChannel.SLACK)

        assertThat(slack.skipped).isEqualTo(1)
        assertThat(slack.failed).isZero()
    }

    @Test
    fun `falls back to the user channel via the workspace Slack bot when no webhook is set`() {
        subscribeSlack(1)
        slackSettings(1, webhookUrl = null, channel = "#mine")
        every { slackConfigService.find() } returns
            SlackConfig(enabled = true, botToken = "xoxb-token", defaultChannel = "#fallback")
        every { slackClient.postChatMessage(any(), any(), any()) } returns ChatDeliveryResult.ok()

        val slack = resultFor(service.dispatch(event), NotificationChannel.SLACK)

        assertThat(slack.sent).isEqualTo(1)
        verify(exactly = 1) { slackClient.postChatMessage("xoxb-token", "#mine", any()) }
    }

    @Test
    fun `falls back to the workspace default channel when the user set none`() {
        subscribeSlack(1)
        slackSettings(1, webhookUrl = null, channel = null)
        every { slackConfigService.find() } returns
            SlackConfig(enabled = true, botToken = "xoxb-token", defaultChannel = "#fallback")
        every { slackClient.postChatMessage(any(), any(), any()) } returns ChatDeliveryResult.ok()

        val slack = resultFor(service.dispatch(event), NotificationChannel.SLACK)

        assertThat(slack.sent).isEqualTo(1)
        verify(exactly = 1) { slackClient.postChatMessage("xoxb-token", "#fallback", any()) }
    }

    @Test
    fun `a personal webhook wins over the workspace Slack bot`() {
        subscribeSlack(1)
        slackSettings(1, channel = "#mine")
        every { slackConfigService.find() } returns
            SlackConfig(enabled = true, botToken = "xoxb-token", defaultChannel = "#fallback")
        every { slackClient.postWebhook(any(), any()) } returns ChatDeliveryResult.ok()

        service.dispatch(event)

        verify(exactly = 1) { slackClient.postWebhook(any(), any()) }
        verify(exactly = 0) { slackClient.postChatMessage(any(), any(), any()) }
    }

    @Test
    fun `a Slack channel cannot deliver while workspace bot delivery is disabled`() {
        subscribeSlack(1)
        slackSettings(1, webhookUrl = null, channel = "#mine")
        every { slackConfigService.find() } returns
            SlackConfig(enabled = false, botToken = "xoxb-token", defaultChannel = "#fallback")

        val slack = resultFor(service.dispatch(event), NotificationChannel.SLACK)

        assertThat(slack.skipped).isEqualTo(1)
        verify(exactly = 0) { slackClient.postChatMessage(any(), any(), any()) }
    }

    @Test
    fun `one failing Slack recipient does not stop the others`() {
        subscribeSlack(1, 2)
        slackSettings(1, webhookUrl = "https://hooks.slack.com/services/A")
        slackSettings(2, webhookUrl = "https://hooks.slack.com/services/B")
        every { slackClient.postWebhook("https://hooks.slack.com/services/A", any()) } returns
            ChatDeliveryResult.failed("boom")
        every { slackClient.postWebhook("https://hooks.slack.com/services/B", any()) } returns
            ChatDeliveryResult.ok()

        val slack = resultFor(service.dispatch(event), NotificationChannel.SLACK)

        assertThat(slack.failed).isEqualTo(1)
        assertThat(slack.sent).isEqualTo(1)
    }

    @Test
    fun `records the Slack delivery outcome on the user's settings row`() {
        subscribeSlack(1)
        val stored = slackSettings(1)
        every { slackClient.postWebhook(any(), any()) } returns ChatDeliveryResult.failed("bad webhook")

        service.dispatch(event)

        assertThat(stored.lastDeliveryStatus).isEqualTo(ChatDeliveryStatus.FAILED)
        assertThat(stored.lastDeliveryError).isEqualTo("bad webhook")
        assertThat(stored.lastNotifiedAt).isNotNull()
        verify { slackSettingsRepository.update(stored) }
    }

    // --- Telegram ------------------------------------------------------------------

    @Test
    fun `sends to a Telegram subscriber via the workspace bot`() {
        subscribeTelegram(1)
        telegramSettings(1)
        every { telegramConfigService.find() } returns TelegramConfig(enabled = true, botToken = "123:abc")
        every { telegramClient.sendMessage(any(), any(), any()) } returns ChatDeliveryResult.ok()

        val telegram = resultFor(service.dispatch(event), NotificationChannel.TELEGRAM)

        assertThat(telegram.sent).isEqualTo(1)
        verify(exactly = 1) { telegramClient.sendMessage("123:abc", "123456789", any()) }
    }

    @Test
    fun `a personal Telegram bot token wins over the workspace bot`() {
        subscribeTelegram(1)
        telegramSettings(1, botToken = "999:mine")
        every { telegramConfigService.find() } returns TelegramConfig(enabled = true, botToken = "123:abc")
        every { telegramClient.sendMessage(any(), any(), any()) } returns ChatDeliveryResult.ok()

        service.dispatch(event)

        verify(exactly = 1) { telegramClient.sendMessage("999:mine", "123456789", any()) }
    }

    @Test
    fun `a personal Telegram bot works even when workspace delivery is disabled`() {
        subscribeTelegram(1)
        telegramSettings(1, botToken = "999:mine")
        every { telegramConfigService.find() } returns TelegramConfig(enabled = false, botToken = "123:abc")
        every { telegramClient.sendMessage(any(), any(), any()) } returns ChatDeliveryResult.ok()

        val telegram = resultFor(service.dispatch(event), NotificationChannel.TELEGRAM)

        assertThat(telegram.sent).isEqualTo(1)
    }

    @Test
    fun `skips a Telegram subscriber with no chat ID`() {
        subscribeTelegram(1)
        telegramSettings(1, chatId = null)
        every { telegramConfigService.find() } returns TelegramConfig(enabled = true, botToken = "123:abc")

        val telegram = resultFor(service.dispatch(event), NotificationChannel.TELEGRAM)

        assertThat(telegram.skipped).isEqualTo(1)
        verify(exactly = 0) { telegramClient.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `skips a Telegram subscriber when no bot token is available at all`() {
        subscribeTelegram(1)
        telegramSettings(1)

        val telegram = resultFor(service.dispatch(event), NotificationChannel.TELEGRAM)

        assertThat(telegram.skipped).isEqualTo(1)
        verify(exactly = 0) { telegramClient.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `records the Telegram delivery outcome on the user's settings row`() {
        subscribeTelegram(1)
        val stored = telegramSettings(1)
        every { telegramConfigService.find() } returns TelegramConfig(enabled = true, botToken = "123:abc")
        every { telegramClient.sendMessage(any(), any(), any()) } returns ChatDeliveryResult.failed("chat not found")

        service.dispatch(event)

        assertThat(stored.lastDeliveryStatus).isEqualTo(ChatDeliveryStatus.FAILED)
        assertThat(stored.lastDeliveryError).isEqualTo("chat not found")
        verify { telegramSettingsRepository.update(stored) }
    }

    // --- Channel independence ------------------------------------------------------

    @Test
    fun `a Slack subscription does not deliver over Telegram`() {
        subscribeSlack(1)
        slackSettings(1)
        telegramSettings(1)
        every { telegramConfigService.find() } returns TelegramConfig(enabled = true, botToken = "123:abc")
        every { slackClient.postWebhook(any(), any()) } returns ChatDeliveryResult.ok()

        val results = service.dispatch(event)

        assertThat(resultFor(results, NotificationChannel.SLACK).sent).isEqualTo(1)
        assertThat(resultFor(results, NotificationChannel.TELEGRAM).subscribers).isZero()
        verify(exactly = 0) { telegramClient.sendMessage(any(), any(), any()) }
    }

    @Test
    fun `a user subscribed on both channels receives both`() {
        subscribeSlack(1)
        subscribeTelegram(1)
        slackSettings(1)
        telegramSettings(1)
        every { telegramConfigService.find() } returns TelegramConfig(enabled = true, botToken = "123:abc")
        every { slackClient.postWebhook(any(), any()) } returns ChatDeliveryResult.ok()
        every { telegramClient.sendMessage(any(), any(), any()) } returns ChatDeliveryResult.ok()

        val results = service.dispatch(event)

        assertThat(resultFor(results, NotificationChannel.SLACK).sent).isEqualTo(1)
        assertThat(resultFor(results, NotificationChannel.TELEGRAM).sent).isEqualTo(1)
    }

    @Test
    fun `a failing channel does not suppress the other`() {
        subscribeSlack(1)
        subscribeTelegram(1)
        slackSettings(1)
        telegramSettings(1)
        every { telegramConfigService.find() } returns TelegramConfig(enabled = true, botToken = "123:abc")
        every { slackClient.postWebhook(any(), any()) } throws RuntimeException("slack exploded")
        every { telegramClient.sendMessage(any(), any(), any()) } returns ChatDeliveryResult.ok()

        val results = service.dispatch(event)

        assertThat(resultFor(results, NotificationChannel.SLACK).failed).isEqualTo(1)
        assertThat(resultFor(results, NotificationChannel.TELEGRAM).sent).isEqualTo(1)
    }

    // --- Test messages --------------------------------------------------------------

    @Test
    fun `test message reports a helpful error when the channel is not configured`() {
        every { slackSettingsRepository.findByUserId(9L) } returns Optional.empty()
        every { telegramSettingsRepository.findByUserId(9L) } returns Optional.empty()

        assertThat(service.sendTestMessage(9L, NotificationChannel.SLACK, "hi").error).contains("not configured")
        assertThat(service.sendTestMessage(9L, NotificationChannel.TELEGRAM, "hi").error).contains("not configured")
    }

    @Test
    fun `test message goes to the user's own destination`() {
        slackSettings(1)
        every { slackClient.postWebhook(any(), any()) } returns ChatDeliveryResult.ok()

        val result = service.sendTestMessage(1L, NotificationChannel.SLACK, "hello")

        assertThat(result.success).isTrue()
        verify(exactly = 1) { slackClient.postWebhook("https://hooks.slack.com/services/T/B/X", "hello") }
    }
}
