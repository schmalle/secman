package com.secman.controller

import com.secman.domain.IdentityProvider
import com.secman.repository.IdentityProviderRepository
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

@Controller("/api/identity-providers")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.BLOCKING)
open class IdentityProviderController(
    private val identityProviderRepository: IdentityProviderRepository
) {
    
    private val logger = LoggerFactory.getLogger(IdentityProviderController::class.java)

    @Serdeable
    data class ErrorResponse(
        val error: String,
        val timestamp: LocalDateTime = LocalDateTime.now()
    )

    @Serdeable
    data class IdentityProviderCreateRequest(
        val name: String,
        val type: String,
        val clientId: String,
        val clientSecret: String? = null,
        val discoveryUrl: String? = null,
        val authorizationUrl: String? = null,
        val tokenUrl: String? = null,
        val userInfoUrl: String? = null,
        val issuer: String? = null,
        val jwksUri: String? = null,
        val scopes: String? = null,
        val enabled: Boolean = false,
        val autoProvision: Boolean = false,
        val buttonText: String,
        val buttonColor: String = "#007bff",
        val roleMapping: String? = null,
        val claimMappings: String? = null
    )

    @Serdeable
    data class IdentityProviderUpdateRequest(
        val name: String?,
        val type: String?,
        val clientId: String?,
        val clientSecret: String?,
        val discoveryUrl: String?,
        val authorizationUrl: String?,
        val tokenUrl: String?,
        val userInfoUrl: String?,
        val issuer: String?,
        val jwksUri: String?,
        val scopes: String?,
        val enabled: Boolean?,
        val autoProvision: Boolean?,
        val buttonText: String?,
        val buttonColor: String?,
        val roleMapping: String?,
        val claimMappings: String?
    )

    /**
     * Get all identity providers
     */
    @Get
    @Secured(SecurityRule.IS_AUTHENTICATED)
    open fun getAllProviders(): HttpResponse<*> {
        return try {
            val providers = identityProviderRepository.findAll().toList()
            logger.info("Retrieved {} identity providers", providers.size)
            HttpResponse.ok(providers)
        } catch (e: Exception) {
            logger.error("Error fetching identity providers: {}", e.message, e)
            HttpResponse.serverError(ErrorResponse("Failed to fetch identity providers: ${e.message}"))
        }
    }

    /**
     * Get enabled identity providers (for public access during login)
     */
    @Get("/enabled")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun getEnabledProviders(): HttpResponse<*> {
        return try {
            val providers = identityProviderRepository.findByEnabled(true)
            logger.info("Retrieved {} enabled identity providers", providers.size)
            HttpResponse.ok(providers)
        } catch (e: Exception) {
            logger.error("Error fetching enabled identity providers: {}", e.message, e)
            HttpResponse.serverError(ErrorResponse("Failed to fetch enabled identity providers: ${e.message}"))
        }
    }

    /**
     * Get identity provider by ID
     */
    @Get("/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    open fun getProvider(@PathVariable id: Long): HttpResponse<*> {
        return try {
            val providerOpt = identityProviderRepository.findById(id)
            if (providerOpt.isPresent) {
                HttpResponse.ok(providerOpt.get())
            } else {
                HttpResponse.notFound(ErrorResponse("Identity provider not found"))
            }
        } catch (e: Exception) {
            logger.error("Error fetching identity provider {}: {}", id, e.message, e)
            HttpResponse.serverError(ErrorResponse("Failed to fetch identity provider: ${e.message}"))
        }
    }

    /**
     * Create new identity provider
     */
    @Post
    @Transactional
    @Secured(SecurityRule.IS_AUTHENTICATED)
    open fun createProvider(@Body request: IdentityProviderCreateRequest): HttpResponse<*> {
        return try {
            // Check if name already exists
            if (identityProviderRepository.existsByNameIgnoreCase(request.name)) {
                return HttpResponse.badRequest(ErrorResponse("Identity provider with name '${request.name}' already exists"))
            }

            // Parse and validate type
            val providerType = try {
                IdentityProvider.ProviderType.valueOf(request.type.uppercase())
            } catch (e: IllegalArgumentException) {
                return HttpResponse.badRequest(ErrorResponse("Invalid provider type. Must be OIDC or SAML"))
            }

            val provider = IdentityProvider(
                name = request.name,
                type = providerType,
                clientId = request.clientId,
                clientSecret = request.clientSecret,
                discoveryUrl = request.discoveryUrl,
                authorizationUrl = request.authorizationUrl,
                tokenUrl = request.tokenUrl,
                userInfoUrl = request.userInfoUrl,
                issuer = request.issuer,
                jwksUri = request.jwksUri,
                scopes = request.scopes,
                enabled = request.enabled,
                autoProvision = request.autoProvision,
                buttonText = request.buttonText,
                buttonColor = request.buttonColor,
                roleMapping = request.roleMapping,
                claimMappings = request.claimMappings
            )

            val saved = identityProviderRepository.save(provider)
            logger.info("Created identity provider: {}", saved.name)
            HttpResponse.created(saved)

        } catch (e: Exception) {
            logger.error("Error creating identity provider: {}", e.message, e)
            HttpResponse.serverError(ErrorResponse("Failed to create identity provider: ${e.message}"))
        }
    }

    /**
     * Update identity provider
     */
    @Put("/{id}")
    @Transactional
    @Secured(SecurityRule.IS_AUTHENTICATED)
    open fun updateProvider(@PathVariable id: Long, @Body request: IdentityProviderUpdateRequest): HttpResponse<*> {
        return try {
            val providerOpt = identityProviderRepository.findById(id)
            if (!providerOpt.isPresent) {
                return HttpResponse.notFound(ErrorResponse("Identity provider not found"))
            }

            val provider = providerOpt.get()

            // Check if name change conflicts with existing provider
            if (request.name != null && request.name != provider.name) {
                if (identityProviderRepository.existsByNameIgnoreCase(request.name)) {
                    return HttpResponse.badRequest(ErrorResponse("Identity provider with name '${request.name}' already exists"))
                }
            }

            // Update fields if provided
            request.name?.let { provider.name = it }
            request.type?.let { 
                provider.type = try {
                    IdentityProvider.ProviderType.valueOf(it.uppercase())
                } catch (e: IllegalArgumentException) {
                    return HttpResponse.badRequest(ErrorResponse("Invalid provider type. Must be OIDC or SAML"))
                }
            }
            request.clientId?.let { provider.clientId = it }
            request.clientSecret?.let { provider.clientSecret = it }
            request.discoveryUrl?.let { provider.discoveryUrl = it }
            request.authorizationUrl?.let { provider.authorizationUrl = it }
            request.tokenUrl?.let { provider.tokenUrl = it }
            request.userInfoUrl?.let { provider.userInfoUrl = it }
            request.issuer?.let { provider.issuer = it }
            request.jwksUri?.let { provider.jwksUri = it }
            request.scopes?.let { provider.scopes = it }
            request.enabled?.let { provider.enabled = it }
            request.autoProvision?.let { provider.autoProvision = it }
            request.buttonText?.let { provider.buttonText = it }
            request.buttonColor?.let { provider.buttonColor = it }
            request.roleMapping?.let { provider.roleMapping = it }
            request.claimMappings?.let { provider.claimMappings = it }

            val saved = identityProviderRepository.update(provider)
            logger.info("Updated identity provider: {}", saved.name)
            HttpResponse.ok(saved)

        } catch (e: Exception) {
            logger.error("Error updating identity provider {}: {}", id, e.message, e)
            HttpResponse.serverError(ErrorResponse("Failed to update identity provider: ${e.message}"))
        }
    }

    /**
     * Delete identity provider
     */
    @Delete("/{id}")
    @Transactional
    @Secured(SecurityRule.IS_AUTHENTICATED)
    open fun deleteProvider(@PathVariable id: Long): HttpResponse<*> {
        return try {
            val providerOpt = identityProviderRepository.findById(id)
            if (!providerOpt.isPresent) {
                return HttpResponse.notFound(ErrorResponse("Identity provider not found"))
            }

            identityProviderRepository.deleteById(id)
            logger.info("Deleted identity provider with ID: {}", id)
            HttpResponse.ok(mapOf("message" to "Identity provider deleted successfully"))

        } catch (e: Exception) {
            logger.error("Error deleting identity provider {}: {}", id, e.message, e)
            HttpResponse.serverError(ErrorResponse("Failed to delete identity provider: ${e.message}"))
        }
    }
}