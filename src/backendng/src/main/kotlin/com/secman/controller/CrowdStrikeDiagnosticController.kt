package com.secman.controller

import com.secman.domain.Asset
import com.secman.domain.CrowdStrikeCleanupRun
import com.secman.domain.CrowdStrikeImportHistory
import com.secman.dto.AssetDiag
import com.secman.dto.CleanupRunSummary
import com.secman.dto.HostDiagnosticDto
import com.secman.dto.ImportHistorySummary
import com.secman.repository.AssetRepository
import com.secman.repository.CrowdStrikeCleanupRunRepository
import com.secman.repository.CrowdStrikeImportHistoryRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.data.model.Pageable
import io.micronaut.data.model.Sort
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import org.slf4j.LoggerFactory

/**
 * ADMIN-only read-only diagnostic for the "545-in-Lookup / 0-in-Current" glitch.
 *
 * Given a hostname (or EC2 instance id), reports every matching Asset row, how many
 * vulnerabilities are persisted per asset (with provenance + import-timestamp window),
 * duplicate short-name-vs-FQDN rows, and recent cleanup/import activity — then
 * classifies why the persisted table may be empty while Falcon still has findings.
 *
 * Purely read-only: no mutation endpoints. Modelled on CrowdStrikeCleanupController.
 */
@Controller("/api/crowdstrike/diagnostic")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class CrowdStrikeDiagnosticController(
    private val assetRepository: AssetRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val cleanupRunRepository: CrowdStrikeCleanupRunRepository,
    private val importHistoryRepository: CrowdStrikeImportHistoryRepository
) {
    private val log = LoggerFactory.getLogger(CrowdStrikeDiagnosticController::class.java)

    @Get("/host")
    open fun diagnoseHost(@QueryValue hostname: String): HttpResponse<*> {
        val trimmed = hostname.trim()
        if (trimmed.isBlank()) {
            return HttpResponse.badRequest(mapOf("error" to "hostname is required"))
        }
        log.info("Host diagnostic requested for '{}'", trimmed)

        // 1. Exact lookup the buggy Lookup view uses.
        val byName = assetRepository.findByNameIgnoreCase(trimmed)

        // 2. Instance-id lookup when the query looks like an EC2 instance id.
        val looksLikeInstanceId = trimmed.startsWith("i-", ignoreCase = true)
        val byInstanceId = if (looksLikeInstanceId) {
            assetRepository.findByCloudInstanceIdIgnoreCase(trimmed)
        } else null

        // 3. Short-name vs FQDN duplicates — the exact query the importer uses.
        val shortName = trimmed.substringBefore(".")
        val duplicates = assetRepository.findPotentialDuplicates(shortName)

        val nameDiag = byName?.let { toAssetDiag(it) }
        val instanceDiag = byInstanceId?.let { toAssetDiag(it) }
        // Exclude the name-matched asset from the duplicate list to avoid double-reporting.
        val duplicateDiags = duplicates
            .filter { it.id != byName?.id }
            .map { toAssetDiag(it) }

        val notes = mutableListOf<String>()
        val classification = classify(trimmed, nameDiag, instanceDiag, duplicateDiags, notes)

        val cleanupRuns = cleanupRunRepository
            .findAll(Pageable.from(0, 10, Sort.of(Sort.Order.desc("startedAt"))))
            .content
            .map { toCleanupSummary(it) }
        val latestImport = importHistoryRepository.findLatest()?.let { toImportSummary(it) }
        val recentImports = importHistoryRepository
            .findRecent(Pageable.from(0, 5))
            .map { toImportSummary(it) }

        return HttpResponse.ok(
            HostDiagnosticDto(
                queriedHostname = trimmed,
                classification = classification,
                notes = notes,
                matchedByName = nameDiag,
                matchedByInstanceId = instanceDiag,
                duplicateCandidates = duplicateDiags,
                recentCleanupRuns = cleanupRuns,
                latestImport = latestImport,
                recentImports = recentImports
            )
        )
    }

    /**
     * Best-effort classifier. Appends human-readable reasoning to [notes].
     */
    private fun classify(
        hostname: String,
        nameDiag: AssetDiag?,
        instanceDiag: AssetDiag?,
        duplicateDiags: List<AssetDiag>,
        notes: MutableList<String>
    ): String {
        val allMatches = listOfNotNull(nameDiag, instanceDiag) + duplicateDiags

        if (allMatches.isEmpty()) {
            notes += "No asset matches '$hostname' by name, instance id, or short-name duplicate. The host was never imported."
            return "NEVER_IMPORTED"
        }

        // The Lookup view reads the name-matched asset. If it has 0 rows but a
        // duplicate/instance sibling has rows, the view is reading the wrong row.
        val siblingsWithRows = (duplicateDiags + listOfNotNull(instanceDiag))
            .filter { it.id != nameDiag?.id && it.vulnerabilityCount > 0 }
        if (nameDiag != null && nameDiag.vulnerabilityCount == 0L && siblingsWithRows.isNotEmpty()) {
            notes += "Name-matched asset id=${nameDiag.id} has 0 persisted rows, but " +
                siblingsWithRows.joinToString { "id=${it.id} (${it.name}) has ${it.vulnerabilityCount}" } +
                ". The views read the empty row while another asset holds the data."
            return "DUPLICATE_ASSET_ROWS"
        }

        if (nameDiag == null && instanceDiag != null) {
            notes += "No name match, but an asset resolves by cloudInstanceId (id=${instanceDiag.id}, name='${instanceDiag.name}'). " +
                "A hostname query would miss it."
            return "INSTANCE_ID_VS_HOSTNAME_MISMATCH"
        }

        val primary = nameDiag ?: instanceDiag ?: allMatches.first()
        if (primary.vulnerabilityCount == 0L) {
            return if (primary.crowdStrikeLastImportedAt != null) {
                notes += "Asset id=${primary.id} exists with 0 persisted vulnerabilities but a non-null " +
                    "crowdStrikeLastImportedAt (${primary.crowdStrikeLastImportedAt}). The rows were deleted after import " +
                    "— consistent with the reconcile sweep or a cleanup run. Compare recentCleanupRuns and recentImports."
                "DELETED_BY_RECONCILE_OR_CLEANUP"
            } else {
                notes += "Asset id=${primary.id} exists with 0 persisted vulnerabilities and no crowdStrikeLastImportedAt " +
                    "— it was likely created by another path and never had CrowdStrike rows imported."
                "NEVER_IMPORTED"
            }
        }

        notes += "Asset id=${primary.id} has ${primary.vulnerabilityCount} persisted vulnerabilities; the persisted views should show them."
        return "HAS_PERSISTED_ROWS"
    }

    private fun toAssetDiag(asset: Asset): AssetDiag {
        val id = asset.id
        return AssetDiag(
            id = id,
            name = asset.name,
            owner = asset.owner,
            cloudAccountId = asset.cloudAccountId,
            cloudInstanceId = asset.cloudInstanceId,
            adDomain = asset.adDomain,
            osVersion = asset.osVersion,
            crowdStrikeLastImportedAt = asset.crowdStrikeLastImportedAt,
            createdAt = asset.createdAt,
            lastSeen = asset.lastSeen,
            updatedAt = asset.updatedAt,
            vulnerabilityCount = id?.let { vulnerabilityRepository.countByAssetId(it) } ?: 0L,
            sourceStats = id?.let { vulnerabilityRepository.findSourceStatsByAssetId(it) } ?: emptyList()
        )
    }

    private fun toCleanupSummary(run: CrowdStrikeCleanupRun): CleanupRunSummary =
        CleanupRunSummary(
            id = run.id,
            status = run.status.name,
            startedAt = run.startedAt,
            completedAt = run.completedAt,
            cutoff = run.cutoff,
            candidateCount = run.candidateCount,
            deletedCount = run.deletedCount,
            legacyCandidateCount = run.legacyCandidateCount,
            legacyDeletedCount = run.legacyDeletedCount
        )

    private fun toImportSummary(h: CrowdStrikeImportHistory): ImportHistorySummary =
        ImportHistorySummary(
            id = h.id,
            importedAt = h.importedAt,
            importedBy = h.importedBy,
            serversProcessed = h.serversProcessed,
            serversCreated = h.serversCreated,
            serversUpdated = h.serversUpdated,
            vulnerabilitiesImported = h.vulnerabilitiesImported,
            errorCount = h.errorCount
        )
}
