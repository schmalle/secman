package com.secman.dto

import io.micronaut.serde.annotation.Serdeable

/**
 * Request body for POST /api/assets/match-clear-aws.
 *
 * The CLI ships the set of (accountId, resourceId) pairs it parsed from the
 * S3 snapshot. The backend deletes every asset whose `cloudAccountId` is in
 * `accountIds` and whose `cloudInstanceId` (case-insensitive) is NOT in
 * `resourceIds`. Assets in accounts outside `accountIds` are never touched —
 * this is the partial-snapshot safety property.
 */
@Serdeable
data class AssetMatchClearRequest(
    val accountIds: List<String>,
    val resourceIds: List<String>,
    val dryRun: Boolean = false,
    val maxDeletePercent: Int? = 25,
    val strict: Boolean = false
)

@Serdeable
data class AssetMatchClearCandidateDto(
    val assetId: Long,
    val name: String,
    val cloudAccountId: String?,
    val cloudInstanceId: String?
)

@Serdeable
data class AssetMatchClearErrorDto(
    val assetId: Long,
    val assetName: String,
    val message: String
)

@Serdeable
data class AssetMatchClearResponse(
    val dryRun: Boolean,
    val scopeMode: String = "snapshot accounts",
    val snapshotAccountCount: Int,
    val snapshotResourceCount: Int,
    val scopedAssetCount: Int,
    val uncoveredAccountCount: Int = 0,
    val uncoveredAssetCount: Int = 0,
    val uncoveredAccounts: Map<String, Int> = emptyMap(),
    val candidateCount: Int,
    val deletedCount: Int,
    val skippedCount: Int,
    val candidates: List<AssetMatchClearCandidateDto>,
    val errors: List<AssetMatchClearErrorDto>,
    val status: String,
    val safetyBrakePercent: Int?,
    val safetyBrakeTripped: Boolean = false
)
