package com.secman.service

import com.secman.dto.AssetMatchClearCandidateDto
import com.secman.dto.AssetMatchClearErrorDto
import com.secman.dto.AssetMatchClearResponse
import com.secman.repository.AssetRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Reconcile secman's AWS asset inventory against an authoritative resource
 * snapshot (typically a JSON file produced upstream and parked in S3).
 *
 * The CLI side downloads the JSON and passes the resulting `(accountIds,
 * resourceIds)` pair to this service via the controller. We delete every
 * asset whose `cloudAccountId` is in `accountIds` and whose
 * `cloudInstanceId` is NOT in `resourceIds` — i.e. assets that secman still
 * thinks exist but no longer appear in the upstream inventory.
 *
 * Safety properties:
 *  - Partial-snapshot safe: only deletes within the accounts the snapshot
 *    covers. Assets in other accounts are never touched.
 *  - Empty-snapshot guard: a 0-resource snapshot is rejected — refuses to
 *    treat it as authoritative.
 *  - Safety brake: aborts when the proposed deletion would exceed
 *    `maxDeletePercent` of the scoped account total. `null` or `>= 100`
 *    disables the brake.
 */
@Singleton
open class AssetMatchClearService(
    private val assetRepository: AssetRepository,
    private val assetCascadeDeleteService: AssetCascadeDeleteService
) {

    private val log = LoggerFactory.getLogger(AssetMatchClearService::class.java)

    class EmptySnapshotException(message: String) : RuntimeException(message)

    fun clear(
        accountIds: Collection<String>,
        resourceIds: Collection<String>,
        dryRun: Boolean,
        username: String,
        maxDeletePercent: Int? = 25
    ): AssetMatchClearResponse {
        val normalizedAccounts = accountIds
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val normalizedResources = resourceIds
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        if (normalizedAccounts.isEmpty()) {
            throw EmptySnapshotException(
                "Snapshot contains no accountIds — refusing to treat as authoritative."
            )
        }
        if (normalizedResources.isEmpty()) {
            throw EmptySnapshotException(
                "Snapshot contains no resourceIds — refusing to treat as authoritative."
            )
        }

        val scopedAssets = assetRepository.findAwsAssetsInAccounts(normalizedAccounts)
        val candidates = scopedAssets
            .asSequence()
            .filter { asset ->
                val instance = asset.cloudInstanceId?.trim()?.lowercase()
                instance != null && instance.isNotEmpty() && instance !in normalizedResources
            }
            .mapNotNull { asset ->
                val id = asset.id ?: return@mapNotNull null
                AssetMatchClearCandidateDto(
                    assetId = id,
                    name = asset.name,
                    cloudAccountId = asset.cloudAccountId,
                    cloudInstanceId = asset.cloudInstanceId
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()

        val brakeApplied = maxDeletePercent != null && maxDeletePercent in 0..99
        if (brakeApplied && !dryRun && candidates.isNotEmpty()) {
            val total = assetRepository.countAwsAssetsInAccounts(normalizedAccounts)
            if (total > 0) {
                val percent = candidates.size * 100.0 / total
                if (percent > maxDeletePercent) {
                    log.warn(
                        "asset-match-clear safety brake tripped: {} of {} scoped assets ({}%) exceeds max {}%",
                        candidates.size, total, "%.2f".format(percent), maxDeletePercent
                    )
                    return AssetMatchClearResponse(
                        dryRun = false,
                        snapshotAccountCount = normalizedAccounts.size,
                        snapshotResourceCount = normalizedResources.size,
                        scopedAssetCount = scopedAssets.size,
                        candidateCount = candidates.size,
                        deletedCount = 0,
                        skippedCount = candidates.size,
                        candidates = candidates,
                        errors = emptyList(),
                        status = "ABORTED_SAFETY_BRAKE",
                        safetyBrakePercent = maxDeletePercent,
                        safetyBrakeTripped = true
                    )
                }
            }
        }

        if (dryRun) {
            log.info(
                "asset_match_clear_run dryRun=true user={} snapshotAccounts={} snapshotResources={} scoped={} candidates={}",
                username, normalizedAccounts.size, normalizedResources.size, scopedAssets.size, candidates.size
            )
            return AssetMatchClearResponse(
                dryRun = true,
                snapshotAccountCount = normalizedAccounts.size,
                snapshotResourceCount = normalizedResources.size,
                scopedAssetCount = scopedAssets.size,
                candidateCount = candidates.size,
                deletedCount = 0,
                skippedCount = candidates.size,
                candidates = candidates,
                errors = emptyList(),
                status = "SUCCESS",
                safetyBrakePercent = if (brakeApplied) maxDeletePercent else null,
                safetyBrakeTripped = false
            )
        }

        val errors = mutableListOf<AssetMatchClearErrorDto>()
        var deletedCount = 0
        val operationId = java.util.UUID.randomUUID().toString()

        candidates.forEach { candidate ->
            try {
                assetCascadeDeleteService.deleteAsset(
                    assetId = candidate.assetId,
                    username = username,
                    forceTimeout = true,
                    bulkOperationId = operationId
                )
                deletedCount++
            } catch (e: Exception) {
                log.warn(
                    "Failed to delete unmatched AWS asset {} ({}): {}",
                    candidate.assetId, candidate.name, e.message
                )
                errors.add(
                    AssetMatchClearErrorDto(
                        assetId = candidate.assetId,
                        assetName = candidate.name,
                        message = e.message ?: e.javaClass.simpleName
                    )
                )
            }
        }

        val status = when {
            errors.isEmpty() -> "SUCCESS"
            deletedCount == 0 -> "FAILED"
            else -> "PARTIAL"
        }

        log.info(
            "asset_match_clear_run dryRun=false user={} snapshotAccounts={} snapshotResources={} scoped={} candidates={} deleted={} errors={} status={}",
            username, normalizedAccounts.size, normalizedResources.size,
            scopedAssets.size, candidates.size, deletedCount, errors.size, status
        )

        return AssetMatchClearResponse(
            dryRun = false,
            snapshotAccountCount = normalizedAccounts.size,
            snapshotResourceCount = normalizedResources.size,
            scopedAssetCount = scopedAssets.size,
            candidateCount = candidates.size,
            deletedCount = deletedCount,
            skippedCount = errors.size,
            candidates = candidates,
            errors = errors,
            status = status,
            safetyBrakePercent = if (brakeApplied) maxDeletePercent else null,
            safetyBrakeTripped = false
        )
    }
}
