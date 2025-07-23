package com.secman.controller

import com.secman.domain.Norm
import com.secman.repository.NormRepository
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

@Controller("/api/norms")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class NormController(
    private val normRepository: NormRepository,
    private val entityManager: EntityManager
) {
    
    private val log = LoggerFactory.getLogger(NormController::class.java)

    @Serdeable
    data class CreateNormRequest(
        @NotBlank val name: String,
        @Nullable val version: String? = null,
        @Nullable val year: Int? = null
    )

    @Serdeable
    data class UpdateNormRequest(
        @Nullable val name: String? = null,
        @Nullable val version: String? = null,
        @Nullable val year: Int? = null
    )

    @Serdeable
    data class ErrorResponse(
        val error: String
    )

    @Get
    @Transactional(readOnly = true)
    open fun listNorms(): HttpResponse<List<Norm>> {
        return try {
            log.debug("Fetching all norms")
            
            // Get norms ordered by name (same as Java implementation)
            val norms = entityManager.createQuery(
                "SELECT n FROM Norm n ORDER BY n.name ASC",
                Norm::class.java
            ).resultList
            
            log.debug("Found {} norms", norms.size)
            HttpResponse.ok(norms)
        } catch (e: Exception) {
            log.error("Error fetching norms", e)
            HttpResponse.serverError<List<Norm>>()
        }
    }

    @Get("/{id}")
    @Transactional(readOnly = true)
    open fun getNorm(id: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching norm with id: {}", id)
            
            val norm = normRepository.findById(id).orElse(null)
            
            if (norm != null) {
                log.debug("Found norm: {}", norm.name)
                HttpResponse.ok(norm)
            } else {
                log.debug("Norm not found with id: {}", id)
                HttpResponse.notFound(ErrorResponse("Norm not found"))
            }
        } catch (e: Exception) {
            log.error("Error fetching norm with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Post
    @Transactional
    open fun createNorm(@Valid @Body request: CreateNormRequest): HttpResponse<*> {
        return try {
            log.debug("Creating norm with name: {}", request.name)
            
            val trimmedName = request.name.trim()
            
            // Check for empty name after trimming
            if (trimmedName.isBlank()) {
                return HttpResponse.badRequest(ErrorResponse("Name cannot be empty"))
            }
            
            // Check for name uniqueness (case-sensitive as per Java implementation)
            if (normRepository.existsByName(trimmedName)) {
                log.warn("Norm name already exists: {}", trimmedName)
                return HttpResponse.badRequest(ErrorResponse("Norm with this name already exists"))
            }
            
            // Process version - use empty string if null (same as Java)
            val processedVersion = request.version?.trim() ?: ""
            
            // Process year - set to null if 0 or negative (same as Java logic)
            val processedYear = request.year?.let { year ->
                if (year > 0) year else null
            }
            
            // Create new norm
            val norm = Norm(
                name = trimmedName,
                version = processedVersion,
                year = processedYear
            )
            
            val savedNorm = normRepository.save(norm)
            
            log.info("Created norm: {} with id: {}", savedNorm.name, savedNorm.id)
            HttpResponse.status<Norm>(HttpStatus.CREATED).body(savedNorm)
        } catch (e: Exception) {
            log.error("Error creating norm", e)
            HttpResponse.serverError<Any>()
        }
    }

    @Put("/{id}")
    @Transactional
    open fun updateNorm(id: Long, @Valid @Body request: UpdateNormRequest): HttpResponse<*> {
        return try {
            log.debug("Updating norm with id: {}", id)
            
            val norm = normRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("Norm not found"))
            
            // Update name if provided
            request.name?.let { newName ->
                val trimmedName = newName.trim()
                
                if (trimmedName.isBlank()) {
                    return HttpResponse.badRequest(ErrorResponse("Name cannot be empty"))
                }
                
                // Check for name uniqueness (excluding current norm)
                val existingNorm = normRepository.findByName(trimmedName).orElse(null)
                if (existingNorm != null && existingNorm.id != id) {
                    log.warn("Norm name already exists: {}", trimmedName)
                    return HttpResponse.badRequest(ErrorResponse("Norm with this name already exists"))
                }
                
                norm.name = trimmedName
            }
            
            // Update version if provided
            request.version?.let { newVersion ->
                norm.version = newVersion.trim()
            }
            
            // Update year if provided - set to null if 0 or negative
            request.year?.let { newYear ->
                norm.year = if (newYear > 0) newYear else null
            }
            
            val updatedNorm = normRepository.update(norm)
            
            log.info("Updated norm: {} with id: {}", updatedNorm.name, updatedNorm.id)
            HttpResponse.ok(updatedNorm)
        } catch (e: Exception) {
            log.error("Error updating norm with id: {}", id, e)
            HttpResponse.serverError<Any>()
        }
    }

    @Delete("/{id}")
    @Transactional
    open fun deleteNorm(id: Long): HttpResponse<*> {
        return try {
            log.debug("Deleting norm with id: {}", id)
            
            val norm = normRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("Norm not found"))
            
            normRepository.delete(norm)
            
            log.info("Deleted norm: {} with id: {}", norm.name, id)
            HttpResponse.ok(mapOf("message" to "Norm deleted successfully"))
        } catch (e: Exception) {
            log.error("Error deleting norm with id: {}", id, e)
            
            // Check if it's a constraint violation (norm in use by requirements)
            if (e.message?.contains("constraint", ignoreCase = true) == true ||
                e.message?.contains("foreign key", ignoreCase = true) == true) {
                return HttpResponse.badRequest(ErrorResponse("Cannot delete norm - it may be in use"))
            }
            
            HttpResponse.serverError<Any>()
        }
    }

    @Delete("/all")
    @Transactional
    open fun deleteAllNorms(): HttpResponse<*> {
        return try {
            log.debug("Deleting all norms")
            
            val allNorms = normRepository.findAll()
            val count = allNorms.size
            
            if (count > 0) {
                normRepository.deleteAll()
                log.info("Deleted all {} norms", count)
                HttpResponse.ok(mapOf(
                    "message" to "All norms deleted successfully",
                    "deletedCount" to count
                ))
            } else {
                log.debug("No norms to delete")
                HttpResponse.ok(mapOf(
                    "message" to "No norms to delete",
                    "deletedCount" to 0
                ))
            }
        } catch (e: Exception) {
            log.error("Error deleting all norms", e)
            
            // Check if it's a constraint violation
            if (e.message?.contains("constraint", ignoreCase = true) == true ||
                e.message?.contains("foreign key", ignoreCase = true) == true) {
                return HttpResponse.badRequest(ErrorResponse("Cannot delete norms - some may be in use"))
            }
            
            HttpResponse.serverError<Any>()
        }
    }
}