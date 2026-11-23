package com.secman.service

import com.secman.crowdstrike.CrowdStrikeSeverityScope
import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import com.secman.crowdstrike.dto.CrowdStrikeFindingCoverage
import com.secman.domain.CrowdStrikeSnapshot
import com.secman.repository.CrowdStrikeSnapshotRepository
import jakarta.inject.Singleton
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Called under the import transaction's asset lock. Failed fetches never reach this writer. */
@Singleton
class CrowdStrikeSnapshotService(private val repository: CrowdStrikeSnapshotRepository) {
    companion object {
        val SEVERITIES = CrowdStrikeSeverityScope.ALL

        internal fun severityScope(severities: List<String>?): List<String> =
            (severities?.map { it.trim().uppercase() }?.distinct() ?: SEVERITIES).also { scope ->
                require(scope.isNotEmpty() && scope.all { it in SEVERITIES }) { "Invalid snapshot severity coverage" }
            }
    }

    fun invalidate(assetId: Long, severities: List<String>? = null) {
        val scope = severities?.map { it.trim().uppercase() }?.takeIf { it.isNotEmpty() } ?: SEVERITIES
        repository.findByAssetIdAndActive(assetId, true).filter { it.severity in scope }.forEach {
            it.active = false
            repository.update(it)
        }
    }

    fun replace(assetId: Long, selection: CrowdStrikeDeviceSelection, severities: List<String>?, enrollmentChanged: Boolean,
        complete: Boolean = true, coverage: CrowdStrikeFindingCoverage? = null): Map<String, Long> {
        val scope = severityScope(severities)
        val observed = LocalDateTime.ofInstant(selection.observedAt, ZoneOffset.UTC)
        val current = currentForObservation(assetId, selection, scope, observed)
        current.filter { enrollmentChanged || it.crowdStrikeAid != selection.selected.aid || it.severity in scope }.forEach {
            it.active = false
            repository.update(it)
        }
        return scope.associateWith { severity ->
            requireNotNull(repository.save(CrowdStrikeSnapshot(assetId = assetId,
                crowdStrikeAid = selection.selected.aid, severity = severity, observedAt = observed, complete = complete,
                fetchedCount = coverage?.fetched, acceptedCount = coverage?.accepted,
                missingCveCount = coverage?.missingCve, duplicateCount = coverage?.duplicates,
                coverageKind = coverage?.let { "SUPPORTED_CVE" })).id)
        }
    }

    internal fun refreshUnchanged(
        assetId: Long,
        selection: CrowdStrikeDeviceSelection,
        scope: List<String>,
        findingSnapshotIds: Map<String, Set<Long?>>,
        coverage: CrowdStrikeFindingCoverage
    ): List<Long>? {
        val observed = LocalDateTime.ofInstant(selection.observedAt, ZoneOffset.UTC)
        val current = currentForObservation(assetId, selection, scope, observed)
        val covered = current.filter { it.severity in scope }
        if (current.any { it.crowdStrikeAid != selection.selected.aid } ||
            covered.size != scope.size || covered.map { it.severity }.toSet() != scope.toSet() ||
            covered.any { !it.complete || it.coverageKind != "SUPPORTED_CVE" || it.id == null ||
                findingSnapshotIds[it.severity]?.any { id -> id != it.id } == true }) return null

        val completed = LocalDateTime.now(ZoneOffset.UTC)
        covered.forEach {
            // The active ID is referenced by findings. Keep the previous observation in history.
            repository.save(it.copy(id = null, active = false))
            it.observedAt = observed
            it.completedAt = completed
            it.fetchedCount = coverage.fetched
            it.acceptedCount = coverage.accepted
            it.missingCveCount = coverage.missingCve
            it.duplicateCount = coverage.duplicates
            repository.update(it)
        }
        return covered.map { requireNotNull(it.id) }
    }

    private fun currentForObservation(
        assetId: Long, selection: CrowdStrikeDeviceSelection, scope: List<String>, observed: LocalDateTime
    ): List<CrowdStrikeSnapshot> {
        require(!selection.observedAt.isAfter(java.time.Instant.now().plusSeconds(60))) { "Future snapshot observation" }
        val current = repository.findByAssetIdAndActive(assetId, true)
        require(current.none { it.crowdStrikeAid == selection.selected.aid && it.severity in scope &&
            it.observedAt.isAfter(observed) }) { "An older Falcon snapshot cannot replace newer data" }
        return current
    }
}
