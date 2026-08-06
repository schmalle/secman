package com.secman.service

/**
 * Sealed class representing CrowdStrike API integration errors
 *
 * Related to: Feature 015-we-have-currently (CrowdStrike System Vulnerability Lookup)
 *
 * Error Handling Strategy:
 * - AuthenticationError: CrowdStrike OAuth2 authentication failed (401/403)
 * - NotFoundError: System hostname not found in CrowdStrike (404)
 * - RateLimitError: CrowdStrike API rate limit exceeded (429)
 * - NetworkError: Network connectivity issues, timeouts
 * - ServerError: CrowdStrike service unavailable (500+)
 * - ConfigurationError: Missing or invalid CrowdStrike credentials in database
 */
sealed class CrowdStrikeError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Authentication failed with CrowdStrike API
     * HTTP Status: 401/403
     */
    class AuthenticationError(
        message: String = "CrowdStrike authentication failed",
        cause: Throwable? = null
    ) : CrowdStrikeError(message, cause)

    /**
     * System hostname not found in CrowdStrike
     * HTTP Status: 404
     */
    class NotFoundError(
        val hostname: String,
        message: String = "System '$hostname' not found in CrowdStrike",
        cause: Throwable? = null
    ) : CrowdStrikeError(message, cause)

    /**
     * CrowdStrike API rate limit exceeded
     * HTTP Status: 429
     */
    class RateLimitError(
        val retryAfterSeconds: Int? = null,
        message: String = "CrowdStrike API rate limit exceeded. ${retryAfterSeconds?.let { "Try again in $it seconds" } ?: "Please try again later"}",
        cause: Throwable? = null
    ) : CrowdStrikeError(message, cause)

    /**
     * Network connectivity issues or timeouts
     * HTTP Status: 500
     */
    class NetworkError(
        message: String = "Unable to reach CrowdStrike API. Please try again later.",
        cause: Throwable? = null
    ) : CrowdStrikeError(message, cause)

    /**
     * CrowdStrike service unavailable or internal errors
     * HTTP Status: 500
     */
    class ServerError(
        val statusCode: Int? = null,
        message: String = "CrowdStrike service temporarily unavailable. ${statusCode?.let { "Status: $it" } ?: ""}",
        cause: Throwable? = null
    ) : CrowdStrikeError(message, cause)

    /**
     * CrowdStrike credentials not configured or invalid in database
     * HTTP Status: 500
     */
    class ConfigurationError(
        message: String = "CrowdStrike API credentials not configured in database. Contact administrator.",
        cause: Throwable? = null
    ) : CrowdStrikeError(message, cause)
}
