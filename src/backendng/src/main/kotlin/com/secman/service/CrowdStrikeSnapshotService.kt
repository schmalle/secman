package com.secman.service

import com.secman.crowdstrike.dto.CrowdStrikeDeviceSelection
import com.secman.domain.CrowdStrikeSnapshot
import com.secman.repository.CrowdStrikeSnapshotRepository
import jakarta.inject.Singleton
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Called under the import transaction's asset lock. Failed fetches never reach this writer. */
@Singleton
class CrowdStrikeSnapshotService(private val repository: CrowdStrikeSnapshotRepository) {
    companion object {
        val SEVERITIES = listOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFORMATIONAL", "UNKNOWN")
    }

    fun invalidate(assetId: Long, severities: List<String>? = null) {
        val scope = severities?.map { it.trim().uppercase() }?.takeIf { it.isNotEmpty() } ?: SEVERITIES
        repository.findByAssetIdAndActive(assetId, true).filter { it.severity in scope }.forEach {
            it.active = false
            repository.update(it)
        }
    }

    fun replace(assetId: Long, selection: CrowdStrikeDeviceSelection, severities: List<String>?, enrollmentChanged: Boolean,
        complete: Boolean = true, coverage: com.secman.crowdstrike.dto.CrowdStrikeFindingCoverage? = null): Map<String, Long> {
        val scope = severities?.map { it.trim().uppercase() }?.distinct() ?: SEVERITIES
        require(scope.isNotEmpty() && scope.all { it in SEVERITIES }) { "Invalid snapshot severity coverage" }
        require(!selection.observedAt.isAfter(java.time.Instant.now().plusSeconds(60))) { "Future snapshot observation" }
        val observed = LocalDateTime.ofInstant(selection.observedAt, ZoneOffset.UTC)
        val current = repository.findByAssetIdAndActive(assetId, true)
        require(current.filter { it.crowdStrikeAid == selection.selected.aid && it.severity in scope }
            .none { it.observedAt.isAfter(observed) }) { "An older Falcon snapshot cannot replace newer data" }
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
}
