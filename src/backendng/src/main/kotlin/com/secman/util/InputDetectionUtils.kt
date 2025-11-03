package com.secman.util

import com.secman.service.QueryType

/**
 * Input detection utilities for query type identification
 *
 * Feature: 041-falcon-instance-lookup
 * Task: T007
 */
object InputDetectionUtils {
    /**
     * AWS EC2 Instance ID detection regex
     *
     * Matches strings that start with "i-" followed by 8 or 17 hex characters
     */
    private val AWS_INSTANCE_ID_PATTERN = Regex("^i-[0-9a-fA-F]{8,17}$", RegexOption.IGNORE_CASE)

    /**
     * Detect query type from input string
     *
     * Auto-detection logic:
     * - If matches AWS instance ID pattern (i-[0-9a-fA-F]{8,17}) → INSTANCE_ID
     * - Otherwise → HOSTNAME
     *
     * @param input The user input (hostname or instance ID)
     * @return QueryType.INSTANCE_ID or QueryType.HOSTNAME
     */
    fun detectQueryType(input: String): QueryType {
        return if (AWS_INSTANCE_ID_PATTERN.matches(input)) {
            QueryType.INSTANCE_ID
        } else {
            QueryType.HOSTNAME
        }
    }

    /**
     * Check if input matches AWS instance ID pattern
     *
     * @param input The user input to check
     * @return true if matches instance ID pattern, false otherwise
     */
    fun isAwsInstanceId(input: String): Boolean {
        return AWS_INSTANCE_ID_PATTERN.matches(input)
    }

    /**
     * Check if input is a hostname (not an instance ID)
     *
     * @param input The user input to check
     * @return true if NOT an instance ID, false otherwise
     */
    fun isHostname(input: String): Boolean {
        return !isAwsInstanceId(input)
    }
}
