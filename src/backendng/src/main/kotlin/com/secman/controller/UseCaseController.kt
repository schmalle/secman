package com.secman.controller

import com.secman.domain.UseCase
import com.secman.repository.UseCaseRepository
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

/**
 * UseCase Controller
 * Feature: 025-role-based-access-control
 *
 * Access Control:
 * - ADMIN: Full access to all use case operations
 * - REQ: Full access to all use case operations
 * - SECCHAMPION: Full access to all use case operations
 * - Other roles: Access denied (403 Forbidden)
 */
@Controller("/api/usecases")
@Secured("ADMIN", "REQ", "SECCHAMPION")
@ExecuteOn(TaskExecutors.BLOCKING)
open class UseCaseController(
    private val useCaseRepository: UseCaseRepository,
    private val entityManager: EntityManager
) {
    
    private val log = LoggerFactory.getLogger(UseCaseController::class.java)

    @Serdeable
    data class CreateUseCaseRequest(
        @NotBlank val name: String
    )

    @Serdeable
    data class UpdateUseCaseRequest(
        @NotBlank val name: String
    )

    @Serdeable
    data class ErrorResponse(
        val error: String
    )

    @Get
    @Transactional(readOnly = true)
    open fun getUseCases(): HttpResponse<List<UseCase>> {
        return try {
            log.debug("Fetching all use cases")
            
            // Get use cases ordered by name (same as Java implementation)
            val useCases = entityManager.createQuery(
                "SELECT uc FROM UseCase uc ORDER BY uc.name",
                UseCase::class.java
            ).resultList
            
            log.debug("Found {} use cases", useCases.size)
            HttpResponse.ok(useCases)
        } catch (e: Exception) {
            log.error("Error fetching use cases", e)
            HttpResponse.serverError<List<UseCase>>()
        }
    }

    @Get("/{id}")
    @Transactional(readOnly = true)
    open fun getUseCase(id: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching use case with id: {}", id)
            
            val useCase = useCaseRepository.findById(id).orElse(null)
            
            if (useCase != null) {
                log.debug("Found use case: {}", useCase.name)
                HttpResponse.ok(useCase)
            } else {
                log.debug("UseCase not found with id: {}", id)
                HttpResponse.notFound(ErrorResponse("UseCase not found"))
            }
        } catch (e: Exception) {
            log.error("Error fetching use case with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post
    @Secured("ADMIN", "SECCHAMPION", "REQ") // Only privileged users can create
    @Transactional
    open fun createUseCase(@Valid @Body request: CreateUseCaseRequest): HttpResponse<*> {
        return try {
            log.debug("Creating use case with name: {}", request.name)
            
            val trimmedName = request.name.trim()
            
            // Check for empty name after trimming
            if (trimmedName.isBlank()) {
                return HttpResponse.badRequest(ErrorResponse("Name is required"))
            }
            
            // Check for case-insensitive name uniqueness (same as Java implementation)
            val existingUseCase = useCaseRepository.findByNameIgnoreCase(trimmedName).orElse(null)
            if (existingUseCase != null) {
                log.warn("UseCase name already exists (case-insensitive): {}", trimmedName)
                return HttpResponse.badRequest(ErrorResponse("UseCase with this name already exists"))
            }
            
            // Create new use case
            val useCase = UseCase(name = trimmedName)
            
            val savedUseCase = useCaseRepository.save(useCase)
            
            log.info("Created use case: {} with id: {}", savedUseCase.name, savedUseCase.id)
            HttpResponse.status<UseCase>(HttpStatus.CREATED).body(savedUseCase)
        } catch (e: Exception) {
            log.error("Error creating use case", e)
            HttpResponse.badRequest(ErrorResponse("Error creating use case: ${e.message}"))
        }
    }

    @Put("/{id}")
    @Secured("ADMIN", "SECCHAMPION", "REQ") // Only privileged users can update
    @Transactional
    open fun updateUseCase(id: Long, @Valid @Body request: UpdateUseCaseRequest): HttpResponse<*> {
        return try {
            log.debug("Updating use case with id: {}", id)
            
            val useCase = useCaseRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("UseCase not found"))
            
            val trimmedName = request.name.trim()
            
            // Check for empty name after trimming
            if (trimmedName.isBlank()) {
                return HttpResponse.badRequest(ErrorResponse("Name is required"))
            }
            
            // Check for case-insensitive name uniqueness excluding current entity
            val existingUseCase = useCaseRepository.findByNameIgnoreCaseExcludingId(trimmedName, id).orElse(null)
            if (existingUseCase != null) {
                log.warn("UseCase name already exists (case-insensitive): {}", trimmedName)
                return HttpResponse.badRequest(ErrorResponse("UseCase with this name already exists"))
            }
            
            // Update name
            useCase.name = trimmedName
            
            val updatedUseCase = useCaseRepository.update(useCase)
            
            log.info("Updated use case: {} with id: {}", updatedUseCase.name, updatedUseCase.id)
            HttpResponse.ok(updatedUseCase)
        } catch (e: Exception) {
            log.error("Error updating use case with id: {}", id, e)
            HttpResponse.badRequest(ErrorResponse("Error updating use case: ${e.message}"))
        }
    }

    @Delete("/{id}")
    @Secured("ADMIN", "SECCHAMPION", "REQ") // Only privileged users can delete
    @Transactional
    open fun deleteUseCase(id: Long): HttpResponse<*> {
        return try {
            log.debug("Deleting use case with id: {}", id)
            
            val useCase = useCaseRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("UseCase not found"))
            
            // Check if UseCase is associated with Requirements (critical constraint validation)
            val requirementCount = useCaseRepository.countRequirementsByUseCaseId(id)
            
            if (requirementCount > 0) {
                log.warn("Cannot delete use case {} - associated with {} requirement(s)", useCase.name, requirementCount)
                return HttpResponse.badRequest(ErrorResponse(
                    "UseCase is associated with $requirementCount requirement(s) and cannot be deleted."
                ))
            }
            
            useCaseRepository.delete(useCase)
            
            log.info("Deleted use case: {} with id: {}", useCase.name, id)
            HttpResponse.ok(mapOf("message" to "UseCase deleted successfully"))
        } catch (e: Exception) {
            log.error("Error deleting use case with id: {}", id, e)
            HttpResponse.serverError<ErrorResponse>().body(ErrorResponse("Error deleting use case: ${e.message}"))
        }
    }
}