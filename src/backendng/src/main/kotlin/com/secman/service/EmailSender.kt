package com.secman.service

import com.secman.config.EmailConfig
import jakarta.inject.Singleton
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Low-level SMTP email sending service with retry logic
 * Feature 035: Outdated Asset Notification System
 */
@Singleton
class EmailSender(
    private val emailConfig: EmailConfig
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
     * Returns SendResult with success status and error details
     */
    fun sendEmail(message: EmailMessage): SendResult {
        val maxRetries = emailConfig.retry.maxRetries
        val delayMs = emailConfig.retry.delayBetweenRetriesMs

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
     * Internal method to send email via SMTP
     * Throws exception on failure for retry logic
     */
    private fun sendEmailInternal(message: EmailMessage) {
        // Get SMTP configuration from database (via EmailConfig which reads from application.yml)
        // TODO: In production, this should query the email configuration from the database table
        // For now, using configuration from application.yml as specified by email config pattern

        val props = Properties().apply {
            put("mail.smtp.host", emailConfig.smtpHost)
            put("mail.smtp.port", emailConfig.smtpPort)
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", emailConfig.enableTls)
            put("mail.smtp.ssl.protocols", "TLSv1.2")
            put("mail.smtp.connectiontimeout", emailConfig.performance.connectionTimeoutMs)
            put("mail.smtp.timeout", emailConfig.performance.readTimeoutMs)
        }

        val authenticator = object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(emailConfig.username, emailConfig.password)
            }
        }

        val session = Session.getInstance(props, authenticator)

        val mimeMessage = MimeMessage(session).apply {
            setFrom(InternetAddress(emailConfig.fromAddress, emailConfig.fromName))
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
