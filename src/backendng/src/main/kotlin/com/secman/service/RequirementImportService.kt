package com.secman.service

import com.secman.domain.Requirement
import com.secman.domain.UseCase
import com.secman.repository.UseCaseRepository
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import jakarta.transaction.Transactional.TxType
import org.slf4j.LoggerFactory

@Singleton
open class RequirementImportService(
    private val requirementService: RequirementService,
    private val useCaseRepository: UseCaseRepository,
    private val entityManager: EntityManager,
) {
    private val log = LoggerFactory.getLogger(RequirementImportService::class.java)

    @Transactional(TxType.REQUIRES_NEW)
    open fun saveOne(requirement: Requirement): Requirement {
        val saved = requirementService.createRequirement(requirement)
        entityManager.flush()
        return saved
    }

    @Transactional(TxType.REQUIRES_NEW)
    open fun findOrCreateUseCase(name: String): UseCase {
        val existing = useCaseRepository.findByNameIgnoreCase(name).orElse(null)
        if (existing != null) {
            log.debug("Found existing use case: {}", name)
            return existing
        }
        val saved = useCaseRepository.save(UseCase(name = name))
        log.debug("Created new use case: {}", name)
        return saved
    }
}
