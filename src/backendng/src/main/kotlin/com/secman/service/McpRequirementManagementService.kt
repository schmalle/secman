package com.secman.service

import com.secman.domain.Norm
import com.secman.domain.Requirement
import com.secman.domain.UseCase
import com.secman.repository.NormRepository
import com.secman.repository.RequirementRepository
import com.secman.repository.UseCaseRepository
import io.micronaut.data.model.Page
import io.micronaut.data.model.Pageable
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory

data class RequirementChanges(
    val shortreq: String? = null,
    val details: String? = null,
    val language: String? = null,
    val example: String? = null,
    val motivation: String? = null,
    val usecase: String? = null,
    val norm: String? = null,
    val chapter: String? = null,
    val clearFields: Set<String> = emptySet(),
    val useCaseIds: List<Long>? = null,
    val normIds: List<Long>? = null
)

class RequirementManagementNotFound(message: String) : RuntimeException(message)
class RequirementManagementConflict(message: String) : RuntimeException(message)

/** Transactional business rules shared by the MCP requirement and use-case tools. */
@Singleton
open class McpRequirementManagementService(
    private val requirementService: RequirementService,
    private val requirementRepository: RequirementRepository,
    private val useCaseRepository: UseCaseRepository,
    private val normRepository: NormRepository
) {
    private val log = LoggerFactory.getLogger(McpRequirementManagementService::class.java)

    @Transactional
    open fun createRequirement(
        requirement: Requirement,
        useCaseIds: List<Long>,
        normIds: List<Long>,
        actorUserId: Long?
    ): Requirement {
        requirement.usecases = resolveUseCases(useCaseIds)
        requirement.norms = resolveNorms(normIds)
        val saved = requirementService.createRequirement(requirement)
        log.info("MCP requirement created: actorUserId={} requirementId={} outcome=created", actorUserId, saved.id)
        return saved
    }

    @Transactional
    open fun updateRequirement(id: Long, changes: RequirementChanges, actorUserId: Long?): Requirement {
        val requirement = requirementRepository.findById(id).orElse(null)
            ?: throw RequirementManagementNotFound("Requirement not found")

        if (changes.changesVersionedContent(requirement)) requirement.incrementVersion()

        changes.shortreq?.let { requirement.shortreq = it }
        requirement.details = changes.updatedValue("details", requirement.details, changes.details)
        requirement.language = changes.updatedValue("language", requirement.language, changes.language)
        requirement.example = changes.updatedValue("example", requirement.example, changes.example)
        requirement.motivation = changes.updatedValue("motivation", requirement.motivation, changes.motivation)
        requirement.usecase = changes.updatedValue("usecase", requirement.usecase, changes.usecase)
        requirement.norm = changes.updatedValue("norm", requirement.norm, changes.norm)
        requirement.chapter = changes.updatedValue("chapter", requirement.chapter, changes.chapter)
        changes.useCaseIds?.let { requirement.usecases = resolveUseCases(it) }
        changes.normIds?.let { requirement.norms = resolveNorms(it) }

        val saved = requirementRepository.update(requirement)
        log.info("MCP requirement updated: actorUserId={} requirementId={} outcome=updated", actorUserId, id)
        return saved
    }

    @Transactional
    open fun replaceRequirementUseCases(id: Long, useCaseIds: List<Long>, actorUserId: Long?): Requirement {
        val requirement = requirementRepository.findById(id).orElse(null)
            ?: throw RequirementManagementNotFound("Requirement not found")
        requirement.usecases = resolveUseCases(useCaseIds)
        val saved = requirementRepository.update(requirement)
        log.info(
            "MCP requirement use cases replaced: actorUserId={} requirementId={} assignmentCount={} outcome=updated",
            actorUserId,
            id,
            useCaseIds.size
        )
        return saved
    }

    @Transactional
    open fun deleteRequirement(id: Long, actorUserId: Long?) {
        try {
            if (!requirementService.deleteRequirement(id)) {
                throw RequirementManagementNotFound("Requirement not found")
            }
        } catch (e: IllegalStateException) {
            throw RequirementManagementConflict(e.message ?: "Requirement is still referenced")
        }
        log.info("MCP requirement deleted: actorUserId={} requirementId={} outcome=deleted", actorUserId, id)
    }

    fun listUseCases(search: String, page: Int, pageSize: Int): Page<UseCase> {
        val pageable = Pageable.from(page, pageSize)
        return if (search.isBlank()) {
            useCaseRepository.findAll(pageable)
        } else {
            useCaseRepository.findByNameContainingIgnoreCase(search, pageable)
        }
    }

    @Transactional
    open fun createUseCase(name: String, actorUserId: Long?): UseCase {
        if (useCaseRepository.findByNameIgnoreCase(name).isPresent) {
            throw RequirementManagementConflict("A use case with this name already exists")
        }
        val saved = useCaseRepository.save(UseCase(name = name))
        log.info("MCP use case created: actorUserId={} useCaseId={} outcome=created", actorUserId, saved.id)
        return saved
    }

    @Transactional
    open fun updateUseCase(id: Long, name: String, actorUserId: Long?): UseCase {
        val useCase = useCaseRepository.findById(id).orElse(null)
            ?: throw RequirementManagementNotFound("Use case not found")
        if (useCase.systemProtected && name != useCase.name) {
            throw RequirementManagementConflict("System-protected use cases cannot be renamed")
        }
        if (useCaseRepository.findByNameIgnoreCaseExcludingId(name, id).isPresent) {
            throw RequirementManagementConflict("A use case with this name already exists")
        }
        useCase.name = name
        val saved = useCaseRepository.update(useCase)
        log.info("MCP use case updated: actorUserId={} useCaseId={} outcome=updated", actorUserId, id)
        return saved
    }

    @Transactional
    open fun deleteUseCase(id: Long, actorUserId: Long?) {
        val useCase = useCaseRepository.findById(id).orElse(null)
            ?: throw RequirementManagementNotFound("Use case not found")
        if (useCase.systemProtected) {
            throw RequirementManagementConflict("System-protected use cases cannot be deleted")
        }
        val requirementCount = useCaseRepository.countRequirementsByUseCaseId(id)
        if (requirementCount > 0) {
            throw RequirementManagementConflict(
                "Use case is assigned to $requirementCount requirement(s); remove those assignments first"
            )
        }
        useCaseRepository.delete(useCase)
        log.info("MCP use case deleted: actorUserId={} useCaseId={} outcome=deleted", actorUserId, id)
    }

    private fun resolveUseCases(ids: List<Long>): MutableSet<UseCase> = ids.map { id ->
        useCaseRepository.findById(id).orElse(null)
            ?: throw RequirementManagementNotFound("Use case $id not found")
    }.toMutableSet()

    private fun resolveNorms(ids: List<Long>): MutableSet<Norm> = ids.map { id ->
        normRepository.findById(id).orElse(null)
            ?: throw RequirementManagementNotFound("Norm $id not found")
    }.toMutableSet()

    private fun RequirementChanges.updatedValue(field: String, current: String?, supplied: String?): String? = when {
        field in clearFields -> null
        supplied != null -> supplied
        else -> current
    }

    private fun RequirementChanges.changesVersionedContent(existing: Requirement): Boolean =
        (shortreq != null && shortreq != existing.shortreq) ||
            fieldChanged("details", existing.details, details) ||
            fieldChanged("example", existing.example, example) ||
            fieldChanged("motivation", existing.motivation, motivation) ||
            fieldChanged("usecase", existing.usecase, usecase) ||
            fieldChanged("norm", existing.norm, norm) ||
            fieldChanged("chapter", existing.chapter, chapter)

    private fun RequirementChanges.fieldChanged(field: String, current: String?, supplied: String?): Boolean =
        if (field in clearFields) current != null else supplied != null && supplied != current
}
