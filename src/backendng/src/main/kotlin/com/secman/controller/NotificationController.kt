package com.secman.controller

import com.secman.domain.RiskAssessmentNotificationConfig
import com.secman.service.EmailService
import com.secman.service.NotificationConfigService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * Controller for notification configuration and email management
 */
@Controller("/api/notifications")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.IO)
open class NotificationController(
    private val notificationConfigService: NotificationConfigService,
    private val emailService: EmailService
) {

    private val logger = LoggerFactory.getLogger(NotificationController::class.java)

    /**
     * Get all notification configurations
     * GET /api/notifications/configs
     */
    @Get("/configs")
    open fun getNotificationConfigs(): HttpResponse<List<RiskAssessmentNotificationConfig>> {
        return runBlocking {
            try {
                val configs = notificationConfigService.getAllConfigurations()
                HttpResponse.ok(configs)
            } catch (e: Exception) {
                logger.error("Failed to retrieve notification configurations", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Get notification configuration by ID
     * GET /api/notifications/configs/{id}
     */
    @Get("/configs/{id}")
    open fun getNotificationConfig(@PathVariable id: Long): HttpResponse<RiskAssessmentNotificationConfig> {
        return runBlocking {
            try {
                val config = notificationConfigService.getConfigurationById(id)
                    ?: return@runBlocking HttpResponse.notFound<RiskAssessmentNotificationConfig>()

                HttpResponse.ok(config)
            } catch (e: Exception) {
                logger.error("Failed to retrieve notification configuration: $id", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Create new notification configuration
     * POST /api/notifications/configs
     */
    @Post("/configs")
    open fun createNotificationConfig(@Body @Valid request: CreateNotificationConfigRequest): HttpResponse<RiskAssessmentNotificationConfig> {
        return runBlocking {
            try {
                val config = notificationConfigService.createNotificationConfig(
                    name = request.name,
                    description = request.description,
                    recipientEmails = request.recipientEmails,
                    notificationTiming = request.notificationTiming ?: "immediate",
                    notificationFrequency = request.notificationFrequency ?: "all",
                    conditions = request.conditions,
                    isActive = request.isActive ?: true
                )

                logger.info("Created notification configuration: ${config.name}")
                HttpResponse.created(config)

            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid notification configuration request: ${e.message}")
                HttpResponse.badRequest<RiskAssessmentNotificationConfig>()
            } catch (e: Exception) {
                logger.error("Failed to create notification configuration", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Update notification configuration
     * PUT /api/notifications/configs/{id}
     */
    @Put("/configs/{id}")
    open fun updateNotificationConfig(
        @PathVariable id: Long,
        @Body @Valid request: UpdateNotificationConfigRequest
    ): HttpResponse<RiskAssessmentNotificationConfig> {
        return runBlocking {
            try {
                val config = notificationConfigService.updateConfiguration(
                    id = id,
                    name = request.name,
                    description = request.description,
                    recipientEmails = request.recipientEmails,
                    notificationTiming = request.notificationTiming,
                    notificationFrequency = request.notificationFrequency,
                    conditions = request.conditions,
                    isActive = request.isActive
                ) ?: return@runBlocking HttpResponse.notFound<RiskAssessmentNotificationConfig>()

                logger.info("Updated notification configuration: $id")
                HttpResponse.ok(config)

            } catch (e: IllegalArgumentException) {
                logger.warn("Invalid notification configuration update: ${e.message}")
                HttpResponse.badRequest<RiskAssessmentNotificationConfig>()
            } catch (e: Exception) {
                logger.error("Failed to update notification configuration: $id", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Delete notification configuration
     * DELETE /api/notifications/configs/{id}
     */
    @Delete("/configs/{id}")
    open fun deleteNotificationConfig(@PathVariable id: Long): HttpResponse<Map<String, Any>> {
        return runBlocking {
            try {
                val deleted = notificationConfigService.deleteConfiguration(id)
                if (deleted) {
                    logger.info("Deleted notification configuration: $id")
                    HttpResponse.ok(mapOf("message" to "Configuration deleted successfully"))
                } else {
                    HttpResponse.notFound<Map<String, Any>>()
                }
            } catch (e: Exception) {
                logger.error("Failed to delete notification configuration: $id", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Get active notification configurations
     * GET /api/notifications/configs?active=true
     */
    @Get("/configs{?active}")
    open fun getNotificationConfigs(@QueryValue active: Boolean?): HttpResponse<List<RiskAssessmentNotificationConfig>> {
        return runBlocking {
            try {
                val configs = if (active == true) {
                    notificationConfigService.getActiveConfigurations()
                } else {
                    notificationConfigService.getAllConfigurations()
                }
                HttpResponse.ok(configs)
            } catch (e: Exception) {
                logger.error("Failed to retrieve notification configurations", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Activate notification configuration
     * POST /api/notifications/configs/{id}/activate
     */
    @Post("/configs/{id}/activate")
    open fun activateNotificationConfig(@PathVariable id: Long): HttpResponse<Map<String, Any>> {
        return runBlocking {
            try {
                val activated = notificationConfigService.activateConfiguration(id)
                if (activated) {
                    logger.info("Activated notification configuration: $id")
                    HttpResponse.ok(mapOf("message" to "Configuration activated"))
                } else {
                    HttpResponse.notFound<Map<String, Any>>()
                }
            } catch (e: Exception) {
                logger.error("Failed to activate notification configuration: $id", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Deactivate notification configuration
     * POST /api/notifications/configs/{id}/deactivate
     */
    @Post("/configs/{id}/deactivate")
    open fun deactivateNotificationConfig(@PathVariable id: Long): HttpResponse<Map<String, Any>> {
        return runBlocking {
            try {
                val deactivated = notificationConfigService.deactivateConfiguration(id)
                if (deactivated) {
                    logger.info("Deactivated notification configuration: $id")
                    HttpResponse.ok(mapOf("message" to "Configuration deactivated"))
                } else {
                    HttpResponse.notFound<Map<String, Any>>()
                }
            } catch (e: Exception) {
                logger.error("Failed to deactivate notification configuration: $id", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Test notification configuration
     * POST /api/notifications/configs/{id}/test
     */
    @Post("/configs/{id}/test")
    open fun testNotificationConfig(
        @PathVariable id: Long,
        @Body @Valid request: TestNotificationRequest
    ): HttpResponse<Map<String, Any>> {
        return runBlocking {
            try {
                val matches = notificationConfigService.testConfiguration(id, request.riskAssessmentData)
                HttpResponse.ok(mapOf(
                    "configId" to id,
                    "matches" to matches,
                    "riskAssessmentData" to request.riskAssessmentData
                ))
            } catch (e: Exception) {
                logger.error("Failed to test notification configuration: $id", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Send manual notification
     * POST /api/notifications/send
     */
    @Post("/send")
    open fun sendManualNotification(@Body @Valid request: ManualNotificationRequest): HttpResponse<Map<String, Any>> {
        return runBlocking {
            try {
                logger.info("Sending manual notification to: ${request.recipientEmails}")

                val recipients = request.recipientEmails.split(",").map { it.trim() }
                val results = mutableMapOf<String, Boolean>()

                recipients.forEach { recipient ->
                    val future = emailService.sendNotificationEmail(
                        riskAssessmentId = request.riskAssessmentId ?: 0L,
                        to = recipient,
                        subject = request.subject,
                        textContent = request.textContent ?: request.htmlContent,
                        htmlContent = request.htmlContent,
                        emailConfigId = request.emailConfigId
                    )

                    results[recipient] = future.get()
                }

                val successCount = results.values.count { it }
                logger.info("Manual notification sent: {} successful out of {} recipients", successCount, recipients.size)

                HttpResponse.ok(mapOf(
                    "message" to "Notification sent",
                    "recipients" to recipients.size,
                    "successful" to successCount,
                    "results" to results
                ))

            } catch (e: Exception) {
                logger.error("Failed to send manual notification", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Get notification logs
     * GET /api/notifications/logs
     */
    @Get("/logs")
    open fun getNotificationLogs(
        @QueryValue since: String?,
        @QueryValue status: String?,
        @QueryValue limit: Int?
    ): HttpResponse<Map<String, Any>> {
        return try {
            val sinceDate = since?.let { LocalDateTime.parse(it) }
            val stats = runBlocking { emailService.getEmailStatistics(sinceDate) }

            val responseMap = mutableMapOf<String, Any>()
            responseMap["statistics"] = stats
            since?.let { responseMap["since"] = it }
            status?.let { responseMap["status"] = it }
            limit?.let { responseMap["limit"] = it }
            HttpResponse.ok(responseMap)

        } catch (e: Exception) {
            logger.error("Failed to retrieve notification logs", e)
            HttpResponse.serverError<Map<String, Any>>()
        }
    }

    /**
     * Get notification statistics
     * GET /api/notifications/stats
     */
    @Get("/stats")
    open fun getNotificationStatistics(@QueryValue since: String?): HttpResponse<Map<String, Any>> {
        return try {
            val sinceDate = since?.let { LocalDateTime.parse(it) }
            val emailStats = runBlocking { emailService.getEmailStatistics(sinceDate) }
            val configStats = runBlocking { notificationConfigService.getConfigurationStatistics() }

            val responseMap = mutableMapOf<String, Any>()
            responseMap["emailStats"] = emailStats
            responseMap["configStats"] = configStats
            since?.let { responseMap["since"] = it }
            HttpResponse.ok(responseMap)

        } catch (e: Exception) {
            logger.error("Failed to retrieve notification statistics", e)
            HttpResponse.serverError<Map<String, Any>>()
        }
    }

    /**
     * Retry failed notifications
     * POST /api/notifications/retry
     */
    @Post("/retry")
    open fun retryFailedNotifications(@Body request: RetryNotificationsRequest): HttpResponse<Map<String, Any>> {
        return try {
            val beforeDate = request.beforeDate?.let { LocalDateTime.parse(it) } ?: LocalDateTime.now().minusHours(24)

            val future = emailService.retryFailedNotifications(beforeDate)
            val retryCount = future.get()

            logger.info("Retried {} failed notifications", retryCount)

            HttpResponse.ok(mapOf(
                "message" to "Retry completed",
                "retriedCount" to retryCount,
                "beforeDate" to beforeDate
            ))

        } catch (e: Exception) {
            logger.error("Failed to retry notifications", e)
            HttpResponse.serverError()
        }
    }

    /**
     * Bulk operations on notification configurations
     * POST /api/notifications/configs/bulk
     */
    @Post("/configs/bulk")
    open fun bulkOperationConfigs(@Body @Valid request: BulkConfigOperationRequest): HttpResponse<Map<String, Any>> {
        return runBlocking {
            try {
                val result = when (request.operation) {
                    "activate" -> {
                        notificationConfigService.bulkActivateConfigurations(request.configIds)
                    }
                    "deactivate" -> {
                        notificationConfigService.bulkDeactivateConfigurations(request.configIds)
                    }
                    else -> {
                        return@runBlocking HttpResponse.badRequest<Map<String, Any>>()
                    }
                }

                logger.info("Bulk {} operation completed for {} configurations", request.operation, result)

                HttpResponse.ok(mapOf(
                    "message" to "Bulk operation completed",
                    "operation" to request.operation,
                    "affectedCount" to result
                ))

            } catch (e: Exception) {
                logger.error("Failed to perform bulk operation: ${request.operation}", e)
                HttpResponse.serverError()
            }
        }
    }

    /**
     * Search notification configurations
     * GET /api/notifications/configs/search
     */
    @Get("/configs/search")
    open fun searchNotificationConfigs(@QueryValue q: String): HttpResponse<List<RiskAssessmentNotificationConfig>> {
        return runBlocking {
            try {
                val configs = notificationConfigService.searchConfigurationsByName(q)
                HttpResponse.ok(configs)
            } catch (e: Exception) {
                logger.error("Failed to search notification configurations", e)
                HttpResponse.serverError()
            }
        }
    }

    // Request/Response DTOs

    data class CreateNotificationConfigRequest(
        @field:NotBlank val name: String,
        val description: String? = null,
        @field:NotBlank val recipientEmails: String,
        val notificationTiming: String? = null,
        val notificationFrequency: String? = null,
        val conditions: String? = null,
        val isActive: Boolean? = null
    )

    data class UpdateNotificationConfigRequest(
        val name: String? = null,
        val description: String? = null,
        val recipientEmails: String? = null,
        val notificationTiming: String? = null,
        val notificationFrequency: String? = null,
        val conditions: String? = null,
        val isActive: Boolean? = null
    )

    data class TestNotificationRequest(
        @field:NotNull val riskAssessmentData: Map<String, Any>
    )

    data class ManualNotificationRequest(
        @field:NotBlank val recipientEmails: String,
        @field:NotBlank val subject: String,
        @field:NotBlank val htmlContent: String,
        val textContent: String? = null,
        val riskAssessmentId: Long? = null,
        val emailConfigId: Long? = null
    )

    data class RetryNotificationsRequest(
        val beforeDate: String? = null
    )

    data class BulkConfigOperationRequest(
        @field:NotBlank val operation: String,
        @field:NotNull val configIds: List<Long>
    )
}