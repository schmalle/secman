package com.secman.service

import com.secman.constants.AssetOwners
import com.secman.dto.CleanupCandidateReason
import com.secman.dto.CrowdStrikeAssetCleanupCandidateDto
import com.secman.dto.CrowdStrikeAssetCleanupErrorDto
import com.secman.dto.CrowdStrikeAssetCleanupResponse
import com.secman.repository.AssetRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Singleton
open class CrowdStrikeAssetCleanupService(
    private val assetRepository: AssetRepository,
    private val assetCascadeDeleteService: AssetCascadeDeleteService
) {
    private val log = LoggerFactory.getLogger(CrowdStrikeAssetCleanupService::class.java)
    private var clock: Clock = Clock.systemDefaultZone()

    constructor(
        assetRepository: AssetRepository,
        assetCascadeDeleteService: AssetCascadeDeleteService,
        clock: Clock
    ) : this(assetRepository, assetCascadeDeleteService) {
        this.clock = clock
    }

    /**
     * Find and (unless `dryRun`) delete CrowdStrike-stale assets.
     *
     * Two candidate rules combined:
     *   - Rule A (always on): `crowdStrikeLastImportedAt < cutoff`.
     *   - Rule B (when `includeLegacy = true`): legacy fence — owner =
     *     "CrowdStrike Import" AND no import timestamp AND no
     *     `manualCreator` AND no `scanUploader` AND
     *     `COALESCE(lastSeen, updatedAt, createdAt) < cutoff`.
     *
     * Combined list is `.distinctBy { it.id }` so an asset matching both
     * rules is processed once. The two predicates are mutually exclusive on
     * `crowdStrikeLastImportedAt IS NULL` vs `< cutoff`, so the de-dup is
     * defensive belt-and-braces today; it protects future predicate changes.
     *
     * Caller (CrowdStrikeCleanupAuditService) resolves `includeLegacy` from
     * the optional per-run override and the configured default — this
     * service does NOT itself read configuration.
     */
    fun cleanup(
        days: Int,
        dryRun: Boolean,
        username: String,
        includeLegacy: Boolean,
        cutoffOverride: LocalDateTime? = null
    ): CrowdStrikeAssetCleanupResponse {
        require(days > 0) { "Days must be greater than zero" }

        // cutoffOverride lets the audit service compute ONE cutoff per run and share it
        // between the safety brake and this selection — two separate clock reads made the
        // brake's candidate set and the deleted set measurably different populations.
        val cutoff = cutoffOverride ?: LocalDateTime.now(clock).minusDays(days.toLong())

        // Rule A is agent-seen aware: an asset CrowdStrike reported as a managed device
        // within the window (crowdStrikeAgentSeenAt >= cutoff) is never a candidate, even
        // when it has no recent findings import. A fully-patched host with a live sensor
        // never refreshes crowdStrikeLastImportedAt — deleting it would destroy a healthy,
        // remediated asset.
        val timestampCandidates = assetRepository.findCrowdStrikeStaleExcludingAgentSeen(cutoff)
            .mapNotNull { asset ->
                val importedAt = asset.crowdStrikeLastImportedAt ?: return@mapNotNull null
                val assetId = asset.id ?: return@mapNotNull null
                CrowdStrikeAssetCleanupCandidateDto(
                    assetId = assetId,
                    name = asset.name,
                    crowdStrikeLastImportedAt = importedAt,
                    reason = CleanupCandidateReason.TIMESTAMP_STALE
                )
            }

        val legacyCandidates = if (includeLegacy) {
            assetRepository.findLegacyCrowdStrikeStale(AssetOwners.CROWDSTRIKE_IMPORT, cutoff)
                .mapNotNull { asset ->
                    val assetId = asset.id ?: return@mapNotNull null
                    CrowdStrikeAssetCleanupCandidateDto(
                        assetId = assetId,
                        name = asset.name,
                        crowdStrikeLastImportedAt = null,
                        reason = CleanupCandidateReason.LEGACY_NULL_TIMESTAMP
                    )
                }
        } else {
            emptyList()
        }

        val candidates = (timestampCandidates + legacyCandidates)
            .distinctBy { it.assetId }
            .sortedBy { it.name.lowercase() }

        val legacyCandidateCount = candidates.count { it.reason == CleanupCandidateReason.LEGACY_NULL_TIMESTAMP }

        if (dryRun) {
            return CrowdStrikeAssetCleanupResponse(
                days = days,
                cutoff = cutoff,
                dryRun = true,
                candidateCount = candidates.size,
                deletedCount = 0,
                skippedCount = candidates.size,
                candidates = candidates,
                errors = emptyList(),
                legacyCandidateCount = legacyCandidateCount,
                legacyDeletedCount = 0
            )
        }

        val operationId = UUID.randomUUID().toString()
        val errors = mutableListOf<CrowdStrikeAssetCleanupErrorDto>()
        var deletedCount = 0
        var legacyDeletedCount = 0

        candidates.forEach { candidate ->
            try {
                assetCascadeDeleteService.deleteAsset(
                    assetId = candidate.assetId,
                    username = username,
                    forceTimeout = true,
                    bulkOperationId = operationId
                )
                deletedCount++
                if (candidate.reason == CleanupCandidateReason.LEGACY_NULL_TIMESTAMP) {
                    legacyDeletedCount++
                }
            } catch (e: Exception) {
                log.warn("Failed to delete CrowdStrike-stale asset {} ({}, reason={})",
                    candidate.assetId, candidate.name, candidate.reason, e)
                errors.add(
                    CrowdStrikeAssetCleanupErrorDto(
                        assetId = candidate.assetId,
                        assetName = candidate.name,
                        message = e.message ?: e.javaClass.simpleName
                    )
                )
            }
        }

        return CrowdStrikeAssetCleanupResponse(
            days = days,
            cutoff = cutoff,
            dryRun = false,
            candidateCount = candidates.size,
            deletedCount = deletedCount,
            skippedCount = errors.size,
            candidates = candidates,
            errors = errors,
            legacyCandidateCount = legacyCandidateCount,
            legacyDeletedCount = legacyDeletedCount
        )
    }
}
