package com.secman.crowdstrike.exception

/**
 * Base exception for CrowdStrike API errors
 */
open class CrowdStrikeException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
