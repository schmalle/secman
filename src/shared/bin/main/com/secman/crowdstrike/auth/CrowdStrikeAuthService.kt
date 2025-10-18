package com.secman.crowdstrike.auth

import com.secman.crowdstrike.dto.FalconConfigDto
import com.secman.crowdstrike.exception.AuthenticationException
import com.secman.crowdstrike.exception.RateLimitException
import com.secman.crowdstrike.model.AuthToken
import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Service for OAuth2 authentication with CrowdStrike Falcon API
 *
 * Provides:
 * - OAuth2 client credentials flow authentication
 * - Token caching (30-minute validity)
 * - Proactive token refresh before expiration
 * - Error handling with retry logic for rate limits
 *
 * Related to: Feature 023-create-in-the (CrowdStrike CLI)
 * Tasks: T026-T028
 */
@Singleton
open class CrowdStrikeAuthService(
    @Client("https://api.crowdstrike.com")
    private val httpClient: HttpClient
) {
    private val log = LoggerFactory.getLogger(CrowdStrikeAuthService::class.java)

    // Token cache
    private var cachedToken: AuthToken? = null

    /**
     * Authenticate with CrowdStrike using OAuth2 client credentials flow
     *
     * Returns cached token if still valid. Otherwise, fetches new token and caches it.
     *
     * Task: T026
     *
     * @param config Falcon configuration with client credentials
     * @return AuthToken with access token and expiration
     * @throws AuthenticationException if authentication fails
     * @throws RateLimitException if rate limit exceeded during auth
     */
    fun authenticate(config: FalconConfigDto): AuthToken {
        require(config.clientId.isNotBlank()) { "Client ID cannot be blank" }
        require(config.clientSecret.isNotBlank()) { "Client secret cannot be blank" }

        log.debug("Authenticating with CrowdStrike, client_id={}", config.clientId)

        // Check cached token - Task T027
        if (isCachedTokenValid()) {
            log.debug("Using cached OAuth2 token")
            return cachedToken!!
        }

        try {
            // Build OAuth2 token request
            val tokenRequest = HttpRequest.POST("/oauth2/token", mapOf(
                "client_id" to config.clientId,
                "client_secret" to config.clientSecret
            ))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE)

            // Make request
            val response = httpClient.toBlocking().exchange(tokenRequest, Map::class.java)

            if (response.status.code != 201) {
                log.error("OAuth2 authentication failed: status={}, code={}", response.status, response.status.code)
                throw AuthenticationException(
                    "CrowdStrike authentication failed with status ${response.status.code}: ${response.status.reason}"
                )
            }

            val tokenData = response.body() as? Map<*, *>
                ?: throw AuthenticationException("Empty response body from CrowdStrike OAuth2 endpoint")

            val accessToken = tokenData["access_token"]?.toString()
                ?: throw AuthenticationException("No access_token in OAuth2 response")

            val expiresIn = (tokenData["expires_in"] as? Number)?.toLong() ?: 1800L

            // Create token object - Task T028
            val token = AuthToken(
                accessToken = accessToken,
                expiresAt = Instant.now().plusSeconds(expiresIn - 60),  // Subtract 60s safety margin
                tokenType = tokenData["token_type"]?.toString() ?: "bearer"
            )

            // Cache token - Task T027
            cachedToken = token

            log.info("Successfully authenticated with CrowdStrike, token expires at {}", token.expiresAt)
            return token
        } catch (e: io.micronaut.http.client.exceptions.HttpClientResponseException) {
            log.error("HTTP error during authentication: status={}", e.status, e)
            when (e.status.code) {
                401, 403 -> throw AuthenticationException("Invalid CrowdStrike credentials (status: ${e.status.code})", e)
                429 -> {
                    val retryAfter = e.response.headers.get("Retry-After")?.toLongOrNull() ?: 60L
                    throw RateLimitException("Rate limited during authentication", retryAfter, e)
                }
                in 500..599 -> throw AuthenticationException("CrowdStrike service error (status: ${e.status.code})", e)
                else -> throw AuthenticationException("Authentication failed: HTTP ${e.status.code}", e)
            }
        } catch (e: AuthenticationException) {
            throw e
        } catch (e: RateLimitException) {
            throw e
        } catch (e: Exception) {
            log.error("Unexpected error during authentication", e)
            throw AuthenticationException("Authentication error: ${e.message}", e)
        }
    }

    /**
     * Check if cached token is still valid
     *
     * Task: T027 (token caching)
     *
     * @return True if cached token exists and is not expired/expiring soon
     */
    private fun isCachedTokenValid(): Boolean {
        if (cachedToken == null) {
            return false
        }

        // Token is considered expired/invalid if it's expiring within next 5 minutes
        return !cachedToken!!.isExpiringSoon(bufferSeconds = 300)
    }

    /**
     * Clear cached token (for testing or explicit refresh)
     */
    fun clearCache() {
        log.debug("Clearing authentication token cache")
        cachedToken = null
    }

    /**
     * Get the currently cached token without authentication
     *
     * @return Cached token or null
     */
    fun getCachedToken(): AuthToken? = cachedToken
}
