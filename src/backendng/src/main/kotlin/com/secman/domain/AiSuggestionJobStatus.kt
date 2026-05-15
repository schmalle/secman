package com.secman.domain

import io.micronaut.serde.annotation.Serdeable

/**
 * Lifecycle states of an AiSuggestionJob. Feature 088. Mirrors ExportJobStatus.
 */
@Serdeable
enum class AiSuggestionJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    fun isTerminal(): Boolean = this == COMPLETED || this == FAILED || this == CANCELLED
    fun isRunning(): Boolean = this == QUEUED || this == RUNNING
}
