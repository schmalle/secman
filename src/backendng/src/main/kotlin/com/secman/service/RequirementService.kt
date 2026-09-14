package com.secman.service

import com.secman.domain.Requirement
import com.secman.repository.RequirementRepository
import com.secman.repository.RequirementSnapshotRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import io.micronaut.data.model.Pageable
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
        val boundedLimit = limit ?: 50
        val fetchSize = offset + boundedLimit
        val page = requirementRepository.findCurrentFiltered(
            search = search?.trim().orEmpty(),
            usecase = usecase?.trim().orEmpty(),
            norm = norm?.trim().orEmpty(),
            chapter = chapter?.trim().orEmpty(),
            pageable = Pageable.from(0, fetchSize)
        )
        return page.content.drop(offset).take(boundedLimit) to page.totalSize.toInt()
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
