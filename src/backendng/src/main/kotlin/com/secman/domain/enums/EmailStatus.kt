package com.secman.domain.enums

/**
 * Status values for email notification delivery
 */
enum class EmailStatus {
    /**
     * Email is queued for sending
     */
    PENDING,

    /**
     * Email was successfully sent
     */
    SENT,

    /**
     * Email sending failed (temporary failure)
     */
    FAILED,

    /**
     * Email is being retried after a failure
     */
    RETRYING,

    /**
     * Email permanently failed after max retries
     */
    PERMANENTLY_FAILED;

    /**
     * Check if the email can be retried
     */
    fun canRetry(): Boolean {
        return when (this) {
            PENDING, FAILED, RETRYING -> true
            SENT, PERMANENTLY_FAILED -> false
        }
    }

    /**
     * Check if this is a final status (no further processing)
     */
    fun isFinal(): Boolean {
        return when (this) {
            SENT, PERMANENTLY_FAILED -> true
            PENDING, FAILED, RETRYING -> false
        }
    }

    /**
     * Get the next status after a failed retry
     */
    fun afterFailedRetry(isMaxRetriesReached: Boolean): EmailStatus {
        return if (isMaxRetriesReached) {
            PERMANENTLY_FAILED
        } else {
            RETRYING
        }
    }

    /**
     * Get the next status after successful sending
     */
    fun afterSuccessfulSend(): EmailStatus {
        return SENT
    }

    /**
     * Get a human-readable description
     */
    fun getDescription(): String {
        return when (this) {
            PENDING -> "Email is queued for delivery"
            SENT -> "Email was successfully delivered"
            FAILED -> "Email delivery failed (will retry)"
            RETRYING -> "Email delivery is being retried"
            PERMANENTLY_FAILED -> "Email permanently failed after all retries"
        }
    }
}