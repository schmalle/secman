package com.secman.service

import com.secman.domain.EmailConfig
import jakarta.inject.Singleton
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Amazon SES email sending service via SMTP
 * Provides email delivery via AWS SES SMTP endpoint using Jakarta Mail
 */
@Singleton
class SesEmailService {
    private val logger = LoggerFactory.getLogger(SesEmailService::class.java)

    data class SendResult(
        val success: Boolean,
        val errorMessage: String? = null,
        val messageId: String? = null
    )

    /**
     * Send email via Amazon SES SMTP endpoint
     */
    fun sendEmail(
        config: EmailConfig,
        to: String,
        subject: String,
        htmlBody: String,
        plainTextBody: String
    ): SendResult {
        if (config.sesAccessKey.isNullOrBlank() || config.sesSecretKey.isNullOrBlank() || config.sesRegion.isNullOrBlank()) {
            return SendResult(
                success = false,
                errorMessage = "SES configuration is incomplete"
            )
        }

        try {
            val props = Properties()
            config.getSesSmtpProperties().forEach { (key, value) -> props[key] = value }

            val authenticator = object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(config.sesAccessKey, config.sesSecretKey)
                }
            }

            val session = Session.getInstance(props, authenticator)

            val mimeMessage = MimeMessage(session).apply {
                setFrom(InternetAddress(config.fromEmail, config.fromName))
                addRecipient(Message.RecipientType.TO, InternetAddress(to))
                setSubject(sanitizeEmailHeader(subject), "UTF-8")

                val multipart = MimeMultipart("alternative")

                val textPart = MimeBodyPart().apply {
                    setText(plainTextBody, "UTF-8")
                }
                multipart.addBodyPart(textPart)

                val htmlPart = MimeBodyPart().apply {
                    setContent(htmlBody, "text/html; charset=UTF-8")
                }
                multipart.addBodyPart(htmlPart)

                setContent(multipart)
                sentDate = Date()
            }

            Transport.send(mimeMessage)

            val messageId = mimeMessage.messageID
            logger.info("Email sent successfully via SES SMTP to $to, MessageId: $messageId")

            return SendResult(
                success = true,
                messageId = messageId
            )
        } catch (e: AuthenticationFailedException) {
            val errorMsg = "SES SMTP authentication failed: ${e.message}"
            logger.error(errorMsg, e)
            return SendResult(success = false, errorMessage = errorMsg)
        } catch (e: MessagingException) {
            val errorMsg = "SES SMTP error: ${e.message}"
            logger.error(errorMsg, e)
            return SendResult(success = false, errorMessage = errorMsg)
        } catch (e: Exception) {
            val errorMsg = "Failed to send email via SES SMTP: ${e.message}"
            logger.error(errorMsg, e)
            return SendResult(success = false, errorMessage = errorMsg)
        }
    }

    /**
     * Send test email via SES SMTP
     */
    fun sendTestEmail(config: EmailConfig, toAddress: String): SendResult {
        val htmlBody = """
            <html>
            <body>
                <h2>Test Email from Amazon SES</h2>
                <p>This is a test email from SecMan notification system using Amazon SES.</p>
                <p>If you received this, your SES configuration is working correctly.</p>
            </body>
            </html>
        """.trimIndent()

        val plainTextBody = """
            Test Email from Amazon SES

            This is a test email from SecMan notification system using Amazon SES.
            If you received this, your SES configuration is working correctly.
        """.trimIndent()

        return sendEmail(
            config = config,
            to = toAddress,
            subject = "SecMan Test Email (Amazon SES)",
            htmlBody = htmlBody,
            plainTextBody = plainTextBody
        )
    }

    /**
     * Sanitize email header to prevent header injection attacks (CWE-93)
     * Removes CRLF characters that could be used to inject additional headers
     */
    private fun sanitizeEmailHeader(value: String): String {
        return value.replace(Regex("[\r\n]"), "")
    }

    /**
     * Verify SES SMTP configuration by attempting an SMTP connection
     */
    fun verifyConfiguration(config: EmailConfig): Pair<Boolean, String> {
        if (config.sesAccessKey.isNullOrBlank() || config.sesSecretKey.isNullOrBlank() || config.sesRegion.isNullOrBlank()) {
            return Pair(false, "SES configuration is incomplete")
        }

        try {
            val props = Properties()
            config.getSesSmtpProperties().forEach { (key, value) -> props[key] = value }

            val session = Session.getInstance(props)
            val transport = session.getTransport("smtp")

            transport.use {
                it.connect(
                    config.getSesSmtpHost(),
                    587,
                    config.sesAccessKey,
                    config.sesSecretKey
                )
            }

            return Pair(true, "SES SMTP configuration is valid")
        } catch (e: AuthenticationFailedException) {
            return Pair(false, "SES SMTP authentication failed: ${e.message}")
        } catch (e: MessagingException) {
            return Pair(false, "SES SMTP connection failed: ${e.message}")
        } catch (e: Exception) {
            return Pair(false, "Configuration verification failed: ${e.message}")
        }
    }
}
