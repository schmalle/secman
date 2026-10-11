package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Serdeable
data class CrowdStrikeSyncRequest(
    @field:NotBlank @field:Size(max = 255)
    val hostname: String,
    val assetId: Long? = null,
    @field:Size(max = 255)
    val instanceId: String? = null
)
