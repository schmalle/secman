package com.secman.service

import com.secman.domain.EmailConfig
import com.secman.domain.EmailNotificationLog
import com.secman.domain.enums.EmailStatus
import com.secman.repository.EmailConfigRepository
import com.secman.repository.EmailNotificationLogRepository
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import jakarta.inject.Singleton
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.*
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

@Singleton
@ExecuteOn(TaskExecutors.IO)
open class EmailService(
    private val emailConfigRepository: EmailConfigRepository,
    private val emailNotificationLogRepository: EmailNotificationLogRepository? = null
) {

    private val log = LoggerFactory.getLogger(EmailService::class.java)

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_SECONDS = 2L
        private const val EMAIL_TIMEOUT_SECONDS = 30L
    }
    
    /**
     * Send email with both text and HTML content
     */
    fun sendEmail(
        to: String,
        subject: String,
        textContent: String,
        htmlContent: String
    ): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            runBlocking {
                try {
                    val activeConfig = getActiveEmailConfig()
                        ?: throw IllegalStateException("No active email configuration found")
                    
                    sendEmailWithConfig(activeConfig, to, subject, textContent, htmlContent)
                } catch (e: Exception) {
                    log.error("Failed to send email to {}: {}", to, e.message, e)
                    false
                }
            }
        }
    }
    
    /**
     * Send HTML email with auto-generated text content
     */
    fun sendHtmlEmail(
        to: String,
        subject: String,
        htmlContent: String
    ): CompletableFuture<Boolean> {
        val textContent = convertHtmlToText(htmlContent)
        return sendEmail(to, subject, textContent, htmlContent)
    }
    
    /**
     * Test email configuration by sending a test email
     */
    fun testEmailConfiguration(
        config: EmailConfig,
        testToEmail: String
    ): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            try {
                val subject = "SecMan Email Configuration Test"
                val textContent = """
                    This is a test email from SecMan to verify your email configuration.
                    
                    Configuration Details:
                    - SMTP Host: ${config.smtpHost}
                    - SMTP Port: ${config.smtpPort}
                    - TLS Enabled: ${config.smtpTls}
                    - SSL Enabled: ${config.smtpSsl}
                    - Authentication: ${if (config.hasAuthentication()) "Yes" else "No"}
                    
                    If you received this email, your configuration is working correctly!
                """.trimIndent()
                
                val htmlContent = """
                    <html>
                    <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                        <div style="max-width: 600px; margin: 0 auto; padding: 20px;">
                            <h2 style="color: #2c3e50;">SecMan Email Configuration Test</h2>
                            <p>This is a test email from SecMan to verify your email configuration.</p>
                            
                            <div style="background-color: #f8f9fa; padding: 15px; border-radius: 5px; margin: 20px 0;">
                                <h3 style="margin-top: 0; color: #495057;">Configuration Details:</h3>
                                <ul style="margin: 0; padding-left: 20px;">
                                    <li><strong>SMTP Host:</strong> ${config.smtpHost}</li>
                                    <li><strong>SMTP Port:</strong> ${config.smtpPort}</li>
                                    <li><strong>TLS Enabled:</strong> ${config.smtpTls}</li>
                                    <li><strong>SSL Enabled:</strong> ${config.smtpSsl}</li>
                                    <li><strong>Authentication:</strong> ${if (config.hasAuthentication()) "Yes" else "No"}</li>
                                </ul>
                            </div>
                            
                            <p style="color: #28a745; font-weight: bold;">
                                ✅ If you received this email, your configuration is working correctly!
                            </p>
                            
                            <hr style="margin: 30px 0; border: none; border-top: 1px solid #dee2e6;">
                            <p style="font-size: 0.9em; color: #6c757d;">
                                This email was sent by SecMan Email Configuration Test
                            </p>
                        </div>
                    </body>
                    </html>
                """.trimIndent()
                
                sendEmailWithConfig(config, testToEmail, subject, textContent, htmlContent)
            } catch (e: Exception) {
                log.error("Failed to test email configuration: {}", e.message, e)
                false
            }
        }
    }
    
    /**
     * Get the currently active email configuration
     */
    private fun getActiveEmailConfig(): EmailConfig? {
        return emailConfigRepository.findActiveConfig().orElse(null)
    }
    
    /**
     * Send email using specific configuration
     */
    private fun sendEmailWithConfig(
        config: EmailConfig,
        to: String,
        subject: String,
        textContent: String,
        htmlContent: String
    ): Boolean {
        return try {
            log.debug("Sending email to {} using SMTP {}:{}", to, config.smtpHost, config.smtpPort)
            log.debug("Email config - TLS: {}, SSL: {}, Auth: {}", config.smtpTls, config.smtpSsl, config.hasAuthentication())
            
            val properties = createMailProperties(config)
            val session = createMailSession(properties, config)
            
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(config.fromEmail, config.fromName))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                setSubject(subject)
                
                // Create multipart message with both text and HTML
                val multipart = MimeMultipart("alternative")
                
                // Add text part
                val textPart = MimeBodyPart().apply {
                    setText(textContent, "UTF-8")
                }
                multipart.addBodyPart(textPart)
                
                // Add HTML part
                val htmlPart = MimeBodyPart().apply {
                    setContent(htmlContent, "text/html; charset=UTF-8")
                }
                multipart.addBodyPart(htmlPart)
                
                setContent(multipart)
                sentDate = Date()
            }
            
            log.debug("Attempting to send email...")
            Transport.send(message)
            log.info("Successfully sent email to {} with subject: {}", to, subject)
            true
            
        } catch (e: Exception) {
            log.error("Failed to send email to {}: {} - {}", to, e.javaClass.simpleName, e.message, e)
            false
        }
    }
    
    /**
     * Create mail properties from email configuration
     */
    private fun createMailProperties(config: EmailConfig): Properties {
        return Properties().apply {
            put("mail.smtp.host", config.smtpHost)
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.auth", config.hasAuthentication().toString())
            
            if (config.smtpTls) {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
            
            if (config.smtpSsl) {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                put("mail.smtp.socketFactory.port", config.smtpPort.toString())
            }
            
            // Additional security properties
            put("mail.smtp.ssl.trust", config.smtpHost)
            put("mail.smtp.ssl.protocols", "TLSv1.2")
        }
    }
    
    /**
     * Create mail session with or without authentication
     */
    private fun createMailSession(properties: Properties, config: EmailConfig): Session {
        return if (config.hasAuthentication()) {
            Session.getInstance(properties, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    // Ensure non-null values (hasAuthentication already checked they're not null/blank)
                    val username = config.smtpUsername ?: ""
                    val password = config.smtpPassword ?: ""
                    return PasswordAuthentication(username, password)
                }
            })
        } else {
            Session.getInstance(properties)
        }
    }
    
    /**
     * Send notification email with retry logic and logging
     */
    fun sendNotificationEmail(
        riskAssessmentId: Long,
        to: String,
        subject: String,
        textContent: String,
        htmlContent: String,
        emailConfigId: Long? = null
    ): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            runBlocking {
                try {
                    val config = emailConfigId?.let {
                        emailConfigRepository.findById(it).orElse(null)
                    } ?: getActiveEmailConfig()

                    if (config == null) {
                        log.error("No email configuration found for notification")
                        return@runBlocking false
                    }

                    // Create notification log entry
                    val logEntry = emailNotificationLogRepository?.let {
                        createNotificationLogEntry(riskAssessmentId, config.id!!, to, subject)
                    }

                    // Send email with retry logic
                    val success = sendEmailWithRetry(config, to, subject, textContent, htmlContent)

                    // Update notification log
                    logEntry?.let { entry ->
                        updateNotificationLogEntry(entry, success)
                    }

                    success
                } catch (e: Exception) {
                    log.error("Failed to send notification email to {}: {}", to, e.message, e)
                    false
                }
            }
        }
    }

    /**
     * Send email with retry logic
     */
    suspend fun sendEmailWithRetry(
        config: EmailConfig,
        to: String,
        subject: String,
        textContent: String,
        htmlContent: String,
        maxAttempts: Int = MAX_RETRY_ATTEMPTS
    ): Boolean = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        repeat(maxAttempts) { attempt ->
            try {
                log.debug("Sending email attempt {} of {} to {}", attempt + 1, maxAttempts, to)

                // Add timeout to email sending
                val result = withTimeout(EMAIL_TIMEOUT_SECONDS.seconds) {
                    sendEmailWithConfig(config, to, subject, textContent, htmlContent)
                }

                if (result) {
                    if (attempt > 0) {
                        log.info("Email sent successfully to {} after {} retries", to, attempt)
                    }
                    return@withContext true
                }

            } catch (e: Exception) {
                lastException = e
                log.warn("Email sending attempt {} failed for {}: {}", attempt + 1, to, e.message)

                // Don't wait after the last attempt
                if (attempt < maxAttempts - 1) {
                    try {
                        delay(RETRY_DELAY_SECONDS.seconds * (attempt + 1)) // Exponential backoff
                    } catch (interrupted: Exception) {
                        log.warn("Retry delay interrupted", interrupted)
                        return@withContext false
                    }
                }
            }
        }

        log.error("Failed to send email to {} after {} attempts", to, maxAttempts, lastException)
        false
    }

    /**
     * Send bulk notification emails
     */
    fun sendBulkNotificationEmails(
        notifications: List<NotificationEmailRequest>
    ): CompletableFuture<Map<String, Boolean>> {
        return CompletableFuture.supplyAsync {
            runBlocking {
                val results = mutableMapOf<String, Boolean>()

                notifications.forEach { request ->
                    try {
                        val success = sendNotificationEmail(
                            riskAssessmentId = request.riskAssessmentId,
                            to = request.to,
                            subject = request.subject,
                            textContent = request.textContent,
                            htmlContent = request.htmlContent,
                            emailConfigId = request.emailConfigId
                        ).get()

                        results[request.to] = success

                    } catch (e: Exception) {
                        log.error("Failed to send bulk notification to {}", request.to, e)
                        results[request.to] = false
                    }
                }

                log.info("Bulk notification completed: {} emails, {} successful",
                    notifications.size, results.values.count { it })

                results
            }
        }
    }

    /**
     * Validate email configuration connectivity
     */
    suspend fun validateEmailConfigConnectivity(config: EmailConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val properties = createMailProperties(config)
            val session = createMailSession(properties, config)

            val transport = session.getTransport("smtp")
            transport.connect()
            transport.close()

            log.debug("Email configuration validation successful for {}", config.name)
            true

        } catch (e: Exception) {
            log.warn("Email configuration validation failed for {}: {}", config.name, e.message)
            false
        }
    }

    /**
     * Get email sending statistics
     */
    suspend fun getEmailStatistics(since: LocalDateTime? = null): Map<String, Any> = withContext(Dispatchers.IO) {
        emailNotificationLogRepository?.let { repo ->
            val stats = mutableMapOf<String, Any>()

            val totalSent = since?.let {
                repo.findSentBetween(it, LocalDateTime.now()).size.toLong()
            } ?: repo.countByStatus(EmailStatus.SENT)

            val totalFailed = since?.let {
                repo.findCreatedBetween(it, LocalDateTime.now()).count { it.status == EmailStatus.FAILED }.toLong()
            } ?: repo.countByStatus(EmailStatus.FAILED)

            val totalPending = since?.let {
                repo.findCreatedBetween(it, LocalDateTime.now()).count { it.status == EmailStatus.PENDING }.toLong()
            } ?: repo.countByStatus(EmailStatus.PENDING)

            stats["totalSent"] = totalSent
            stats["totalFailed"] = totalFailed
            stats["totalPending"] = totalPending
            stats["successRate"] = if (totalSent + totalFailed > 0) {
                (totalSent.toDouble() / (totalSent + totalFailed)) * 100
            } else 0.0

            return@withContext stats
        } ?: emptyMap()
    }

    /**
     * Retry failed notifications
     */
    fun retryFailedNotifications(beforeDate: LocalDateTime): CompletableFuture<Int> {
        return CompletableFuture.supplyAsync {
            runBlocking {
                emailNotificationLogRepository?.let { repo ->
                    val failedLogs = repo.findRetriableNotifications(5).filter {
                        it.updatedAt?.isBefore(beforeDate) ?: true
                    }
                    var successCount = 0

                    failedLogs.forEach { notificationLog ->
                        try {
                            val config = emailConfigRepository.findById(notificationLog.emailConfigId).orElse(null)
                            if (config != null) {
                                // Get original email content (would need to be stored or regenerated)
                                val success = runBlocking { sendEmailWithRetry(
                                    config = config,
                                    to = notificationLog.recipientEmail,
                                    subject = notificationLog.subject,
                                    textContent = "Retry notification for risk assessment ${notificationLog.riskAssessmentId}",
                                    htmlContent = "<p>Retry notification for risk assessment ${notificationLog.riskAssessmentId}</p>"
                                ) }

                                if (success) {
                                    val updatedLog = notificationLog.markAsSent("retry-${System.currentTimeMillis()}")
                                    repo.update(updatedLog)
                                    successCount++
                                }
                            }
                        } catch (e: Exception) {
                            log.error("Failed to retry notification for log ID: ${notificationLog.id}", e)
                        }
                    }

                    log.info("Retried {} failed notifications, {} succeeded", failedLogs.size, successCount)
                    successCount
                } ?: 0
            }
        }
    }

    /**
     * Create notification log entry
     */
    private suspend fun createNotificationLogEntry(
        riskAssessmentId: Long,
        emailConfigId: Long,
        recipientEmail: String,
        subject: String
    ): EmailNotificationLog? = withContext(Dispatchers.IO) {
        emailNotificationLogRepository?.let { repo ->
            val logEntry = EmailNotificationLog.create(
                riskAssessmentId = riskAssessmentId,
                emailConfigId = emailConfigId,
                recipientEmail = recipientEmail,
                subject = subject
            )
            repo.save(logEntry)
        }
    }

    /**
     * Update notification log entry with result
     */
    private suspend fun updateNotificationLogEntry(
        logEntry: EmailNotificationLog,
        success: Boolean
    ) = withContext(Dispatchers.IO) {
        emailNotificationLogRepository?.let { repo ->
            val updatedEntry = if (success) {
                logEntry.markAsSent("email-${System.currentTimeMillis()}")
            } else {
                logEntry.markAsFailed("Email delivery failed after retries")
            }
            repo.update(updatedEntry)
        }
    }

    /**
     * Enhanced email sending with config that includes timeout and detailed logging
     */
    private suspend fun sendEmailWithConfigEnhanced(
        config: EmailConfig,
        to: String,
        subject: String,
        textContent: String,
        htmlContent: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            log.debug("Sending email to {} using SMTP {}:{}", to, config.smtpHost, config.smtpPort)

            val startTime = System.currentTimeMillis()
            val properties = createMailProperties(config)
            val session = createMailSession(properties, config)

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(config.fromEmail, config.fromName))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                setSubject(subject)

                // Create multipart message with both text and HTML
                val multipart = MimeMultipart("alternative")

                // Add text part
                val textPart = MimeBodyPart().apply {
                    setText(textContent, "UTF-8")
                }
                multipart.addBodyPart(textPart)

                // Add HTML part
                val htmlPart = MimeBodyPart().apply {
                    setContent(htmlContent, "text/html; charset=UTF-8")
                }
                multipart.addBodyPart(htmlPart)

                setContent(multipart)
                sentDate = Date()
            }

            Transport.send(message)

            val duration = System.currentTimeMillis() - startTime
            log.info("Successfully sent email to {} with subject: {} ({}ms)", to, subject, duration)
            true

        } catch (e: Exception) {
            log.error("Failed to send email to {}: {}", to, e.message, e)
            false
        }
    }

    /**
     * Convert HTML content to plain text
     */
    private fun convertHtmlToText(htmlContent: String): String {
        return try {
            Jsoup.parse(htmlContent).text()
        } catch (e: Exception) {
            log.warn("Failed to convert HTML to text: {}", e.message)
            // Fallback: strip HTML tags with regex
            htmlContent.replace(Regex("<[^>]*>"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    /**
     * Data class for bulk notification requests
     */
    data class NotificationEmailRequest(
        val riskAssessmentId: Long,
        val to: String,
        val subject: String,
        val textContent: String,
        val htmlContent: String,
        val emailConfigId: Long? = null
    )
}