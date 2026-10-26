package com.secman.service

import com.secman.constants.AssetOwners
import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
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
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * ADMIN opt-in cleanup of a failed hostname and its verified selected-AID owner.
 *
 * Under `identity-v3` ([recover]'s `includeSelectedOwner`) the scope also covers stale
 * assets that hold the selected EC2 instance in the selected account under another name:
 * the renamed-host or reinstalled-sensor case the normal import rejects with
 * `UNEXPLAINED_AID` or `DUPLICATE_ASSET_CONFLICT`. See [instanceOwnerTargets] for what
 * counts as stale; a holder that might be a live machine is reported, never deleted.
 */
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
    /**
     * Delete every in-scope asset for [batch] inside the caller's transaction and return their IDs.
     * [includeSelectedOwner] is the `identity-v3` contract: it adds the verified selected-AID owner
     * and stale holders of the selected EC2 instance. Any guard failure throws
     * [CrowdStrikeRecoveryBlocked] before the first deletion.
     */
    fun recover(batch: CrowdStrikeVulnerabilityBatchDto, conflict: CrowdStrikeIdentityConflict, username: String, includeSelectedOwner: Boolean = false): List<Long> {
        require(canRecover(batch, conflict)) { "Import rejection cannot be recovered by hostname cleanup" }
        val selection = requireNotNull(batch.deviceSelection)
        val selectionAids = (listOf(selection.selected) + selection.superseded).map { it.aid }
        val outsideBindings = identities.findByCrowdStrikeAidIn(selectionAids).filter {
            !matchesHostname(it.asset, batch.hostname)
        }
        val outsideOwner = outsideBindings.singleOrNull { it.crowdStrikeAid == selection.selected.aid }
        guard(outsideOwner == null || includeSelectedOwner,
            "CONFLICT_OUTSIDE_HOSTNAME", "Selected Falcon identity belongs outside the hostname cleanup scope")
        val hostnameCandidates = hostnameTargets(batch.hostname)
        val instanceOwners = if (includeSelectedOwner) instanceOwnerTargets(batch.hostname, selection.selected) else InstanceOwners.NONE
        val instanceOwnerIds = instanceOwners.removable.map { requireNotNull(it.id) }.toSet()
        val candidates = (hostnameCandidates + listOfNotNull(outsideOwner?.asset) + instanceOwners.removable).distinctBy { it.id }
        guard(candidates.size <= 101, "TOO_MANY_HOSTNAME_MATCHES", "Recovery exceeds the import candidate limit")
        val outsideId = outsideOwner?.asset?.id
        fun validateOutsideOwner() {
            if (outsideId == null) return
            val owned = identities.findByAssetIdIn(listOf(outsideId))
            guard(owned.size == 1 && owned.single().crowdStrikeAid == selection.selected.aid,
                "UNEXPLAINED_AID", "Selected AID owner asset $outsideId has additional or changed Falcon bindings")
            val binding = owned.single()
            val firstSeen = selection.selected.firstSeen?.let {
                java.time.LocalDateTime.ofInstant(it, java.time.ZoneOffset.UTC)
            }
            guard(firstSeen != null && binding.falconFirstSeenAt == firstSeen && selection.selected.lastSeen != null,
                "UNVERIFIED_SELECTED_OWNER", "Selected AID owner asset $outsideId requires matching enrollment and provider contact")
        }
        validateOutsideOwner()
        if (candidates.isEmpty()) throw noTargets(conflict, includeSelectedOwner, instanceOwners.retained)
        val expected = candidates.associateBy { requireNotNull(it.id) }
        // A superseded binding may have caused the original rejection. It is not a
        // cleanup target and will be excluded from the hostname-scoped retry.
        guard(conflict.assetId == null || conflict.assetId in expected ||
            outsideBindings.any { it.asset.id == conflict.assetId && it.crowdStrikeAid == conflict.aid },
            "CONFLICT_OUTSIDE_HOSTNAME", "Rejected identity belongs to an asset outside the requested hostname")
        conflict.assetId?.takeIf { it in expected }?.let { id ->
            guard(expected.getValue(id).updatedAt == conflict.assetUpdatedAt, "TARGET_CHANGED",
                "Recovery target changed after import rejection")
        }
        val versions = expected.mapValues { (_, asset) ->
            listOf(asset.updatedAt, asset.cloudInstanceId, asset.cloudAccountId, asset.adDomain, asset.crowdStrikeHostname, asset.name)
        }
        fun unchanged(asset: Asset) = versions[asset.id] ==
            listOf(asset.updatedAt, asset.cloudInstanceId, asset.cloudAccountId, asset.adDomain, asset.crowdStrikeHostname, asset.name)
        fun inScope(asset: Asset) = matchesHostname(asset, batch.hostname) || asset.id == outsideId || asset.id in instanceOwnerIds
        val locked = expected.keys.sorted().map { id ->
            val asset = entityManager.find(Asset::class.java, id, LockModeType.PESSIMISTIC_WRITE)
                ?: throw CrowdStrikeRecoveryBlocked("TARGET_CHANGED", "Recovery target no longer exists")
            entityManager.refresh(asset, LockModeType.PESSIMISTIC_WRITE)
            guard(inScope(asset) && unchanged(asset),
                "TARGET_CHANGED", "Recovery target changed while acquiring deletion locks")
            asset
        }
        guard(hostnameTargets(batch.hostname).map { it.id }.toSet() == hostnameCandidates.map { it.id }.toSet(), "TARGET_CHANGED",
            "Assets matching the hostname changed during recovery")
        if (includeSelectedOwner) guard(instanceOwnerTargets(batch.hostname, selection.selected).removable
            .map { requireNotNull(it.id) }.toSet() == instanceOwnerIds, "TARGET_CHANGED", "Assets holding the selected EC2 instance changed during recovery")
        validateOutsideOwner()
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
                guard(inScope(asset) && unchanged(asset),
                    "TARGET_CHANGED", "Hostname cleanup target metadata changed")
                if (id == outsideId) validateOutsideOwner()
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

    /** Instance owners outside the hostname: [removable] ones are targets, [retained] ids are only reported. */
    private data class InstanceOwners(val removable: List<Asset>, val retained: List<Long>) {
        companion object { val NONE = InstanceOwners(emptyList(), emptyList()) }
    }

    /**
     * Assets outside [hostname] that hold the selected device's EC2 instance in its account.
     *
     * A bound holder is removable when every Falcon binding is a different AID whose last
     * provider contact is strictly older than the selected device's. An unbound holder is
     * removable when the CrowdStrike import alone owns it (no manual creator or scan uploader)
     * and its recorded sensor contact, if any, is strictly older; that is the legacy row from before identity bindings, which the
     * import itself would adopt if it were the only one. Everything else, including rows
     * from other sources, may be a live machine and is reported instead of deleted.
     */
    private fun instanceOwnerTargets(hostname: String, selected: CrowdStrikeDeviceRecord): InstanceOwners {
        val instanceId = selected.instanceId?.trim()
        val accountId = selected.cloudAccountId?.trim()
        val lastSeen = selected.lastSeen?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
        if (instanceId == null || !instanceId.matches(EC2_INSTANCE_ID) || accountId.isNullOrEmpty() || lastSeen == null)
            return InstanceOwners.NONE
        val page = assets.findCrowdStrikeInstanceOwners(instanceId, accountId, Pageable.from(0, 102))
        guard(page.totalSize <= 101, "TOO_MANY_INSTANCE_MATCHES", "Instance cleanup exceeds the import candidate limit")
        val owners = page.content.filterNot { matchesHostname(it, hostname) }
        if (owners.isEmpty()) return InstanceOwners.NONE
        val bindings = identities.findByAssetIdIn(owners.map { requireNotNull(it.id) }).groupBy { it.asset.id }
        val (removable, retained) = owners.partition { owner ->
            val bound = bindings[owner.id].orEmpty()
            if (bound.isEmpty()) owner.owner == AssetOwners.CROWDSTRIKE_IMPORT &&
                owner.manualCreator == null && owner.scanUploader == null &&
                owner.crowdStrikeAgentSeenAt?.let { it < lastSeen } != false
            else bound.all { binding ->
                binding.crowdStrikeAid != selected.aid && binding.falconLastSeenAt?.let { it < lastSeen } == true
            }
        }
        return InstanceOwners(removable, retained.map { requireNotNull(it.id) })
    }

    /**
     * Why recovery found nothing to delete. `TARGET_CHANGED` is kept for a rejected asset that
     * has since disappeared; otherwise the blocker lies outside what delete-on-fail may remove.
     */
    private fun noTargets(conflict: CrowdStrikeIdentityConflict, identityScope: Boolean, retained: List<Long>): CrowdStrikeRecoveryBlocked {
        val assetId = conflict.assetId
        if (assetId != null && entityManager.find(Asset::class.java, assetId) == null)
            return CrowdStrikeRecoveryBlocked("TARGET_CHANGED", "Rejected asset $assetId no longer exists; run the import again")
        val scope = if (identityScope) "the failed hostname, the selected Falcon AID or a stale holder of the selected EC2 instance"
            else "the failed hostname"
        val blocker = assetId?.let { " Blocking asset: $it." } ?: ""
        val holders = if (retained.isEmpty()) "" else
            " Assets holding the EC2 instance that may be live and were kept: ${retained.sorted().joinToString(", ")}."
        return CrowdStrikeRecoveryBlocked("CONFLICT_OUTSIDE_RECOVERY_SCOPE",
            "The rejection came from an asset delete-on-fail cannot remove; no asset matches $scope." +
                "$blocker$holders Nothing was deleted")
    }

    private fun matchesHostname(asset: Asset, hostname: String) =
        (asset.crowdStrikeHostname ?: asset.name).equals(hostname, ignoreCase = true)

    private fun guard(allowed: Boolean, reason: String, message: String) {
        if (!allowed) throw CrowdStrikeRecoveryBlocked(reason, message)
    }

    companion object {
        private val EC2_INSTANCE_ID = Regex("i-(?:[0-9a-f]{8}|[0-9a-f]{17})")

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
