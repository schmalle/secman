package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.IdentityProvider
import com.secman.domain.OAuthState
import com.secman.domain.User
import com.secman.repository.IdentityProviderRepository
import com.secman.repository.OAuthStateRepository
import com.secman.repository.UserRepository
import com.secman.util.MicrosoftErrorMapper
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.security.token.generator.TokenGenerator
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.*

@Singleton
open class OAuthService(
    private val identityProviderRepository: IdentityProviderRepository,
    private val oauthStateRepository: OAuthStateRepository,
    private val userRepository: UserRepository,
    private val tokenGenerator: TokenGenerator,
    private val objectMapper: ObjectMapper,
    private val microsoftErrorMapper: MicrosoftErrorMapper,
    @Client("\${oauth.http-client.url:https://api.github.com}") private val githubApiClient: HttpClient,
    @Client private val genericHttpClient: HttpClient
) {
    
    private val logger = LoggerFactory.getLogger(OAuthService::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()

    /**
     * Find OAuth state by state value
     */
    fun findStateByValue(stateToken: String): Optional<OAuthState> {
        return oauthStateRepository.findByStateToken(stateToken)
    }

    /**
     * Build authorization URL for OAuth provider
     *
     * Note: This method is NOT @Transactional to ensure the OAuth state is committed
     * to the database immediately before redirecting to the OAuth provider. This prevents
     * race conditions where the callback arrives before the transaction commits.
     */
    open fun buildAuthorizationUrl(providerId: Long, baseUrl: String): String? {
        val providerOpt = identityProviderRepository.findById(providerId)
        if (!providerOpt.isPresent) {
            logger.error("Identity provider not found: {}", providerId)
            return null
        }

        val provider = providerOpt.get()
        if (!provider.enabled) {
            logger.error("Identity provider is disabled: {}", provider.name)
            return null
        }

        // Clean up expired states
        oauthStateRepository.deleteExpiredStates()

        // Generate state parameter
        val state = generateState()
        val redirectUri = "$baseUrl/oauth/callback"

        // Save state (this will commit immediately since method is not @Transactional)
        val oauthState = OAuthState(
            stateToken = state,
            providerId = providerId,
            redirectUri = redirectUri
        )
        val savedState = oauthStateRepository.save(oauthState)
        logger.info("Saved OAuth state with token {} (first 10 chars) for provider {}, expires at {}",
            state.take(10) + "...", providerId, savedState.expiresAt)

        // Verify state was actually saved
        val verificationState = oauthStateRepository.findByStateToken(state)
        if (!verificationState.isPresent) {
            logger.error("Failed to verify saved OAuth state - state not found after save!")
            return null
        }
        logger.debug("Verified OAuth state exists in database before redirect")

        // Build authorization URL - replace {tenantId} placeholder for Microsoft providers
        var authUrl = provider.authorizationUrl ?: return null
        val tenantId = provider.tenantId
        if (!tenantId.isNullOrBlank() && authUrl.contains("{tenantId}")) {
            authUrl = authUrl.replace("{tenantId}", tenantId)
        }

        val clientId = URLEncoder.encode(provider.clientId, StandardCharsets.UTF_8)
        val encodedRedirectUri = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
        val encodedState = URLEncoder.encode(state, StandardCharsets.UTF_8)
        val scopes = URLEncoder.encode(provider.scopes ?: "user:email", StandardCharsets.UTF_8)

        return "$authUrl?client_id=$clientId&redirect_uri=$encodedRedirectUri&scope=$scopes&state=$encodedState&response_type=code"
    }

    /**
     * Handle OAuth callback and exchange code for token
     */
    @Transactional
    open fun handleCallback(providerId: Long, code: String, state: String): CallbackResult {
        // Validate state
        val stateOpt = oauthStateRepository.findByStateToken(state)
        if (!stateOpt.isPresent) {
            logger.error("OAuth state not found in database for state token: {}", state.take(10) + "...")
            logger.error("This may indicate: 1) State was never saved (transaction timing issue), 2) State was already used/deleted, 3) State expired and was cleaned up")
            return CallbackResult.Error("Invalid or expired state parameter")
        }

        val oauthState = stateOpt.get()
        if (oauthState.providerId != providerId) {
            logger.error("State provider mismatch: state belongs to provider {}, but callback is for provider {}", oauthState.providerId, providerId)
            return CallbackResult.Error("State provider mismatch")
        }

        if (oauthState.expiresAt.isBefore(LocalDateTime.now())) {
            logger.error("Expired OAuth state: created at {}, expired at {}, current time {}",
                oauthState.createdAt, oauthState.expiresAt, LocalDateTime.now())
            oauthStateRepository.deleteByStateToken(state)
            return CallbackResult.Error("Expired state parameter")
        }

        val providerOpt = identityProviderRepository.findById(providerId)
        if (!providerOpt.isPresent) {
            logger.error("Identity provider not found: {}", providerId)
            return CallbackResult.Error("Identity provider not found")
        }

        val provider = providerOpt.get()
        
        logger.debug("Processing OAuth callback for provider: {}", provider.name)

        try {
            // Exchange code for access token
            val tokenResponse = exchangeCodeForToken(provider, code, oauthState.redirectUri!!)
            if (tokenResponse == null) {
                oauthStateRepository.deleteByStateToken(state)
                return CallbackResult.Error("Unable to authenticate with ${provider.name}. Please try again.")
            }

            // For OIDC providers (Microsoft), validate ID token
            var emailFromIdToken: String? = null
            if (tokenResponse.idToken != null) {
                val idTokenClaims = parseIdToken(tokenResponse.idToken)
                if (idTokenClaims == null) {
                    oauthStateRepository.deleteByStateToken(state)
                    return CallbackResult.Error("Invalid ID token received from ${provider.name}.")
                }

                // Validate tenant ID for Microsoft providers
                if (!validateTenantId(provider, idTokenClaims)) {
                    oauthStateRepository.deleteByStateToken(state)
                    val errorMessage = if (microsoftErrorMapper.isTenantMismatchError("AADSTS50128")) {
                        microsoftErrorMapper.mapError("AADSTS50128")
                    } else {
                        "User account not found in the configured tenant. Please contact your administrator."
                    }
                    return CallbackResult.Error(errorMessage)
                }

                // Extract email from ID token
                emailFromIdToken = extractEmailFromIdToken(idTokenClaims)

                // For Microsoft providers, email is required
                if (provider.name.contains("Microsoft", ignoreCase = true) && emailFromIdToken.isNullOrBlank()) {
                    oauthStateRepository.deleteByStateToken(state)
                    return CallbackResult.Error("Email address is required but not provided by ${provider.name}. Please ensure your account has an email address configured.")
                }
            }

            // Get user info (for GitHub and other providers without ID token)
            val userInfo = if (emailFromIdToken != null) {
                // For OIDC providers with ID token, create minimal UserInfoResponse
                UserInfoResponse(
                    id = "oidc-user",
                    login = emailFromIdToken.substringBefore("@"),
                    email = emailFromIdToken,
                    name = emailFromIdToken.substringBefore("@")
                )
            } else {
                // For OAuth 2.0 providers (GitHub), fetch from userinfo endpoint
                val info = getUserInfo(provider, tokenResponse.accessToken)
                if (info == null) {
                    oauthStateRepository.deleteByStateToken(state)
                    return CallbackResult.Error("Unable to retrieve user information from ${provider.name}. Please try again.")
                }
                info
            }

            // Find or create user
            val user = findOrCreateUser(provider, userInfo)
            if (user == null) {
                oauthStateRepository.deleteByStateToken(state)
                return CallbackResult.Error("Unable to create user account. Please contact support if this persists.")
            }

            // Generate JWT token
            val userDetails = mapOf(
                "sub" to user.username,
                "username" to user.username,
                "email" to user.email,
                "roles" to user.roles.map { it.name },
                "iss" to "secman-backend-ng",
                "userId" to user.id.toString()
            )

            val tokenOptional = tokenGenerator.generateToken(userDetails)
            if (tokenOptional.isEmpty) {
                oauthStateRepository.deleteByStateToken(state)
                return CallbackResult.Error("Unable to complete login process. Please try again.")
            }

            // Clean up used state only after successful processing
            oauthStateRepository.deleteByStateToken(state)
            
            return CallbackResult.Success(
                token = tokenOptional.get(),
                user = UserInfo(
                    id = user.id!!,
                    username = user.username,
                    email = user.email,
                    roles = user.roles.map { it.name }
                )
            )

        } catch (e: Exception) {
            logger.error("OAuth callback error: {}", e.message, e)
            // Clean up state on error to prevent accumulation
            oauthStateRepository.deleteByStateToken(state)
            return CallbackResult.Error("OAuth processing failed: ${e.message}")
        }
    }

    private fun generateState(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    private fun exchangeCodeForToken(provider: IdentityProvider, code: String, redirectUri: String): TokenResponse? {
        return try {
            var tokenUrl = provider.tokenUrl ?: return null

            // Replace {tenantId} placeholder for Microsoft providers
            val tenantId = provider.tenantId
            if (!tenantId.isNullOrBlank() && tokenUrl.contains("{tenantId}")) {
                tokenUrl = tokenUrl.replace("{tenantId}", tenantId)
            }

            logger.debug("Exchanging code for token with provider: {}", provider.name)
            
            // For GitHub, the token endpoint expects form data
            val formData = "client_id=${URLEncoder.encode(provider.clientId, StandardCharsets.UTF_8)}" +
                    "&client_secret=${URLEncoder.encode(provider.clientSecret ?: "", StandardCharsets.UTF_8)}" +
                    "&code=${URLEncoder.encode(code, StandardCharsets.UTF_8)}" +
                    "&redirect_uri=${URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)}"
            
            
            val response = genericHttpClient.toBlocking().retrieve(
                HttpRequest.POST(tokenUrl, formData)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded"),
                String::class.java
            )
            
            
            // Parse JSON response
            val responseData = parseJsonResponse(response)

            // Check for error in response
            if (responseData.containsKey("error")) {
                val error = responseData["error"] as? String
                val errorDescription = responseData["error_description"] as? String

                // Use Microsoft error mapper if it's a Microsoft provider
                val friendlyMessage = if (provider.name.contains("Microsoft", ignoreCase = true)) {
                    microsoftErrorMapper.mapError(error, errorDescription)
                } else {
                    errorDescription ?: error ?: "Token exchange failed"
                }

                logger.error("OAuth token exchange error: {} - {}", error, friendlyMessage)
                return null
            }

            val accessToken = responseData["access_token"] as? String ?: return null
            val tokenType = responseData["token_type"] as? String ?: "bearer"
            val scope = responseData["scope"] as? String ?: provider.scopes ?: ""
            val idToken = responseData["id_token"] as? String  // Extract ID token for OIDC providers

            TokenResponse(
                accessToken = accessToken,
                tokenType = tokenType,
                scope = scope,
                idToken = idToken
            )
            
        } catch (e: Exception) {
            logger.error("Failed to exchange code for token: {}", e.message, e)
            null
        }
    }

    private fun getUserInfo(provider: IdentityProvider, accessToken: String): UserInfoResponse? {
        return try {
            val userInfoUrl = provider.userInfoUrl ?: return null
            
            
            val response = githubApiClient.toBlocking().retrieve(
                HttpRequest.GET<Any>(userInfoUrl)
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .header("User-Agent", "SecMan-OAuth-Client"),
                String::class.java
            )
            
            // Parse JSON response
            val userData = parseJsonResponse(response)
            
            // Extract user information (GitHub format)
            val id = userData["id"]?.toString() ?: return null
            val login = userData["login"] as? String
            val email = userData["email"] as? String
            val name = userData["name"] as? String
            
            UserInfoResponse(
                id = id,
                login = login,
                email = email,
                name = name
            )
            
        } catch (e: Exception) {
            logger.error("Failed to fetch user info: {}", e.message, e)
            null
        }
    }
    
    private fun parseJsonResponse(response: String): Map<String, Any> {
        return try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(response, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            logger.error("Failed to parse JSON response: {}", e.message, e)
            emptyMap()
        }
    }

    /**
     * Parse ID token JWT and extract claims (payload only, no signature verification)
     */
    private fun parseIdToken(idToken: String): Map<String, Any>? {
        return try {
            // JWT format: header.payload.signature
            val parts = idToken.split(".")
            if (parts.size != 3) {
                logger.error("Invalid ID token format")
                return null
            }

            // Decode base64url payload
            val payload = parts[1]
            val decodedBytes = Base64.getUrlDecoder().decode(payload)
            val payloadJson = String(decodedBytes, StandardCharsets.UTF_8)

            // Parse JSON
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(payloadJson, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            logger.error("Failed to parse ID token: {}", e.message, e)
            null
        }
    }

    /**
     * Validate Microsoft tenant ID from ID token
     */
    private fun validateTenantId(provider: IdentityProvider, idTokenClaims: Map<String, Any>): Boolean {
        // Only validate for Microsoft providers with configured tenant ID
        if (!provider.name.contains("Microsoft", ignoreCase = true)) {
            return true  // Not a Microsoft provider, skip validation
        }

        if (provider.tenantId.isNullOrBlank()) {
            logger.warn("Microsoft provider {} has no tenant ID configured", provider.name)
            return true  // No tenant ID configured, skip validation
        }

        val tokenTenantId = idTokenClaims["tid"] as? String
        if (tokenTenantId.isNullOrBlank()) {
            logger.error("ID token missing 'tid' claim")
            return false
        }

        if (tokenTenantId != provider.tenantId) {
            logger.error("Tenant ID mismatch: expected {}, got {}", provider.tenantId, tokenTenantId)
            return false
        }

        return true
    }

    /**
     * Extract email from ID token claims
     */
    private fun extractEmailFromIdToken(idTokenClaims: Map<String, Any>): String? {
        // Try 'email' claim first
        val email = idTokenClaims["email"] as? String
        if (!email.isNullOrBlank()) {
            return email
        }

        // Try 'preferred_username' as fallback (often contains email for Microsoft)
        val preferredUsername = idTokenClaims["preferred_username"] as? String
        if (!preferredUsername.isNullOrBlank() && preferredUsername.contains("@")) {
            return preferredUsername
        }

        return null
    }

    private fun findOrCreateUser(provider: IdentityProvider, userInfo: UserInfoResponse): User? {
        val email = userInfo.email ?: return null
        
        // Try to find existing user by email
        val existingUserOpt = userRepository.findByEmail(email)
        if (existingUserOpt.isPresent) {
            return existingUserOpt.get()
        }

        // Auto-provision user if enabled
        if (!provider.autoProvision) {
            logger.warn("User not found and auto-provisioning is disabled for provider: {}", provider.name)
            return null
        }

        // Create new user
        val username = userInfo.login ?: userInfo.email.substringBefore("@")
        val name = userInfo.name ?: username

        val newUser = User(
            username = username,
            email = email,
            passwordHash = passwordEncoder.encode(UUID.randomUUID().toString()), // Random password for OAuth users
            roles = mutableSetOf(User.Role.USER) // Default role
        )

        return try {
            userRepository.save(newUser)
        } catch (e: Exception) {
            logger.error("Failed to create user: {}", e.message, e)
            null
        }
    }

    data class TokenResponse(
        val accessToken: String,
        val tokenType: String,
        val scope: String,
        val idToken: String? = null
    )

    data class UserInfoResponse(
        val id: String,
        val login: String?,
        val email: String?,
        val name: String?
    )

    data class UserInfo(
        val id: Long,
        val username: String,
        val email: String,
        val roles: List<String>
    )

    sealed class CallbackResult {
        data class Success(val token: String, val user: UserInfo) : CallbackResult()
        data class Error(val message: String) : CallbackResult()
    }
}