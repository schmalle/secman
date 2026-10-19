package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeIdentityRepair
import com.secman.dto.*
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import com.secman.repository.InstalledProductRepository
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** No network calls here. Every device refresh joins this transaction; any failure restores all bindings and rows. */
@Singleton
open class CrowdStrikeIdentityRepairWriter(
    private val em: EntityManager,
    private val identities: CrowdStrikeAssetIdentityRepository,
    private val products: InstalledProductRepository,
    private val replacements: CrowdStrikeDeviceReplacementService,
    private val importer: CrowdStrikeVulnerabilityImportService,
    private val requests: CrowdStrikeRepairRequestStore
) {
    @Transactional
    open fun preview(request: CrowdStrikeIdentityReconcileRequest, batches: List<CrowdStrikeVulnerabilityBatchDto>,
        expected: LegacyCrowdStrikeAssetState): CrowdStrikeIdentityReconcileResponse {
        validate(request, batches, expected, false)
        return response(request, batches, true)
    }

    @Transactional
    open fun repair(request: CrowdStrikeIdentityReconcileRequest, batches: List<CrowdStrikeVulnerabilityBatchDto>,
        expected: LegacyCrowdStrikeAssetState, actor: String): CrowdStrikeIdentityReconcileResponse {
        // Same database identity locks as normal import, including the former instance.
        batches.sortedBy { it.deviceSelection!!.selected.aid }.forEach { replacements.lockIdentity(requireNotNull(it.deviceSelection)) }
        validate(request, batches, expected, true)
        identities.findByAssetIdIn(listOf(request.assetId)).forEach(identities::delete)
        em.flush()
        products.deleteByAssetId(request.assetId)
        val operationId = request.requestId ?: UUID.randomUUID().toString()
        // Refresh the retained machine first. All legacy CrowdStrike severities are replaced;
        // manual findings and every existing asset relationship stay on that machine.
        batches.sortedBy { if (it.cloudInstanceId == expected.instance) 0 else 1 }.forEach { batch ->
            val result = importer.importVulnerabilitiesForServer(batch, actor, true)
            require(result.outcome?.status in setOf("CREATED", "UPDATED")) { "Repair snapshot was not committed" }
            val targetId = requireNotNull(result.outcome?.assetId)
            val device = requireNotNull(batch.deviceSelection).selected
            em.persist(CrowdStrikeIdentityRepair(operationId = operationId, sourceAssetId = request.assetId,
                targetAssetId = targetId, crowdstrikeAid = device.aid, instanceId = requireNotNull(device.instanceId),
                accountId = requireNotNull(device.cloudAccountId),
                falconFirstSeenAt = LocalDateTime.ofInstant(requireNotNull(device.firstSeen), ZoneOffset.UTC),
                falconLastSeenAt = LocalDateTime.ofInstant(requireNotNull(device.lastSeen), ZoneOffset.UTC),
                actor = actor.replace('\r', '_').replace('\n', '_').take(255), reason = request.reason.trim()))
        }
        em.flush()
        val result = response(request, batches, false).copy(findingsFetched = true, requestId = request.requestId)
        request.requestId?.let { requests.succeed(it, result) }
        return result
    }

    private fun validate(request: CrowdStrikeIdentityReconcileRequest, batches: List<CrowdStrikeVulnerabilityBatchDto>,
        expected: LegacyCrowdStrikeAssetState, lock: Boolean) {
        val source = if (lock) em.find(Asset::class.java, request.assetId, LockModeType.PESSIMISTIC_WRITE)
            else em.find(Asset::class.java, request.assetId)
        if (source == null) throw CrowdStrikeIdentityConflict("SOURCE_CHANGED", "Source asset disappeared; discover again", request.assetId)
        em.refresh(source)
        if (LegacyCrowdStrikeAssetState(source.cloudAccountId, source.cloudInstanceId, source.updatedAt) != expected)
            throw CrowdStrikeIdentityConflict("SOURCE_CHANGED", "Stored account or instance changed during Falcon fetch; preview again", request.assetId)
        val bound = identities.findByAssetIdIn(listOf(request.assetId))
        bound.forEach(em::refresh)
        if (!(bound.map { it.crowdStrikeAid }.toSet() == request.expectedAids && bound.all {
            it.falconFirstSeenAt == null && it.falconLastSeenAt == null && it.identityObservedAt == null
        })) throw CrowdStrikeIdentityConflict("BINDINGS_CHANGED", "Stored AID bindings changed during Falcon fetch; preview again", request.assetId)
        require(batches.map { it.deviceSelection?.selected?.aid }.toSet() == request.expectedAids &&
            batches.size == request.expectedAids.size && batches.map { it.cloudInstanceId }.distinct().size == batches.size &&
            batches.count { it.cloudInstanceId == expected.instance } == 1 &&
            batches.all { it.cloudAccountId == expected.account }) { "Invalid identity split" }
        batches.forEach { batch ->
            val owners = owners(batch)
            if (owners.size > 1) throw CrowdStrikeIdentityConflict("DESTINATION_AMBIGUOUS", "Incoming instance has multiple stored owners", request.assetId,
                instanceId = batch.cloudInstanceId, aid = batch.deviceSelection?.selected?.aid)
            owners.forEach { id ->
                if (lock) em.find(Asset::class.java, id, LockModeType.PESSIMISTIC_WRITE)
                if (id != request.assetId && identities.findByAssetIdIn(listOf(id)).isNotEmpty())
                    throw CrowdStrikeIdentityConflict("DESTINATION_ALREADY_BOUND", "Destination already has Falcon bindings; separate review is required", request.assetId,
                        instanceId = batch.cloudInstanceId, aid = batch.deviceSelection?.selected?.aid)
            }
        }
    }

    private fun owners(batch: CrowdStrikeVulnerabilityBatchDto): List<Long> = em.createNativeQuery("""
        SELECT id FROM asset WHERE cloud_instance_id = :instance AND cloud_account_id = :account ORDER BY id LIMIT 2
    """).setParameter("instance", batch.cloudInstanceId).setParameter("account", batch.cloudAccountId)
        .resultList.map { (it as Number).toLong() }

    private fun response(request: CrowdStrikeIdentityReconcileRequest, batches: List<CrowdStrikeVulnerabilityBatchDto>,
        dryRun: Boolean) = CrowdStrikeIdentityReconcileResponse(request.assetId, dryRun, batches.map { batch ->
        CrowdStrikeReconciledDevice(requireNotNull(batch.deviceSelection).selected.aid, requireNotNull(batch.cloudInstanceId),
            requireNotNull(batch.cloudAccountId), owners(batch).singleOrNull(), batch.vulnerabilities.size)
    })
}
