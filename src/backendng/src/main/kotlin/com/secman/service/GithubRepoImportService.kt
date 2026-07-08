package com.secman.service

import com.secman.domain.GithubRepoFindingSnapshot
import com.secman.domain.GithubRepository
import com.secman.repository.GithubAppConfigRepository
import com.secman.repository.GithubRepoFindingSnapshotRepository
import com.secman.repository.GithubRepositoryRepository
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Imports the GitHub App installation's repositories into secman: upserts
 * [GithubRepository] rows (by stable numeric GitHub repo id) with their open
 * Dependabot alert counts and writes one [GithubRepoFindingSnapshot] per repo
 * per run — the history behind the 30-day non-decrease alert.
 *
 * Triggered by `POST /api/github/import` (CLI `import-github-repos`, MCP
 * `import_github_repos`, or the UI "Import now" button).
 */
@Singleton
open class GithubRepoImportService(
    private val githubAppConfigRepository: GithubAppConfigRepository,
    private val githubRepositoryRepository: GithubRepositoryRepository,
    private val snapshotRepository: GithubRepoFindingSnapshotRepository,
    private val githubClient: GithubAppClientService
) {
    private val logger = LoggerFactory.getLogger(GithubRepoImportService::class.java)

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

        val token = githubClient.getInstallationToken(config)
        val discovered = githubClient.listInstallationRepositories(token)
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
                val counts = githubClient.countOpenDependabotAlerts(token, repoDto.owner, repoDto.name)
                if (counts.disabled) {
                    alertsDisabled.add(repoDto.fullName)
                }
                totalCritical += counts.critical
                totalHigh += counts.high
                val wasNew = persistRepo(repoDto, counts, now)
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

    /** Upsert one repo + its snapshot. Returns true when a new row was created. */
    @Transactional
    open fun persistRepo(
        repoDto: GithubAppClientService.GithubRepoDto,
        counts: GithubAppClientService.SeverityCounts,
        now: Instant
    ): Boolean {
        val existing = githubRepositoryRepository.findByGithubRepoId(repoDto.repoId)
            .or { githubRepositoryRepository.findByFullName(repoDto.fullName) }
            .orElse(null)

        val isNew = existing == null
        val repo = existing ?: GithubRepository()
        repo.githubRepoId = repoDto.repoId
        repo.name = repoDto.name
        repo.owner = repoDto.owner
        repo.fullName = repoDto.fullName
        repo.htmlUrl = repoDto.htmlUrl
        repo.archived = repoDto.archived
        // ownerEmail is user-maintained — never touched by the import
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
        return isNew
    }
}
