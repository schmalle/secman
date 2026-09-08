package com.secman.service

import com.secman.domain.*
import com.secman.dto.*
import com.secman.repository.IntegrationRepository
import com.secman.repository.UserRepository
import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException
import io.micronaut.security.authentication.Authentication
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

@Singleton
open class IntegrationAdminService(
    private val repository: IntegrationRepository,
    private val access: IntegrationAccessService,
    private val users: UserRepository
) {
    private val log = LoggerFactory.getLogger(IntegrationAdminService::class.java)

    @Transactional
    open fun saveScanner(id: Long?, request: IntegrationScannerRequest, auth: Authentication): IntegrationScannerDto {
        access.requireAdmin(auth)
        if (request.name.isBlank() || request.name.length > 200 || request.name.any { it.isISOControl() } ||
            request.source !in IntegrationRunValidator.SOURCES || request.staleAfterHours !in 1..8760 ||
            !users.existsById(request.serviceUserId)) invalid("Invalid scanner configuration")
        val scanner = id?.let { repository.lock(IntegrationScanner::class.java, it) ?: access.notFound() } ?: IntegrationScanner()
        if (id == null && repository.count(com.secman.repository.IntegrationCountQuery.SCANNERS, null) >= 100) invalid("Scanner limit reached")
        if (id != null && scanner.source != request.source) invalid("Scanner source cannot be changed")
        scanner.name = request.name; scanner.source = request.source; scanner.serviceUserId = request.serviceUserId
        scanner.staleAfterHours = request.staleAfterHours; scanner.enabled = request.enabled
        if (id == null) repository.persist(scanner)
        log.info("Integration scanner saved: actorId={} scannerId={} outcome=success", users.findByUsername(auth.name).orElseThrow().id, scanner.id)
        return scannerDto(scanner)
    }

    @Transactional
    open fun bind(scannerId: Long, request: IntegrationSubjectRequest, auth: Authentication): Long {
        access.requireAdmin(auth)
        val scanner = repository.lock(IntegrationScanner::class.java, scannerId) ?: access.notFound()
        if ((request.assetId == null) == (request.githubRepositoryId == null)) invalid("Exactly one inventory ID is required")
        val repo = request.githubRepositoryId?.let { repository.lock(GithubRepository::class.java, it) ?: access.notFound() }
        if (repo != null && scanner.source != "GITHUB_AI") invalid("GitHub subjects require a GITHUB_AI scanner")
        val asset = if (repo != null) {
            repository.repositorySubject(repo.id!!)?.let { repository.find(Asset::class.java, it.assetId) }
                ?: resolveRepositoryAsset(repo, auth)
        } else {
            access.requireAsset(request.assetId!!, auth)
            repository.lock(Asset::class.java, request.assetId) ?: access.notFound()
        }
        access.requireAsset(asset.id!!, auth)
        repository.boundSubject(scannerId, asset.id!!)?.let { existing ->
            if (existing.githubRepositoryId != repo?.id) invalid("Asset already has a different binding")
            return existing.id!!
        }
        val subject = IntegrationSubject(scannerId = scannerId, assetId = asset.id!!, githubRepositoryId = repo?.id)
        repository.persist(subject)
        log.info("Integration subject bound: scannerId={} subjectId={} assetId={} outcome=success", scannerId, subject.id, asset.id)
        return subject.id!!
    }

    private fun resolveRepositoryAsset(repo: GithubRepository, auth: Authentication): Asset {
        val legacy = repository.legacyAssets(repo.fullName)
        // A name-only legacy match is safe only on the public instance; enterprise assets need a matching URI.
        val matching = legacy.filter { it.uri == repo.htmlUrl || (repo.githubInstance == "github.com" && it.uri.isNullOrBlank()) }
        if (legacy.size == 1 && matching.size == 1) {
            access.requireAsset(matching.single().id!!, auth)
            return matching.single()
        }
        val owner = repo.ownerEmail?.let { users.findByEmailIgnoreCase(it).orElse(null)?.username }
            ?: users.findByUsername(auth.name).orElseThrow().username
        val asset = Asset(name = repo.fullName.take(255), type = "REPOSITORY", owner = owner, uri = repo.htmlUrl)
        asset.manualCreator = users.findByUsername(auth.name).orElseThrow()
        repository.persist(asset)
        return asset
    }

    private fun invalid(message: String): Nothing = throw HttpStatusException(HttpStatus.BAD_REQUEST, message)
    companion object {
        fun scannerDto(s: IntegrationScanner) = IntegrationScannerDto(s.id!!, s.name, s.source, s.serviceUserId, s.staleAfterHours, s.enabled)
    }
}
