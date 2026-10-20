package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.Pattern
import com.fasterxml.jackson.annotation.JsonInclude

@Serdeable
data class CrowdStrikeIdentityReconcileRequest(
    @field:Positive val assetId: Long,
    @field:Size(min = 2, max = 100) val expectedAids: Set<String>,
    @field:Size(min = 10, max = 255) val reason: String,
    val dryRun: Boolean = true,
    @field:Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    val requestId: String? = null
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class CrowdStrikeIdentityReconcileResponse(
    val assetId: Long,
    val dryRun: Boolean,
    val devices: List<CrowdStrikeReconciledDevice>,
    val findingsFetched: Boolean = false,
    val requestId: String? = null
)

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
data class CrowdStrikeReconciledDevice(
    val aid: String,
    val instanceId: String,
    val accountId: String,
    val assetId: Long?,
    val findings: Int?
)
