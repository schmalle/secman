package com.secman.service

import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.dto.CrowdStrikeFindingCoverage
import com.secman.crowdstrike.dto.selectMostRecentlySeenCrowdStrikeDevice
import com.secman.dto.*
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeAssetIdentityRepository
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Explicit recovery for legacy multi-AID assets. Provider I/O precedes the atomic writer. */
@Singleton
class CrowdStrikeIdentityReconcileService(
    private val client: CrowdStrikeApiClient,
    private val queries: CrowdStrikeQueryService,
    private val assets: AssetRepository,
    private val identities: CrowdStrikeAssetIdentityRepository,
    private val access: AssetFilterService,
    private val writer: CrowdStrikeIdentityRepairWriter,
    private val lease: CrowdStrikeImportRunLease,
    private val retention: CrowdStrikeRetentionPolicy,
    private val refresh: MaterializedViewRefreshService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun reconcile(request: CrowdStrikeIdentityReconcileRequest, auth: Authentication): CrowdStrikeIdentityReconcileResponse {
        if ("ADMIN" !in auth.roles) throw HttpStatusException(HttpStatus.FORBIDDEN, "Identity repair requires ADMIN")
        require(request.assetId > 0 && request.expectedAids.size in 2..20 &&
            request.expectedAids.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } &&
            request.reason.trim().length in 10..255 && request.reason.none(Char::isISOControl)) { "Invalid repair request" }
        if (!access.canAccessAsset(request.assetId, auth)) throw HttpStatusException(HttpStatus.NOT_FOUND, "System not found")
        val asset = assets.findById(request.assetId).orElseThrow { HttpStatusException(HttpStatus.NOT_FOUND, "System not found") }
        val bound = identities.findByAssetIdIn(listOf(request.assetId))
        if (bound.size == 1 && bound.single().let {
                it.falconFirstSeenAt != null && it.falconLastSeenAt != null && it.identityObservedAt != null
            }) throw CrowdStrikeIdentityConflict("ALREADY_RESOLVED", "Source now has one verified Falcon binding", request.assetId)
        require(bound.map { it.crowdStrikeAid }.toSet() == request.expectedAids && bound.all {
            it.falconFirstSeenAt == null && it.falconLastSeenAt == null && it.identityObservedAt == null
        }) { "Only unchanged, unverified legacy multi-AID bindings can be split" }
        require(asset.cloudAccountId?.matches(Regex("[0-9]{12}")) == true && !asset.cloudInstanceId.isNullOrBlank()) {
            "Stored AWS account and instance are required"
        }
        val config = queries.getConfiguration()
        val batches = request.expectedAids.sorted().map { aid ->
            val response = client.queryDeviceVulnerabilities(null, aid, false, config)
            require(response.failedAids.isEmpty() && response.totalCount == response.vulnerabilities.size) { "Incomplete Falcon snapshot" }
            val device = requireNotNull(response.devices.singleOrNull()) { "AID must resolve to exactly one device" }
            val selection = requireNotNull(device.deviceSelection) { "Missing Falcon enrollment evidence" }
            val selected = selection.selected
            require(selected.aid == aid && selection.superseded.isEmpty() && device.crowdStrikeAid == aid &&
                selected.instanceId?.matches(Regex("i-(?:[0-9a-f]{8}|[0-9a-f]{17})")) == true &&
                selected.cloudAccountId == asset.cloudAccountId && retention.acceptsImport(selected.lastSeen)) {
                "Falcon identity is incomplete or conflicts with the stored AWS account"
            }
            selectMostRecentlySeenCrowdStrikeDevice(listOf(selected))
            require(response.vulnerabilities.size <= 50000 && response.vulnerabilities.all { it.crowdStrikeAid == aid }) {
                "Incomplete or mismatched Falcon findings"
            }
            val supported = response.vulnerabilities.filter { !it.cveId.isNullOrBlank() }
            val now = LocalDateTime.now()
            CrowdStrikeVulnerabilityBatchDto(selected.hostname, null, selected.cloudAccountId, selected.instanceId,
                selected.adDomain, device.osVersion, device.ip, ipAddresses = device.ipAddresses,
                vulnerabilities = supported.map { row ->
                    VulnerabilityDto(requireNotNull(row.cveId), row.severity, row.affectedProduct?.take(512),
                        row.detectedAt?.let { ChronoUnit.DAYS.between(it, now).toInt().coerceAtLeast(0) } ?: 0,
                        row.detectedAt, row.patchPublicationDate, row.cvePublishedDate)
                }, crowdStrikeAids = setOf(aid), deviceSelection = selection,
                findingCoverage = CrowdStrikeFindingCoverage(response.vulnerabilities.size, supported.size,
                    missingCve = response.vulnerabilities.size - supported.size))
        }
        require(batches.map { it.cloudInstanceId }.distinct().size == batches.size &&
            batches.count { it.cloudInstanceId == asset.cloudInstanceId } == 1) {
            "Repair requires distinct instances and exactly one device retaining the stored instance"
        }
        val expected = LegacyCrowdStrikeAssetState(asset.cloudAccountId, asset.cloudInstanceId, asset.updatedAt)
        val result = if (request.dryRun) writer.preview(request, batches, expected) else lease.withImportWrite {
            DeadlockRetry.withRetry("legacy Falcon identity repair") {
                writer.repair(request, batches, expected, auth.name)
            }
        }
        if (!request.dryRun) {
            try {
                queries.invalidateAllCachedQueries()
                refresh.requestDeferredRefresh("Falcon legacy identity repair")
            } catch (e: Exception) {
                log.error("Falcon repair assetId={} outcome=committed-refresh-failed", request.assetId, e)
            }
        }
        log.info("Falcon identity repair actor={} assetId={} devices={} outcome={}",
            auth.name.replace('\r', '_').replace('\n', '_'), request.assetId, batches.size,
            if (request.dryRun) "preview" else "committed")
        return result
    }
}

data class LegacyCrowdStrikeAssetState(val account: String?, val instance: String?, val updatedAt: LocalDateTime?)
