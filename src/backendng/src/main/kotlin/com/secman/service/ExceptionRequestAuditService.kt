package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.*
import com.secman.repository.ExceptionRequestAuditLogRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

/**
 * Service for comprehensive audit logging of exception request lifecycle events.
 *
 * All audit operations are asynchronous (non-blocking) to prevent audit logging
 * from impacting the performance of the main exception request workflow.
 *
 * Audit logs are immutable - once created, they cannot be modified or deleted
 * (except for manual compliance cleanup after 7 years).
 *
 * Feature: 031-vuln-exception-approval (FR-026b - Audit logging)
 * Reference: research.md lines 169-203, quickstart.md lines 93-133
 */
@Singleton
class ExceptionRequestAuditService(
    @Inject private val auditLogRepository: ExceptionRequestAuditLogRepository,
    @Inject private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(ExceptionRequestAuditService::class.java)

    /**
     * Log request creation event.
     *
     * @param request The newly created exception request
     * @param actorUser User who created the request
     * @param clientIp Client IP address (optional)
     */
    fun logRequestCreated(
        request: VulnerabilityExceptionRequest,
        actorUser: User,
        clientIp: String? = null
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                val contextData = createContextData(
                    mapOf(
                        "scope" to request.scope.name,
                        "reasonSummary" to request.reason.take(200),
                        "expirationDate" to request.expirationDate.toString(),
                        "autoApproved" to request.autoApproved,
                        "vulnerabilityId" to (request.vulnerability?.id ?: "null")
                    )
                )

                val auditLog = ExceptionRequestAuditLog(
                    requestId = request.id!!,
                    eventType = AuditEventType.REQUEST_CREATED,
                    timestamp = LocalDateTime.now(),
                    oldState = null,
                    newState = request.status.name,
                    actorUsername = actorUser.username,
                    actorUser = actorUser,
                    contextData = contextData,
                    severity = AuditSeverity.INFO,
                    clientIp = clientIp
                )

                auditLogRepository.save(auditLog)

                logger.debug(
                    "Audit: Request created - requestId={}, actorUsername={}, status={}",
                    request.id, actorUser.username, request.status
                )

            } catch (e: Exception) {
                logger.error(
                    "Failed to log request creation: requestId={}, actorUsername={}",
                    request.id, actorUser.username, e
                )
            }
        }
    }

    /**
     * Log approval event.
     *
     * @param request The approved exception request
     * @param reviewer User who approved the request
     * @param clientIp Client IP address (optional)
     */
    fun logApproval(
        request: VulnerabilityExceptionRequest,
        reviewer: User,
        clientIp: String? = null
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                val contextData = createContextData(
                    mapOf(
                        "reviewerUsername" to reviewer.username,
                        "reviewComment" to (request.reviewComment ?: ""),
                        "autoApproved" to request.autoApproved,
                        "requesterUsername" to request.requestedByUsername
                    )
                )

                val auditLog = ExceptionRequestAuditLog(
                    requestId = request.id!!,
                    eventType = AuditEventType.APPROVED,
                    timestamp = LocalDateTime.now(),
                    oldState = ExceptionRequestStatus.PENDING.name,
                    newState = ExceptionRequestStatus.APPROVED.name,
                    actorUsername = reviewer.username,
                    actorUser = reviewer,
                    contextData = contextData,
                    severity = AuditSeverity.INFO,
                    clientIp = clientIp
                )

                auditLogRepository.save(auditLog)

                logger.info(
                    "Audit: Request approved - requestId={}, reviewerUsername={}, autoApproved={}",
                    request.id, reviewer.username, request.autoApproved
                )

            } catch (e: Exception) {
                logger.error(
                    "Failed to log approval: requestId={}, reviewerUsername={}",
                    request.id, reviewer.username, e
                )
            }
        }
    }

    /**
     * Log rejection event.
     *
     * @param request The rejected exception request
     * @param reviewer User who rejected the request
     * @param clientIp Client IP address (optional)
     */
    fun logRejection(
        request: VulnerabilityExceptionRequest,
        reviewer: User,
        clientIp: String? = null
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                val contextData = createContextData(
                    mapOf(
                        "reviewerUsername" to reviewer.username,
                        "reviewComment" to (request.reviewComment ?: "No comment"),
                        "requesterUsername" to request.requestedByUsername,
                        "reason" to request.reason.take(200)
                    )
                )

                val auditLog = ExceptionRequestAuditLog(
                    requestId = request.id!!,
                    eventType = AuditEventType.REJECTED,
                    timestamp = LocalDateTime.now(),
                    oldState = ExceptionRequestStatus.PENDING.name,
                    newState = ExceptionRequestStatus.REJECTED.name,
                    actorUsername = reviewer.username,
                    actorUser = reviewer,
                    contextData = contextData,
                    severity = AuditSeverity.WARN,
                    clientIp = clientIp
                )

                auditLogRepository.save(auditLog)

                logger.warn(
                    "Audit: Request rejected - requestId={}, reviewerUsername={}, comment={}",
                    request.id, reviewer.username, request.reviewComment
                )

            } catch (e: Exception) {
                logger.error(
                    "Failed to log rejection: requestId={}, reviewerUsername={}",
                    request.id, reviewer.username, e
                )
            }
        }
    }

    /**
     * Log cancellation event.
     *
     * @param request The cancelled exception request
     * @param actorUser User who cancelled the request (typically the requester)
     * @param clientIp Client IP address (optional)
     */
    fun logCancellation(
        request: VulnerabilityExceptionRequest,
        actorUser: User,
        clientIp: String? = null
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                val contextData = createContextData(
                    mapOf(
                        "cancelledBy" to actorUser.username,
                        "wasAutoApproved" to request.autoApproved,
                        "originalStatus" to ExceptionRequestStatus.PENDING.name
                    )
                )

                val auditLog = ExceptionRequestAuditLog(
                    requestId = request.id!!,
                    eventType = AuditEventType.CANCELLED,
                    timestamp = LocalDateTime.now(),
                    oldState = ExceptionRequestStatus.PENDING.name,
                    newState = ExceptionRequestStatus.CANCELLED.name,
                    actorUsername = actorUser.username,
                    actorUser = actorUser,
                    contextData = contextData,
                    severity = AuditSeverity.INFO,
                    clientIp = clientIp
                )

                auditLogRepository.save(auditLog)

                logger.info(
                    "Audit: Request cancelled - requestId={}, actorUsername={}",
                    request.id, actorUser.username
                )

            } catch (e: Exception) {
                logger.error(
                    "Failed to log cancellation: requestId={}, actorUsername={}",
                    request.id, actorUser.username, e
                )
            }
        }
    }

    /**
     * Log expiration event (automated by scheduled job).
     *
     * @param request The expired exception request
     * @param systemUser System actor performing expiration
     */
    fun logExpiration(
        request: VulnerabilityExceptionRequest,
        systemUser: String = "SYSTEM"
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                val contextData = createContextData(
                    mapOf(
                        "originalRequester" to request.requestedByUsername,
                        "expirationDate" to request.expirationDate.toString(),
                        "reviewedBy" to (request.reviewedByUsername ?: "N/A"),
                        "autoApproved" to request.autoApproved
                    )
                )

                val auditLog = ExceptionRequestAuditLog(
                    requestId = request.id!!,
                    eventType = AuditEventType.EXPIRED,
                    timestamp = LocalDateTime.now(),
                    oldState = ExceptionRequestStatus.APPROVED.name,
                    newState = ExceptionRequestStatus.EXPIRED.name,
                    actorUsername = systemUser,
                    actorUser = null, // System action, no user
                    contextData = contextData,
                    severity = AuditSeverity.INFO,
                    clientIp = null
                )

                auditLogRepository.save(auditLog)

                logger.info(
                    "Audit: Request expired - requestId={}, expirationDate={}",
                    request.id, request.expirationDate
                )

            } catch (e: Exception) {
                logger.error(
                    "Failed to log expiration: requestId={}",
                    request.id, e
                )
            }
        }
    }

    /**
     * Log generic status change event.
     *
     * @param request The exception request with changed status
     * @param oldStatus Previous status
     * @param newStatus New status
     * @param actorUser User who performed the status change
     * @param clientIp Client IP address (optional)
     */
    fun logStatusChange(
        request: VulnerabilityExceptionRequest,
        oldStatus: ExceptionRequestStatus,
        newStatus: ExceptionRequestStatus,
        actorUser: User,
        clientIp: String? = null
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                val contextData = createContextData(
                    mapOf(
                        "oldStatus" to oldStatus.name,
                        "newStatus" to newStatus.name,
                        "actorUsername" to actorUser.username
                    )
                )

                val auditLog = ExceptionRequestAuditLog(
                    requestId = request.id!!,
                    eventType = AuditEventType.STATUS_CHANGED,
                    timestamp = LocalDateTime.now(),
                    oldState = oldStatus.name,
                    newState = newStatus.name,
                    actorUsername = actorUser.username,
                    actorUser = actorUser,
                    contextData = contextData,
                    severity = AuditSeverity.INFO,
                    clientIp = clientIp
                )

                auditLogRepository.save(auditLog)

                logger.debug(
                    "Audit: Status changed - requestId={}, oldStatus={}, newStatus={}, actorUsername={}",
                    request.id, oldStatus, newStatus, actorUser.username
                )

            } catch (e: Exception) {
                logger.error(
                    "Failed to log status change: requestId={}, oldStatus={}, newStatus={}",
                    request.id, oldStatus, newStatus, e
                )
            }
        }
    }

    /**
     * Get complete audit trail for a specific exception request.
     *
     * @param requestId ID of the exception request
     * @return List of audit log entries in chronological order
     */
    fun getAuditTrail(requestId: Long): List<ExceptionRequestAuditLog> {
        return try {
            auditLogRepository.findByRequestIdOrderByTimestampAsc(requestId)
        } catch (e: Exception) {
            logger.error("Failed to retrieve audit trail for requestId={}", requestId, e)
            emptyList()
        }
    }

    /**
     * Create JSON context data from map.
     *
     * @param data Map of context data key-value pairs
     * @return JSON string representation
     */
    private fun createContextData(data: Map<String, Any>): String {
        return try {
            objectMapper.writeValueAsString(data)
        } catch (e: Exception) {
            logger.error("Failed to serialize context data: {}", data, e)
            "{}"
        }
    }
}
