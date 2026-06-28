package com.secman.service

import com.secman.constants.AssetOwners
import com.secman.constants.AssetTypes
import com.secman.constants.VulnerabilitySources
import com.secman.domain.Asset
import com.secman.domain.Vulnerability
import com.secman.dto.GitHubDependabotBatchDto
import com.secman.repository.AssetRepository
import com.secman.repository.VulnerabilityRepository
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Imports GitHub Dependabot alerts as vulnerabilities, treating each repository
 * as an [Asset] of type [AssetTypes.REPOSITORY].
 *
 * Mirrors [CrowdStrikeVulnerabilityImportService] but for repositories:
 *  - find-or-create the repository asset by its "owner/repo" full name
 *  - transactional delete-insert replace per repository (idempotent; remediated
 *    alerts disappear on the next import because they are no longer in the batch)
 *  - preserve `firstSeenAt` per (vulnerabilityId, product) across re-imports
 *  - tag every row `source = GITHUB_DEPENDABOT`
 *
 * IMPORTANT: like the CrowdStrike path, this relies on `Asset.vulnerabilities`
 * NOT using cascade=ALL / orphanRemoval. Deletion is explicit via
 * [VulnerabilityRepository.deleteByAssetId].
 */
@Singleton
open class GitHubDependabotImportService(
    private val assetRepository: AssetRepository,
    private val vulnerabilityRepository: VulnerabilityRepository,
    private val materializedViewRefreshService: MaterializedViewRefreshService,
    private val entityManager: EntityManager
) {
    private val log = LoggerFactory.getLogger(GitHubDependabotImportService::class.java)

    @jakarta.inject.Inject
    private lateinit var selfProvider: jakarta.inject.Provider<GitHubDependabotImportService>

    @Serdeable
    data class ImportResult(
        val reposProcessed: Int,
        val reposCreated: Int,
        val reposUpdated: Int,
        val vulnerabilitiesImported: Int,
        val vulnerabilitiesSkipped: Int,
        val errors: List<String>
    )

    @Serdeable
    data class RepoImportResult(
        val assetCreated: Boolean,
        val vulnerabilitiesImported: Int,
        val vulnerabilitiesSkipped: Int
    )

    /**
     * Import a list of per-repository Dependabot alert batches. Each repository is
     * processed in its own transaction; a failure on one repository is recorded in
     * `errors` and does not abort the others.
     */
    open fun importDependabotAlerts(
        batches: List<GitHubDependabotBatchDto>,
        triggeredBy: String? = null
    ): ImportResult {
        log.info("Starting Dependabot import of {} repositories (by {})", batches.size, triggeredBy)

        var reposCreated = 0
        var reposUpdated = 0
        var vulnerabilitiesImported = 0
        var vulnerabilitiesSkipped = 0
        val errors = mutableListOf<String>()

        for (batch in batches) {
            try {
                val result = selfProvider.get().importAlertsForRepository(batch)
                if (result.assetCreated) reposCreated++ else reposUpdated++
                vulnerabilitiesImported += result.vulnerabilitiesImported
                vulnerabilitiesSkipped += result.vulnerabilitiesSkipped
            } catch (e: Exception) {
                val msg = "Failed to import repository '${batch.repositoryFullName}': ${e.message}"
                log.error(msg, e)
                errors.add(msg)
            }
        }

        if (vulnerabilitiesImported > 0) {
            materializedViewRefreshService.triggerAsyncRefresh(
                "GitHub Dependabot import - $vulnerabilitiesImported vulnerabilities imported"
            )
        }

        log.info(
            "Dependabot import complete: repos={}, created={}, updated={}, imported={}, skipped={}, errors={}",
            batches.size, reposCreated, reposUpdated, vulnerabilitiesImported, vulnerabilitiesSkipped, errors.size
        )

        return ImportResult(
            reposProcessed = batches.size,
            reposCreated = reposCreated,
            reposUpdated = reposUpdated,
            vulnerabilitiesImported = vulnerabilitiesImported,
            vulnerabilitiesSkipped = vulnerabilitiesSkipped,
            errors = errors
        )
    }

    /**
     * Transactional replace for a single repository: snapshot existing firstSeenAt,
     * delete all current rows for the asset, insert the batch.
     */
    @Transactional
    open fun importAlertsForRepository(batch: GitHubDependabotBatchDto): RepoImportResult {
        val repoName = batch.repositoryFullName.trim()
        val existing = assetRepository.findByNameIgnoreCase(repoName)
        val (asset, isNewAsset) = if (existing != null) {
            Pair(updateAsset(existing, batch), false)
        } else {
            Pair(createAsset(batch), true)
        }

        // Snapshot earliest firstSeenAt per (vulnerabilityId, product) so re-imports
        // keep the original detection date as the SLA anchor.
        val existingFirstSeen = mutableMapOf<Pair<String?, String?>, LocalDateTime>()
        vulnerabilityRepository.findByAssetId(asset.id!!, io.micronaut.data.model.Pageable.UNPAGED)
            .content
            .forEach { v ->
                val key = v.vulnerabilityId to v.vulnerableProductVersions
                val anchor = v.firstSeenAt ?: v.scanTimestamp
                val current = existingFirstSeen[key]
                if (current == null || anchor.isBefore(current)) {
                    existingFirstSeen[key] = anchor
                }
            }

        // Lock the asset row to serialize concurrent imports of the same repo, then
        // delete its current vulnerabilities (explicit delete; NO JPA cascade).
        entityManager.find(Asset::class.java, asset.id!!, LockModeType.PESSIMISTIC_WRITE)
        vulnerabilityRepository.deleteByAssetId(asset.id!!)

        // Deduplicate by (vulnerabilityId, product) — GitHub can surface the same
        // advisory for the same package more than once across pages.
        val importTimestamp = LocalDateTime.now()
        var skipped = 0
        val deduped = batch.alerts
            .filter { !it.ghsaId.isBlank() }
            .also { skipped = batch.alerts.size - it.size }
            .map { alert ->
                val vulnerabilityId = alert.cveId?.takeIf { it.isNotBlank() } ?: alert.ghsaId
                val product = buildProductString(alert.ecosystem, alert.packageName, alert.vulnerableVersionRange)
                Triple(vulnerabilityId, product, alert)
            }
            .associateBy { (vulnId, product, _) -> vulnId to product }  // keep last per key
            .values

        val entities = deduped.map { (vulnerabilityId, product, alert) ->
            val scanTimestamp = parseCreatedAt(alert.createdAt) ?: importTimestamp
            val daysOpen = ChronoUnit.DAYS.between(scanTimestamp, importTimestamp).coerceAtLeast(0)
            val daysOpenText = if (daysOpen == 1L) "1 day" else "$daysOpen days"

            val priorFirstSeen = existingFirstSeen[vulnerabilityId to product]
            val firstSeenAt = when {
                priorFirstSeen == null -> scanTimestamp
                scanTimestamp.isBefore(priorFirstSeen) -> scanTimestamp
                else -> priorFirstSeen
            }

            Vulnerability(
                asset = asset,
                vulnerabilityId = vulnerabilityId.take(255),
                cvssSeverity = normalizeSeverity(alert.severity),
                vulnerableProductVersions = product?.take(512),
                daysOpen = daysOpenText,
                scanTimestamp = scanTimestamp,
                firstSeenAt = firstSeenAt,
                importTimestamp = importTimestamp,
                source = VulnerabilitySources.GITHUB_DEPENDABOT
            )
        }

        if (entities.isNotEmpty()) {
            vulnerabilityRepository.saveAll(entities)
        }

        log.debug(
            "Repository '{}' (id={}): imported {} alerts, skipped {}",
            repoName, asset.id, entities.size, skipped
        )

        return RepoImportResult(
            assetCreated = isNewAsset,
            vulnerabilitiesImported = entities.size,
            vulnerabilitiesSkipped = skipped
        )
    }

    private fun createAsset(batch: GitHubDependabotBatchDto): Asset {
        val asset = Asset(
            name = batch.repositoryFullName.trim(),
            type = AssetTypes.REPOSITORY,
            owner = AssetOwners.GITHUB_IMPORT,
            uri = batch.repositoryUrl?.takeIf { it.isNotBlank() },
            description = "GitHub repository (Dependabot)",
            lastSeen = LocalDateTime.now(),
            manualCreator = null,
            scanUploader = null
        )
        val created = assetRepository.save(asset)
        log.debug("Created repository asset: {} (id={})", created.name, created.id)
        return created
    }

    private fun updateAsset(existing: Asset, batch: GitHubDependabotBatchDto): Asset {
        var changed = false
        val newUri = batch.repositoryUrl?.takeIf { it.isNotBlank() }
        if (newUri != null && newUri != existing.uri) {
            existing.uri = newUri
            changed = true
        }
        existing.lastSeen = LocalDateTime.now()
        existing.updatedAt = LocalDateTime.now()
        // Always persist (lastSeen changed). Keep owner/type as-is so a repo that was
        // (re)classified by a human is not clobbered.
        return assetRepository.update(existing).also {
            if (changed) log.debug("Updated repository asset '{}' (uri refreshed)", existing.name)
        }
    }

    /** "ecosystem:package range" — null when no package info is available. */
    private fun buildProductString(ecosystem: String?, packageName: String?, range: String?): String? {
        val pkg = listOfNotNull(
            ecosystem?.takeIf { it.isNotBlank() },
            packageName?.takeIf { it.isNotBlank() }
        ).joinToString(":")
        val parts = listOfNotNull(
            pkg.takeIf { it.isNotBlank() },
            range?.takeIf { it.isNotBlank() }
        )
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    /** Match the DB's title-case severity convention ("Critical", "High", …). */
    private fun normalizeSeverity(severity: String): String =
        when (severity.trim().uppercase()) {
            "CRITICAL" -> "Critical"
            "HIGH" -> "High"
            "MEDIUM", "MODERATE" -> "Medium"
            "LOW" -> "Low"
            else -> severity.trim().replaceFirstChar { it.uppercase() }
        }

    private fun parseCreatedAt(createdAt: String?): LocalDateTime? {
        if (createdAt.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(createdAt).toLocalDateTime()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(createdAt)
            } catch (_: DateTimeParseException) {
                log.debug("Unparseable Dependabot createdAt '{}', falling back to import time", createdAt)
                null
            }
        }
    }
}
