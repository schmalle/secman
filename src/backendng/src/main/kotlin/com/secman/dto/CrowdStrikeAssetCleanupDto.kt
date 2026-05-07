package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

@Serdeable
data class CrowdStrikeAssetCleanupRequest(
    val days: Int,
    val dryRun: Boolean = false
)

@Serdeable
data class CrowdStrikeAssetCleanupCandidateDto(
    val assetId: Long,
    val name: String,
    val crowdStrikeLastImportedAt: LocalDateTime
)

@Serdeable
data class CrowdStrikeAssetCleanupErrorDto(
    val assetId: Long,
    val assetName: String,
    val message: String
)

@Serdeable
data class CrowdStrikeAssetCleanupResponse(
    val days: Int,
    val cutoff: LocalDateTime,
    val dryRun: Boolean,
    val candidateCount: Int,
    val deletedCount: Int,
    val skippedCount: Int,
    val candidates: List<CrowdStrikeAssetCleanupCandidateDto>,
    val errors: List<CrowdStrikeAssetCleanupErrorDto>
)
