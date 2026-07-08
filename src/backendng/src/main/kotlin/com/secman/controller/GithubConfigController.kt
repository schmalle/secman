package com.secman.controller

import com.secman.domain.GithubAppConfig
import com.secman.repository.GithubAppConfigRepository
import com.secman.service.GithubAppClientService
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

/**
 * ADMIN-only management of GitHub App credentials (Feature: GitHub repo
 * vulnerability management). The private key is encrypted at rest and every
 * response masks it — mirrors [FalconConfigController].
 */
@Controller("/api/github-config")
@Secured("ADMIN")
@ExecuteOn(TaskExecutors.BLOCKING)
open class GithubConfigController(
    private val githubAppConfigRepository: GithubAppConfigRepository,
    private val githubClient: GithubAppClientService
) {
    private val log = LoggerFactory.getLogger(GithubConfigController::class.java)

    @Serdeable
    data class CreateGithubConfigRequest(
        @NotBlank val appId: String,
        @NotBlank val privateKeyPem: String,
        @Nullable val installationId: String? = null,
        @Nullable val organization: String? = null
    )

    @Serdeable
    data class UpdateGithubConfigRequest(
        @Nullable val appId: String? = null,
        @Nullable val privateKeyPem: String? = null,
        @Nullable val installationId: String? = null,
        @Nullable val organization: String? = null,
        @Nullable val isActive: Boolean? = null
    )

    @Serdeable
    data class ErrorResponse(val error: String)

    @Serdeable
    data class TestResponse(val success: Boolean, val message: String)

    @Get
    @Transactional(readOnly = true)
    open fun listConfigs(): HttpResponse<List<GithubAppConfig>> {
        return try {
            val configs = githubAppConfigRepository.findAll().map { it.toSafeResponse() }
            HttpResponse.ok(configs)
        } catch (e: Exception) {
            log.error("Error fetching GitHub App configurations", e)
            HttpResponse.serverError<List<GithubAppConfig>>()
        }
    }

    @Get("/active")
    @Transactional(readOnly = true)
    open fun getActiveConfig(): HttpResponse<*> {
        return try {
            val activeConfig = githubAppConfigRepository.findActiveConfig().orElse(null)
            if (activeConfig != null) {
                HttpResponse.ok(activeConfig.toSafeResponse())
            } else {
                HttpResponse.notFound<ErrorResponse>()
            }
        } catch (e: Exception) {
            log.error("Error fetching active GitHub App configuration", e)
            HttpResponse.serverError<ErrorResponse>()
        }
    }

    @Get("/{id}")
    @Transactional(readOnly = true)
    open fun getConfig(id: Long): HttpResponse<*> {
        return try {
            val config = githubAppConfigRepository.findById(id).orElse(null)
            if (config != null) {
                HttpResponse.ok(config.toSafeResponse())
            } else {
                HttpResponse.notFound<ErrorResponse>()
            }
        } catch (e: Exception) {
            log.error("Error fetching GitHub App configuration", e)
            HttpResponse.serverError<ErrorResponse>()
        }
    }

    @Post
    @Transactional
    open fun createConfig(@Valid @Body request: CreateGithubConfigRequest): HttpResponse<*> {
        return try {
            log.info("Creating new GitHub App configuration for App ID: {}", request.appId)

            val newConfig = GithubAppConfig(
                appId = request.appId.trim(),
                privateKeyPem = request.privateKeyPem,
                installationId = request.installationId?.trim()?.takeIf { it.isNotBlank() },
                organization = request.organization?.trim()?.takeIf { it.isNotBlank() },
                isActive = true
            )
            val validationErrors = newConfig.validate()
            if (validationErrors.isNotEmpty()) {
                return HttpResponse.badRequest(ErrorResponse(validationErrors.joinToString("; ")))
            }

            // Single-active invariant: deactivate any existing active config
            githubAppConfigRepository.findActiveConfig().ifPresent {
                log.debug("Deactivating existing active GitHub App configuration: {}", it.id)
                githubAppConfigRepository.update(it.deactivate())
            }

            val savedConfig = githubAppConfigRepository.save(newConfig)
            log.info("Successfully created GitHub App configuration with id: {}", savedConfig.id)
            HttpResponse.created(savedConfig.toSafeResponse())
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Invalid request"))
        } catch (e: Exception) {
            log.error("Error creating GitHub App configuration", e)
            HttpResponse.serverError(ErrorResponse("Failed to create GitHub App configuration"))
        }
    }

    @Put("/{id}")
    @Transactional
    open fun updateConfig(id: Long, @Valid @Body request: UpdateGithubConfigRequest): HttpResponse<*> {
        return try {
            log.info("Updating GitHub App configuration: {}", id)

            val existingConfig = githubAppConfigRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound<ErrorResponse>()

            var updatedConfig = existingConfig
            if (existingConfig.shouldUpdateCredentials(request.privateKeyPem)) {
                updatedConfig = updatedConfig.withUpdatedCredentials(request.privateKeyPem)
            }
            if (request.appId != null) {
                updatedConfig = updatedConfig.copy(appId = request.appId.trim())
            }
            if (request.installationId != null) {
                updatedConfig = updatedConfig.copy(
                    installationId = request.installationId.trim().takeIf { it.isNotBlank() }
                )
            }
            if (request.organization != null) {
                updatedConfig = updatedConfig.copy(
                    organization = request.organization.trim().takeIf { it.isNotBlank() }
                )
            }

            val validationErrors = updatedConfig.validate()
            if (validationErrors.isNotEmpty()) {
                return HttpResponse.badRequest(ErrorResponse(validationErrors.joinToString("; ")))
            }

            if (request.isActive != null && request.isActive != existingConfig.isActive) {
                updatedConfig = if (request.isActive) {
                    githubAppConfigRepository.deactivateAllExcept(id)
                    updatedConfig.activate()
                } else {
                    updatedConfig.deactivate()
                }
            }

            val savedConfig = githubAppConfigRepository.update(updatedConfig)
            HttpResponse.ok(savedConfig.toSafeResponse())
        } catch (e: IllegalArgumentException) {
            HttpResponse.badRequest(ErrorResponse(e.message ?: "Invalid request"))
        } catch (e: Exception) {
            log.error("Error updating GitHub App configuration", e)
            HttpResponse.serverError(ErrorResponse("Failed to update GitHub App configuration"))
        }
    }

    @Delete("/{id}")
    @Transactional
    open fun deleteConfig(id: Long): HttpResponse<*> {
        return try {
            val config = githubAppConfigRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound<ErrorResponse>()
            githubAppConfigRepository.delete(config)
            log.info("Deleted GitHub App configuration: {}", id)
            HttpResponse.noContent<Any>()
        } catch (e: Exception) {
            log.error("Error deleting GitHub App configuration", e)
            HttpResponse.serverError(ErrorResponse("Failed to delete GitHub App configuration"))
        }
    }

    @Post("/{id}/activate")
    @Transactional
    open fun activateConfig(id: Long): HttpResponse<*> {
        return try {
            val config = githubAppConfigRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound<ErrorResponse>()
            githubAppConfigRepository.deactivateAllExcept(id)
            val activatedConfig = githubAppConfigRepository.update(config.activate())
            log.info("Activated GitHub App configuration: {}", id)
            HttpResponse.ok(activatedConfig.toSafeResponse())
        } catch (e: Exception) {
            log.error("Error activating GitHub App configuration", e)
            HttpResponse.serverError(ErrorResponse("Failed to activate GitHub App configuration"))
        }
    }

    /** Credential check: signs an App JWT and lists installations. */
    @Post("/{id}/test")
    @Transactional(readOnly = true)
    open fun testConfig(id: Long): HttpResponse<*> {
        return try {
            val config = githubAppConfigRepository.findById(id).orElse(null)
                ?: return HttpResponse.notFound<ErrorResponse>()
            val message = githubClient.testConnection(config)
            HttpResponse.ok(TestResponse(success = true, message = message))
        } catch (e: GithubAppClientService.GithubApiException) {
            HttpResponse.ok(TestResponse(success = false, message = e.message ?: "Connection test failed"))
        } catch (e: Exception) {
            log.error("Error testing GitHub App configuration", e)
            HttpResponse.serverError(ErrorResponse("Failed to test GitHub App configuration"))
        }
    }
}
