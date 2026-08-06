package com.secman.domain.enums

/**
 * Status values for test email accounts
 */
enum class TestAccountStatus {
    /**
     * Account is active and available for testing
     */
    ACTIVE,

    /**
     * Account is temporarily disabled
     */
    INACTIVE,

    /**
     * Account failed validation or testing
     */
    FAILED,

    /**
     * Account credentials are being verified
     */
    VERIFICATION_PENDING;

    /**
     * Check if the account can be used for testing
     */
    fun canTest(): Boolean {
        return this == ACTIVE
    }

    /**
     * Check if the account can be verified
     */
    fun canVerify(): Boolean {
        return when (this) {
            INACTIVE, FAILED, VERIFICATION_PENDING -> true
            ACTIVE -> false
        }
    }

    /**
     * Get the next status after successful verification
     */
    fun afterSuccessfulVerification(): TestAccountStatus {
        return ACTIVE
    }

    /**
     * Get the next status after failed verification
     */
    fun afterFailedVerification(): TestAccountStatus {
        return FAILED
    }

    /**
     * Get a human-readable description of the status
     */
    fun getDescription(): String {
        return when (this) {
            ACTIVE -> "Account is active and ready for testing"
            INACTIVE -> "Account is temporarily disabled"
            FAILED -> "Account failed verification or testing"
            VERIFICATION_PENDING -> "Account credentials are being verified"
        }
    }
}