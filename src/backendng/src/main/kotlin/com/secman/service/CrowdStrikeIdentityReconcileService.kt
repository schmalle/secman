package com.secman.service

import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.crowdstrike.dto.CrowdStrikeFindingCoverage
import com.secman.crowdstrike.dto.CrowdStrikeDeviceRecord
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
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
        require(request.assetId > 0 && request.expectedAids.size in 2..100 &&
            request.expectedAids.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } &&
            request.reason.trim().length in 10..255 && request.reason.none(Char::isISOControl)) { "Invalid repair request" }
        if (!access.canAccessAsset(request.assetId, auth)) throw HttpStatusException(HttpStatus.NOT_FOUND, "System not found")
        val asset = assets.findById(request.assetId).orElseThrow { HttpStatusException(HttpStatus.NOT_FOUND, "System not found") }
        val bound = identities.findByAssetIdIn(listOf(request.assetId))
        if (bound.size == 1 && bound.single().let {
                it.falconFirstSeenAt != null && it.falconLastSeenAt != null && it.identityObservedAt != null
            }) throw CrowdStrikeIdentityConflict("ALREADY_RESOLVED", "Source now has one verified Falcon binding", request.assetId)
        checkEvidence(bound.map { it.crowdStrikeAid }.toSet() == request.expectedAids,
            "BINDINGS_CHANGED", "Stored AID bindings changed; discover the asset again")
        checkEvidence(bound.all { it.falconFirstSeenAt == null && it.falconLastSeenAt == null && it.identityObservedAt == null },
            "VERIFIED_BINDING_PRESENT", "A verified binding requires separate review; automatic legacy split is not allowed")
        checkEvidence(!asset.cloudAccountId.isNullOrBlank(), "SOURCE_ACCOUNT_MISSING", "Stored asset has no AWS account ID")
        checkEvidence(asset.cloudAccountId?.matches(Regex("[0-9]{12}")) == true,
            "SOURCE_ACCOUNT_INVALID", "Stored AWS account must contain exactly 12 digits")
        checkEvidence(!asset.cloudInstanceId.isNullOrBlank(), "SOURCE_INSTANCE_MISSING", "Stored asset has no EC2 instance ID")
        checkEvidence(asset.cloudInstanceId?.matches(Regex("i-(?:[0-9a-f]{8}|[0-9a-f]{17})")) == true,
            "SOURCE_NOT_EC2", "Stored instance is not a valid EC2 instance; this recovery only splits EC2 devices")
        val expected = LegacyCrowdStrikeAssetState(asset.cloudAccountId, asset.cloudInstanceId, asset.updatedAt)
        val config = queries.getConfiguration()
        val records = client.resolveDeviceRecords(request.expectedAids, config)
        checkEvidence(records.size == request.expectedAids.size && records.map { it.aid }.toSet() == request.expectedAids,
            "DEVICE_METADATA_INCOMPLETE", "Falcon did not return metadata for every expected AID")
        records.forEach { validateDevice(it, requireNotNull(asset.cloudAccountId)) }
        checkEvidence(records.map { it.instanceId }.distinct().size == records.size,
            "SAME_INSTANCE_ENROLLMENTS", "Multiple AIDs report the same EC2 instance; they are enrollments, not independent instances")
        checkEvidence(records.count { it.instanceId == asset.cloudInstanceId } == 1,
            "RETAINED_INSTANCE_NOT_FOUND", "No Falcon device matches the stored EC2 instance; the original asset cannot be retained safely")
        val metadataBatches = records.sortedBy { it.aid }.map { device ->
            CrowdStrikeVulnerabilityBatchDto(device.hostname, null, device.cloudAccountId, device.instanceId, device.adDomain,
                null, null, vulnerabilities = emptyList(), crowdStrikeAids = setOf(device.aid), deviceSelection = CrowdStrikeDeviceSelection(device))
        }
        // Check destination ownership and concurrent state before any Spotlight download.
        val preview = writer.preview(request, metadataBatches, expected)
        if (request.dryRun) return preview.copy(devices = preview.devices.map { it.copy(findings = null) },
            findingsFetched = false, requestId = request.requestId)
        val batches = metadataBatches.map { metadata ->
            val before = requireNotNull(metadata.deviceSelection).selected
            val response = client.queryDeviceVulnerabilities(null, before.aid, false, config)
            checkEvidence(response.failedAids.isEmpty() && response.totalCount == response.vulnerabilities.size,
                "INCOMPLETE_FINDINGS", "Falcon findings are incomplete; existing data was preserved", before.aid)
            val device = response.devices.singleOrNull()
            val selection = device?.deviceSelection
            checkEvidence(selection != null && selection.superseded.isEmpty(), "DEVICE_METADATA_INCOMPLETE",
                "Findings did not resolve to one verified Falcon enrollment", before.aid)
            val selected = requireNotNull(selection).selected
            checkEvidence(selected.aid == before.aid && device?.crowdStrikeAid == before.aid &&
                selected.instanceId == before.instanceId && selected.cloudAccountId == before.cloudAccountId &&
                selected.adDomain == before.adDomain && selected.hostname == before.hostname && selected.firstSeen == before.firstSeen,
                "IDENTITY_CHANGED_DURING_FETCH", "Falcon identity changed after metadata classification; preview again", before.aid)
            validateDevice(selected, requireNotNull(asset.cloudAccountId))
            checkEvidence(selected.lastSeen!! >= before.lastSeen!!, "STALE_DEVICE_OBSERVATION", "Falcon contact regressed during findings fetch", before.aid)
            checkEvidence(response.vulnerabilities.size <= 50000 && response.vulnerabilities.all { it.crowdStrikeAid == before.aid },
                "FINDING_IDENTITY_MISMATCH", "Finding coverage is too large or belongs to another AID", before.aid)
            val supported = response.vulnerabilities.filter { !it.cveId.isNullOrBlank() }
            val now = LocalDateTime.now()
            metadata.copy(osVersion = device?.osVersion, ip = device?.ip, ipAddresses = device?.ipAddresses.orEmpty(),
                vulnerabilities = supported.map { row ->
                    VulnerabilityDto(requireNotNull(row.cveId), row.severity, row.affectedProduct?.take(512),
                        row.detectedAt?.let { ChronoUnit.DAYS.between(it, now).toInt().coerceAtLeast(0) } ?: 0,
                        row.detectedAt, row.patchPublicationDate, row.cvePublishedDate)
                }, deviceSelection = selection,
                findingCoverage = CrowdStrikeFindingCoverage(response.vulnerabilities.size, supported.size,
                    missingCve = response.vulnerabilities.size - supported.size))
        }
        val result = lease.withImportWrite {
            DeadlockRetry.withRetry("legacy Falcon identity repair") { writer.repair(request, batches, expected, auth.name) }
        }.copy(findingsFetched = true, requestId = request.requestId)
        if (!request.dryRun) {
            try {
                queries.invalidateAllCachedQueries()
                refresh.requestDeferredRefresh("Falcon legacy identity repair")
            } catch (e: Exception) {
                log.error("Falcon repair assetId={} outcome=committed-refresh-failed", request.assetId, e)
            }
        }
        log.info("Falcon identity repair actor={} assetId={} devices={} outcome={}",
            auth.name.replace('\r', '_').replace('\n', '_'), request.assetId, records.size,
            if (request.dryRun) "preview" else "committed")
        return result
    }
    private fun validateDevice(device: CrowdStrikeDeviceRecord, account: String) {
        val aid = device.aid
        val contact = device.lastSeen
        checkEvidence(!device.instanceId.isNullOrBlank(), "DEVICE_INSTANCE_MISSING", "Falcon device has no EC2 instance ID; it may be non-cloud", aid)
        checkEvidence(device.instanceId?.matches(Regex("i-(?:[0-9a-f]{8}|[0-9a-f]{17})")) == true,
            "DEVICE_INSTANCE_INVALID", "Falcon instance is not a valid EC2 instance ID", aid)
        checkEvidence(!device.cloudAccountId.isNullOrBlank(), "DEVICE_ACCOUNT_MISSING", "Falcon device has no AWS account ID", aid)
        checkEvidence(device.cloudAccountId == account, "CLOUD_ACCOUNT_CONFLICT", "Falcon AWS account differs from the stored AWS account", aid)
        checkEvidence(device.firstSeen != null, "ENROLLMENT_TIMESTAMP_MISSING", "Falcon enrollment first_seen is missing", aid)
        checkEvidence(contact != null && contact <= java.time.Instant.now() && contact >= requireNotNull(device.firstSeen),
            "CONTACT_TIMESTAMP_INVALID", "Falcon last_seen is missing, in the future, or before enrollment", aid)
        checkEvidence(device.hostname.matches(Regex("[A-Za-z0-9_.-]{1,255}")), "DEVICE_HOSTNAME_INVALID", "Falcon hostname is missing or invalid", aid)
        checkEvidence(retention.acceptsImport(device.lastSeen), "SENSOR_RETENTION_EXCLUDED", "Falcon contact is outside the configured sensor retention window", aid)
    }

    private fun checkEvidence(condition: Boolean, code: String, message: String, aid: String? = null) {
        if (!condition) throw CrowdStrikeIdentityConflict(code, message, aid = aid)
    }

}

data class LegacyCrowdStrikeAssetState(val account: String?, val instance: String?, val updatedAt: LocalDateTime?)
