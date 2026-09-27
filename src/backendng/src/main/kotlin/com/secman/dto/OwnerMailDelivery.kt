package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class OwnerMailDelivery(
    val requested: Boolean = false,
    val status: String = "SKIPPED",
    val notificationId: Long? = null,
    val retryable: Boolean = false,
    val errorCode: String? = null
)
