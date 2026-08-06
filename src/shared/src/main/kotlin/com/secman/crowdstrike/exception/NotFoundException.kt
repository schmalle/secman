package com.secman.crowdstrike.exception

/**
 * Exception thrown when a requested resource is not found
 */
class NotFoundException(
    message: String,
    cause: Throwable? = null
) : CrowdStrikeException(message, cause)
