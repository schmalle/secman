package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

@Serdeable
data class CrowdStrikeAssetCleanupRequest(
    val days: Int,
    val dryRun: Boolean = false,
    // Feature 087: per-run override for the legacy rule (rule B). Null means
    // "use the configured default" (`secman.crowdstrike.cleanup.include-legacy`).
    val includeLegacy: Boolean? = null
)

/**
 * Why a candidate was selected for deletion. Feature 087.
 * - TIMESTAMP_STALE: rule A — `crowdStrikeLastImportedAt < cutoff`.
 * - LEGACY_NULL_TIMESTAMP: rule B — `owner='CrowdStrike Import'` AND no
 *   import timestamp AND no manualCreator AND no scanUploader AND
 *   `COALESCE(lastSeen, updatedAt, createdAt) < cutoff`.
 */
@Serdeable
enum class CleanupCandidateReason {
    TIMESTAMP_STALE,
    LEGACY_NULL_TIMESTAMP
}

@Serdeable
data class CrowdStrikeAssetCleanupCandidateDto(
    val assetId: Long,
    val name: String,
    // Nullable since Feature 087: legacy candidates have no import timestamp.
    val crowdStrikeLastImportedAt: LocalDateTime?,
    val reason: CleanupCandidateReason
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
    val errors: List<CrowdStrikeAssetCleanupErrorDto>,
    // Optional run status — populated by CrowdStrikeCleanupAuditService.
    // One of: SUCCESS, PARTIAL, ABORTED_SAFETY_BRAKE, FAILED. Null on legacy paths.
    val status: String? = null,
    // Audit-row id, present when the run was persisted to crowdstrike_cleanup_run.
    val runId: Long? = null,
    // Feature 087: rule-B contribution split. Always ≤ candidateCount / deletedCount.
    val legacyCandidateCount: Int = 0,
    val legacyDeletedCount: Int = 0
)
