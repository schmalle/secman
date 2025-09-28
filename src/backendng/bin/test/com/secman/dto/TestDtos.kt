package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

// Common test DTOs to avoid duplication across test files

@Serdeable
data class TestErrorResponse(
    val error: String?,
    val message: String?,
    val timestamp: LocalDateTime?
)