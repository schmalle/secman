package com.secman.service

import com.secman.crowdstrike.client.CrowdStrikeApiClient
import com.secman.dto.CrowdStrikeSyncRequest
import com.secman.dto.CrowdStrikeSaveResponse
import com.secman.dto.CrowdStrikeVulnerabilityBatchDto
import com.secman.dto.VulnerabilityDto
import com.secman.repository.AssetRepository
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Fetch on the server; browser filters and submitted finding lists are never deletion evidence. */
@Singleton
class CrowdStrikeSyncService(
    private val client: CrowdStrikeApiClient,
    private val queries: CrowdStrikeQueryService,
    private val importer: CrowdStrikeVulnerabilityImportService,
    private val assets: AssetRepository,
    private val access: AssetFilterService,
    private val identities: com.secman.repository.CrowdStrikeAssetIdentityRepository
) {
    fun sync(request: CrowdStrikeSyncRequest, authentication: Authentication): CrowdStrikeSaveResponse {
        if (!authentication.roles.contains("ADMIN")) throw HttpStatusException(HttpStatus.FORBIDDEN, "Synchronization requires ADMIN")
        val asset = request.assetId?.let { id ->
            if (!access.canAccessAsset(id, authentication)) throw HttpStatusException(HttpStatus.NOT_FOUND, "System not found")
            assets.findById(id).orElseThrow { HttpStatusException(HttpStatus.NOT_FOUND, "System not found") }
        } ?: request.instanceId?.takeIf(String::isNotBlank)?.let(assets::findByCloudInstanceIdIgnoreCase)?.also {
            if (!access.canAccessAsset(requireNotNull(it.id), authentication))
                throw HttpStatusException(HttpStatus.NOT_FOUND, "System not found")
        }
        val instance = asset?.cloudInstanceId ?: request.instanceId
        val hostname = asset?.crowdStrikeHostname ?: asset?.name ?: request.hostname
        require(hostname.matches(Regex("[A-Za-z0-9_.-]{1,255}"))) { "Invalid hostname" }
        val config = queries.getConfiguration()
        // A stored instance ID may be precisely the stale value this synchronization repairs.
        val response = if (asset == null && !instance.isNullOrBlank()) client.queryVulnerabilitiesByInstanceId(instance, config)
            else client.queryAllVulnerabilities(hostname, config)
        require(response.failedAids.isEmpty()) { "Incomplete Falcon snapshot; existing data was preserved" }
        val device = requireNotNull(response.devices.singleOrNull()) { "System does not resolve to one Falcon machine" }
        val selection = requireNotNull(device.deviceSelection) { "Missing Falcon enrollment evidence" }
        if (asset != null) {
            require(asset.cloudAccountId.isNullOrBlank() || asset.cloudAccountId.equals(device.cloudAccountId, ignoreCase = true)) {
                "Falcon account conflicts with the selected asset"
            }
            require(asset.cloudInstanceId.equals(device.instanceId, ignoreCase = true) ||
                identities.findByAssetIdIn(listOf(requireNotNull(asset.id))).any { it.crowdStrikeAid == selection.selected.aid }) {
                "Falcon device does not match the selected asset identity"
            }
        }
        require(response.vulnerabilities.all { it.crowdStrikeAid == selection.selected.aid }) { "Findings belong to another enrollment" }
        val now = LocalDateTime.now()
        val supported = response.vulnerabilities.filter { !it.cveId.isNullOrBlank() }
        val findings = supported.map { row ->
            VulnerabilityDto(requireNotNull(row.cveId), row.severity, row.affectedProduct?.take(512),
                row.detectedAt?.let { ChronoUnit.DAYS.between(it, now).toInt().coerceAtLeast(0) } ?: 0,
                row.detectedAt, row.patchPublicationDate, row.cvePublishedDate)
        }
        val batch = CrowdStrikeVulnerabilityBatchDto(selection.selected.hostname, null, device.cloudAccountId,
            device.instanceId, device.adDomain, device.osVersion, device.ip, ipAddresses = device.ipAddresses, vulnerabilities = findings,
            crowdStrikeAids = setOf(selection.selected.aid), deviceSelection = selection,
            findingCoverage = com.secman.crowdstrike.dto.CrowdStrikeFindingCoverage(response.vulnerabilities.size,
                findings.size, missingCve = response.vulnerabilities.size - supported.size))
        val result = importer.importServerVulnerabilities(listOf(batch), authentication.name, allowDeviceReplacement = true)
        if (result.errors.isNotEmpty()) {
            val failure = result.hostOutcomes.firstOrNull { it.status == "REJECTED" }
            throw CrowdStrikeIdentityConflict(failure?.errorCode ?: "IMPORT_REJECTED",
                failure?.message ?: "Falcon snapshot could not be committed", failure?.assetId,
                failure?.formerInstanceId, failure?.instanceId)
        }
        return CrowdStrikeSaveResponse(message = "Synchronized the current Falcon enrollment (${result.vulnerabilitiesImported} findings).",
            vulnerabilitiesSaved = result.vulnerabilitiesImported, vulnerabilitiesSkipped = result.vulnerabilitiesSkipped,
            assetsCreated = result.serversCreated, errors = emptyList(), hostOutcomes = result.hostOutcomes)
    }
}
