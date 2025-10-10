package com.secman.controller

import com.secman.domain.Standard
import com.secman.domain.Requirement
import com.secman.repository.StandardRepository
import com.secman.repository.UseCaseRepository
import com.secman.repository.RequirementRepository
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.annotation.Transactional
import jakarta.persistence.EntityManager
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory

@Controller("/api/standards")
@Secured(SecurityRule.IS_AUTHENTICATED) // All authenticated users can read
@ExecuteOn(TaskExecutors.BLOCKING)
open class StandardController(
    private val standardRepository: StandardRepository,
    private val useCaseRepository: UseCaseRepository,
    private val requirementRepository: RequirementRepository,
    private val entityManager: EntityManager
) {
    
    private val log = LoggerFactory.getLogger(StandardController::class.java)

    @Serdeable
    data class CreateStandardRequest(
        @NotBlank val name: String,
        @Nullable val description: String? = null,
        @Nullable val useCaseIds: List<Long>? = null
    )

    @Serdeable
    data class UpdateStandardRequest(
        @Nullable val name: String? = null,
        @Nullable val description: String? = null,
        @Nullable val useCaseIds: List<Long>? = null
    )

    @Serdeable
    data class ErrorResponse(
        val error: String,
        val message: String
    )

    @Get
    @Transactional(readOnly = true)
    open fun listStandards(): HttpResponse<List<Standard>> {
        return try {
            log.debug("Fetching all standards")
            
            // Query with JOIN FETCH to force loading of useCases
            val standards = entityManager.createQuery(
                "SELECT DISTINCT s FROM Standard s LEFT JOIN FETCH s.useCases ORDER BY s.name",
                Standard::class.java
            ).resultList
            
            log.debug("Found {} standards", standards.size)
            HttpResponse.ok(standards)
        } catch (e: Exception) {
            log.error("Error fetching standards", e)
            HttpResponse.serverError<List<Standard>>()
        }
    }

    @Get("/{id}")
    @Transactional(readOnly = true)
    open fun getStandard(id: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching standard with id: {}", id)
            
            // Use JOIN FETCH to force loading of useCases
            val standard = entityManager.createQuery(
                "SELECT s FROM Standard s LEFT JOIN FETCH s.useCases WHERE s.id = :id",
                Standard::class.java
            ).setParameter("id", id).resultList.firstOrNull()
            
            if (standard != null) {
                log.debug("Found standard: {}", standard.name)
                HttpResponse.ok(standard)
            } else {
                log.debug("Standard not found with id: {}", id)
                HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Standard not found"))
            }
        } catch (e: Exception) {
            log.error("Error fetching standard with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post
    @Secured("ADMIN", "CHAMPION", "REQ") // Only privileged users can create
    @Transactional
    open fun createStandard(@Valid @Body request: CreateStandardRequest): HttpResponse<*> {
        return try {
            log.debug("Creating standard with name: {}", request.name)
            
            val trimmedName = request.name.trim()
            
            // Check for empty name after trimming
            if (trimmedName.isBlank()) {
                return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Name cannot be empty"))
            }
            
            // Check for name uniqueness (case-insensitive)
            if (standardRepository.existsByName(trimmedName)) {
                log.warn("Standard name already exists: {}", trimmedName)
                return HttpResponse.status<ErrorResponse>(HttpStatus.CONFLICT)
                    .body(ErrorResponse("CONFLICT", "Standard with this name already exists"))
            }
            
            // Create new standard
            val standard = Standard(
                name = trimmedName,
                description = request.description?.trim()
            )
            
            // Handle use case associations
            request.useCaseIds?.let { ids ->
                val useCases = ids.mapNotNull { useCaseId ->
                    useCaseRepository.findById(useCaseId).orElse(null)
                }.toMutableSet()
                standard.useCases = useCases
                log.debug("Associated {} use cases with standard", useCases.size)
            }
            
            val savedStandard = standardRepository.save(standard)
            
            // Force loading of useCases for response
            entityManager.refresh(savedStandard)
            savedStandard.useCases.size // Force lazy loading
            
            log.info("Created standard: {} with id: {}", savedStandard.name, savedStandard.id)
            HttpResponse.status<Standard>(HttpStatus.CREATED).body(savedStandard)
        } catch (e: Exception) {
            log.error("Error creating standard", e)
            HttpResponse.serverError<Any>()
        }
    }

    @Put("/{id}")
    @Secured("ADMIN", "CHAMPION", "REQ") // Only privileged users can update
    @Transactional
    open fun updateStandard(id: Long, @Valid @Body request: UpdateStandardRequest): HttpResponse<*> {
        return try {
            log.debug("Updating standard with id: {}", id)
            
            val standard = standardRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Standard not found"))
            
            // Update name if provided
            request.name?.let { newName ->
                val trimmedName = newName.trim()
                
                if (trimmedName.isBlank()) {
                    return HttpResponse.badRequest(ErrorResponse("VALIDATION_ERROR", "Name cannot be empty"))
                }
                
                // Check for name uniqueness (excluding current standard)
                val existingStandard = standardRepository.findByName(trimmedName).orElse(null)
                if (existingStandard != null && existingStandard.id != id) {
                    log.warn("Standard name already exists: {}", trimmedName)
                    return HttpResponse.status<ErrorResponse>(HttpStatus.CONFLICT)
                        .body(ErrorResponse("CONFLICT", "Standard with this name already exists"))
                }
                
                standard.name = trimmedName
            }
            
            // Update description if provided
            request.description?.let { newDescription ->
                standard.description = newDescription.trim().takeIf { it.isNotBlank() }
            }
            
            // Update use case associations if provided
            request.useCaseIds?.let { ids ->
                val useCases = ids.mapNotNull { useCaseId ->
                    useCaseRepository.findById(useCaseId).orElse(null)
                }.toMutableSet()
                standard.useCases.clear()
                standard.useCases.addAll(useCases)
                log.debug("Updated use case associations: {} use cases", useCases.size)
            }
            
            val updatedStandard = standardRepository.update(standard)
            
            // Force loading of useCases for response
            entityManager.refresh(updatedStandard)
            updatedStandard.useCases.size // Force lazy loading
            
            log.info("Updated standard: {} with id: {}", updatedStandard.name, updatedStandard.id)
            HttpResponse.ok(updatedStandard)
        } catch (e: Exception) {
            log.error("Error updating standard with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Delete("/{id}")
    @Secured("ADMIN", "CHAMPION", "REQ") // Only privileged users can delete
    @Transactional
    open fun deleteStandard(id: Long): HttpResponse<*> {
        return try {
            log.debug("Deleting standard with id: {}", id)
            
            val standard = standardRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Standard not found"))
            
            standardRepository.delete(standard)
            
            log.info("Deleted standard: {} with id: {}", standard.name, id)
            HttpResponse.ok(mapOf("message" to "Standard deleted successfully"))
        } catch (e: Exception) {
            log.error("Error deleting standard with id: {}", id, e)
            
            // Check if it's a constraint violation (standard in use)
            if (e.message?.contains("constraint", ignoreCase = true) == true ||
                e.message?.contains("foreign key", ignoreCase = true) == true) {
                return HttpResponse.status<ErrorResponse>(HttpStatus.CONFLICT)
                    .body(ErrorResponse("CONFLICT", "Cannot delete standard - it may be in use"))
            }
            
            HttpResponse.serverError<Any>()
        }
    }

    @Get("/{id}/requirements")
    @Transactional(readOnly = true)
    open fun getStandardRequirements(id: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching requirements for standard with id: {}", id)
            
            // First get the standard with its use cases
            val standard = entityManager.createQuery(
                "SELECT s FROM Standard s LEFT JOIN FETCH s.useCases WHERE s.id = :id",
                Standard::class.java
            ).setParameter("id", id).resultList.firstOrNull()
            
            if (standard == null) {
                log.debug("Standard not found with id: {}", id)
                return HttpResponse.notFound(ErrorResponse("NOT_FOUND", "Standard not found"))
            }
            
            // Get all requirements for the use cases associated with this standard
            val requirements = mutableListOf<Requirement>()
            standard.useCases.forEach { useCase ->
                val useCaseRequirements = requirementRepository.findByUsecaseId(useCase.id!!)
                requirements.addAll(useCaseRequirements)
            }
            
            // Remove duplicates (in case a requirement is associated with multiple use cases)
            val uniqueRequirements = requirements.distinctBy { it.id }
            
            log.debug("Found {} requirements for standard: {}", uniqueRequirements.size, standard.name)
            HttpResponse.ok(uniqueRequirements)
        } catch (e: Exception) {
            log.error("Error fetching requirements for standard with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }
}