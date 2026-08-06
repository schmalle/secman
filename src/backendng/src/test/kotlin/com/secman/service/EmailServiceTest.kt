package com.secman.service

import com.secman.domain.EmailConfig
import com.secman.repository.EmailConfigRepository
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties

class EmailServiceTest {
    private val emailConfigRepository = mockk<EmailConfigRepository>()
    private val service = EmailService(emailConfigRepository)

    @Test
    fun `smtp read and write timeouts allow slower SES data responses`() {
        val properties = createMailProperties(
            EmailConfig.createSmtp(
                name = "SES SMTP",
                smtpHost = "email-smtp.eu-central-1.amazonaws.com",
                smtpPort = 587,
                smtpUsername = "ssm-user",
                smtpPassword = "secret",
                smtpTls = true,
                smtpSsl = false,
                fromEmail = "secman@example.com",
                fromName = "SecMan"
            )
        )

        assertThat(properties.getProperty("mail.smtp.timeout")).isEqualTo("30000")
        assertThat(properties.getProperty("mail.smtp.writetimeout")).isEqualTo("30000")
    }

    private fun createMailProperties(config: EmailConfig): Properties {
        val method = EmailService::class.java.getDeclaredMethod("createMailProperties", EmailConfig::class.java)
        method.isAccessible = true
        return method.invoke(service, config) as Properties
    }
}
