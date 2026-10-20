package com.secman.service

import com.secman.domain.Asset
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import com.secman.repository.DemandRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.RiskRepository
import io.micronaut.data.model.Pageable
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType

/** ADMIN opt-in cleanup of the exact failed hostname, inside the replacement transaction. */
@Singleton
class CrowdStrikeImportRecoveryService(
    private val assets: AssetRepository,
    private val identities: CrowdStrikeAssetIdentityRepository,
    private val demands: DemandRepository,
    private val assessments: RiskAssessmentRepository,
    private val risks: RiskRepository,
    private val deletion: AssetCascadeDeleteService,
    private val entityManager: EntityManager
) {
    fun recover(batch: CrowdStrikeVulnerabilityBatchDto, conflict: CrowdStrikeIdentityConflict, username: String): List<Long> {
        require(canRecover(batch, conflict)) { "Import rejection cannot be recovered by hostname cleanup" }
        val selection = requireNotNull(batch.deviceSelection)
        val selectionAids = (listOf(selection.selected) + selection.superseded).map { it.aid }
        guard(identities.findByCrowdStrikeAidIn(selectionAids).none {
            !matchesHostname(it.asset, batch.hostname)
        }, "CONFLICT_OUTSIDE_HOSTNAME", "Selected or superseded Falcon identity belongs outside the hostname cleanup scope")
        val candidates = hostnameTargets(batch.hostname)
        guard(candidates.isNotEmpty(), "TARGET_CHANGED", "No assets match the failed hostname")
        val expected = candidates.associateBy { requireNotNull(it.id) }
        guard(conflict.assetId == null || conflict.assetId in expected, "CONFLICT_OUTSIDE_HOSTNAME",
            "Rejected identity belongs to an asset outside the requested hostname")
        conflict.assetId?.let { id ->
            guard(expected.getValue(id).updatedAt == conflict.assetUpdatedAt, "TARGET_CHANGED",
                "Recovery target changed after import rejection")
        }
        val versions = expected.mapValues { (_, asset) -> asset.updatedAt to asset.cloudInstanceId }
        val locked = expected.keys.sorted().map { id ->
            val asset = entityManager.find(Asset::class.java, id, LockModeType.PESSIMISTIC_WRITE)
                ?: throw CrowdStrikeRecoveryBlocked("TARGET_CHANGED", "Recovery target no longer exists")
            entityManager.refresh(asset, LockModeType.PESSIMISTIC_WRITE)
            guard(matchesHostname(asset, batch.hostname) && versions.getValue(id) == (asset.updatedAt to asset.cloudInstanceId),
                "TARGET_CHANGED", "Recovery target changed while acquiring deletion locks")
            asset
        }
        guard(hostnameTargets(batch.hostname).map { it.id }.toSet() == expected.keys, "TARGET_CHANGED",
            "Assets matching the hostname changed during recovery")
        val bindings = identities.findByAssetIdIn(expected.keys.toList())
        // Other AIDs are part of the explicitly authorized hostname cleanup, not a veto.
        CrowdStrikeDeviceReplacementService.requireFreshObservation(bindings, selection, true)
        guard(bindings.none { binding -> binding.falconLastSeenAt?.let { prior ->
            selection.selected.lastSeen == null || prior > java.time.LocalDateTime.ofInstant(selection.selected.lastSeen, java.time.ZoneOffset.UTC)
        } == true }, "STALE_IDENTITY_OBSERVATION", "Stored Falcon contact is newer than the cleanup observation")
        val bindingIds = bindings.groupBy { it.asset.id }.mapValues { (_, rows) -> rows.map { it.crowdStrikeAid }.toSet() }
        // Validate every target before deleting the first one. The outer transaction also
        // rolls back all deletions and audit rows if any cascade or replacement save fails.
        locked.forEach { asset ->
            val id = requireNotNull(asset.id)
            guard(demands.findByExistingAssetId(id).isEmpty() && assessments.findAllByInvolvedAssetId(id).isEmpty() &&
                risks.findByAssetId(id).isEmpty(), "PROTECTED_REFERENCES", "Hostname cleanup target has protected asset references")
        }
        locked.forEach { target ->
            val id = requireNotNull(target.id)
            deletion.deleteAsset(id, username, forceTimeout = false, cleanupEligibility = { asset ->
                guard(matchesHostname(asset, batch.hostname) && versions.getValue(id) == (asset.updatedAt to asset.cloudInstanceId),
                    "TARGET_CHANGED", "Hostname cleanup target metadata changed")
                guard(identities.findByAssetIdIn(listOf(id)).map { it.crowdStrikeAid }.toSet() == bindingIds[id].orEmpty(),
                    "TARGET_CHANGED", "Hostname cleanup target bindings changed")
                guard(demands.findByExistingAssetId(id).isEmpty() && assessments.findAllByInvolvedAssetId(id).isEmpty() &&
                    risks.findByAssetId(id).isEmpty(), "PROTECTED_REFERENCES", "Hostname cleanup target has protected asset references")
                true
            })
        }
        guard(hostnameTargets(batch.hostname).isEmpty(), "TARGET_CHANGED", "Another hostname match appeared during cleanup")
        return locked.map { requireNotNull(it.id) }
    }

    private fun hostnameTargets(hostname: String): List<Asset> {
        val page = assets.findCrowdStrikeReplacementCandidates(hostname, Pageable.from(0, 102))
        guard(page.totalSize <= 101, "TOO_MANY_HOSTNAME_MATCHES", "Hostname cleanup exceeds the import candidate limit")
        return page.content
    }

    private fun matchesHostname(asset: Asset, hostname: String) =
        (asset.crowdStrikeHostname ?: asset.name).equals(hostname, ignoreCase = true)

    private fun guard(allowed: Boolean, reason: String, message: String) {
        if (!allowed) throw CrowdStrikeRecoveryBlocked(reason, message)
    }

    companion object {
        fun blockedReason(batch: CrowdStrikeVulnerabilityBatchDto, conflict: CrowdStrikeIdentityConflict): String? = when {
            batch.deviceSelection == null -> "MISSING_DEVICE_SELECTION"
            !batch.deviceSelection.selected.hostname.trim().equals(batch.hostname.trim(), ignoreCase = true) -> "HOSTNAME_MISMATCH"
            conflict.code !in setOf("CLOUD_ACCOUNT_CONFLICT", "AD_DOMAIN_CONFLICT", "ENROLLMENT_MISMATCH",
                "INCOMPLETE_IDENTITY", "UNEXPLAINED_AID", "INSTANCE_IDENTITY_CONFLICT", "INSTANCE_ALREADY_BOUND",
                "DUPLICATE_ASSET_CONFLICT", "SUPERSEDED_IDENTITY_CONFLICT") -> conflict.code
            batch.findingCoverage?.complete != true || batch.findingCoverage.invalid != 0 -> "INCOMPLETE_SNAPSHOT"
            else -> null
        }

        fun canRecover(batch: CrowdStrikeVulnerabilityBatchDto, conflict: CrowdStrikeIdentityConflict): Boolean =
            blockedReason(batch, conflict) == null
    }
}
