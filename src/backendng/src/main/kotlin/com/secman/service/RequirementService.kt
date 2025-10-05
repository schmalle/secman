package com.secman.service

import com.secman.domain.Requirement
import com.secman.repository.RequirementRepository
import com.secman.repository.RequirementSnapshotRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.Instant

@Singleton
class RequirementService(
    @Inject private val requirementRepository: RequirementRepository,
    @Inject private val snapshotRepository: RequirementSnapshotRepository
) {

    fun getAllRequirements(limit: Int? = null): List<Requirement> {
        val requirements = requirementRepository.findCurrentRequirements()
        return if (limit != null) requirements.take(limit) else requirements
    }

    fun getRequirementById(id: Long): Requirement? {
        return requirementRepository.findById(id).orElse(null)
    }

    fun searchRequirements(query: String, limit: Int? = null): List<Requirement> {
        val titleMatches = requirementRepository.findByShortreqContainingIgnoreCase(query)
        val descriptionMatches = requirementRepository.findByDetailsContainingIgnoreCase(query)

        val combined = (titleMatches + descriptionMatches).distinctBy { it.id }
        return if (limit != null) combined.take(limit) else combined
    }

    fun getRequirementsByLanguage(language: String, limit: Int? = null): List<Requirement> {
        val requirements = requirementRepository.findCurrentRequirementsByLanguage(language)
        return if (limit != null) requirements.take(limit) else requirements
    }

    fun createRequirement(requirement: Requirement): Requirement {
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
}