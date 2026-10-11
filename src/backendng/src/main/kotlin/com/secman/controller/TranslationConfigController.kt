package com.secman.controller

import com.secman.domain.TranslationConfig
import com.secman.repository.TranslationConfigRepository
import com.secman.service.TranslationServiceSimple
import com.secman.service.aibuddy.AiBuddySettingsService
import io.micronaut.security.authentication.Authentication
import org.slf4j.LoggerFactory
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
    private val translationService: TranslationServiceSimple,
    private val buddySetup: com.secman.service.aibuddy.AiBuddySetupService
) {
    private val log = LoggerFactory.getLogger(javaClass)

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

        val isActive: Boolean = false,
        val aiBuddyEnabled: Boolean = false,
        val aiBuddySingleInstance: Boolean = false,
        val aiBuddyProviders: String = "",
        val aiBuddyMaxCostPerJobUsd: Double = 0.50,
        val aiBuddyDailyBudgetUsd: Double = 10.0,
        val aiBuddyMaxInputPricePerMillion: Double = 5.0,
        val aiBuddyMaxOutputPricePerMillion: Double = 20.0,
        val aiBuddyMicrosoftEvidenceEnabled: Boolean = true,
        val aiBuddyCrowdStrikeEvidenceEnabled: Boolean = false
    )

    @Serdeable
    data class UpdateTranslationConfigRequest(
        val apiKey: String? = null,
        val baseUrl: String? = null,
        val modelName: String? = null,
        val maxTokens: Int? = null,
        val temperature: Double? = null,
        val isActive: Boolean? = null,
        val aiBuddyEnabled: Boolean? = null,
        val aiBuddySingleInstance: Boolean? = null,
        val aiBuddyProviders: String? = null,
        val aiBuddyMaxCostPerJobUsd: Double? = null,
        val aiBuddyDailyBudgetUsd: Double? = null,
        val aiBuddyMaxInputPricePerMillion: Double? = null,
        val aiBuddyMaxOutputPricePerMillion: Double? = null,
        val aiBuddyMicrosoftEvidenceEnabled: Boolean? = null,
        val aiBuddyCrowdStrikeEvidenceEnabled: Boolean? = null
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
    open fun createConfig(@Valid @Body request: CreateTranslationConfigRequest, authentication: Authentication): HttpResponse<*> {
        return try {
            val config = TranslationConfig(
                apiKey = request.apiKey.trim(),
                baseUrl = request.baseUrl.trim(),
                modelName = request.modelName.trim(),
                maxTokens = request.maxTokens,
                temperature = request.temperature,
                isActive = request.isActive,
                aiBuddyEnabled = request.aiBuddyEnabled,
                aiBuddySingleInstance = request.aiBuddySingleInstance,
                aiBuddyProviders = request.aiBuddyProviders,
                aiBuddyMaxCostPerJobUsd = request.aiBuddyMaxCostPerJobUsd,
                aiBuddyDailyBudgetUsd = request.aiBuddyDailyBudgetUsd,
                aiBuddyMaxInputPricePerMillion = request.aiBuddyMaxInputPricePerMillion,
                aiBuddyMaxOutputPricePerMillion = request.aiBuddyMaxOutputPricePerMillion,
                aiBuddyMicrosoftEvidenceEnabled = request.aiBuddyMicrosoftEvidenceEnabled,
                aiBuddyCrowdStrikeEvidenceEnabled = request.aiBuddyCrowdStrikeEvidenceEnabled
            )

            if (config.apiKey == TranslationConfig.API_KEY_MASK || !AiBuddySettingsService.valid(config)) {
                return HttpResponse.badRequest(ErrorResponse("Invalid OpenRouter or AI buddy settings"))
            }
            if (request.isActive) translationConfigRepository.deactivateAll()
            val savedConfig = translationConfigRepository.save(config)
            log.info("OpenRouter configuration actor={} config={} outcome=created aiBuddyEnabled={}", authentication.attributes["userId"], savedConfig.id, savedConfig.aiBuddyEnabled)
            HttpResponse.created(savedConfig.toSafeResponse())
        } catch (e: Exception) {
            HttpResponse.badRequest(ErrorResponse("An internal error occurred"))
        }
    }

    @Put("/{id}")
    @Secured("ADMIN")
    @Transactional
    open fun updateConfig(id: Long, @Valid @Body request: UpdateTranslationConfigRequest, authentication: Authentication): HttpResponse<*> {
        return try {
            val existingConfig = translationConfigRepository.findById(id)
            if (!existingConfig.isPresent) {
                return HttpResponse.notFound(ErrorResponse("Translation configuration not found"))
            }

            val config = existingConfig.get().copy()

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

            request.aiBuddyEnabled?.let { config.aiBuddyEnabled = it }
            request.aiBuddySingleInstance?.let { config.aiBuddySingleInstance = it }
            request.aiBuddyProviders?.let { config.aiBuddyProviders = it.trim() }
            request.aiBuddyMaxCostPerJobUsd?.let { config.aiBuddyMaxCostPerJobUsd = it }
            request.aiBuddyDailyBudgetUsd?.let { config.aiBuddyDailyBudgetUsd = it }
            request.aiBuddyMaxInputPricePerMillion?.let { config.aiBuddyMaxInputPricePerMillion = it }
            request.aiBuddyMaxOutputPricePerMillion?.let { config.aiBuddyMaxOutputPricePerMillion = it }
            request.aiBuddyMicrosoftEvidenceEnabled?.let { config.aiBuddyMicrosoftEvidenceEnabled = it }
            request.aiBuddyCrowdStrikeEvidenceEnabled?.let { config.aiBuddyCrowdStrikeEvidenceEnabled = it }
            if (!AiBuddySettingsService.valid(config)) {
                return HttpResponse.badRequest(ErrorResponse("Invalid OpenRouter or AI buddy settings"))
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
            log.info("OpenRouter configuration actor={} config={} outcome=updated aiBuddyEnabled={}", authentication.attributes["userId"], id, updatedConfig.aiBuddyEnabled)
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

    @Serdeable
    data class AiBuddySetupRequest(val configId: Long? = null, val settings: UpdateTranslationConfigRequest)

    @Post("/ai-buddy/setup")
    @Secured("ADMIN")
    open fun checkAiBuddySetup(@Body request: AiBuddySetupRequest, authentication: Authentication): HttpResponse<*> {
        val stored = request.configId?.let {
            translationConfigRepository.findById(it).orElse(null)
                ?: return HttpResponse.notFound(ErrorResponse("Translation configuration not found"))
        }
        val draft = stored?.copy() ?: TranslationConfig(apiKey = "")
        val input = request.settings
        if (draft.shouldUpdateApiKey(input.apiKey)) draft.apiKey = input.apiKey!!.trim()
        input.baseUrl?.let { draft.baseUrl = it.trim() }
        input.modelName?.let { draft.modelName = it.trim() }
        input.maxTokens?.let { draft.maxTokens = it }
        input.isActive?.let { draft.isActive = it }
        input.aiBuddyEnabled?.let { draft.aiBuddyEnabled = it }
        input.aiBuddySingleInstance?.let { draft.aiBuddySingleInstance = it }
        input.aiBuddyProviders?.let { draft.aiBuddyProviders = it.trim() }
        input.aiBuddyMaxCostPerJobUsd?.let { draft.aiBuddyMaxCostPerJobUsd = it }
        input.aiBuddyDailyBudgetUsd?.let { draft.aiBuddyDailyBudgetUsd = it }
        input.aiBuddyMaxInputPricePerMillion?.let { draft.aiBuddyMaxInputPricePerMillion = it }
        input.aiBuddyMaxOutputPricePerMillion?.let { draft.aiBuddyMaxOutputPricePerMillion = it }
        return try {
            if (draft.apiKey.length > 512 || draft.aiBuddyProviders.length > 512 ||
                draft.modelName.length > 255 || draft.baseUrl.length > 2048)
                return HttpResponse.badRequest(ErrorResponse("OpenRouter setup fields exceed their size limits"))
            val result = buddySetup.check(draft)
            log.info("AI buddy setup actor={} config={} outcome={}", authentication.attributes["userId"], request.configId,
                if (result.ready) "ready" else "incomplete")
            HttpResponse.ok(result)
        } catch (e: Exception) {
            log.warn("AI buddy setup actor={} config={} outcome=metadata-unavailable", authentication.attributes["userId"], request.configId)
            HttpResponse.status<ErrorResponse>(io.micronaut.http.HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse("Provider metadata could not be verified. Check the API key and try again; no settings were saved."))
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
