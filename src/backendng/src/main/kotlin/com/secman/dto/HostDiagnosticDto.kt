package com.secman.dto

import com.secman.repository.projection.VulnSourceStatRow
import io.micronaut.serde.annotation.Serdeable
import java.time.LocalDateTime

/**
 * Read-only diagnostic for a single hostname, answering "why does the CrowdStrike
 * Lookup view show N vulnerabilities for this host while the Current/Account
 * Vulnerabilities views show 0?".
 *
 * The Lookup view falls through to the live Falcon API when the persisted table has
 * no rows for an asset, so a live count with an empty persisted table is invisible to
 * operators. This DTO surfaces the persisted state and classifies the gap.
 */
@Serdeable
data class HostDiagnosticDto(
    val queriedHostname: String,
    /**
     * Best-effort verdict. Always accompanied by [notes] and the raw facts so an
     * operator can override the heuristic.
     * One of: NEVER_IMPORTED, DELETED_BY_RECONCILE_OR_CLEANUP, DUPLICATE_ASSET_ROWS,
     * INSTANCE_ID_VS_HOSTNAME_MISMATCH, HAS_PERSISTED_ROWS.
     */
    val classification: String,
    val notes: List<String>,
    /** Asset resolved by the exact lookup the buggy view uses (findByNameIgnoreCase). */
    val matchedByName: AssetDiag?,
    /** Asset resolved by cloudInstanceId, when the queried string looks like an EC2 id. */
    val matchedByInstanceId: AssetDiag?,
    /** Short-name-vs-FQDN duplicate candidates (findPotentialDuplicates). */
    val duplicateCandidates: List<AssetDiag>,
    val recentCleanupRuns: List<CleanupRunSummary>,
    val latestImport: ImportHistorySummary?,
    val recentImports: List<ImportHistorySummary>
)

@Serdeable
data class AssetDiag(
    val id: Long?,
    val name: String,
    val owner: String?,
    val cloudAccountId: String?,
    val cloudInstanceId: String?,
    val adDomain: String?,
    val osVersion: String?,
    val crowdStrikeLastImportedAt: LocalDateTime?,
    val createdAt: LocalDateTime?,
    val lastSeen: LocalDateTime?,
    val updatedAt: LocalDateTime?,
    val vulnerabilityCount: Long,
    val sourceStats: List<VulnSourceStatRow>
)

@Serdeable
data class CleanupRunSummary(
    val id: Long?,
    val status: String,
    val startedAt: LocalDateTime,
    val completedAt: LocalDateTime?,
    val cutoff: LocalDateTime,
    val candidateCount: Int,
    val deletedCount: Int,
    val legacyCandidateCount: Int,
    val legacyDeletedCount: Int
)

@Serdeable
data class ImportHistorySummary(
    val id: Long?,
    val importedAt: LocalDateTime?,
    val importedBy: String?,
    val serversProcessed: Int,
    val serversCreated: Int,
    val serversUpdated: Int,
    val vulnerabilitiesImported: Int,
    val errorCount: Int
)
