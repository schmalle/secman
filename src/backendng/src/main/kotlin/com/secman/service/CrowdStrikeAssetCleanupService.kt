package com.secman.service

import com.secman.constants.AssetOwners
import com.secman.dto.CleanupCandidateReason
import com.secman.dto.CrowdStrikeAssetCleanupCandidateDto
import com.secman.dto.CrowdStrikeAssetCleanupErrorDto
import com.secman.dto.CrowdStrikeAssetCleanupResponse
import com.secman.repository.AssetRepository
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.ZoneOffset
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Singleton
open class CrowdStrikeAssetCleanupService(
    private val assetRepository: AssetRepository,
    private val assetCascadeDeleteService: AssetCascadeDeleteService,
    private val retentionPolicy: CrowdStrikeRetentionPolicy = CrowdStrikeRetentionPolicy()
) {
    private val log = LoggerFactory.getLogger(CrowdStrikeAssetCleanupService::class.java)
    private var clock: Clock = Clock.systemDefaultZone()

    constructor(assetRepository: AssetRepository, assetCascadeDeleteService: AssetCascadeDeleteService, clock: Clock)
        : this(assetRepository, assetCascadeDeleteService) { this.clock = clock }

    fun cleanup(days: Int, dryRun: Boolean, username: String, includeLegacy: Boolean,
                cutoffOverride: LocalDateTime? = null,
                basis: CrowdStrikeCleanupBasis = CrowdStrikeCleanupBasis.IMPORT_INACTIVITY,
                selection: CrowdStrikeAssetCleanupResponse? = null): CrowdStrikeAssetCleanupResponse {
        require(days in 1..36500) { "Days must be between 1 and 36500" }
        val cutoff = cutoffOverride ?: LocalDateTime.now(clock).minusDays(days.toLong())
        val cutoffUtc = cutoff.atZone(clock.zone).toInstant()
        val agentCutoff = LocalDateTime.ofInstant(cutoffUtc, ZoneOffset.UTC)
        val candidates = mutableListOf<CrowdStrikeAssetCleanupCandidateDto>()
        val exclusions = mutableMapOf<String, Long>()
        if (selection != null) {
            candidates.addAll(selection.candidates)
            exclusions.putAll(selection.exclusions)
        } else {
            var page = 0
            do {
                val population = assetRepository.findCleanupPopulation(AssetOwners.CROWDSTRIKE_IMPORT,
                    Pageable.from(page++, 200, Sort.of(Sort.Order.asc("id"))))
                population.content.forEach { asset ->
                    val excluded = retentionPolicy.exclusion(asset, basis, includeLegacy, cutoff, agentCutoff)
                    if (excluded != null) exclusions[excluded] = (exclusions[excluded] ?: 0L) + 1
                    else candidates.add(CrowdStrikeAssetCleanupCandidateDto(
                        assetId = requireNotNull(asset.id), name = asset.name,
                        crowdStrikeLastImportedAt = asset.crowdStrikeLastImportedAt,
                        reason = when {
                            basis == CrowdStrikeCleanupBasis.SENSOR_CONTACT -> CleanupCandidateReason.SENSOR_CONTACT_STALE
                            asset.crowdStrikeLastImportedAt == null -> CleanupCandidateReason.LEGACY_NULL_TIMESTAMP
                            else -> CleanupCandidateReason.TIMESTAMP_STALE
                        },
                        falconLastContact = asset.crowdStrikeAgentSeenAt?.toInstant(ZoneOffset.UTC),
                        lastImportedAt = asset.crowdStrikeLastImportedAt?.atZone(clock.zone)?.toInstant()
                    ))
                }
            } while (population.hasNext())
        }

        val errors = mutableListOf<CrowdStrikeAssetCleanupErrorDto>()
        var deleted = 0
        var legacyDeleted = 0
        var changed = 0
        val operationId = UUID.randomUUID().toString()
        if (!dryRun) candidates.forEach { candidate ->
            try {
                assetCascadeDeleteService.deleteAsset(candidate.assetId, username, forceTimeout = true,
                    bulkOperationId = operationId,
                    cleanupEligibility = { retentionPolicy.exclusion(it, basis, includeLegacy, cutoff, agentCutoff) == null })
                deleted++
                if (candidate.reason == CleanupCandidateReason.LEGACY_NULL_TIMESTAMP) legacyDeleted++
            } catch (e: CleanupEligibilityChangedException) {
                changed++
                log.info("Cleanup asset={} outcome=retained reason=eligibility-changed", candidate.assetId)
            } catch (e: Exception) {
                log.warn("Cleanup asset={} outcome=failed", candidate.assetId, e)
                errors.add(CrowdStrikeAssetCleanupErrorDto(candidate.assetId, candidate.name,
                    "Deletion failed; existing data was preserved. See backend log for details"))
            }
        }
        if (changed > 0) exclusions["ELIGIBILITY_CHANGED"] = changed.toLong()
        log.info("CrowdStrike cleanup actor={} basis={} cutoffUtc={} dryRun={} candidates={} deleted={} errors={}",
            username.replace('\r', '_').replace('\n', '_'), basis.value, cutoffUtc, dryRun, candidates.size, deleted, errors.size)
        return CrowdStrikeAssetCleanupResponse(days, cutoff, dryRun, candidates.size, deleted,
            if (dryRun) candidates.size else errors.size + changed, candidates, errors,
            legacyCandidateCount = candidates.count { it.reason == CleanupCandidateReason.LEGACY_NULL_TIMESTAMP },
            legacyDeletedCount = legacyDeleted, basis = basis.value, cutoffUtc = cutoffUtc,
            exclusions = exclusions, retentionDays = retentionPolicy.sensorRetentionDays)
    }
}
