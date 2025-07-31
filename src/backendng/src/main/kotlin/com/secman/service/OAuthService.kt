package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.domain.IdentityProvider
import com.secman.domain.OAuthState
import com.secman.domain.User
import com.secman.repository.IdentityProviderRepository
import com.secman.repository.OAuthStateRepository
import com.secman.repository.UserRepository
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
     */
    @Transactional
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

        // Save state
        val oauthState = OAuthState(
            stateToken = state,
            providerId = providerId,
            redirectUri = redirectUri
        )
        val savedState = oauthStateRepository.save(oauthState)
        
        // Verify state was actually saved
        val verificationState = oauthStateRepository.findByStateToken(state)
        if (!verificationState.isPresent) {
            logger.error("Failed to verify saved OAuth state")
            return null
        }

        // Build authorization URL
        val authUrl = provider.authorizationUrl ?: return null
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
            logger.error("OAuth state not found in database")
            return CallbackResult.Error("Invalid or expired state parameter")
        }

        val oauthState = stateOpt.get()
        if (oauthState.providerId != providerId) {
            logger.error("State provider mismatch: expected {}, got {}", oauthState.providerId, providerId)
            return CallbackResult.Error("State provider mismatch")
        }

        if (oauthState.expiresAt.isBefore(LocalDateTime.now())) {
            logger.error("Expired OAuth state")
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

            // Get user info
            val userInfo = getUserInfo(provider, tokenResponse.accessToken)
            if (userInfo == null) {
                oauthStateRepository.deleteByStateToken(state)
                return CallbackResult.Error("Unable to retrieve user information from ${provider.name}. Please try again.")
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
            val tokenUrl = provider.tokenUrl ?: return null
            
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
                logger.error("OAuth token exchange error: {} - {}", error, errorDescription)
                return null
            }
            
            val accessToken = responseData["access_token"] as? String ?: return null
            val tokenType = responseData["token_type"] as? String ?: "bearer"
            val scope = responseData["scope"] as? String ?: provider.scopes ?: ""
            
            TokenResponse(
                accessToken = accessToken,
                tokenType = tokenType,
                scope = scope
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
        val scope: String
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