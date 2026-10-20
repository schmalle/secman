package com.secman.service

import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import com.secman.repository.DemandRepository
import com.secman.repository.RiskAssessmentRepository
import com.secman.repository.RiskRepository
import io.micronaut.data.model.Pageable
import jakarta.inject.Singleton

/** Deletes only the exact stored conflict identified by an authorized import. */
@Singleton
class CrowdStrikeImportRecoveryService(
    private val assets: AssetRepository,
    private val identities: CrowdStrikeAssetIdentityRepository,
    private val demands: DemandRepository,
    private val assessments: RiskAssessmentRepository,
    private val risks: RiskRepository,
    private val deletion: AssetCascadeDeleteService
) {
    fun recover(batch: CrowdStrikeVulnerabilityBatchDto, conflict: CrowdStrikeIdentityConflict, username: String) {
        require(canRecover(batch, conflict)) { "Import rejection cannot be recovered by deleting an asset" }
        val id = requireNotNull(conflict.assetId)
        val selected = requireNotNull(batch.deviceSelection).selected
        val owners = identities.findByCrowdStrikeAidIn(listOf(selected.aid))
        val provenOwner = owners.singleOrNull()?.asset?.id == id
        guard(owners.isEmpty() || provenOwner, "TARGET_CHANGED", "Selected Falcon device changed its stored asset binding")
        val target = assets.findById(id).orElseThrow { IllegalStateException("Recovery target no longer exists") }
        guard(provenOwner || uniqueHostnameTarget(batch.hostname, id), "AMBIGUOUS_TARGET",
            "Recovery requires a proven AID owner or one exact hostname target")
        guard(target.updatedAt == conflict.assetUpdatedAt, "TARGET_CHANGED", "Recovery target changed after import rejection")
        val expectedUpdatedAt = conflict.assetUpdatedAt
        val aids = batch.crowdStrikeAids + selected.aid
        val bindings = identities.findByAssetIdIn(listOf(id))
        guard(bindings.all { it.crowdStrikeAid in aids }, "UNEXPLAINED_AID",
            "Recovery cannot delete an asset with another Falcon device binding")
        CrowdStrikeDeviceReplacementService.requireFreshObservation(bindings, requireNotNull(batch.deviceSelection), true)
        // Use the normal cascade service, with its timeout and audit behavior. Recheck
        // hostname, instance and protected references under its pessimistic asset lock.
        deletion.deleteAsset(id, username, forceTimeout = false, cleanupEligibility = { asset ->
            guard((asset.crowdStrikeHostname ?: asset.name).equals(batch.hostname, ignoreCase = true) &&
                asset.cloudInstanceId == conflict.formerInstanceId && asset.updatedAt == expectedUpdatedAt,
                "TARGET_CHANGED", "Recovery target metadata changed")
            guard(if (provenOwner) identities.findByCrowdStrikeAidIn(listOf(selected.aid)).singleOrNull()?.asset?.id == id
                else uniqueHostnameTarget(batch.hostname, id), "TARGET_CHANGED", "Recovery target ownership changed")
            guard(stillConflicts(asset, batch, conflict.code), "CONFLICT_CHANGED", "Original identity conflict no longer applies")
            guard(demands.findByExistingAssetId(id).isEmpty() && assessments.findAllByInvolvedAssetId(id).isEmpty() &&
                risks.findByAssetId(id).isEmpty(), "PROTECTED_REFERENCES", "Recovery target has protected asset references")
            guard(identities.findByAssetIdIn(listOf(id)).all { it.crowdStrikeAid in aids },
                "UNEXPLAINED_AID", "Recovery target has another Falcon device binding")
            true
        })
    }

    private fun guard(allowed: Boolean, reason: String, message: String) {
        if (!allowed) throw CrowdStrikeRecoveryBlocked(reason, message)
    }

    private fun uniqueHostnameTarget(hostname: String, id: Long): Boolean =
        assets.findCrowdStrikeReplacementCandidates(hostname, Pageable.from(0, 2)).let {
            it.totalSize == 1L && it.content.singleOrNull()?.id == id
        }

    private fun stillConflicts(asset: com.secman.domain.Asset, batch: CrowdStrikeVulnerabilityBatchDto, code: String): Boolean {
        val selection = requireNotNull(batch.deviceSelection)
        val selected = selection.selected
        val bindings = identities.findByAssetIdIn(listOf(requireNotNull(asset.id)))
        CrowdStrikeDeviceReplacementService.requireFreshObservation(bindings, selection, true)
        return when (code) {
            "CLOUD_ACCOUNT_CONFLICT" -> asset.cloudAccountId.isNullOrBlank() ||
                !asset.cloudAccountId.orEmpty().trim().equals(selected.cloudAccountId.orEmpty().trim(), ignoreCase = true)
            "AD_DOMAIN_CONFLICT" -> !asset.adDomain.orEmpty().trim().equals(selected.adDomain.orEmpty().trim(), ignoreCase = true)
            "ENROLLMENT_MISMATCH" -> bindings.singleOrNull { it.crowdStrikeAid == selected.aid }?.let { binding ->
                    binding.falconFirstSeenAt == null || selected.firstSeen == null ||
                        binding.falconFirstSeenAt != java.time.LocalDateTime.ofInstant(selected.firstSeen, java.time.ZoneOffset.UTC)
                } == true
            else -> false
        }
    }

    companion object {
        fun blockedReason(batch: CrowdStrikeVulnerabilityBatchDto, conflict: CrowdStrikeIdentityConflict): String? = when {
            conflict.assetId == null -> "MISSING_CONFLICT_ASSET"
            batch.deviceSelection == null -> "MISSING_DEVICE_SELECTION"
            conflict.code !in setOf("CLOUD_ACCOUNT_CONFLICT", "AD_DOMAIN_CONFLICT", "ENROLLMENT_MISMATCH") -> conflict.code
            conflict.code == "CLOUD_ACCOUNT_CONFLICT" && batch.deviceSelection.selected.cloudAccountId.isNullOrBlank() -> "MISSING_CLOUD_ACCOUNT"
            conflict.code == "AD_DOMAIN_CONFLICT" && batch.deviceSelection.selected.adDomain.isNullOrBlank() -> "MISSING_AD_DOMAIN"
            conflict.code == "ENROLLMENT_MISMATCH" && batch.deviceSelection.selected.firstSeen == null -> "MISSING_ENROLLMENT"
            batch.findingCoverage?.complete != true || batch.findingCoverage.invalid != 0 -> "INCOMPLETE_SNAPSHOT"
            else -> null
        }

        fun canRecover(batch: CrowdStrikeVulnerabilityBatchDto, conflict: CrowdStrikeIdentityConflict): Boolean =
            blockedReason(batch, conflict) == null
    }
}
