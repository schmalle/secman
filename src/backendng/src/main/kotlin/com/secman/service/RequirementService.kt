package com.secman.service

import com.secman.domain.Requirement
import com.secman.repository.RequirementRepository
import com.secman.repository.RequirementSnapshotRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.Instant

@Singleton
open class RequirementService(
    @Inject private val requirementRepository: RequirementRepository,
    @Inject private val snapshotRepository: RequirementSnapshotRepository,
    @Inject private val requirementIdService: RequirementIdService
) {

    fun getAllRequirements(limit: Int? = null): List<Requirement> {
        val requirements = requirementRepository.findCurrentRequirements()
        return if (limit != null) requirements.take(limit) else requirements
    }

    fun getRequirementById(id: Long): Requirement? {
        return requirementRepository.findById(id).orElse(null)
    }

    fun searchRequirements(query: String, limit: Int? = null): List<Requirement> {
        val results = requirementRepository.searchCurrentRequirements(query)
        return if (limit != null) results.take(limit) else results
    }

    fun getRequirementsByLanguage(language: String, limit: Int? = null): List<Requirement> {
        val requirements = requirementRepository.findCurrentRequirementsByLanguage(language)
        return if (limit != null) requirements.take(limit) else requirements
    }

    fun createRequirement(requirement: Requirement): Requirement {
        if (requirement.internalId.isBlank()) {
            requirement.internalId = requirementIdService.getNextId()
        }
        requirement.createdAt = Instant.now()
        requirement.updatedAt = Instant.now()
        return requirementRepository.save(requirement)
    }

    fun updateRequirement(id: Long, updatedRequirement: Requirement): Requirement? {
        return getRequirementById(id)?.let { existing ->
            val updated = existing.copy(
                shortreq = updatedRequirement.shortreq,
                details = updatedRequirement.details,
                language = updatedRequirement.language,
                example = updatedRequirement.example,
                motivation = updatedRequirement.motivation,
                usecase = updatedRequirement.usecase,
                norm = updatedRequirement.norm,
                chapter = updatedRequirement.chapter,
                usecases = updatedRequirement.usecases,
                norms = updatedRequirement.norms,
                updatedAt = Instant.now()
            )
            requirementRepository.update(updated)
            updated
        }
    }

    fun deleteRequirement(id: Long): Boolean {
        // Check if requirement is frozen in any release
        val snapshots = snapshotRepository.findByOriginalRequirementId(id)
        if (snapshots.isNotEmpty()) {
            val releaseVersions = snapshots.map { it.release.version }.distinct().sorted()
            throw IllegalStateException(
                "Cannot delete requirement: frozen in releases ${releaseVersions.joinToString(", ")}"
            )
        }

        return if (requirementRepository.existsById(id)) {
            requirementRepository.deleteById(id)
            true
        } else {
            false
        }
    }

    fun getRequirementsByUsecaseId(usecaseId: Long): List<Requirement> {
        return requirementRepository.findByUsecaseId(usecaseId)
    }

    fun getRequirementsByNormId(normId: Long): List<Requirement> {
        return requirementRepository.findByNormId(normId)
    }

    /**
     * Combined filter method for MCP: filters by usecase (entity name + free-text field),
     * norm (entity name + free-text field), chapter, and full-text search.
     * All filters are ANDed together. Each individual filter matches broadly
     * (UseCase entity name OR free-text usecase field).
     */
    fun filterRequirements(
        search: String? = null,
        usecase: String? = null,
        norm: String? = null,
        chapter: String? = null,
        limit: Int? = null,
        offset: Int = 0
    ): Pair<List<Requirement>, Int> {
        // Start with the most selective filter to minimize in-memory work
        var results: List<Requirement> = when {
            !search.isNullOrBlank() -> requirementRepository.searchCurrentRequirements(search)
            !usecase.isNullOrBlank() -> requirementRepository.findCurrentByUsecaseNameOrTextField(usecase)
            !norm.isNullOrBlank() -> requirementRepository.findCurrentByNormName(norm)
            else -> requirementRepository.findCurrentRequirements()
        }

        // Apply remaining filters in memory (already narrowed by SQL above)
        if (!usecase.isNullOrBlank() && !search.isNullOrBlank()) {
            // usecase wasn't the primary query, apply it as secondary filter
            results = results.filter { req ->
                req.usecases.any { it.name.equals(usecase, ignoreCase = true) } ||
                    req.usecase?.contains(usecase, ignoreCase = true) == true
            }
        }
        if (!norm.isNullOrBlank() && (!search.isNullOrBlank() || !usecase.isNullOrBlank())) {
            results = results.filter { req ->
                req.norms.any { it.name.equals(norm, ignoreCase = true) } ||
                    req.norm?.contains(norm, ignoreCase = true) == true
            }
        }
        if (!chapter.isNullOrBlank()) {
            results = results.filter {
                it.chapter?.contains(chapter, ignoreCase = true) == true
            }
        }

        val total = results.size
        val paged = results.drop(offset).let { if (limit != null) it.take(limit) else it }
        return Pair(paged, total)
    }

    /**
     * Checks if a content change requires a revision increment.
     * Content fields: shortreq, details, example, motivation, usecase, norm, chapter
     * Relationship fields (usecases, norms ManyToMany) do NOT trigger revision increment.
     */
    fun shouldIncrementRevision(
        existing: Requirement,
        newShortreq: String?,
        newDetails: String?,
        newExample: String?,
        newMotivation: String?,
        newUsecase: String?,
        newNorm: String?,
        newChapter: String?
    ): Boolean {
        return (newShortreq != null && newShortreq != existing.shortreq) ||
               (newDetails != null && newDetails != existing.details) ||
               (newExample != null && newExample != existing.example) ||
               (newMotivation != null && newMotivation != existing.motivation) ||
               (newUsecase != null && newUsecase != existing.usecase) ||
               (newNorm != null && newNorm != existing.norm) ||
               (newChapter != null && newChapter != existing.chapter)
    }
}