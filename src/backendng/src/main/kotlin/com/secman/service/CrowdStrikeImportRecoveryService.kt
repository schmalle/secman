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
        val candidates = assets.findCrowdStrikeReplacementCandidates(batch.hostname, Pageable.from(0, 2))
        check(candidates.totalSize == 1L && candidates.content.singleOrNull()?.id == id) {
            "Recovery requires exactly one hostname match with the rejected asset ID"
        }
        val expectedUpdatedAt = candidates.content.single().updatedAt
        val aids = batch.crowdStrikeAids + listOfNotNull(batch.deviceSelection?.selected?.aid)
        val bindings = identities.findByAssetIdIn(listOf(id))
        check(bindings.all { it.crowdStrikeAid in aids }) {
            "Recovery cannot delete an asset with another Falcon device binding"
        }
        CrowdStrikeDeviceReplacementService.requireFreshObservation(bindings, requireNotNull(batch.deviceSelection), true)
        // Use the normal cascade service, with its timeout and audit behavior. Recheck
        // hostname, instance and protected references under its pessimistic asset lock.
        deletion.deleteAsset(id, username, forceTimeout = false, cleanupEligibility = { asset ->
            (asset.crowdStrikeHostname ?: asset.name).equals(batch.hostname, ignoreCase = true) &&
                asset.cloudInstanceId == conflict.formerInstanceId && asset.updatedAt == expectedUpdatedAt &&
                assets.findCrowdStrikeReplacementCandidates(batch.hostname, Pageable.from(0, 2)).let {
                    it.totalSize == 1L && it.content.singleOrNull()?.id == id
                } && stillConflicts(asset, batch, conflict.code) &&
                demands.findByExistingAssetId(id).isEmpty() &&
                assessments.findAllByInvolvedAssetId(id).isEmpty() &&
                risks.findByAssetId(id).isEmpty() &&
                identities.findByAssetIdIn(listOf(id)).all { it.crowdStrikeAid in aids }
        })
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
        fun canRecover(batch: CrowdStrikeVulnerabilityBatchDto, conflict: CrowdStrikeIdentityConflict): Boolean =
            conflict.assetId != null && batch.deviceSelection != null &&
                (conflict.code != "CLOUD_ACCOUNT_CONFLICT" || !batch.deviceSelection.selected.cloudAccountId.isNullOrBlank()) &&
                (conflict.code != "AD_DOMAIN_CONFLICT" || !batch.deviceSelection.selected.adDomain.isNullOrBlank()) &&
                (conflict.code != "ENROLLMENT_MISMATCH" || batch.deviceSelection.selected.firstSeen != null) &&
                batch.findingCoverage?.complete == true && batch.findingCoverage.invalid == 0 &&
                conflict.code in setOf("CLOUD_ACCOUNT_CONFLICT", "AD_DOMAIN_CONFLICT", "ENROLLMENT_MISMATCH")
    }
}
