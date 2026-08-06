package com.secman.listener

import com.secman.domain.EmailNotificationLog
import com.secman.domain.enums.EmailStatus
import com.secman.event.RiskAssessmentCreatedEvent
import com.secman.repository.EmailConfigRepository
import com.secman.repository.EmailNotificationLogRepository
import com.secman.repository.RiskAssessmentNotificationConfigRepository
import com.secman.service.EmailService
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import jakarta.inject.Singleton
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

/**
 * Event listener that handles automatic email notifications for risk assessments
 */
@Singleton
@ExecuteOn(TaskExecutors.IO)
class EmailNotificationEventListener(
    private val notificationConfigRepository: RiskAssessmentNotificationConfigRepository,
    private val emailConfigRepository: EmailConfigRepository,
    private val emailNotificationLogRepository: EmailNotificationLogRepository,
    private val emailService: EmailService
) : ApplicationEventListener<RiskAssessmentCreatedEvent> {

    private val logger = LoggerFactory.getLogger(EmailNotificationEventListener::class.java)
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Handle risk assessment created events
     */
    override fun onApplicationEvent(event: RiskAssessmentCreatedEvent) {
        logger.info("Processing risk assessment created event: ${event.getSummary()}")

        try {
            // Validate event
            val validationErrors = event.validate()
            if (validationErrors.isNotEmpty()) {
                logger.error("Invalid risk assessment event: ${validationErrors.joinToString()}")
                return
            }

            // Process notifications asynchronously
            coroutineScope.launch {
                processNotifications(event)
            }

        } catch (e: Exception) {
            logger.error("Failed to process risk assessment created event: ${event.riskAssessmentId}", e)
        }
    }

    /**
     * Process notifications for the risk assessment
     */
    private suspend fun processNotifications(event: RiskAssessmentCreatedEvent) {
        try {
            // Get active email configuration
            val activeEmailConfig = emailConfigRepository.findActiveConfig().orElse(null)
            if (activeEmailConfig == null) {
                logger.warn("No active email configuration found. Skipping notifications for risk assessment: ${event.riskAssessmentId}")
                return
            }

            // Find matching notification configurations
            val matchingConfigs = findMatchingNotificationConfigs(event)
            if (matchingConfigs.isEmpty()) {
                logger.debug("No notification configurations match risk assessment: ${event.riskAssessmentId}")
                return
            }

            logger.info("Found ${matchingConfigs.size} matching notification configurations for risk assessment: ${event.riskAssessmentId}")

            // Process each matching configuration
            matchingConfigs.forEach { config ->
                processNotificationConfig(event, config, activeEmailConfig)
            }

        } catch (e: Exception) {
            logger.error("Failed to process notifications for risk assessment: ${event.riskAssessmentId}", e)
        }
    }

    /**
     * Find notification configurations that match the event
     */
    private suspend fun findMatchingNotificationConfigs(event: RiskAssessmentCreatedEvent) = withContext(Dispatchers.IO) {
        val allActiveConfigs = notificationConfigRepository.findActiveConfigurations()
        val eventData = event.getRiskAssessmentData()

        allActiveConfigs.filter { config ->
            // Check if configuration matches the risk assessment
            val matches = config.matchesRiskAssessment(eventData)

            // Check if should notify for this risk level
            val shouldNotify = config.shouldNotifyForRiskLevel(event.riskLevel)

            // For immediate notifications, always process if matches
            val timingMatch = if (config.notificationTiming == "immediate") {
                true
            } else {
                // For scheduled notifications, check timing
                config.isTimeToNotify(null) // For new assessments, always consider it time
            }

            val result = matches && shouldNotify && timingMatch

            logger.debug("Config '${config.name}' match result: matches=$matches, shouldNotify=$shouldNotify, timingMatch=$timingMatch, result=$result")

            result
        }
    }

    /**
     * Process a single notification configuration
     */
    private suspend fun processNotificationConfig(
        event: RiskAssessmentCreatedEvent,
        config: com.secman.domain.RiskAssessmentNotificationConfig,
        emailConfig: com.secman.domain.EmailConfig
    ) {
        try {
            val recipients = config.getRecipientEmailsList()
            logger.info("Sending notifications to ${recipients.size} recipients for config '${config.name}'")

            // Create notification logs for each recipient
            val notificationLogs = recipients.map { recipientEmail ->
                val subject = generateEmailSubject(event, config)
                EmailNotificationLog.create(
                    riskAssessmentId = event.riskAssessmentId,
                    emailConfigId = emailConfig.id!!,
                    recipientEmail = recipientEmail,
                    subject = subject
                )
            }

            // Save all notification logs
            val savedLogs = withContext(Dispatchers.IO) {
                emailNotificationLogRepository.saveAll(notificationLogs)
            }

            // Send emails asynchronously for each recipient
            savedLogs.forEach { log ->
                sendNotificationEmail(event, config, log, emailConfig)
            }

        } catch (e: Exception) {
            logger.error("Failed to process notification config '${config.name}' for risk assessment: ${event.riskAssessmentId}", e)
        }
    }

    /**
     * Send notification email for a single recipient
     */
    private fun sendNotificationEmail(
        event: RiskAssessmentCreatedEvent,
        config: com.secman.domain.RiskAssessmentNotificationConfig,
        log: EmailNotificationLog,
        emailConfig: com.secman.domain.EmailConfig
    ) {
        CompletableFuture.supplyAsync {
            try {
                val subject = log.subject
                val textContent = generateTextContent(event, config)
                val htmlContent = generateHtmlContent(event, config)

                logger.debug("Sending email to ${log.recipientEmail} for risk assessment ${event.riskAssessmentId}")

                // Send email using EmailService
                val success = emailService.sendEmail(
                    to = log.recipientEmail,
                    subject = subject,
                    textContent = textContent,
                    htmlContent = htmlContent
                ).get() // Block to get result

                // Update notification log based on result
                runBlocking {
                    if (success) {
                        val updatedLog = log.markAsSent("email-${System.currentTimeMillis()}")
                        emailNotificationLogRepository.update(updatedLog)
                        logger.info("Successfully sent notification to ${log.recipientEmail} for risk assessment ${event.riskAssessmentId}")
                    } else {
                        val updatedLog = log.markAsFailed("Email delivery failed")
                        emailNotificationLogRepository.update(updatedLog)
                        logger.warn("Failed to send notification to ${log.recipientEmail} for risk assessment ${event.riskAssessmentId}")
                    }
                }

            } catch (e: Exception) {
                logger.error("Exception while sending email to ${log.recipientEmail}", e)

                // Update log with error
                runBlocking {
                    withContext(Dispatchers.IO) {
                        val updatedLog = log.markAsFailed("Exception: ${e.message}")
                        emailNotificationLogRepository.update(updatedLog)
                    }
                }
            }
        }.exceptionally { throwable ->
            logger.error("Failed to send notification email to ${log.recipientEmail}", throwable)
            null
        }
    }

    /**
     * Generate email subject
     */
    private fun generateEmailSubject(
        event: RiskAssessmentCreatedEvent,
        config: com.secman.domain.RiskAssessmentNotificationConfig
    ): String {
        val urgencyPrefix = when (event.riskLevel.uppercase()) {
            "CRITICAL" -> "[CRITICAL] "
            "HIGH" -> "[HIGH PRIORITY] "
            else -> ""
        }

        return "${urgencyPrefix}New Risk Assessment: ${event.title}"
    }

    /**
     * Generate text content for email
     */
    private fun generateTextContent(
        event: RiskAssessmentCreatedEvent,
        config: com.secman.domain.RiskAssessmentNotificationConfig
    ): String {
        return """
            A new risk assessment has been created that requires your attention.

            Risk Assessment Details:
            - Title: ${event.title}
            - Risk Level: ${event.riskLevel.uppercase()}
            - Created By: ${event.createdBy}
            - Created At: ${event.createdAt}
            ${event.description?.let { "- Description: $it" } ?: ""}
            ${event.category?.let { "- Category: $it" } ?: ""}
            ${event.impact?.let { "- Impact: $it" } ?: ""}
            ${event.probability?.let { "- Probability: $it" } ?: ""}

            Please review this assessment and take appropriate action.

            This notification was sent to the '${config.name}' group.

            ---
            SecMan Security Management System
        """.trimIndent()
    }

    /**
     * Generate HTML content for email
     */
    private fun generateHtmlContent(
        event: RiskAssessmentCreatedEvent,
        config: com.secman.domain.RiskAssessmentNotificationConfig
    ): String {
        val riskLevelColor = when (event.riskLevel.uppercase()) {
            "CRITICAL" -> "#dc3545"
            "HIGH" -> "#fd7e14"
            "MEDIUM" -> "#ffc107"
            "LOW" -> "#28a745"
            else -> "#6c757d"
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>New Risk Assessment Notification</title>
            </head>
            <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                <div style="background-color: #f8f9fa; padding: 20px; border-radius: 8px; margin-bottom: 20px;">
                    <h1 style="color: #2c3e50; margin-top: 0;">New Risk Assessment Created</h1>
                    <p style="margin-bottom: 0; font-size: 16px;">A new risk assessment requires your attention.</p>
                </div>

                <div style="background-color: #fff; padding: 20px; border: 1px solid #dee2e6; border-radius: 8px; margin-bottom: 20px;">
                    <h2 style="color: #495057; margin-top: 0;">Risk Assessment Details</h2>

                    <table style="width: 100%; border-collapse: collapse;">
                        <tr>
                            <td style="padding: 8px 0; border-bottom: 1px solid #dee2e6; font-weight: bold; width: 120px;">Title:</td>
                            <td style="padding: 8px 0; border-bottom: 1px solid #dee2e6;">${event.title}</td>
                        </tr>
                        <tr>
                            <td style="padding: 8px 0; border-bottom: 1px solid #dee2e6; font-weight: bold;">Risk Level:</td>
                            <td style="padding: 8px 0; border-bottom: 1px solid #dee2e6;">
                                <span style="background-color: $riskLevelColor; color: white; padding: 4px 8px; border-radius: 4px; font-weight: bold;">
                                    ${event.riskLevel.uppercase()}
                                </span>
                            </td>
                        </tr>
                        <tr>
                            <td style="padding: 8px 0; border-bottom: 1px solid #dee2e6; font-weight: bold;">Created By:</td>
                            <td style="padding: 8px 0; border-bottom: 1px solid #dee2e6;">${event.createdBy}</td>
                        </tr>
                        <tr>
                            <td style="padding: 8px 0; border-bottom: 1px solid #dee2e6; font-weight: bold;">Created At:</td>
                            <td style="padding: 8px 0; border-bottom: 1px solid #dee2e6;">${event.createdAt}</td>
                        </tr>
                        ${event.description?.let {
                            """<tr>
                                <td style="padding: 8px 0; border-bottom: 1px solid #dee2e6; font-weight: bold;">Description:</td>
                                <td style="padding: 8px 0; border-bottom: 1px solid #dee2e6;">$it</td>
                            </tr>"""
                        } ?: ""}
                        ${event.category?.let {
                            """<tr>
                                <td style="padding: 8px 0; border-bottom: 1px solid #dee2e6; font-weight: bold;">Category:</td>
                                <td style="padding: 8px 0; border-bottom: 1px solid #dee2e6;">$it</td>
                            </tr>"""
                        } ?: ""}
                        ${event.impact?.let {
                            """<tr>
                                <td style="padding: 8px 0; border-bottom: 1px solid #dee2e6; font-weight: bold;">Impact:</td>
                                <td style="padding: 8px 0; border-bottom: 1px solid #dee2e6;">$it</td>
                            </tr>"""
                        } ?: ""}
                        ${event.probability?.let {
                            """<tr>
                                <td style="padding: 8px 0; font-weight: bold;">Probability:</td>
                                <td style="padding: 8px 0;">$it</td>
                            </tr>"""
                        } ?: ""}
                    </table>
                </div>

                <div style="background-color: #e9ecef; padding: 15px; border-radius: 8px; margin-bottom: 20px;">
                    <p style="margin: 0; font-weight: bold; color: #495057;">Action Required</p>
                    <p style="margin: 5px 0 0 0;">Please review this risk assessment and take appropriate action based on your organization's risk management procedures.</p>
                </div>

                <hr style="border: none; border-top: 1px solid #dee2e6; margin: 30px 0;">

                <div style="text-align: center; color: #6c757d; font-size: 14px;">
                    <p>This notification was sent to the <strong>${config.name}</strong> group.</p>
                    <p>SecMan Security Management System</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Cleanup resources when the listener is destroyed
     */
    fun destroy() {
        coroutineScope.cancel("EmailNotificationEventListener destroyed")
    }
}