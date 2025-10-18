package com.secman.crowdstrike.exception

/**
 * Exception thrown when authentication with CrowdStrike API fails
 */
class AuthenticationException(
    message: String,
    cause: Throwable? = null
) : CrowdStrikeException(message, cause)
