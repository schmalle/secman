package com.secman.controller

import com.secman.config.AppConfig
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
@Secured("ADMIN")  // SECURITY: Restrict all IDP management to ADMIN role
@ExecuteOn(TaskExecutors.BLOCKING)
open class IdentityProviderController(
    private val identityProviderRepository: IdentityProviderRepository,
    private val appConfig: AppConfig
) {

    private val logger = LoggerFactory.getLogger(IdentityProviderController::class.java)

    @Serdeable
    data class ErrorResponse(
        val error: String,
        val timestamp: LocalDateTime = LocalDateTime.now()
    )

    /**
     * Validate callback URL format
     * - Must be a valid URL
     * - Must start with https:// (production) or http://localhost (development)
     * - Must not exceed 512 characters
     */
    private fun validateCallbackUrl(callbackUrl: String?): String? {
        if (callbackUrl.isNullOrBlank()) {
            return null // Valid: null/blank means use default
        }

        // Check length
        if (callbackUrl.length > 512) {
            return "Callback URL must not exceed 512 characters"
        }

        // Check URL format
        val urlRegex = Regex("^https?://[^\\s/$.?#].[^\\s]*$")
        if (!urlRegex.matches(callbackUrl)) {
            return "Callback URL must be a valid URL"
        }

        // Check protocol: must be https:// or http://localhost
        if (!callbackUrl.startsWith("https://") && !callbackUrl.startsWith("http://localhost")) {
            return "Callback URL must start with https:// (production) or http://localhost (development)"
        }

        return null // Valid
    }

    @Serdeable
    data class IdentityProviderCreateRequest(
        val name: String,
        val type: String,
        val clientId: String,
        val clientSecret: String? = null,
        val tenantId: String? = null,
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
        val claimMappings: String? = null,
        val callbackUrl: String? = null
    )

    @Serdeable
    data class IdentityProviderUpdateRequest(
        val name: String?,
        val type: String?,
        val clientId: String?,
        val clientSecret: String?,
        val tenantId: String?,
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
        val claimMappings: String?,
        val callbackUrl: String?
    )

    @Serdeable
    data class TestProviderResponse(
        val valid: Boolean,
        val checks: List<ValidationCheck>
    )

    @Serdeable
    data class ValidationCheck(
        val name: String,
        val status: String,  // "pass", "fail", "warning"
        val message: String
    )

    /**
     * Get all identity providers
     */
    @Get
    @Secured("ADMIN")
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
            logger.info("BACKEND_BASE_URL: {}", appConfig.backend.baseUrl)
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
    @Secured("ADMIN")
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
    @Secured("ADMIN")
    open fun createProvider(@Body request: IdentityProviderCreateRequest): HttpResponse<*> {
        return try {
            // Check if name already exists
            if (identityProviderRepository.existsByNameIgnoreCase(request.name)) {
                return HttpResponse.badRequest(ErrorResponse("Identity provider with name '${request.name}' already exists"))
            }

            // Validate tenant ID for Microsoft providers
            if (request.name.contains("Microsoft", ignoreCase = true)) {
                if (request.tenantId.isNullOrBlank()) {
                    return HttpResponse.badRequest(ErrorResponse("Tenant ID is required for Microsoft providers"))
                }

                val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                if (!uuidRegex.matches(request.tenantId)) {
                    return HttpResponse.badRequest(ErrorResponse("Tenant ID must be a valid UUID format"))
                }
            }

            // Validate callback URL
            val callbackUrlError = validateCallbackUrl(request.callbackUrl)
            if (callbackUrlError != null) {
                return HttpResponse.badRequest(ErrorResponse(callbackUrlError))
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
                tenantId = request.tenantId,
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
                claimMappings = request.claimMappings,
                callbackUrl = request.callbackUrl
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
    @Secured("ADMIN")
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

            // Validate tenant ID if being updated for Microsoft providers
            if (request.tenantId != null && provider.name.contains("Microsoft", ignoreCase = true)) {
                val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                if (!uuidRegex.matches(request.tenantId)) {
                    return HttpResponse.badRequest(ErrorResponse("Tenant ID must be a valid UUID format"))
                }
            }

            // Validate callback URL if being updated
            if (request.callbackUrl != null) {
                val callbackUrlError = validateCallbackUrl(request.callbackUrl)
                if (callbackUrlError != null) {
                    return HttpResponse.badRequest(ErrorResponse(callbackUrlError))
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
            request.tenantId?.let { provider.tenantId = it }
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
            request.callbackUrl?.let { provider.callbackUrl = it }

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
    @Secured("ADMIN")
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

    /**
     * Test identity provider configuration
     */
    @Post("/{id}/test")
    @Secured("ADMIN")
    open fun testProvider(@PathVariable id: Long): HttpResponse<*> {
        return try {
            val providerOpt = identityProviderRepository.findById(id)
            if (!providerOpt.isPresent) {
                return HttpResponse.notFound(ErrorResponse("Identity provider not found"))
            }

            val provider = providerOpt.get()
            val checks = mutableListOf<ValidationCheck>()

            // Check 1: Client ID present
            checks.add(
                if (provider.clientId.isNotBlank()) {
                    ValidationCheck("Client ID", "pass", "Present")
                } else {
                    ValidationCheck("Client ID", "fail", "Missing")
                }
            )

            // Check 2: Client Secret present
            checks.add(
                if (!provider.clientSecret.isNullOrBlank()) {
                    ValidationCheck("Client Secret", "pass", "Present")
                } else {
                    ValidationCheck("Client Secret", "fail", "Missing")
                }
            )

            // Check 3: Tenant ID valid UUID format (for Microsoft)
            if (provider.name.contains("Microsoft", ignoreCase = true)) {
                val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                val tenantId = provider.tenantId
                checks.add(
                    when {
                        tenantId.isNullOrBlank() -> ValidationCheck("Tenant ID", "fail", "Required for Microsoft providers")
                        !uuidRegex.matches(tenantId) -> ValidationCheck("Tenant ID", "fail", "Invalid UUID format")
                        else -> ValidationCheck("Tenant ID", "pass", "Valid UUID format")
                    }
                )
            }

            // Check 4: Authorization URL valid HTTPS
            val authUrl = provider.authorizationUrl
            checks.add(
                when {
                    authUrl.isNullOrBlank() -> ValidationCheck("Authorization URL", "fail", "Missing")
                    authUrl.startsWith("https://") -> ValidationCheck("Authorization URL", "pass", "Valid HTTPS URL")
                    else -> ValidationCheck("Authorization URL", "fail", "Must be HTTPS URL")
                }
            )

            // Check 5: Token URL valid HTTPS
            val tokenUrl = provider.tokenUrl
            checks.add(
                when {
                    tokenUrl.isNullOrBlank() -> ValidationCheck("Token URL", "fail", "Missing")
                    tokenUrl.startsWith("https://") -> ValidationCheck("Token URL", "pass", "Valid HTTPS URL")
                    else -> ValidationCheck("Token URL", "fail", "Must be HTTPS URL")
                }
            )

            // Check 6: Scopes include 'openid'
            val scopes = provider.scopes
            checks.add(
                when {
                    scopes.isNullOrBlank() -> ValidationCheck("Scopes", "fail", "Missing")
                    scopes.contains("openid") -> ValidationCheck("Scopes", "pass", "Includes 'openid'")
                    else -> ValidationCheck("Scopes", "warning", "Missing 'openid' scope")
                }
            )

            val allPass = checks.all { it.status == "pass" }
            val response = TestProviderResponse(valid = allPass, checks = checks)

            logger.info("Tested identity provider {}: {}", provider.name, if (allPass) "PASS" else "FAIL")
            HttpResponse.ok(response)

        } catch (e: Exception) {
            logger.error("Error testing identity provider {}: {}", id, e.message, e)
            HttpResponse.serverError(ErrorResponse("Failed to test identity provider: ${e.message}"))
        }
    }
}