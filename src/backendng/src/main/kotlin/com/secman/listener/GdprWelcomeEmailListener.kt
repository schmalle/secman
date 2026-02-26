package com.secman.listener

import com.secman.event.UserCreatedEvent
import com.secman.repository.EmailConfigRepository
import com.secman.service.EmailService
import io.micronaut.runtime.event.annotation.EventListener
import io.micronaut.scheduling.annotation.Async
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Sends a GDPR data processing notification email to newly created users.
 *
 * Feature: 210 - GDPR compliance notification
 *
 * Under GDPR Article 13/14, data subjects must be informed when their personal
 * data is collected and processed. This listener sends that notification whenever
 * a user account is created (manually, via OAuth, MCP, or import).
 *
 * The email is sent asynchronously and best-effort: failures are logged but do
 * not prevent user creation.
 */
@Singleton
open class GdprWelcomeEmailListener(
    private val emailService: EmailService,
    private val emailConfigRepository: EmailConfigRepository
) {

    private val logger = LoggerFactory.getLogger(GdprWelcomeEmailListener::class.java)

    @EventListener
    @Async
    open fun onUserCreated(event: UserCreatedEvent) {
        val user = event.user
        logger.info("Sending GDPR welcome email to {} (source: {})", user.email, event.source)

        try {
            val activeConfig = emailConfigRepository.findActiveConfig().orElse(null)
            if (activeConfig == null) {
                logger.warn("No active email configuration found. Skipping GDPR welcome email for {}", user.email)
                return
            }

            val subject = "Welcome to SecMan - Data Processing Notice"
            val htmlContent = buildHtmlContent(user.username, user.email)
            val textContent = buildTextContent(user.username, user.email)

            val success = emailService.sendEmail(
                to = user.email,
                subject = subject,
                textContent = textContent,
                htmlContent = htmlContent
            ).get()

            if (success) {
                logger.info("GDPR welcome email sent successfully to {}", user.email)
            } else {
                logger.warn("Failed to send GDPR welcome email to {}", user.email)
            }
        } catch (e: Exception) {
            logger.error("Error sending GDPR welcome email to {}: {}", user.email, e.message, e)
        }
    }

    private fun buildHtmlContent(username: String, email: String): String {
        val templateStream = javaClass.classLoader.getResourceAsStream("email-templates/gdpr-welcome.html")
        if (templateStream != null) {
            return templateStream.bufferedReader().readText()
                .replace("\${username}", escapeHtml(username))
                .replace("\${email}", escapeHtml(email))
        }

        logger.warn("GDPR welcome HTML template not found, using inline fallback")
        return buildFallbackHtml(username, email)
    }

    private fun buildTextContent(username: String, email: String): String {
        val templateStream = javaClass.classLoader.getResourceAsStream("email-templates/gdpr-welcome.txt")
        if (templateStream != null) {
            return templateStream.bufferedReader().readText()
                .replace("\${username}", username)
                .replace("\${email}", email)
        }

        logger.warn("GDPR welcome text template not found, using inline fallback")
        return buildFallbackText(username, email)
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun buildFallbackHtml(username: String, email: String): String {
        val safeUsername = escapeHtml(username)
        val safeEmail = escapeHtml(email)
        return """
            <!DOCTYPE html>
            <html>
            <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                <h1 style="color: #0d6efd;">Welcome to SecMan</h1>
                <p>Hello $safeUsername,</p>
                <p>Your account has been created in the SecMan Security Management System.
                In accordance with the GDPR, we inform you that your personal data
                (email: $safeEmail, username, role assignments, and activity logs)
                is processed for security management, notifications, and audit purposes.</p>
                <p>Legal basis: Legitimate interest (Art. 6(1)(f)) and contractual necessity (Art. 6(1)(b)).</p>
                <p>You have the right to access, rectify, erase, restrict, and object to the processing
                of your data. Contact your system administrator to exercise these rights.</p>
                <hr style="border: none; border-top: 1px solid #dee2e6;">
                <p style="color: #6c757d; font-size: 12px;">This is an automated notification from SecMan. Do not reply.</p>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildFallbackText(username: String, email: String): String {
        return """
            Welcome to SecMan - Data Processing Notice

            Hello $username,

            Your account has been created in the SecMan Security Management System.
            In accordance with the GDPR, we inform you that your personal data
            (email: $email, username, role assignments, and activity logs)
            is processed for security management, notifications, and audit purposes.

            Legal basis: Legitimate interest (Art. 6(1)(f)) and contractual necessity (Art. 6(1)(b)).

            You have the right to access, rectify, erase, restrict, and object to the processing
            of your data. Contact your system administrator to exercise these rights.

            ---
            This is an automated notification from SecMan. Do not reply.
        """.trimIndent()
    }
}
