package com.secman.util

/**
 * Validation utilities for input data
 *
 * Feature: 041-falcon-instance-lookup
 * Task: T006
 */
object ValidationUtils {
    /**
     * AWS EC2 Instance ID validation regex
     *
     * Format: i-[0-9a-fA-F]{8,17}
     * - Prefix: "i-"
     * - Followed by 8 (legacy) or 17 (current) hexadecimal characters
     * - Case-insensitive
     *
     * Examples:
     * - i-1234567a (legacy 8-char)
     * - i-0048f94221fe110cf (current 17-char)
     */
    private val AWS_INSTANCE_ID_REGEX = Regex("^i-[0-9a-fA-F]{8,17}$", RegexOption.IGNORE_CASE)

    /**
     * Validate AWS EC2 Instance ID format
     *
     * @param instanceId The instance ID to validate
     * @return true if valid format, false otherwise
     */
    fun isValidAwsInstanceId(instanceId: String): Boolean {
        return AWS_INSTANCE_ID_REGEX.matches(instanceId)
    }

    /**
     * Validate and normalize AWS instance ID
     *
     * @param instanceId The instance ID to validate
     * @return Normalized (lowercase) instance ID
     * @throws IllegalArgumentException if format is invalid
     */
    fun validateAndNormalizeAwsInstanceId(instanceId: String): String {
        require(isValidAwsInstanceId(instanceId)) {
            "Invalid instance ID format. Expected 'i-' followed by 8 or 17 hexadecimal characters (e.g., i-0048f94221fe110cf)"
        }
        return instanceId.lowercase()
    }

    /**
     * Get user-friendly error message for invalid instance ID
     *
     * @param instanceId The invalid instance ID
     * @return Error message explaining expected format
     */
    fun getInstanceIdValidationError(instanceId: String): String {
        return "Invalid instance ID format: '$instanceId'. Expected 'i-' followed by 8 or 17 hexadecimal characters (e.g., i-0048f94221fe110cf)"
    }
}
