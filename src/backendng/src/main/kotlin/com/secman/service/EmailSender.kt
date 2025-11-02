package com.secman.service

import com.secman.config.EmailConfig as YamlEmailConfig
import com.secman.domain.EmailProvider
import com.secman.repository.EmailConfigRepository
import jakarta.inject.Singleton
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Email sending service with support for SMTP and Amazon SES
 * Supports multiple providers with retry logic
 * Feature 035: Outdated Asset Notification System
 * Extended: Amazon SES support
 */
@Singleton
class EmailSender(
    private val yamlEmailConfig: YamlEmailConfig,
    private val emailConfigRepository: EmailConfigRepository,
    private val sesEmailService: SesEmailService
) {
    private val logger = LoggerFactory.getLogger(EmailSender::class.java)

    data class EmailMessage(
        val to: String,
        val subject: String,
        val htmlBody: String,
        val plainTextBody: String
    )

    data class SendResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val attemptCount: Int = 0
    )

    /**
     * Send email with automatic retry on failure
     * Uses active email configuration from database, falls back to YAML config
     * Returns SendResult with success status and error details
     */
    fun sendEmail(message: EmailMessage): SendResult {
        // Get active email configuration from database
        val dbConfig = emailConfigRepository.findActiveConfig().orElse(null)

        if (dbConfig != null) {
            // Use database configuration with provider-specific sending
            return when (dbConfig.provider) {
                EmailProvider.SMTP -> sendEmailViaSmtp(message, dbConfig)
                EmailProvider.AMAZON_SES -> sendEmailViaSes(message, dbConfig)
            }
        } else {
            // Fallback to YAML configuration (SMTP only)
            logger.warn("No active email configuration in database, using YAML config")
            return sendEmailViaSmtpYaml(message)
        }
    }

    /**
     * Send email via Amazon SES
     */
    private fun sendEmailViaSes(message: EmailMessage, config: com.secman.domain.EmailConfig): SendResult {
        val maxRetries = yamlEmailConfig.retry.maxRetries
        val delayMs = yamlEmailConfig.retry.delayBetweenRetriesMs

        var lastError: String? = null

        for (attempt in 1..maxRetries) {
            val result = sesEmailService.sendEmail(
                config = config,
                to = message.to,
                subject = message.subject,
                htmlBody = message.htmlBody,
                plainTextBody = message.plainTextBody
            )

            if (result.success) {
                logger.info("Email sent successfully via SES to ${message.to} on attempt $attempt")
                return SendResult(success = true, attemptCount = attempt)
            } else {
                lastError = result.errorMessage
                logger.warn("SES email send attempt $attempt/$maxRetries failed for ${message.to}: ${result.errorMessage}")

                if (attempt < maxRetries) {
                    Thread.sleep(delayMs)
                }
            }
        }

        val errorMsg = "Failed to send email via SES after $maxRetries attempts: $lastError"
        logger.error(errorMsg)
        return SendResult(success = false, errorMessage = errorMsg, attemptCount = maxRetries)
    }

    /**
     * Send email via SMTP using database configuration
     */
    private fun sendEmailViaSmtp(message: EmailMessage, config: com.secman.domain.EmailConfig): SendResult {
        val maxRetries = yamlEmailConfig.retry.maxRetries
        val delayMs = yamlEmailConfig.retry.delayBetweenRetriesMs

        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                sendEmailInternalWithDbConfig(message, config)
                logger.info("Email sent successfully via SMTP to ${message.to} on attempt $attempt")
                return SendResult(success = true, attemptCount = attempt)
            } catch (e: Exception) {
                lastException = e
                logger.warn("SMTP email send attempt $attempt/$maxRetries failed for ${message.to}: ${e.message}")

                if (attempt < maxRetries) {
                    Thread.sleep(delayMs)
                }
            }
        }

        val errorMsg = "Failed to send email via SMTP after $maxRetries attempts: ${lastException?.message}"
        logger.error(errorMsg, lastException)
        return SendResult(success = false, errorMessage = errorMsg, attemptCount = maxRetries)
    }

    /**
     * Send email via SMTP using YAML configuration (fallback)
     */
    private fun sendEmailViaSmtpYaml(message: EmailMessage): SendResult {
        val maxRetries = yamlEmailConfig.retry.maxRetries
        val delayMs = yamlEmailConfig.retry.delayBetweenRetriesMs

        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                sendEmailInternal(message)
                logger.info("Email sent successfully to ${message.to} on attempt $attempt")
                return SendResult(success = true, attemptCount = attempt)
            } catch (e: Exception) {
                lastException = e
                logger.warn("Email send attempt $attempt/$maxRetries failed for ${message.to}: ${e.message}")

                if (attempt < maxRetries) {
                    Thread.sleep(delayMs)
                }
            }
        }

        val errorMsg = "Failed to send email after $maxRetries attempts: ${lastException?.message}"
        logger.error(errorMsg, lastException)
        return SendResult(success = false, errorMessage = errorMsg, attemptCount = maxRetries)
    }

    /**
     * Internal method to send email via SMTP using database configuration
     * Throws exception on failure for retry logic
     */
    private fun sendEmailInternalWithDbConfig(message: EmailMessage, config: com.secman.domain.EmailConfig) {
        val props = config.getSmtpProperties()
        val javaProps = Properties()
        props.forEach { (key, value) -> javaProps[key] = value }

        val authenticator = if (config.hasAuthentication()) {
            object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(config.smtpUsername, config.smtpPassword)
                }
            }
        } else {
            null
        }

        val session = Session.getInstance(javaProps, authenticator)

        val mimeMessage = MimeMessage(session).apply {
            setFrom(InternetAddress(config.fromEmail, config.fromName))
            addRecipient(Message.RecipientType.TO, InternetAddress(message.to))
            subject = sanitizeEmailHeader(message.subject)

            // Create multipart message with HTML and plain-text alternatives
            val multipart = MimeMultipart("alternative")

            // Plain text part (first, lowest priority)
            val textPart = MimeBodyPart().apply {
                setText(message.plainTextBody, "UTF-8")
            }
            multipart.addBodyPart(textPart)

            // HTML part (second, higher priority)
            val htmlPart = MimeBodyPart().apply {
                setContent(message.htmlBody, "text/html; charset=UTF-8")
            }
            multipart.addBodyPart(htmlPart)

            setContent(multipart)
            sentDate = Date()
        }

        // Send the email
        Transport.send(mimeMessage)
    }

    /**
     * Internal method to send email via SMTP using YAML configuration
     * Throws exception on failure for retry logic
     */
    private fun sendEmailInternal(message: EmailMessage) {
        val props = Properties().apply {
            put("mail.smtp.host", yamlEmailConfig.smtpHost)
            put("mail.smtp.port", yamlEmailConfig.smtpPort)
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", yamlEmailConfig.enableTls)
            put("mail.smtp.ssl.protocols", "TLSv1.2")
            put("mail.smtp.connectiontimeout", yamlEmailConfig.performance.connectionTimeoutMs)
            put("mail.smtp.timeout", yamlEmailConfig.performance.readTimeoutMs)
        }

        val authenticator = object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(yamlEmailConfig.username, yamlEmailConfig.password)
            }
        }

        val session = Session.getInstance(props, authenticator)

        val mimeMessage = MimeMessage(session).apply {
            setFrom(InternetAddress(yamlEmailConfig.fromAddress, yamlEmailConfig.fromName))
            addRecipient(Message.RecipientType.TO, InternetAddress(message.to))
            subject = sanitizeEmailHeader(message.subject)

            // Create multipart message with HTML and plain-text alternatives
            val multipart = MimeMultipart("alternative")

            // Plain text part (first, lowest priority)
            val textPart = MimeBodyPart().apply {
                setText(message.plainTextBody, "UTF-8")
            }
            multipart.addBodyPart(textPart)

            // HTML part (second, higher priority)
            val htmlPart = MimeBodyPart().apply {
                setContent(message.htmlBody, "text/html; charset=UTF-8")
            }
            multipart.addBodyPart(htmlPart)

            setContent(multipart)
            sentDate = Date()
        }

        // Send the email
        Transport.send(mimeMessage)
    }

    /**
     * Sanitize email header to prevent header injection attacks (CWE-93)
     * Removes CRLF characters that could be used to inject additional headers
     */
    private fun sanitizeEmailHeader(value: String): String {
        return value.replace(Regex("[\r\n]"), "")
    }

    /**
     * Validate email address format
     */
    fun isValidEmail(email: String): Boolean {
        return try {
            InternetAddress(email).validate()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Send test email to verify SMTP configuration
     * Used for admin diagnostics
     */
    fun sendTestEmail(toAddress: String): SendResult {
        val testMessage = EmailMessage(
            to = toAddress,
            subject = "SecMan Test Email",
            htmlBody = """
                <html>
                <body>
                    <h2>Test Email</h2>
                    <p>This is a test email from SecMan notification system.</p>
                    <p>If you received this, your email configuration is working correctly.</p>
                </body>
                </html>
            """.trimIndent(),
            plainTextBody = """
                Test Email

                This is a test email from SecMan notification system.
                If you received this, your email configuration is working correctly.
            """.trimIndent()
        )

        return sendEmail(testMessage)
    }
}
