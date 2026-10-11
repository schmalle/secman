package com.secman.service

import com.secman.domain.NotificationType
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmailTemplateServiceTest {
    @Test
    fun `overdue plain text explains sensor eligibility without changing new vulnerability messages`() {
        val service = EmailTemplateService(mockk())
        for (type in NotificationType.entries) {
            val context = EmailTemplateService.EmailContext(
                recipientEmail = "recipient@example.test", recipientName = null,
                assets = emptyList(), notificationType = type, totalCount = 0,
                criticalCount = 0, highCount = 0, mediumCount = 0, lowCount = 0,
                dashboardUrl = "https://example.test"
            )
            val text = service.renderPlainText(context)
            if (type == NotificationType.NEW_VULNERABILITY) {
                assertThat(text).doesNotContain("Falcon sensor contact")
            } else {
                assertThat(text).contains("Falcon sensor contact within the last 72 hours")
            }
        }
    }
}
