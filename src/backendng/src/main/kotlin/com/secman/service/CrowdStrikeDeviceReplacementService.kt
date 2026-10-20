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

    data class Replacement(val asset: Asset?, val duplicates: List<Asset>, val snapshotChanged: Boolean,
        val formerInstanceId: String? = null,
        val relocatedBindings: List<CrowdStrikeAssetIdentity> = emptyList())

    /** Includes transaction completion, so concurrent first imports cannot create two assets. */
    fun <T> withSelectionLocks(selection: CrowdStrikeDeviceSelection?, action: () -> T): T =
        withSelectionLocks(selection, null, action)

    fun <T> withSelectionLocks(selection: CrowdStrikeDeviceSelection?, hostname: String?, action: () -> T): T {
        val records = selection?.let { listOf(it.selected) + it.superseded }.orEmpty()
        val identityKeys = records.flatMap { device ->
            val scope = device.hostnameScope()
            listOf(listOf("hostname") + scope, listOf("aid", device.aid)) + listOfNotNull(device.instanceId?.takeIf(String::isNotBlank)?.let {
                listOf("instance", it.trim().lowercase(), scope.last())
            })
        }
        // Recovery resolves by hostname across all accounts/domains. Every import,
        // including legacy payloads, must share this lock for the entire recovery loop.
        val hostnameKeys = (records.map { it.hostname } + listOfNotNull(hostname)).map {
            listOf("hostname-global", it.trim().lowercase(java.util.Locale.ROOT))
        }
        val stripes = (identityKeys + hostnameKeys).map { Math.floorMod(it.hashCode(), importLocks.size) }.distinct().sorted()
        fun locked(index: Int): T = if (index == stripes.size) action() else
            synchronized(importLocks[stripes[index]]) { locked(index + 1) }
        return locked(0)
    }

    fun prepare(batch: CrowdStrikeVulnerabilityBatchDto, selection: CrowdStrikeDeviceSelection): Replacement {
        lockIdentity(selection)
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
        // Identity-key locks serialize competing claims. Discover owners without locking
        // the scan, then lock only their primary keys; a scan with FOR UPDATE also locks
        // unrelated assets and deadlocks parallel imports that already hold those rows.
        val instanceCandidates = records.filter { !it.instanceId.isNullOrBlank() && !it.cloudAccountId.isNullOrBlank() }
            .flatMap { device ->
                val ownerIds = entityManager.createNativeQuery("""
                    SELECT id FROM asset WHERE cloud_instance_id = :instance
                        AND cloud_account_id = :account ORDER BY id LIMIT 102
                """).setParameter("instance", device.instanceId).setParameter("account", device.cloudAccountId).resultList
                require(ownerIds.size <= 101) { "Ambiguous stored cloud instance" }
                ownerIds.map { id ->
                    val owner = entityManager.find(Asset::class.java, (id as Number).toLong(), LockModeType.PESSIMISTIC_WRITE)
                        ?: error("Cloud instance owner disappeared")
                    entityManager.refresh(owner)
                    owner
                }
            }.distinctBy { it.id }
        val knownWinner = identities.firstOrNull { it.crowdStrikeAid == selected.aid }
        // A superseded enrollment can now report a different instance than its old
        // inventory row. Retire that binding, never merge the two physical assets.
        val relocatedBindings = identities.filter { binding ->
            val device = selection.superseded.firstOrNull { it.aid == binding.crowdStrikeAid }
            device != null && !binding.asset.cloudInstanceId.isNullOrBlank() &&
                !binding.asset.cloudInstanceId.equals(device.instanceId, ignoreCase = true)
        }
        relocatedBindings.forEach { binding ->
            val device = selection.superseded.first { it.aid == binding.crowdStrikeAid }
            val asset = binding.asset
            val firstSeen = device.firstSeen?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
            val lastSeen = device.lastSeen?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
            val observed = LocalDateTime.ofInstant(selection.observedAt, ZoneOffset.UTC)
            if (device.instanceId?.matches(Regex("i-(?:[0-9a-f]{8}|[0-9a-f]{17})")) != true ||
                asset.cloudAccountId.isNullOrBlank() || device.cloudAccountId.isNullOrBlank() ||
                !asset.cloudAccountId.orEmpty().trim().equals(device.cloudAccountId.orEmpty().trim(), ignoreCase = true) ||
                binding.falconFirstSeenAt == null || binding.falconFirstSeenAt != firstSeen || lastSeen == null ||
                binding.falconLastSeenAt?.let { it > lastSeen } == true ||
                binding.identityObservedAt?.let { it >= observed } == true) {
                throw CrowdStrikeIdentityConflict("SUPERSEDED_IDENTITY_CONFLICT",
                    "Superseded Falcon enrollment needs verified account, enrollment and newer observation",
                    asset.id, asset.cloudInstanceId, device.instanceId)
            }
        }
        val relocatedAssetIds = relocatedBindings.map { it.asset.id }.toSet()
        if (relocatedAssetIds.isNotEmpty() && identityRepository.findByAssetIdIn(relocatedAssetIds.map { requireNotNull(it) })
                .any { it.crowdStrikeAid !in relocatedBindings.map { binding -> binding.crowdStrikeAid } }) {
            throw CrowdStrikeIdentityConflict("UNEXPLAINED_AID", "Stored asset has an unverified Falcon device identity")
        }
        val changingInstance = knownWinner?.asset?.let {
            !it.cloudInstanceId.isNullOrBlank() && !it.cloudInstanceId.equals(selected.instanceId, ignoreCase = true)
        } == true
        requireFreshObservation(identities, selection, changingInstance)
        if (changingInstance) {
            val asset = requireNotNull(knownWinner).asset
            fun conflict(code: String, message: String): Nothing = throw CrowdStrikeIdentityConflict(code, message,
                asset.id, asset.cloudInstanceId, selected.instanceId)
            if (selected.instanceId?.matches(Regex("i-(?:[0-9a-f]{8}|[0-9a-f]{17})")) != true)
                conflict("INCOMPLETE_IDENTITY", "A valid incoming EC2 instance ID is required")
            if (selected.lastSeen == null)
                conflict("INCOMPLETE_IDENTITY", "Provider contact time is required for an instance change")
            if (asset.cloudAccountId.isNullOrBlank() || selected.cloudAccountId.isNullOrBlank() ||
                !asset.cloudAccountId.orEmpty().trim().equals(selected.cloudAccountId.orEmpty().trim(), ignoreCase = true))
                conflict("CLOUD_ACCOUNT_CONFLICT", "Falcon device conflicts with stored account or domain")
            if (!asset.adDomain.orEmpty().trim().equals(selected.adDomain.orEmpty().trim(), ignoreCase = true))
                conflict("AD_DOMAIN_CONFLICT", "Falcon device conflicts with stored AD domain")
            if (knownWinner.falconFirstSeenAt == null || knownWinner.falconFirstSeenAt !=
                LocalDateTime.ofInstant(requireNotNull(selected.firstSeen), ZoneOffset.UTC))
                conflict("ENROLLMENT_MISMATCH", "An instance change requires the same verified Falcon enrollment")
            if (instanceCandidates.any { it.id != asset.id })
                conflict("INSTANCE_ALREADY_BOUND", "Incoming EC2 instance already belongs to another asset")
        }
        fun compatible(asset: Asset): Boolean {
            fun compatibleValue(stored: String?, incoming: String?) = stored.isNullOrBlank() ||
                (!incoming.isNullOrBlank() && stored.trim().equals(incoming.trim(), ignoreCase = true))
            val provenIdentity = identities.any { it.asset.id == asset.id }
            val provenInstance = !asset.cloudInstanceId.isNullOrBlank() && !asset.cloudAccountId.isNullOrBlank() &&
                records.any { it.instanceId.equals(asset.cloudInstanceId, ignoreCase = true) &&
                    it.cloudAccountId.equals(asset.cloudAccountId, ignoreCase = true) }
            return (compatibleValue(asset.cloudInstanceId, selected.instanceId) ||
                (changingInstance && knownWinner?.asset?.id == asset.id)) &&
                compatibleValue(asset.cloudAccountId, selected.cloudAccountId) &&
                (compatibleValue(asset.adDomain, selected.adDomain) || provenIdentity || provenInstance)
        }
        // A hostname-only record is not evidence for a separately identified cloud instance.
        // A matching AID still enters through identities and must pass every identity guard.
        // Names discover legacy records but never prove that another AID is this machine.
        val boundHostnameIds = if (hostnameCandidates.isEmpty()) emptySet() else
            identityRepository.findByAssetIdIn(hostnameCandidates.map { requireNotNull(it.id) })
                .map { it.asset.id }.toSet()
        val matchingHostnameCandidates = hostnameCandidates.filter { asset ->
            compatible(asset) && asset.id !in boundHostnameIds &&
                !selected.instanceId.isNullOrBlank() && !selected.cloudAccountId.isNullOrBlank() &&
                asset.cloudInstanceId.equals(selected.instanceId, ignoreCase = true) &&
                asset.cloudAccountId.equals(selected.cloudAccountId, ignoreCase = true)
        }
        val candidates = (identities.filterNot { it in relocatedBindings }.map { it.asset } + matchingHostnameCandidates +
            instanceCandidates.filter(::compatible)).distinctBy { it.id }
        require(candidates.size <= 101) { "Too many stored Falcon replacement candidates" }
        val locked = candidates.sortedBy { it.id }.map { asset ->
            entityManager.find(Asset::class.java, asset.id, LockModeType.PESSIMISTIC_WRITE)
                ?: error("Falcon replacement candidate disappeared")
        }
        require(locked.all(::compatible)) { "Falcon device conflicts with stored account or domain" }
        val current = if (locked.isEmpty()) emptyList() else
            identityRepository.findByAssetIdIn(locked.map { requireNotNull(it.id) })
        if (current.any { it.crowdStrikeAid !in ids }) throw CrowdStrikeIdentityConflict("UNEXPLAINED_AID",
            "Stored asset has an unverified Falcon device identity")
        require(identities.filterNot { it in relocatedBindings }.all { binding -> current.any { it.crowdStrikeAid == binding.crowdStrikeAid && it.asset.id == binding.asset.id } }) {
            "Falcon identity changed while acquiring replacement locks"
        }
        val incomingFirst = LocalDateTime.ofInstant(requireNotNull(selected.firstSeen), ZoneOffset.UTC)
        val storedWinner = current.firstOrNull { it.crowdStrikeAid == selected.aid }
        requireFreshObservation(current, selection, changingInstance)
        if (changingInstance && (current.size != 1 || current.single().crowdStrikeAid != selected.aid))
            throw CrowdStrikeIdentityConflict("UNEXPLAINED_AID", "An instance change requires one current Falcon binding")
        require(current.none { binding ->
            binding.crowdStrikeAid != selected.aid &&
                (binding.falconLastSeenAt ?: LocalDateTime.MIN) >=
                (selected.lastSeen?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) } ?: LocalDateTime.MIN)
        }) { "An older or tied Falcon device cannot replace the current device" }
        require(current.none { binding -> binding.falconLastSeenAt?.let { prior ->
            selected.lastSeen == null || LocalDateTime.ofInstant(selected.lastSeen, ZoneOffset.UTC) < prior
        } == true }) { "An older Falcon device contact cannot overwrite newer data" }
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
            if (bindings.isNotEmpty()) bindings.all { binding -> selection.superseded.any { it.aid == binding.crowdStrikeAid } }
            else duplicate.owner == com.secman.constants.AssetOwners.CROWDSTRIKE_IMPORT &&
                !duplicate.cloudInstanceId.isNullOrBlank() && selection.superseded.any {
                    it.instanceId.equals(duplicate.cloudInstanceId, ignoreCase = true)
                }
        }) { "Duplicate assets do not have proven superseded Falcon identities" }
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
            }), canonical?.cloudInstanceId?.takeIf { changingInstance }, relocatedBindings)
    }

    companion object {
        internal fun requireFreshObservation(
            bindings: List<CrowdStrikeAssetIdentity>,
            selection: CrowdStrikeDeviceSelection,
            changingInstance: Boolean
        ) {
            val selected = selection.selected
            val storedWinner = bindings.firstOrNull { it.crowdStrikeAid == selected.aid }
            val observed = LocalDateTime.ofInstant(selection.observedAt, ZoneOffset.UTC)
            require(!selection.observedAt.isAfter(java.time.Instant.now().plusSeconds(60))) { "Future identity observation" }
            if (bindings.any { it.identityObservedAt?.let { prior -> prior > observed || (changingInstance && prior == observed) } == true } ||
                (changingInstance && storedWinner?.falconLastSeenAt?.let { prior ->
                    selected.lastSeen == null || LocalDateTime.ofInstant(selected.lastSeen, ZoneOffset.UTC) < prior
                } == true)) {
                throw CrowdStrikeIdentityConflict("STALE_IDENTITY_OBSERVATION", "An older Falcon identity observation cannot replace newer data",
                    storedWinner?.asset?.id, storedWinner?.asset?.cloudInstanceId, selected.instanceId)
            }
        }
    }

    internal fun lockIdentity(selection: CrowdStrikeDeviceSelection) {
        val records = listOf(selection.selected) + selection.superseded
        val stored = identityRepository.findByCrowdStrikeAidIn(records.map { it.aid })
        val keys = (records.flatMap { device ->
            listOf("hostname:" + device.hostname.trim().lowercase(), "aid:" + device.aid) +
                listOfNotNull(device.instanceId?.takeIf(String::isNotBlank)?.let {
                    "instance:${it.trim().lowercase()}"
                })
        } + stored.mapNotNull { it.asset.cloudInstanceId?.takeIf(String::isNotBlank)?.let { id -> "instance:${id.trim().lowercase()}" } })
            .map { key -> java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } }.distinct().sorted()
        keys.forEach { key ->
            entityManager.createNativeQuery("INSERT INTO crowdstrike_identity_lock (identity_key) VALUES (:key) ON DUPLICATE KEY UPDATE identity_key = VALUES(identity_key)")
                .setParameter("key", key).executeUpdate()
            entityManager.createNativeQuery("SELECT identity_key FROM crowdstrike_identity_lock WHERE identity_key = :key FOR UPDATE")
                .setParameter("key", key).singleResult
        }
        // The pre-lock lookup can be cached by Hibernate while another import commits.
        stored.sortedBy { it.asset.id }.forEach { binding ->
            val asset = entityManager.find(Asset::class.java, binding.asset.id, LockModeType.PESSIMISTIC_WRITE)
                ?: error("Falcon replacement candidate disappeared")
            entityManager.refresh(asset)
            entityManager.refresh(binding)
        }
    }

    private fun canDeleteDuplicate(asset: Asset): Boolean {
        if (asset.manualCreator != null || asset.scanUploader != null ||
            asset.owner != com.secman.constants.AssetOwners.CROWDSTRIKE_IMPORT || asset.workgroups.isNotEmpty()) return false
        val linked = entityManager.createNativeQuery("""
            SELECT (SELECT COUNT(*) FROM integration_subject WHERE asset_id = :id)
                 + (SELECT COUNT(*) FROM vulnerability WHERE asset_id = :id AND source <> :source)
                 + (SELECT COUNT(*) FROM asset_tag WHERE asset_id = :id)
                 + (SELECT COUNT(*) FROM application_register_asset WHERE asset_id = :id)
                 + (SELECT COUNT(*) FROM risk WHERE asset_id = :id)
                 + (SELECT COUNT(*) FROM risk_assessment WHERE asset_id = :id)
                 + (SELECT COUNT(*) FROM demand WHERE existing_asset_id = :id)
                 + (SELECT COUNT(*) FROM scan_result WHERE asset_id = :id)
        """).setParameter("id", asset.id).setParameter("source", com.secman.constants.VulnerabilitySources.CROWDSTRIKE).singleResult as Number
        return linked.toLong() == 0L
    }

    fun finish(asset: Asset, replacement: Replacement, selection: CrowdStrikeDeviceSelection, actor: String) {
        replacement.relocatedBindings.forEach { binding ->
            val device = selection.superseded.first { it.aid == binding.crowdStrikeAid }
            entityManager.persist(com.secman.domain.CrowdStrikeInstanceTransition(
                assetId = requireNotNull(binding.asset.id), crowdStrikeAid = binding.crowdStrikeAid,
                formerInstanceId = requireNotNull(binding.asset.cloudInstanceId), instanceId = requireNotNull(device.instanceId),
                cloudAccountId = requireNotNull(device.cloudAccountId), adDomain = device.adDomain,
                falconFirstSeenAt = requireNotNull(binding.falconFirstSeenAt),
                falconLastSeenAt = device.lastSeen?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) },
                observedAt = LocalDateTime.ofInstant(selection.observedAt, ZoneOffset.UTC),
                actor = actor.replace('\r', '_').replace('\n', '_').take(255), reason = "SUPERSEDED_ENROLLMENT_MOVED"))
            identityRepository.delete(binding)
        }
        replacement.relocatedBindings.map { it.asset }.distinctBy { it.id }.forEach { retired ->
            // Keep the former machine and all inventory relationships. Its moved agent
            // no longer supplies a current vulnerability or installed-product snapshot.
            entityManager.createNativeQuery("DELETE FROM vulnerability WHERE asset_id = :id AND source = :source")
                .setParameter("id", retired.id).setParameter("source", com.secman.constants.VulnerabilitySources.CROWDSTRIKE).executeUpdate()
            installedProductRepository.deleteByAssetId(requireNotNull(retired.id))
            entityManager.createNativeQuery("UPDATE crowdstrike_snapshot SET active = 0 WHERE asset_id = :id")
                .setParameter("id", retired.id).executeUpdate()
            log.info("Falcon moved enrollment actor={} assetId={} destinationAssetId={} outcome=retired-binding-inventory-preserved",
                actor.replace('\r', '_').replace('\n', '_'), retired.id, asset.id)
        }
        replacement.formerInstanceId?.let { former ->
            val selected = selection.selected
            entityManager.persist(com.secman.domain.CrowdStrikeInstanceTransition(
                assetId = requireNotNull(asset.id), crowdStrikeAid = selected.aid, formerInstanceId = former,
                instanceId = requireNotNull(selected.instanceId), cloudAccountId = requireNotNull(selected.cloudAccountId),
                adDomain = selected.adDomain, falconFirstSeenAt = LocalDateTime.ofInstant(requireNotNull(selected.firstSeen), ZoneOffset.UTC),
                falconLastSeenAt = selected.lastSeen?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) },
                observedAt = LocalDateTime.ofInstant(selection.observedAt, ZoneOffset.UTC),
                actor = actor.replace('\r', '_').replace('\n', '_').take(255)))
            log.info("Falcon instance change actor={} assetId={} formerInstanceId={} instanceId={} outcome=staged-in-import-transaction",
                actor.replace('\r', '_').replace('\n', '_'), asset.id,
                former.replace('\r', '_').replace('\n', '_'), selected.instanceId)
        }
        // Historical evidence is separate from the single current binding used for matching.
        (listOf(selection.selected) + selection.superseded).forEach { device ->
            entityManager.createNativeQuery("""
                INSERT INTO crowdstrike_enrollment_history
                    (crowdstrike_aid, asset_id, hostname, instance_id, cloud_account_id, product_type,
                     falcon_first_seen_at, falcon_last_seen_at, superseded_by, observed_at)
                VALUES (:aid, :assetId, :hostname, :instanceId, :accountId, :productType,
                        :firstSeen, :lastSeen, :supersededBy, UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE asset_id=VALUES(asset_id), hostname=VALUES(hostname),
                    instance_id=VALUES(instance_id), cloud_account_id=VALUES(cloud_account_id),
                    product_type=VALUES(product_type), falcon_first_seen_at=VALUES(falcon_first_seen_at),
                    falcon_last_seen_at=VALUES(falcon_last_seen_at), superseded_by=VALUES(superseded_by),
                    observed_at=VALUES(observed_at)
            """).setParameter("aid", device.aid).setParameter("assetId", asset.id)
                .setParameter("hostname", device.hostname).setParameter("instanceId", device.instanceId)
                .setParameter("accountId", device.cloudAccountId).setParameter("productType", device.productType)
                .setParameter("firstSeen", device.firstSeen?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) })
                .setParameter("lastSeen", device.lastSeen?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) })
                .setParameter("supersededBy", selection.selected.aid.takeIf { it != device.aid })
                .executeUpdate()
        }
        val operationId = UUID.randomUUID().toString()
        replacement.duplicates.forEach { duplicate ->
            identityRepository.findByAssetIdIn(listOf(requireNotNull(duplicate.id))).forEach(identityRepository::delete)
            entityManager.flush()
            if (canDeleteDuplicate(duplicate)) {
                cascadeDeleteService.deleteAsset(requireNotNull(duplicate.id), actor, bulkOperationId = operationId)
            } else {
                entityManager.createNativeQuery("DELETE FROM vulnerability WHERE asset_id = :id AND source = :source")
                    .setParameter("id", duplicate.id).setParameter("source", com.secman.constants.VulnerabilitySources.CROWDSTRIKE).executeUpdate()
                installedProductRepository.deleteByAssetId(requireNotNull(duplicate.id))
                entityManager.createNativeQuery("UPDATE crowdstrike_snapshot SET active = 0 WHERE asset_id = :id")
                    .setParameter("id", duplicate.id).executeUpdate()
                log.info("Falcon duplicate actor={} assetId={} outcome=retired-findings-inventory-preserved",
                    actor.replace('\r', '_').replace('\n', '_'), duplicate.id)
            }
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
        winner.identityObservedAt = LocalDateTime.ofInstant(selection.observedAt, ZoneOffset.UTC)
        winner.falconLastSeenAt = listOfNotNull(winner.falconLastSeenAt,
            selection.selected.lastSeen?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }).maxOrNull()
        identityRepository.save(winner)
        log.info("Falcon replacement actor={} assetId={} selectedAid={} firstSeen={} lastSeen={} retiredAids={} deletedAssetIds={} outcome=staged-in-import-transaction",
            actor.replace('\r', '_').replace('\n', '_'), asset.id, winner.crowdStrikeAid,
            winner.falconFirstSeenAt, winner.falconLastSeenAt, selection.superseded.map { it.aid },
            replacement.duplicates.map { it.id })
    }
}
