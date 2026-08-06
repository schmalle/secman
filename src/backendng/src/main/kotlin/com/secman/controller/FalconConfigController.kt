package com.secman.controller

import com.secman.domain.FalconConfig
import com.secman.repository.FalconConfigRepository
import io.micronaut.core.annotation.Nullable
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.serde.annotation.Serdeable
import io.micronaut.transaction.annotation.Transactional
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory

@Controller("/api/falcon-config")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class FalconConfigController(
    private val falconConfigRepository: FalconConfigRepository
) {
    
    private val log = LoggerFactory.getLogger(FalconConfigController::class.java)

    @Serdeable
    data class CreateFalconConfigRequest(
        @NotBlank val clientId: String,
        @NotBlank val clientSecret: String,
        @NotBlank val cloudRegion: String = "us-1"
    )

    @Serdeable
    data class UpdateFalconConfigRequest(
        @Nullable val clientId: String? = null,
        @Nullable val clientSecret: String? = null,
        @Nullable val cloudRegion: String? = null,
        @Nullable val isActive: Boolean? = null
    )

    @Serdeable
    data class ErrorResponse(
        val error: String
    )

    @Get
    @Transactional(readOnly = true)
    open fun listConfigs(): HttpResponse<List<FalconConfig>> {
        return try {
            log.debug("Fetching all Falcon configurations")
            
            val configs = falconConfigRepository.findAll()
                .map { it.toSafeResponse() } // Mask credentials
            
            log.debug("Found {} Falcon configurations", configs.size)
            HttpResponse.ok(configs)
        } catch (e: Exception) {
            log.error("Error fetching Falcon configurations", e)
            HttpResponse.serverError<List<FalconConfig>>()
        }
    }

    @Get("/active")
    @Transactional(readOnly = true)
    open fun getActiveConfig(): HttpResponse<*> {
        return try {
            log.debug("Fetching active Falcon configuration")
            
            val activeConfig = falconConfigRepository.findActiveConfig().orElse(null)
            
            if (activeConfig != null) {
                log.debug("Found active configuration: {}", activeConfig.id)
                HttpResponse.ok(activeConfig.toSafeResponse())
            } else {
                log.debug("No active Falcon configuration found")
                HttpResponse.notFound<ErrorResponse>()
            }
        } catch (e: Exception) {
            log.error("Error fetching active Falcon configuration", e)
            HttpResponse.serverError<ErrorResponse>()
        }
    }

    @Get("/{id}")
    @Transactional(readOnly = true)
    open fun getConfig(id: Long): HttpResponse<*> {
        return try {
            log.debug("Fetching Falcon configuration with id: {}", id)
            
            val config = falconConfigRepository.findById(id).orElse(null)
            
            if (config != null) {
                HttpResponse.ok(config.toSafeResponse())
            } else {
                log.warn("Falcon configuration not found: {}", id)
                HttpResponse.notFound<ErrorResponse>()
            }
        } catch (e: Exception) {
            log.error("Error fetching Falcon configuration", e)
            HttpResponse.serverError<ErrorResponse>()
        }
    }

    @Post
    @Transactional
    open fun createConfig(@Valid @Body request: CreateFalconConfigRequest): HttpResponse<*> {
        return try {
            log.info("Creating new Falcon configuration for region: {}", request.cloudRegion)
            
            // Validate region
            if (!FalconConfig.VALID_REGIONS.contains(request.cloudRegion)) {
                return HttpResponse.badRequest(ErrorResponse(
                    "Invalid cloud region. Must be one of: ${FalconConfig.VALID_REGIONS.joinToString(", ")}"
                ))
            }
            
            // Check if there's already an active config
            val existingActive = falconConfigRepository.findActiveConfig().orElse(null)
            
            // Create new config
            val newConfig = FalconConfig.create(
                clientId = request.clientId,
                clientSecret = request.clientSecret,
                cloudRegion = request.cloudRegion
            )
            
            // Deactivate existing active config if present
            if (existingActive != null) {
                log.debug("Deactivating existing active configuration: {}", existingActive.id)
                falconConfigRepository.update(existingActive.deactivate())
            }
            
            val savedConfig = falconConfigRepository.save(newConfig)
            
            log.info("Successfully created Falcon configuration with id: {}", savedConfig.id)
            HttpResponse.created(savedConfig.toSafeResponse())
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid request: {}", e.message)
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Invalid request"))
        } catch (e: Exception) {
            log.error("Error creating Falcon configuration", e)
            HttpResponse.serverError(ErrorResponse("Failed to create Falcon configuration"))
        }
    }

    @Put("/{id}")
    @Transactional
    open fun updateConfig(id: Long, @Valid @Body request: UpdateFalconConfigRequest): HttpResponse<*> {
        return try {
            log.info("Updating Falcon configuration: {}", id)
            
            val existingConfig = falconConfigRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound<ErrorResponse>()
            
            // Validate region if provided
            if (request.cloudRegion != null && !FalconConfig.VALID_REGIONS.contains(request.cloudRegion)) {
                return HttpResponse.badRequest(ErrorResponse(
                    "Invalid cloud region. Must be one of: ${FalconConfig.VALID_REGIONS.joinToString(", ")}"
                ))
            }
            
            // Update credentials if provided (and not masked)
            var updatedConfig = existingConfig
            if (existingConfig.shouldUpdateCredentials(request.clientId, request.clientSecret)) {
                updatedConfig = updatedConfig.withUpdatedCredentials(request.clientId, request.clientSecret)
            }
            
            // Update cloud region if provided
            if (request.cloudRegion != null) {
                updatedConfig = updatedConfig.copy(cloudRegion = request.cloudRegion)
            }
            
            // Handle activation/deactivation
            if (request.isActive != null && request.isActive != existingConfig.isActive) {
                if (request.isActive) {
                    // Activating this config - deactivate others
                    falconConfigRepository.deactivateAllExcept(id)
                    updatedConfig = updatedConfig.activate()
                } else {
                    // Deactivating this config
                    updatedConfig = updatedConfig.deactivate()
                }
            }
            
            val savedConfig = falconConfigRepository.update(updatedConfig)
            
            log.info("Successfully updated Falcon configuration: {}", id)
            HttpResponse.ok(savedConfig.toSafeResponse())
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid request: {}", e.message)
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Invalid request"))
        } catch (e: Exception) {
            log.error("Error updating Falcon configuration", e)
            HttpResponse.serverError(ErrorResponse("Failed to update Falcon configuration"))
        }
    }

    @Delete("/{id}")
    @Transactional
    open fun deleteConfig(id: Long): HttpResponse<*> {
        return try {
            log.info("Deleting Falcon configuration: {}", id)
            
            val config = falconConfigRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound<ErrorResponse>()
            
            falconConfigRepository.delete(config)
            
            log.info("Successfully deleted Falcon configuration: {}", id)
            HttpResponse.noContent<Any>()
        } catch (e: Exception) {
            log.error("Error deleting Falcon configuration", e)
            HttpResponse.serverError(ErrorResponse("Failed to delete Falcon configuration"))
        }
    }

    @Post("/{id}/activate")
    @Transactional
    open fun activateConfig(id: Long): HttpResponse<*> {
        return try {
            log.info("Activating Falcon configuration: {}", id)
            
            val config = falconConfigRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound<ErrorResponse>()
            
            // Deactivate all other configs
            falconConfigRepository.deactivateAllExcept(id)
            
            // Activate this config
            val activatedConfig = falconConfigRepository.update(config.activate())
            
            log.info("Successfully activated Falcon configuration: {}", id)
            HttpResponse.ok(activatedConfig.toSafeResponse())
        } catch (e: Exception) {
            log.error("Error activating Falcon configuration", e)
            HttpResponse.serverError(ErrorResponse("Failed to activate Falcon configuration"))
        }
    }
}
