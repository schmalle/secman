package com.secman.dto.mcp

import io.micronaut.serde.annotation.Serdeable

/**
 * Result of a cleanup operation for MCP services.
 */
@Serdeable
data class CleanupResult(
    val success: Boolean,
    val cleanedUpCount: Int,
    val durationMs: Long,
    val message: String
)