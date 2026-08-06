package com.secman.service

import com.secman.repository.GithubAppConfigRepository
import com.secman.repository.GithubOwnerEmailMappingRepository
import com.secman.repository.GithubRepositoryRepository
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Best-effort, opt-in complement to the manual [com.secman.domain.GithubOwnerEmailMapping]
 * system: for every already-imported [com.secman.domain.GithubRepository] with no
 * `ownerEmail` set, looks up the owner's public GitHub profile email
 * (`GET /users/{owner}`, via [GithubAppClientService.fetchPublicEmail]) and — unless
 * `dryRun` — creates a mapping via [GithubOwnerEmailMappingService.create], which also
 * backfills every matching repo, exactly like a manually-created mapping.
 *
 * Never calls GitHub's repo-listing API; only touches owners that don't already have a
 * mapping. Triggered explicitly via CLI (`manage-github-owner-mappings discover`) or MCP
 * (`discover_github_owner_email_mappings`) — never runs as part of `import-github-repos`.
 */
@Singleton
open class GithubOwnerEmailDiscoveryService(
    private val githubAppConfigRepository: GithubAppConfigRepository,
    private val githubRepositoryRepository: GithubRepositoryRepository,
    private val ownerEmailMappingRepository: GithubOwnerEmailMappingRepository,
    private val ownerEmailMappingService: GithubOwnerEmailMappingService,
    private val githubClient: GithubAppClientService
) {
    private val log = LoggerFactory.getLogger(GithubOwnerEmailDiscoveryService::class.java)

    @Serdeable
    data class DiscoveredMapping(val owner: String, val email: String, val repoCount: Int)

    @Serdeable
    data class DiscoveryResult(
        val status: String,
        val ownersEvaluated: Int,
        val ownersDiscovered: Int,
        val discoveredMappings: List<DiscoveredMapping>,
        val ownersSkippedNoPublicEmail: List<String>,
        val errors: List<String>
    )

    // Deliberately NOT @Transactional: this method makes N sequential GitHub REST calls
    // (fetchPublicEmail) in the loop below. A method-level transaction would pin one pooled
    // DB connection across every one of those HTTP round-trips. The only reads here
    // (findByOwnerEmailIsNull / findByOwnerIgnoreCase) auto-commit per call and touch no
    // lazy relations, and each mapping write goes through GithubOwnerEmailMappingService.create(),
    // which is @Transactional on its own bean — so every DB touch is a short, self-contained tx.
    open fun discover(dryRun: Boolean, actor: String): DiscoveryResult {
        val config = githubAppConfigRepository.findActiveConfig().orElseThrow {
            IllegalStateException("No active GitHub App configuration — configure one under Admin → GitHub App")
        }
        val apiBaseUrl = config.effectiveApiBaseUrl()
        val token = githubClient.getInstallationToken(config)

        val ownersToRepoCount = githubRepositoryRepository.findByOwnerEmailIsNull()
            .groupingBy { it.owner }
            .eachCount()
        val candidateOwners = ownersToRepoCount.keys.filter {
            ownerEmailMappingRepository.findByOwnerIgnoreCase(it).isEmpty
        }
        log.info(
            "GitHub owner email discovery: {} owner(s) with unmapped repos ({} already have a stale mapping, skipped)",
            candidateOwners.size, ownersToRepoCount.size - candidateOwners.size
        )

        val discovered = mutableListOf<DiscoveredMapping>()
        val skipped = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (owner in candidateOwners) {
            try {
                val email = githubClient.fetchPublicEmail(token, owner, apiBaseUrl)
                if (email.isNullOrBlank()) {
                    skipped.add(owner)
                    continue
                }
                val repoCount = ownersToRepoCount[owner] ?: 0
                if (!dryRun) {
                    ownerEmailMappingService.create(owner, email, actor)
                }
                discovered.add(DiscoveredMapping(owner, email, repoCount))
            } catch (e: Exception) {
                log.warn("GitHub owner email discovery: failed for owner {}: {}", owner, e.message)
                errors.add("$owner: ${e.message}")
            }
        }

        val status = when {
            errors.isNotEmpty() && discovered.isEmpty() -> "FAILURE"
            errors.isNotEmpty() -> "PARTIAL_FAILURE"
            dryRun -> "DRY_RUN"
            else -> "SUCCESS"
        }
        log.info(
            "GitHub owner email discovery complete: {} evaluated, {} discovered, {} skipped (no public email), {} errors, dryRun={}",
            candidateOwners.size, discovered.size, skipped.size, errors.size, dryRun
        )
        return DiscoveryResult(
            status = status,
            ownersEvaluated = candidateOwners.size,
            ownersDiscovered = discovered.size,
            discoveredMappings = discovered,
            ownersSkippedNoPublicEmail = skipped,
            errors = errors
        )
    }
}
