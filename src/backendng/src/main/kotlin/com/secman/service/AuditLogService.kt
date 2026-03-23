package com.secman.service

import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Service for logging audit events
 */
@Singleton
class AuditLogService {
    private val logger = LoggerFactory.getLogger(AuditLogService::class.java)

    fun logAction(
        authentication: Authentication,
        action: String,
        entityType: String,
        entityId: Long? = null,
        details: String? = null
    ) {
        val timestamp = Instant.now()
        val username = sanitizeLogInput(authentication.name)
        val roles = authentication.roles?.joinToString(",") ?: ""

        val logMessage = buildString {
            append("AUDIT: ")
            append("timestamp=$timestamp, ")
            append("user=$username, ")
            append("roles=[$roles], ")
            append("action=$action, ")
            append("entityType=$entityType")
            entityId?.let { append(", entityId=$it") }
            details?.let { append(", details=${sanitizeLogInput(it)}") }
        }

        logger.info(logMessage)
    }

    /**
     * Sanitize input for log messages to prevent log injection (CRLF injection).
     * Removes newline characters that could forge log entries.
     */
    private fun sanitizeLogInput(input: String): String {
        return input.replace(Regex("[\r\n\t]"), "_")
    }
}