package com.secman.controller

import com.secman.domain.IdentityProvider
import com.secman.domain.User
import com.secman.repository.IdentityProviderRepository
import com.secman.repository.UserRepository
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.http.client.HttpClient
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.security.token.generator.TokenGenerator
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Singleton
class OAuthStateManager {
    private val logger = LoggerFactory.getLogger(OAuthStateManager::class.java)
    private val oauthStates = ConcurrentHashMap<String, OAuthState>()
    private val secureRandom = SecureRandom()

    @Serdeable
    data class OAuthState(
        val providerId: Long,
        val state: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun generateAndStoreState(providerId: Long): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val oauthState = OAuthState(providerId, state)
        oauthStates[state] = oauthState
        
        logger.info("OAUTH_STATE_GENERATED: state={}, providerId={}, timestamp={}, mapSize={}", 
            state, providerId, oauthState.timestamp, oauthStates.size)
        
        return state
    }

    fun validateAndRemoveState(state: String): OAuthState? {
        logger.info("OAUTH_STATE_VALIDATION: Attempting to validate state={}, mapSize={}, availableStates={}", 
            state, oauthStates.size, oauthStates.keys)
        
        val oauthState = oauthStates.remove(state)
        if (oauthState != null) {
            val age = System.currentTimeMillis() - oauthState.timestamp
            logger.info("OAUTH_STATE_VALID: state={}, providerId={}, age={}ms", 
                state, oauthState.providerId, age)
        } else {
            logger.warn("OAUTH_STATE_INVALID: state={} not found, availableStates={}", state, oauthStates.keys)
        }
        
        return oauthState
    }

    fun cleanupOldStates() {
        val tenMinutesAgo = System.currentTimeMillis() - 600_000 // Reduced to 10 minutes
        val sizeBefore = oauthStates.size
        oauthStates.entries.removeIf { it.value.timestamp < tenMinutesAgo }
        val sizeAfter = oauthStates.size
        
        if (sizeBefore != sizeAfter) {
            logger.info("OAUTH_STATE_CLEANUP: Cleaned up {} old states, remaining={}", 
                sizeBefore - sizeAfter, sizeAfter)
        }
    }
    
    fun clearAllStates() {
        val sizeBefore = oauthStates.size
        oauthStates.clear()
        logger.info("OAUTH_STATE_CLEAR: Cleared all {} states", sizeBefore)
    }
}

@Controller("/oauth")
class OAuthController(
    private val identityProviderRepository: IdentityProviderRepository,
    private val userRepository: UserRepository,
    private val tokenGenerator: TokenGenerator,
    private val httpClient: HttpClient,
    private val oauthStateManager: OAuthStateManager
) {
    
    private val logger = LoggerFactory.getLogger(OAuthController::class.java)

    @Serdeable
    data class GitHubUser(
        val id: Long,
        val login: String,
        val email: String?,
        val name: String?
    )

    @Serdeable
    data class TokenResponse(
        val access_token: String?,
        val token_type: String?,
        val scope: String?,
        val error: String?,
        val error_description: String?
    )

    @Get("/authorize/{providerId}")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun authorize(providerId: Long, @QueryValue("fresh") fresh: Boolean?): HttpResponse<*> {
        val providerOptional = identityProviderRepository.findById(providerId)
        
        if (providerOptional.isEmpty || !providerOptional.get().enabled) {
            return HttpResponse.badRequest<Any>().body(mapOf("error" to "Identity provider not found or disabled"))
        }

        val provider = providerOptional.get()
        
        // Clear old states if fresh parameter is provided
        if (fresh == true) {
            oauthStateManager.cleanupOldStates()
            logger.info("OAUTH_FRESH: Cleaned up old states for fresh authorization")
        }
        
        // Generate and store state using singleton manager
        val state = oauthStateManager.generateAndStoreState(providerId)
        val authUrl = buildAuthorizationUrl(provider, state)
        
        return HttpResponse.redirect<Any>(URI.create(authUrl))
    }

    @Get("/callback")
    @Secured(SecurityRule.IS_ANONYMOUS)
    @ExecuteOn(TaskExecutors.IO)
    fun callback(
        @QueryValue code: String?,
        @QueryValue state: String?,
        @QueryValue error: String?
    ): HttpResponse<*> {
        
        if (error != null) {
            return HttpResponse.redirect<Any>(URI.create("http://localhost:4321/login?error=${URLEncoder.encode(error, StandardCharsets.UTF_8)}"))
        }
        
        if (code == null || state == null) {
            logger.warn("Missing OAuth parameters - code: {}, state: {}", code != null, state != null)
            return HttpResponse.redirect<Any>(URI.create("http://localhost:4321/login?error=missing_parameters"))
        }

        // Validate state parameter to prevent CSRF attacks
        var oauthState = oauthStateManager.validateAndRemoveState(state)
        if (oauthState == null) {
            logger.warn("State validation failed for: {}, trying cleanup and fallback", state)
            // Try cleanup and check again
            oauthStateManager.cleanupOldStates()
            
            // If still not found, it might be an old cached GitHub redirect
            // For now, we'll use a fallback approach (less secure but functional)
            logger.error("Invalid OAuth state parameter: {} - GitHub may have cached an old authorization URL", state)
            return HttpResponse.redirect<Any>(URI.create("http://localhost:4321/login?error=invalid_state"))
        }

        val providerOptional = identityProviderRepository.findById(oauthState.providerId)
        if (providerOptional.isEmpty) {
            return HttpResponse.redirect<Any>(URI.create("http://localhost:4321/login?error=provider_not_found"))
        }

        val provider = providerOptional.get()
        
        try {
            // Exchange code for access token
            val accessToken = exchangeCodeForToken(provider, code)
            
            // Get user info from GitHub
            val githubUser = getUserInfo(accessToken)
            
            // Find or create user
            val user = findOrCreateUser(githubUser, provider)
            
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
                return HttpResponse.redirect<Any>(URI.create("http://localhost:4321/login?error=token_generation_failed"))
            }

            val token = tokenOptional.get()
            
            // Redirect with token (in a real app, consider more secure token passing)
            val redirectUrl = "http://localhost:4321/oauth/success?token=${URLEncoder.encode(token, StandardCharsets.UTF_8)}" +
                "&user=${URLEncoder.encode("{\"id\":${user.id},\"username\":\"${user.username}\",\"email\":\"${user.email}\",\"roles\":[${user.roles.joinToString(",") { "\"${it.name}\"" }}]}", StandardCharsets.UTF_8)}"
            
            // Only do cleanup after successful OAuth completion
            oauthStateManager.cleanupOldStates()
            
            return HttpResponse.redirect<Any>(URI.create(redirectUrl))
            
        } catch (e: Exception) {
            e.printStackTrace()
            return HttpResponse.redirect<Any>(URI.create("http://localhost:4321/login?error=authentication_failed"))
        }
    }


    private fun buildAuthorizationUrl(provider: IdentityProvider, state: String): String {
        val params = mapOf(
            "client_id" to provider.clientId,
            "redirect_uri" to "http://localhost:8080/oauth/callback",
            "scope" to (provider.scopes ?: "user:email"),
            "state" to state,
            "response_type" to "code"
        )
        
        val queryString = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
        
        return "${provider.authorizationUrl}?$queryString"
    }

    private fun exchangeCodeForToken(provider: IdentityProvider, code: String): String {
        val formData = mapOf(
            "client_id" to provider.clientId,
            "client_secret" to provider.clientSecret,
            "code" to code,
            "redirect_uri" to "http://localhost:8080/oauth/callback"
        )
        
        val formBody = formData.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value ?: "", StandardCharsets.UTF_8)}"
        }
        
        val request = HttpRequest.POST(provider.tokenUrl, formBody)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
        
        val response = httpClient.toBlocking().exchange(request, TokenResponse::class.java)
        
        if (response.status.code != 200) {
            logger.error("Failed to exchange code for token. Status: {}, Body: {}", response.status, response.body())
            throw RuntimeException("Failed to exchange code for token: ${response.status}")
        }
        
        val tokenResponse = response.body()
        if (tokenResponse?.error != null) {
            logger.error("OAuth token exchange error: {} - {}", tokenResponse.error, tokenResponse.error_description)
            throw RuntimeException("OAuth error: ${tokenResponse.error} - ${tokenResponse.error_description}")
        }
        
        return tokenResponse?.access_token ?: throw RuntimeException("No access token in response")
    }

    private fun getUserInfo(accessToken: String): GitHubUser {
        val request = HttpRequest.GET<Any>("https://api.github.com/user")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("User-Agent", "SecMan-Backend/1.0")
        
        val response = httpClient.toBlocking().exchange(request, GitHubUser::class.java)
        
        if (response.status.code != 200) {
            throw RuntimeException("Failed to get user info: ${response.status}")
        }
        
        return response.body() ?: throw RuntimeException("No user info in response")
    }

    private fun findOrCreateUser(githubUser: GitHubUser, provider: IdentityProvider): User {
        val email = githubUser.email ?: "${githubUser.login}@github.local"
        
        // Try to find existing user by email first
        var userOptional = userRepository.findByEmail(email)
        
        if (userOptional.isEmpty) {
            // Try to find by username
            userOptional = userRepository.findByUsername(githubUser.login)
        }
        
        if (userOptional.isPresent) {
            return userOptional.get()
        }
        
        // Create new user if auto-provisioning is enabled
        if (!provider.autoProvision) {
            throw RuntimeException("User not found and auto-provisioning is disabled")
        }
        
        val newUser = User(
            username = githubUser.login,
            email = email,
            passwordHash = "", // No password for OAuth users
            roles = mutableSetOf(User.Role.USER)
        )
        
        return userRepository.save(newUser)
    }
}