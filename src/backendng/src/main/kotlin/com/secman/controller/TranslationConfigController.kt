package com.secman.controller

import com.secman.domain.TranslationConfig
import com.secman.repository.TranslationConfigRepository
import com.secman.service.TranslationServiceSimple
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.transaction.Transactional
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import java.time.LocalDateTime

/**
 * Translation (LLM) configuration.
 *
 * SECURITY: reads stay IS_AUTHENTICATED because non-admin pages depend on them — Export.tsx and
 * ImportExport.tsx call GET /active to decide whether to offer translated export — and every read
 * masks the credential via TranslationConfig.toSafeResponse().
 *
 * Every *mutating* method is ADMIN-only, matching the sibling EmailConfig/FalconConfig/GithubConfig
 * controllers. Without that gate any authenticated user could rewrite `baseUrl` on the active
 * config, and TranslationService sends the stored key as `Authorization: Bearer` to whatever host
 * it names — leaking the API key and the requirement text being translated. `/test` is likewise an
 * outbound-fetch primitive, and `activate` deactivates every other config in one call.
 */
@Controller("/api/translation-config")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class TranslationConfigController(
    private val translationConfigRepository: TranslationConfigRepository,
    private val translationService: TranslationServiceSimple
) {

    @Serdeable
    data class CreateTranslationConfigRequest(
        @field:NotBlank
        @field:Size(max = 512)
        val apiKey: String,

        @field:NotBlank
        val baseUrl: String = "https://openrouter.ai/api/v1",

        @field:NotBlank
        val modelName: String = "anthropic/claude-3-haiku",

        @field:Min(100)
        @field:Max(8000)
        val maxTokens: Int = 4000,

        @field:DecimalMin("0.0")
        @field:DecimalMax("1.0")
        val temperature: Double = 0.3,

        val isActive: Boolean = false
    )

    @Serdeable
    data class UpdateTranslationConfigRequest(
        val apiKey: String? = null,
        val baseUrl: String? = null,
        val modelName: String? = null,
        val maxTokens: Int? = null,
        val temperature: Double? = null,
        val isActive: Boolean? = null
    )

    @Serdeable
    data class ErrorResponse(
        val error: String,
        val timestamp: LocalDateTime = LocalDateTime.now()
    )

    @Get
    open fun getAllConfigs(): HttpResponse<List<TranslationConfig>> {
        return try {
            val configs = translationConfigRepository.findAll()
                .map { it.toSafeResponse() }
            HttpResponse.ok(configs)
        } catch (e: Exception) {
            HttpResponse.serverError(listOf(
                TranslationConfig(
                    apiKey = "Error loading configurations",
                    baseUrl = "",
                    modelName = "",
                    maxTokens = 0,
                    temperature = 0.0
                )
            ))
        }
    }

    @Get("/active")
    open fun getActiveConfig(): HttpResponse<*> {
        return try {
            val activeConfig = translationConfigRepository.findActiveConfig()
            if (activeConfig.isPresent) {
                HttpResponse.ok(activeConfig.get().toSafeResponse())
            } else {
                HttpResponse.ok(mapOf("message" to "No active translation configuration found"))
            }
        } catch (e: Exception) {
            HttpResponse.serverError(ErrorResponse("An internal error occurred"))
        }
    }

    @Get("/{id}")
    open fun getConfig(id: Long): HttpResponse<*> {
        return try {
            val config = translationConfigRepository.findById(id)
            if (config.isPresent) {
                HttpResponse.ok(config.get().toSafeResponse())
            } else {
                HttpResponse.notFound(ErrorResponse("Translation configuration not found"))
            }
        } catch (e: Exception) {
            HttpResponse.serverError(ErrorResponse("An internal error occurred"))
        }
    }

    @Post
    @Secured("ADMIN")
    @Transactional
    open fun createConfig(@Valid @Body request: CreateTranslationConfigRequest): HttpResponse<*> {
        return try {
            // If setting as active, deactivate all others first
            if (request.isActive) {
                translationConfigRepository.deactivateAll()
            }

            val config = TranslationConfig(
                apiKey = request.apiKey.trim(),
                baseUrl = request.baseUrl.trim(),
                modelName = request.modelName.trim(),
                maxTokens = request.maxTokens,
                temperature = request.temperature,
                isActive = request.isActive
            )

            val savedConfig = translationConfigRepository.save(config)
            HttpResponse.created(savedConfig.toSafeResponse())
        } catch (e: Exception) {
            HttpResponse.badRequest(ErrorResponse("An internal error occurred"))
        }
    }

    @Put("/{id}")
    @Secured("ADMIN")
    @Transactional
    open fun updateConfig(id: Long, @Valid @Body request: UpdateTranslationConfigRequest): HttpResponse<*> {
        return try {
            val existingConfig = translationConfigRepository.findById(id)
            if (!existingConfig.isPresent) {
                return HttpResponse.notFound(ErrorResponse("Translation configuration not found"))
            }

            val config = existingConfig.get()

            // Update fields if provided
            request.baseUrl?.let { config.baseUrl = it.trim() }
            request.modelName?.let { config.modelName = it.trim() }
            request.maxTokens?.let { config.maxTokens = it }
            request.temperature?.let { config.temperature = it }

            // Handle API key update (only if not the masked value).
            // Trim defensively: invisible whitespace from a paste corrupts the
            // upstream Authorization header.
            if (config.shouldUpdateApiKey(request.apiKey)) {
                config.apiKey = request.apiKey!!.trim()
            }

            // Handle activation status
            request.isActive?.let { newActiveStatus ->
                if (newActiveStatus && !config.isActive) {
                    // Deactivate all others when activating this one
                    translationConfigRepository.deactivateAllExcept(id)
                }
                config.isActive = newActiveStatus
            }

            val updatedConfig = translationConfigRepository.update(config)
            HttpResponse.ok(updatedConfig.toSafeResponse())
        } catch (e: Exception) {
            HttpResponse.badRequest(ErrorResponse("An internal error occurred"))
        }
    }

    @Delete("/{id}")
    @Secured("ADMIN")
    @Transactional
    open fun deleteConfig(id: Long): HttpResponse<*> {
        return try {
            val existingConfig = translationConfigRepository.findById(id)
            if (!existingConfig.isPresent) {
                return HttpResponse.notFound(ErrorResponse("Translation configuration not found"))
            }

            translationConfigRepository.deleteById(id)
            HttpResponse.ok(mapOf("message" to "Translation configuration deleted successfully"))
        } catch (e: Exception) {
            HttpResponse.serverError(ErrorResponse("An internal error occurred"))
        }
    }

    @Post("/{id}/test")
    @Secured("ADMIN")
    open fun testConfig(id: Long): HttpResponse<*> {
        return try {
            val config = translationConfigRepository.findById(id)
            if (!config.isPresent) {
                return HttpResponse.notFound(ErrorResponse("Translation configuration not found"))
            }

            val testResult = translationService.testConfiguration(config.get())
            HttpResponse.ok(testResult)
        } catch (e: Exception) {
            HttpResponse.serverError(ErrorResponse("An internal error occurred"))
        }
    }

    @Post("/{id}/activate")
    @Secured("ADMIN")
    @Transactional
    open fun activateConfig(id: Long): HttpResponse<*> {
        return try {
            val config = translationConfigRepository.findById(id)
            if (!config.isPresent) {
                return HttpResponse.notFound(ErrorResponse("Translation configuration not found"))
            }

            // Deactivate all others
            translationConfigRepository.deactivateAllExcept(id)
            
            // Activate this one
            val targetConfig = config.get()
            targetConfig.isActive = true
            val updatedConfig = translationConfigRepository.update(targetConfig)

            HttpResponse.ok(mapOf(
                "message" to "Configuration activated successfully",
                "config" to updatedConfig.toSafeResponse()
            ))
        } catch (e: Exception) {
            HttpResponse.serverError(ErrorResponse("An internal error occurred"))
        }
    }

    @Post("/{id}/deactivate")
    @Secured("ADMIN")
    @Transactional
    open fun deactivateConfig(id: Long): HttpResponse<*> {
        return try {
            val config = translationConfigRepository.findById(id)
            if (!config.isPresent) {
                return HttpResponse.notFound(ErrorResponse("Translation configuration not found"))
            }

            val targetConfig = config.get()
            targetConfig.isActive = false
            val updatedConfig = translationConfigRepository.update(targetConfig)

            HttpResponse.ok(mapOf(
                "message" to "Configuration deactivated successfully",
                "config" to updatedConfig.toSafeResponse()
            ))
        } catch (e: Exception) {
            HttpResponse.serverError(ErrorResponse("An internal error occurred"))
        }
    }

    @Get("/models")
    open fun getAvailableModels(): HttpResponse<Map<String, String>> {
        return HttpResponse.ok(translationService.getAvailableModels())
    }

    @Get("/languages")
    open fun getSupportedLanguages(): HttpResponse<Map<String, String>> {
        return HttpResponse.ok(translationService.getSupportedLanguages())
    }
}