package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

@Serdeable
data class AccountWelcomeTestRequest(
    val recipient: String,
    val accountIds: List<String>
)
