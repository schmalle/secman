package com.secman.domain

import com.secman.domain.enums.EmailStatus
import io.micronaut.serde.annotation.Serdeable
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/**
 * Entity representing email notification delivery logs
 */
@Entity
@Table(name = "email_notification_logs")
@Serdeable
data class EmailNotificationLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, name = "risk_assessment_id")
    val riskAssessmentId: Long,

    @Column(nullable = false, name = "email_config_id")
    val emailConfigId: Long,

    @Column(nullable = false, length = 255, name = "recipient_email")
    val recipientEmail: String,

    @Column(nullable = false, length = 500)
    val subject: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    val status: EmailStatus = EmailStatus.PENDING,

    @Column(nullable = true, columnDefinition = "TEXT", name = "error_message")
    val errorMessage: String? = null,

    @Column(nullable = false)
    val attempts: Int = 0,

    @Column(nullable = true, name = "sent_at")
    val sentAt: LocalDateTime? = null,

    @Column(nullable = true, name = "next_retry_at")
    val nextRetryAt: LocalDateTime? = null,

    @Column(nullable = true, name = "message_id")
    val messageId: String? = null,

    @CreationTimestamp
    @Column(nullable = false, updatable = false, name = "created_at")
    val createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(nullable = false, name = "updated_at")
    val updatedAt: LocalDateTime? = null
) {
    companion object {
        /**
         * Create a new email notification log entry
         */
        fun create(
            riskAssessmentId: Long,
            emailConfigId: Long,
            recipientEmail: String,
            subject: String
        ): EmailNotificationLog {
            return EmailNotificationLog(
                riskAssessmentId = riskAssessmentId,
                emailConfigId = emailConfigId,
                recipientEmail = recipientEmail,
                subject = subject,
                status = EmailStatus.PENDING,
                attempts = 0
            )
        }

        /**
         * Calculate next retry time using exponential backoff
         */
        fun calculateNextRetry(attempts: Int, baseDelayMinutes: Int = 5): LocalDateTime {
            val delayMinutes = baseDelayMinutes * Math.pow(2.0, attempts.coerceAtMost(5).toDouble()).toInt()
            return LocalDateTime.now().plusMinutes(delayMinutes.toLong())
        }
    }

    /**
     * Mark as sent successfully
     */
    fun markAsSent(messageId: String): EmailNotificationLog {
        return copy(
            status = EmailStatus.SENT,
            messageId = messageId,
            sentAt = LocalDateTime.now(),
            nextRetryAt = null,
            errorMessage = null,
            updatedAt = LocalDateTime.now()
        )
    }

    /**
     * Mark as failed with error message
     */
    fun markAsFailed(error: String, maxRetries: Int = 3): EmailNotificationLog {
        val newAttempts = attempts + 1
        val newStatus = if (newAttempts >= maxRetries) {
            EmailStatus.PERMANENTLY_FAILED
        } else {
            EmailStatus.RETRYING
        }

        val nextRetry = if (newStatus == EmailStatus.RETRYING) {
            calculateNextRetry(newAttempts)
        } else {
            null
        }

        return copy(
            status = newStatus,
            errorMessage = error,
            attempts = newAttempts,
            nextRetryAt = nextRetry,
            updatedAt = LocalDateTime.now()
        )
    }

    /**
     * Mark as being retried
     */
    fun markAsRetrying(): EmailNotificationLog {
        return copy(
            status = EmailStatus.RETRYING,
            nextRetryAt = calculateNextRetry(attempts),
            updatedAt = LocalDateTime.now()
        )
    }

    /**
     * Check if this notification can be retried
     */
    fun canRetry(): Boolean = status.canRetry()

    /**
     * Check if this notification is in a final state
     */
    fun isFinal(): Boolean = status.isFinal()

    /**
     * Check if retry is due
     */
    fun isRetryDue(): Boolean {
        return when {
            !canRetry() -> false
            nextRetryAt == null -> true
            else -> LocalDateTime.now().isAfter(nextRetryAt)
        }
    }

    /**
     * Get time until next retry
     */
    fun getTimeUntilRetry(): Long? {
        return nextRetryAt?.let { retry ->
            val now = LocalDateTime.now()
            if (now.isBefore(retry)) {
                java.time.Duration.between(now, retry).toMinutes()
            } else {
                0L
            }
        }
    }

    /**
     * Get delivery summary for reporting
     */
    fun getDeliverySummary(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        result["id"] = id ?: 0
        result["riskAssessmentId"] = riskAssessmentId
        result["recipientEmail"] = recipientEmail
        result["subject"] = subject
        result["status"] = status.name
        result["attempts"] = attempts
        result["canRetry"] = canRetry()
        result["isFinal"] = isFinal()
        sentAt?.let { result["sentAt"] = it }
        errorMessage?.let { result["errorMessage"] = it }
        createdAt?.let { result["createdAt"] = it }
        return result
    }

    /**
     * Validate notification log data
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()

        if (riskAssessmentId <= 0) {
            errors.add("Risk assessment ID must be positive")
        }

        if (emailConfigId <= 0) {
            errors.add("Email config ID must be positive")
        }

        if (recipientEmail.isBlank()) {
            errors.add("Recipient email cannot be empty")
        } else if (!recipientEmail.matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))) {
            errors.add("Invalid recipient email format")
        }

        if (subject.isBlank()) {
            errors.add("Subject cannot be empty")
        } else if (subject.length > 500) {
            errors.add("Subject cannot exceed 500 characters")
        }

        if (attempts < 0) {
            errors.add("Attempts cannot be negative")
        }

        return errors
    }

    /**
     * Check if notification is overdue for retry
     */
    fun isOverdue(overdueThresholdMinutes: Int = 30): Boolean {
        return when (status) {
            EmailStatus.PENDING -> {
                val createTime = createdAt ?: return false
                LocalDateTime.now().isAfter(createTime.plusMinutes(overdueThresholdMinutes.toLong()))
            }
            EmailStatus.RETRYING -> {
                val retryTime = nextRetryAt ?: return true
                LocalDateTime.now().isAfter(retryTime.plusMinutes(overdueThresholdMinutes.toLong()))
            }
            else -> false
        }
    }
}