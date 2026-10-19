package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

@Serdeable
data class CrowdStrikeIdentityReconcileRequest(
    @field:Positive val assetId: Long,
    @field:Size(min = 2, max = 20) val expectedAids: Set<String>,
    @field:Size(min = 10, max = 255) val reason: String,
    val dryRun: Boolean = true
)

@Serdeable
data class CrowdStrikeIdentityReconcileResponse(
    val assetId: Long,
    val dryRun: Boolean,
    val devices: List<CrowdStrikeReconciledDevice>
)

@Serdeable
data class CrowdStrikeReconciledDevice(
    val aid: String,
    val instanceId: String,
    val accountId: String,
    val assetId: Long?,
    val findings: Int
)
