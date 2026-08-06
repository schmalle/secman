package com.secman.crowdstrike.exception

/**
 * Exception thrown when API rate limit is exceeded
 */
class RateLimitException(
    message: String,
    val retryAfterSeconds: Long = 60,
    cause: Throwable? = null
) : CrowdStrikeException(message, cause)
