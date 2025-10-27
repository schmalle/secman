package com.secman.controller

import com.secman.domain.NotificationLog
import com.secman.domain.NotificationType
import com.secman.service.NotificationLogService
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.io.StringWriter
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Controller for ADMIN users to view notification audit logs
 * Feature 035: Outdated Asset Notification System
 */
@Singleton
@Controller("/api/notification-logs")
@Secured("ADMIN")
class NotificationLogController(
    private val notificationLogService: NotificationLogService
) {
    private val logger = LoggerFactory.getLogger(NotificationLogController::class.java)

    /**
     * List notification logs with pagination and filtering
     */
    @Get
    fun listNotificationLogs(
        @QueryValue(defaultValue = "0") page: Int,
        @QueryValue(defaultValue = "20") size: Int,
        @QueryValue notificationType: NotificationType?,
        @QueryValue status: String?,
        @QueryValue ownerEmail: String?,
        @QueryValue startDate: Instant?,
        @QueryValue endDate: Instant?,
        @QueryValue(defaultValue = "sentAt,desc") sort: String
    ): Page<NotificationLogResponse> {

        val pageable = Pageable.from(page, size)

        val logsPage = when {
            startDate != null && endDate != null -> {
                notificationLogService.getLogsByDateRange(startDate, endDate, pageable)
            }
            notificationType != null -> {
                notificationLogService.getLogsByType(notificationType, pageable)
            }
            status != null -> {
                notificationLogService.getLogsByStatus(status, pageable)
            }
            ownerEmail != null -> {
                notificationLogService.getLogsByOwner(ownerEmail, pageable)
            }
            else -> {
                notificationLogService.getAllLogs(pageable)
            }
        }

        return logsPage.map { NotificationLogResponse.from(it) }
    }

    /**
     * Export notification logs to CSV
     * Limited to 10,000 records to prevent resource exhaustion
     */
    @Get("/export")
    @Produces(MediaType.TEXT_CSV)
    fun exportNotificationLogs(
        @QueryValue notificationType: NotificationType?,
        @QueryValue status: String?,
        @QueryValue ownerEmail: String?,
        @QueryValue startDate: Instant?,
        @QueryValue endDate: Instant?
    ): HttpResponse<String> {

        // Get matching logs with hard limit of 10,000 records to prevent DoS
        val pageable = Pageable.from(0, 10000)
        val logsPage = when {
            startDate != null && endDate != null -> {
                notificationLogService.getLogsByDateRange(startDate, endDate, pageable)
            }
            notificationType != null -> {
                notificationLogService.getLogsByType(notificationType, pageable)
            }
            status != null -> {
                notificationLogService.getLogsByStatus(status, pageable)
            }
            ownerEmail != null -> {
                notificationLogService.getLogsByOwner(ownerEmail, pageable)
            }
            else -> {
                notificationLogService.getAllLogs(pageable)
            }
        }

        // Generate CSV
        val csv = generateCsv(logsPage.content)

        val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val filename = "notification-logs-$timestamp.csv"

        return HttpResponse.ok(csv)
            .header("Content-Disposition", "attachment; filename=\"$filename\"")
    }

    private fun generateCsv(logs: List<NotificationLog>): String {
        val writer = StringWriter()

        // CSV header
        writer.append("ID,Asset ID,Asset Name,Owner Email,Notification Type,Sent At,Status,Error Message\n")

        // CSV rows
        logs.forEach { log ->
            writer.append("${log.id},")
            writer.append("${log.assetId ?: ""},")
            writer.append("\"${sanitizeCsvField(log.assetName)}\",")
            writer.append("\"${sanitizeCsvField(log.ownerEmail)}\",")
            writer.append("${log.notificationType},")
            writer.append("${log.sentAt},")
            writer.append("${log.status},")
            writer.append("\"${sanitizeCsvField(log.errorMessage ?: "")}\"\n")
        }

        return writer.toString()
    }

    /**
     * Sanitize CSV field to prevent formula injection attacks (CWE-1236)
     * Prefixes formula-triggering characters with single quote to prevent execution in Excel/LibreOffice
     */
    private fun sanitizeCsvField(value: String): String {
        if (value.isEmpty()) return ""

        val trimmed = value.trim()

        // Escape double quotes first
        val quotesEscaped = trimmed.replace("\"", "\"\"")

        // Prefix formula-triggering characters with single quote to prevent CSV injection
        return if (trimmed.startsWith("=") ||
                   trimmed.startsWith("+") ||
                   trimmed.startsWith("-") ||
                   trimmed.startsWith("@") ||
                   trimmed.startsWith("|") ||
                   trimmed.startsWith("%") ||
                   trimmed.startsWith("\t") ||
                   trimmed.startsWith("\r")) {
            "'" + quotesEscaped
        } else {
            quotesEscaped
        }
    }

    data class NotificationLogResponse(
        val id: Long?,
        val assetId: Long?,
        val assetName: String,
        val ownerEmail: String,
        val notificationType: NotificationType,
        val sentAt: Instant,
        val status: String,
        val errorMessage: String?
    ) {
        companion object {
            fun from(log: NotificationLog) = NotificationLogResponse(
                id = log.id,
                assetId = log.assetId,
                assetName = log.assetName,
                ownerEmail = log.ownerEmail,
                notificationType = log.notificationType,
                sentAt = log.sentAt,
                status = log.status,
                errorMessage = log.errorMessage
            )
        }
    }
}
