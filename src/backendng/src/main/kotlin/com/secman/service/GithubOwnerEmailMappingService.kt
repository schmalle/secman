package com.secman.service

import com.secman.domain.GithubOwnerEmailMapping
import com.secman.repository.GithubOwnerEmailMappingRepository
import com.secman.repository.GithubRepositoryRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

/**
 * CRUD for [GithubOwnerEmailMapping], the default owner->email mapping used
 * to auto-fill [com.secman.domain.GithubRepository.ownerEmail] on import.
 *
 * Creating or updating a mapping immediately backfills every existing repo
 * for that owner whose `ownerEmail` is currently blank (case-insensitive
 * owner match) — repos with a manually-set or previously auto-filled
 * `ownerEmail` are left untouched. Deleting a mapping does not un-set any
 * `ownerEmail` it previously filled.
 */
@Singleton
open class GithubOwnerEmailMappingService(
    private val mappingRepository: GithubOwnerEmailMappingRepository,
    private val githubRepositoryRepository: GithubRepositoryRepository
) {
    private val log = LoggerFactory.getLogger(GithubOwnerEmailMappingService::class.java)

    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    class DuplicateOwnerException(owner: String) : IllegalStateException("A mapping for owner '$owner' already exists")
    class InvalidEmailException(email: String) : IllegalArgumentException("Invalid email address: $email")
    class NotFoundException(id: Long) : NoSuchElementException("Mapping $id not found")

    @Transactional
    open fun list(): List<GithubOwnerEmailMapping> = mappingRepository.findAll().sortedBy { it.owner.lowercase() }

    fun repoCountFor(owner: String): Long = githubRepositoryRepository.countByOwnerIgnoreCase(owner)

    @Transactional
    open fun create(owner: String, email: String, actor: String): GithubOwnerEmailMapping {
        val trimmedOwner = owner.trim()
        val normalizedEmail = validateAndNormalize(email)

        if (mappingRepository.findByOwnerIgnoreCase(trimmedOwner).isPresent) {
            throw DuplicateOwnerException(trimmedOwner)
        }

        val saved = mappingRepository.save(
            GithubOwnerEmailMapping(
                owner = trimmedOwner,
                email = normalizedEmail,
                createdBy = actor
            )
        )
        backfill(trimmedOwner, normalizedEmail)
        log.info("GitHub owner email mapping created: {} -> {} by {}", trimmedOwner, normalizedEmail, actor)
        return saved
    }

    @Transactional
    open fun update(id: Long, email: String): GithubOwnerEmailMapping {
        val mapping = mappingRepository.findById(id).orElseThrow { NotFoundException(id) }
        val normalizedEmail = validateAndNormalize(email)
        val previousEmail = mapping.email

        mapping.email = normalizedEmail
        mapping.updatedAt = java.time.Instant.now()
        val saved = mappingRepository.update(mapping)
        backfill(mapping.owner, normalizedEmail, replaceable = previousEmail)
        log.info("GitHub owner email mapping {} updated to {}", mapping.owner, normalizedEmail)
        return saved
    }

    @Transactional
    open fun delete(id: Long) {
        val mapping = mappingRepository.findById(id).orElseThrow { NotFoundException(id) }
        mappingRepository.delete(mapping)
        log.info("GitHub owner email mapping for {} deleted", mapping.owner)
    }

    /**
     * Fill `ownerEmail` on every repo for [owner] whose value is currently blank.
     * On mapping update, [replaceable] carries the mapping's previous email so repos
     * still holding the mapping-driven value follow the update; repos whose email was
     * hand-edited to something else are never touched.
     */
    private fun backfill(owner: String, email: String, replaceable: String? = null) {
        val repos = githubRepositoryRepository.findByOwnerIgnoreCase(owner)
            .filter {
                it.ownerEmail.isNullOrBlank() ||
                    (replaceable != null && it.ownerEmail.equals(replaceable, ignoreCase = true))
            }
        repos.forEach { repo ->
            repo.ownerEmail = email
            githubRepositoryRepository.update(repo)
        }
        if (repos.isNotEmpty()) {
            log.info("Backfilled ownerEmail for {} repo(s) under owner {}", repos.size, owner)
        }
    }

    private fun validateAndNormalize(email: String): String {
        val trimmed = email.trim()
        if (!emailRegex.matches(trimmed)) {
            throw InvalidEmailException(email)
        }
        return trimmed.lowercase()
    }
}
