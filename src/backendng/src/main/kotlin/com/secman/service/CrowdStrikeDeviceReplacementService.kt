package com.secman.service

import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import com.secman.crowdstrike.dto.hostnameScope
import com.secman.crowdstrike.dto.selectLatestCrowdStrikeDevices
import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeAssetIdentity
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import com.secman.repository.InstalledProductRepository
import io.micronaut.data.model.Pageable
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Called only inside the ADMIN import's per-host transaction. */
@Singleton
class CrowdStrikeDeviceReplacementService(
    private val assetRepository: AssetRepository,
    private val identityRepository: CrowdStrikeAssetIdentityRepository,
    private val installedProductRepository: InstalledProductRepository,
    private val cascadeDeleteService: AssetCascadeDeleteService,
    private val entityManager: EntityManager
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val importLocks = Array(64) { Any() }

    data class Replacement(val asset: Asset?, val duplicates: List<Asset>, val snapshotChanged: Boolean)

    /** Includes transaction completion, so concurrent first imports cannot create two assets. */
    fun <T> withSelectionLocks(selection: CrowdStrikeDeviceSelection?, action: () -> T): T {
        if (selection == null) return action()
        val stripes = (listOf(selection.selected) + selection.superseded).flatMap { device ->
            val scope = device.hostnameScope()
            listOf(listOf("hostname") + scope) + listOfNotNull(device.instanceId?.takeIf(String::isNotBlank)?.let {
                listOf("instance", it.trim().lowercase(), scope.last())
            })
        }.map { Math.floorMod(it.hashCode(), importLocks.size) }.distinct().sorted()
        fun locked(index: Int): T = if (index == stripes.size) action() else
            synchronized(importLocks[stripes[index]]) { locked(index + 1) }
        return locked(0)
    }

    fun prepare(batch: CrowdStrikeVulnerabilityBatchDto, selection: CrowdStrikeDeviceSelection): Replacement {
        val records = listOf(selection.selected) + selection.superseded
        val verified = selectLatestCrowdStrikeDevices(records).singleOrNull()
        require(verified != null && verified.selected == selection.selected && verified.superseded.toSet() == selection.superseded.toSet()) {
            "Invalid latest Falcon device selection"
        }
        val selected = selection.selected
        require(batch.crowdStrikeAids == setOf(selected.aid) &&
            listOf(batch.hostname, batch.adDomain.orEmpty(), batch.cloudAccountId.orEmpty())
                .map { it.trim().lowercase() } == selected.hostnameScope() &&
            batch.cloudInstanceId.orEmpty().trim().equals(selected.instanceId.orEmpty().trim(), ignoreCase = true)) {
            "Import metadata does not match selected Falcon device"
        }
        val ids = records.map { it.aid }.toSet()
        val identities = identityRepository.findByCrowdStrikeAidIn(ids)
        val hostnameCandidates = records.map { it.hostname.trim().lowercase() }.distinct().flatMap { hostname ->
            val page = assetRepository.findCrowdStrikeReplacementCandidates(hostname, Pageable.from(0, 102))
            require(page.totalSize <= 101) { "Ambiguous stored Falcon hostname" }
            page.content
        }
        val instanceCandidates = records.mapNotNull { it.instanceId?.trim()?.takeIf(String::isNotBlank) }
            .distinctBy(String::lowercase).mapNotNull(assetRepository::findByCloudInstanceIdIgnoreCase)
        fun compatible(asset: Asset): Boolean {
            fun compatibleValue(stored: String?, incoming: String?) = stored.isNullOrBlank() ||
                (!incoming.isNullOrBlank() && stored.trim().equals(incoming.trim(), ignoreCase = true))
            val provenIdentity = identities.any { it.asset.id == asset.id }
            val provenInstance = !asset.cloudInstanceId.isNullOrBlank() && !asset.cloudAccountId.isNullOrBlank() &&
                records.any { it.instanceId.equals(asset.cloudInstanceId, ignoreCase = true) &&
                    it.cloudAccountId.equals(asset.cloudAccountId, ignoreCase = true) }
            return compatibleValue(asset.cloudAccountId, selected.cloudAccountId) &&
                (compatibleValue(asset.adDomain, selected.adDomain) || provenIdentity || provenInstance)
        }
        val candidates = (identities.map { it.asset } + hostnameCandidates.filter(::compatible) +
            instanceCandidates.filter(::compatible)).distinctBy { it.id }
        require(candidates.size <= 101) { "Too many stored Falcon replacement candidates" }
        val locked = candidates.sortedBy { it.id }.map { asset ->
            entityManager.find(Asset::class.java, asset.id, LockModeType.PESSIMISTIC_WRITE)
                ?: error("Falcon replacement candidate disappeared")
        }
        require(locked.all(::compatible)) { "Falcon device conflicts with stored account or domain" }
        val current = if (locked.isEmpty()) emptyList() else
            identityRepository.findByAssetIdIn(locked.map { requireNotNull(it.id) })
        require(current.all { it.crowdStrikeAid in ids }) { "Stored asset has an unverified Falcon device identity" }
        require(identities.all { binding -> current.any { it.crowdStrikeAid == binding.crowdStrikeAid && it.asset.id == binding.asset.id } }) {
            "Falcon identity changed while acquiring replacement locks"
        }
        val newestStored = current.maxOfOrNull { it.falconFirstSeenAt ?: LocalDateTime.MIN }
        val incomingFirst = LocalDateTime.ofInstant(requireNotNull(selected.firstSeen), ZoneOffset.UTC)
        require(newestStored == null || incomingFirst >= newestStored) { "An older Falcon enrollment cannot replace the current device" }
        val storedWinner = current.firstOrNull { it.crowdStrikeAid == selected.aid }
        require(current.none { binding ->
            binding.crowdStrikeAid != selected.aid && binding.falconFirstSeenAt == incomingFirst &&
                (binding.falconLastSeenAt ?: LocalDateTime.MIN) >=
                (selected.lastSeen?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) } ?: LocalDateTime.MIN)
        }) { "An older or tied Falcon device cannot replace the current device" }
        if (storedWinner?.falconFirstSeenAt != null) {
            require(storedWinner.falconFirstSeenAt == incomingFirst) { "Falcon enrollment timestamp changed for an existing device" }
        }
        val canonical = storedWinner?.asset?.let { winner -> locked.first { it.id == winner.id } }
            ?: locked.singleOrNull()
            ?: locked.firstOrNull { it.cloudInstanceId.equals(selected.instanceId, ignoreCase = true) }
            ?: locked.firstOrNull()
        val duplicates = locked.filter { it.id != canonical?.id }
        require(duplicates.all { duplicate ->
            val bindings = current.filter { it.asset.id == duplicate.id }
            val matchingRetired = selection.superseded.filter { old ->
                (duplicate.crowdStrikeHostname ?: duplicate.name).trim().equals(old.hostname.trim(), ignoreCase = true)
            }
            val proven = if (bindings.isNotEmpty()) {
                bindings.all { binding -> matchingRetired.any { it.aid == binding.crowdStrikeAid } }
            } else {
                duplicate.owner == com.secman.constants.AssetOwners.CROWDSTRIKE_IMPORT &&
                    !duplicate.cloudInstanceId.isNullOrBlank() && matchingRetired.any {
                        it.instanceId.equals(duplicate.cloudInstanceId, ignoreCase = true)
                    }
            }
            proven &&
                duplicate.manualCreator == null && duplicate.scanUploader == null &&
                matchingRetired.isNotEmpty()
        }) { "Duplicate assets do not have proven superseded Falcon identities" }
        if (duplicates.isNotEmpty()) {
            val subjectCount = entityManager.createQuery(
                "SELECT COUNT(s) FROM IntegrationSubject s WHERE s.assetId IN :ids", Long::class.javaObjectType
            ).setParameter("ids", duplicates.map { it.id }).singleResult.toLong()
            require(subjectCount == 0L) { "Superseded asset has integration bindings; replacement requires review" }
        }
        if (canonical != null && !canonical.cloudInstanceId.isNullOrBlank() &&
            !canonical.cloudInstanceId.equals(selected.instanceId, ignoreCase = true)) {
            require(!selected.instanceId.isNullOrBlank()) { "Missing replacement cloud instance metadata" }
            require(storedWinner?.asset?.id == canonical.id ||
                current.any { it.asset.id == canonical.id && it.crowdStrikeAid in selection.superseded.map { old -> old.aid } } ||
                selection.superseded.any { !it.instanceId.isNullOrBlank() && it.instanceId.equals(canonical.cloudInstanceId, ignoreCase = true) }) {
                "Unverified cloud instance replacement"
            }
        }
        return Replacement(canonical, duplicates, canonical != null &&
            (current.filter { it.asset.id == canonical.id }.let { bindings ->
                bindings.size != 1 || bindings.singleOrNull()?.crowdStrikeAid != selected.aid ||
                    bindings.singleOrNull()?.falconFirstSeenAt == null
            }))
    }

    fun finish(asset: Asset, replacement: Replacement, selection: CrowdStrikeDeviceSelection, actor: String) {
        val operationId = UUID.randomUUID().toString()
        replacement.duplicates.forEach { duplicate ->
            identityRepository.findByAssetIdIn(listOf(requireNotNull(duplicate.id))).forEach(identityRepository::delete)
            entityManager.flush()
            cascadeDeleteService.deleteAsset(requireNotNull(duplicate.id), actor, bulkOperationId = operationId)
        }
        if (replacement.snapshotChanged) installedProductRepository.deleteByAssetId(requireNotNull(asset.id))
        val bindings = identityRepository.findByAssetIdIn(listOf(requireNotNull(asset.id)))
        bindings.filter { it.crowdStrikeAid != selection.selected.aid }.forEach(identityRepository::delete)
        val now = LocalDateTime.now()
        val winner = bindings.firstOrNull { it.crowdStrikeAid == selection.selected.aid }
            ?: CrowdStrikeAssetIdentity(asset = asset, crowdStrikeAid = selection.selected.aid,
                sourceHostname = selection.selected.hostname)
        winner.sourceHostname = selection.selected.hostname
        winner.lastSeenAt = now
        winner.falconFirstSeenAt = LocalDateTime.ofInstant(requireNotNull(selection.selected.firstSeen), ZoneOffset.UTC)
        winner.falconLastSeenAt = listOfNotNull(winner.falconLastSeenAt,
            selection.selected.lastSeen?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }).maxOrNull()
        identityRepository.save(winner)
        log.info("Falcon replacement actor={} assetId={} selectedAid={} firstSeen={} lastSeen={} retiredAids={} deletedAssetIds={} outcome=staged-in-import-transaction",
            actor.replace('\r', '_').replace('\n', '_'), asset.id, winner.crowdStrikeAid,
            winner.falconFirstSeenAt, winner.falconLastSeenAt, selection.superseded.map { it.aid },
            replacement.duplicates.map { it.id })
    }
}
