package com.secman.service

import com.secman.domain.GithubRepoDependabotAlert
import com.secman.domain.GithubRepoFindingSnapshot
import com.secman.domain.GithubRepository
import com.secman.repository.GithubAppConfigRepository
import com.secman.repository.GithubOwnerEmailMappingRepository
import com.secman.repository.GithubRepoDependabotAlertRepository
import com.secman.repository.GithubRepoFindingSnapshotRepository
import com.secman.repository.GithubRepositoryRepository
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Imports the GitHub App installation's repositories into secman: upserts
 * [GithubRepository] rows (by stable numeric GitHub repo id) with their open
 * Dependabot alert counts, writes one [GithubRepoFindingSnapshot] per repo
 * per run (the history behind the 30-day non-decrease alert), and replaces
 * each repo's [GithubRepoDependabotAlert] rows with the freshly fetched
 * per-alert detail (current-state only — delete then reinsert).
 *
 * Triggered by `POST /api/github/import` (CLI `import-github-repos`, MCP
 * `import_github_repos`, or the UI "Import now" button).
 *
 * When a repo's `ownerEmail` is blank, it is auto-filled from
 * [GithubOwnerEmailMappingRepository] (owner login -> default email); a
 * manually-set or previously auto-filled value is never overwritten.
 */
@Singleton
open class GithubRepoImportService(
    private val githubAppConfigRepository: GithubAppConfigRepository,
    private val githubRepositoryRepository: GithubRepositoryRepository,
    private val snapshotRepository: GithubRepoFindingSnapshotRepository,
    private val alertRepository: GithubRepoDependabotAlertRepository,
    private val githubClient: GithubAppClientService,
    private val ownerEmailMappingRepository: GithubOwnerEmailMappingRepository,
    private val entityManager: EntityManager
) {
    private val logger = LoggerFactory.getLogger(GithubRepoImportService::class.java)

    /**
     * Provider for self-reference so persistRepo's @Transactional applies on the internal
     * call (AOP proxy bypass fix, same as CrowdStrikeVulnerabilityImportService / Feature 053).
     * Without it the per-repo snapshot insert, alert delete and alert reinsert each commit
     * separately - a crash or concurrent import can leave a repo with no or duplicated alerts.
     */
    @jakarta.inject.Inject
    private lateinit var selfProvider: jakarta.inject.Provider<GithubRepoImportService>

    @Serdeable
    data class ImportResult(
        val reposDiscovered: Int,
        val reposNew: Int,
        val reposUpdated: Int,
        val totalCritical: Int,
        val totalHigh: Int,
        val reposWithAlertsDisabled: List<String>,
        val errors: List<String>,
        val importedAt: Instant
    )

    open fun importRepositories(): ImportResult {
        val config = githubAppConfigRepository.findActiveConfig().orElseThrow {
            IllegalStateException("No active GitHub App configuration — configure one under Admin → GitHub App")
        }

        val apiBaseUrl = config.effectiveApiBaseUrl()
        val token = githubClient.getInstallationToken(config)
        val discovered = githubClient.listInstallationRepositories(token, apiBaseUrl)
        logger.info("GitHub import: discovered {} repositories", discovered.size)

        val now = Instant.now()
        var created = 0
        var updated = 0
        var totalCritical = 0
        var totalHigh = 0
        val alertsDisabled = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (repoDto in discovered) {
            try {
                val counts = githubClient.countOpenDependabotAlerts(token, repoDto.owner, repoDto.name, apiBaseUrl)
                if (counts.disabled) {
                    alertsDisabled.add(repoDto.fullName)
                }
                totalCritical += counts.critical
                totalHigh += counts.high
                // Via the self proxy so @Transactional applies; deadlock-retried because the
                // per-repo transaction takes a PESSIMISTIC_WRITE lock on the repo row.
                val wasNew = DeadlockRetry.withRetry("github-import ${repoDto.fullName}") {
                    selfProvider.get().persistRepo(repoDto, counts, now)
                }
                if (wasNew) created++ else updated++
            } catch (e: Exception) {
                logger.warn("GitHub import: failed for {}: {}", repoDto.fullName, e.message)
                errors.add("${repoDto.fullName}: ${e.message}")
            }
        }

        logger.info(
            "GitHub import complete: {} discovered, {} new, {} updated, {} critical, {} high, {} errors",
            discovered.size, created, updated, totalCritical, totalHigh, errors.size
        )
        return ImportResult(
            reposDiscovered = discovered.size,
            reposNew = created,
            reposUpdated = updated,
            totalCritical = totalCritical,
            totalHigh = totalHigh,
            reposWithAlertsDisabled = alertsDisabled,
            errors = errors,
            importedAt = now
        )
    }

    /** Upsert one repo + its snapshot + its current alert rows. Returns true when a new repo row was created. */
    @Transactional
    open fun persistRepo(
        repoDto: GithubAppClientService.GithubRepoDto,
        counts: GithubAppClientService.SeverityCounts,
        now: Instant
    ): Boolean {
        val resolved = githubRepositoryRepository.findByGithubRepoId(repoDto.repoId)
            .or { githubRepositoryRepository.findByFullName(repoDto.fullName) }
            .orElse(null)

        // Serialize concurrent imports of the same repo (CLI + UI "Import now" can overlap):
        // a PESSIMISTIC_WRITE row lock makes the snapshot insert + alert delete + alert
        // reinsert below mutually exclusive per repo. Without it, interleaved delete/insert
        // pairs from two runs produce duplicated alert rows. New repos can't be locked
        // (no row yet) - there the unique constraints on github_repo_id / full_name make
        // the second concurrent creator fail instead of duplicating.
        val existing = resolved?.id?.let {
            entityManager.find(GithubRepository::class.java, it, LockModeType.PESSIMISTIC_WRITE)
        }

        val isNew = existing == null
        val repo = existing ?: GithubRepository()
        repo.githubRepoId = repoDto.repoId
        repo.name = repoDto.name
        repo.owner = repoDto.owner
        repo.fullName = repoDto.fullName
        repo.htmlUrl = repoDto.htmlUrl
        repo.archived = repoDto.archived
        // ownerEmail is user-maintained; only auto-filled from
        // github_owner_email_mapping when currently blank — never overwrites a
        // manually-set or previously auto-filled value.
        if (repo.ownerEmail.isNullOrBlank()) {
            ownerEmailMappingRepository.findByOwnerIgnoreCase(repoDto.owner)
                .ifPresent { repo.ownerEmail = it.email }
        }
        repo.criticalCount = counts.critical
        repo.highCount = counts.high
        repo.lastImportAt = now
        if (counts.critical + counts.high > 0) {
            repo.lastHighCriticalFindingAt = now
        }
        val saved = if (isNew) githubRepositoryRepository.save(repo) else githubRepositoryRepository.update(repo)

        snapshotRepository.save(
            GithubRepoFindingSnapshot(
                githubRepositoryId = saved.id!!,
                snapshotAt = now,
                criticalCount = counts.critical,
                highCount = counts.high
            )
        )

        alertRepository.deleteByGithubRepositoryId(saved.id!!)
        if (counts.alerts.isNotEmpty()) {
            alertRepository.saveAll(
                counts.alerts.map { a ->
                    GithubRepoDependabotAlert(
                        githubRepositoryId = saved.id!!,
                        alertNumber = a.alertNumber,
                        packageName = a.packageName,
                        ecosystem = a.ecosystem,
                        manifestPath = a.manifestPath,
                        severity = a.severity,
                        ghsaId = a.ghsaId,
                        cveId = a.cveId,
                        summary = a.summary,
                        vulnerableVersionRange = a.vulnerableVersionRange,
                        firstPatchedVersion = a.firstPatchedVersion,
                        htmlUrl = a.htmlUrl,
                        alertCreatedAt = a.alertCreatedAt,
                        alertUpdatedAt = a.alertUpdatedAt
                    )
                }
            )
        }
        return isNew
    }
}
