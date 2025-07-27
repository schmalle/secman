package com.secman.controller

import com.secman.service.OAuthService
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.LocalDateTime

@Controller("/oauth")
@Secured(SecurityRule.IS_ANONYMOUS)
class OAuthController(
    private val oauthService: OAuthService
) {
    
    private val logger = LoggerFactory.getLogger(OAuthController::class.java)

    @Serdeable
    data class ErrorResponse(
        val error: String,
        val timestamp: LocalDateTime = LocalDateTime.now()
    )

    @Serdeable
    data class LoginResponse(
        val id: Long,
        val username: String,
        val email: String,
        val roles: List<String>,
        val token: String
    )

    /**
     * Initiate OAuth authorization flow
     */
    @Get("/authorize/{providerId}")
    fun authorize(@PathVariable providerId: Long, request: HttpRequest<*>): HttpResponse<*> {
        return try {
            val baseUrl = getBaseUrl(request)
            val authUrl = oauthService.buildAuthorizationUrl(providerId, baseUrl)
            
            if (authUrl != null) {
                logger.info("Redirecting to OAuth provider {} with URL: {}", providerId, authUrl)
                HttpResponse.redirect(URI.create(authUrl))
            } else {
                logger.error("Failed to build authorization URL for provider: {}", providerId)
                HttpResponse.badRequest(ErrorResponse("Failed to build authorization URL"))
            }
        } catch (e: Exception) {
            logger.error("OAuth authorization error for provider {}: {}", providerId, e.message, e)
            HttpResponse.serverError(ErrorResponse("OAuth authorization failed: ${e.message}"))
        }
    }

    /**
     * Handle OAuth callback without provider ID - fallback for external OAuth providers
     */
    @Get("/callback")
    fun callbackWithoutProvider(
        @QueryValue code: String?,
        @QueryValue state: String?,
        @QueryValue error: String?
    ): HttpResponse<*> {
        return try {
            // Check for OAuth error
            if (error != null) {
                logger.error("OAuth error: {}", error)
                return HttpResponse.redirect<Any>(URI.create("/login?error=${java.net.URLEncoder.encode("OAuth authentication failed: $error", "UTF-8")}"))
            }

            // Validate required parameters
            if (code.isNullOrBlank() || state.isNullOrBlank()) {
                logger.error("Missing required OAuth parameters")
                return HttpResponse.redirect<Any>(URI.create("/login?error=${java.net.URLEncoder.encode("Invalid OAuth callback parameters", "UTF-8")}"))
            }

            // Find provider ID from state
            val stateOpt = oauthService.findStateByValue(state)
            if (!stateOpt.isPresent) {
                logger.error("Invalid OAuth state: {}", state)
                return HttpResponse.redirect<Any>(URI.create("/login?error=${java.net.URLEncoder.encode("Invalid or expired state parameter", "UTF-8")}"))
            }

            val providerId = stateOpt.get().providerId

            // Process OAuth callback
            val result = oauthService.handleCallback(providerId, code, state)
            
            when (result) {
                is OAuthService.CallbackResult.Success -> {
                    logger.info("OAuth login successful for user: {}", result.user.username)
                    
                    // Create a successful login response page or redirect to frontend with token
                    val encodedToken = java.net.URLEncoder.encode(result.token, "UTF-8")
                    val encodedUser = java.net.URLEncoder.encode(
                        """{"id":${result.user.id},"username":"${result.user.username}","email":"${result.user.email}","roles":${result.user.roles.joinToString(",", "[\"", "\"]") { it }}}""",
                        "UTF-8"
                    )
                    
                    HttpResponse.redirect<Any>(URI.create("/login/success?token=$encodedToken&user=$encodedUser"))
                }
                
                is OAuthService.CallbackResult.Error -> {
                    logger.error("OAuth callback error: {}", result.message)
                    HttpResponse.redirect<Any>(URI.create("/login?error=${java.net.URLEncoder.encode(result.message, "UTF-8")}"))
                }
            }
        } catch (e: Exception) {
            logger.error("OAuth callback processing error: {}", e.message, e)
            HttpResponse.redirect<Any>(URI.create("/login?error=${java.net.URLEncoder.encode("OAuth processing failed", "UTF-8")}"))
        }
    }

    /**
     * Handle OAuth callback
     */
    @Get("/callback/{providerId}")
    fun callback(
        @PathVariable providerId: Long,
        @QueryValue code: String?,
        @QueryValue state: String?,
        @QueryValue error: String?
    ): HttpResponse<*> {
        return try {
            // Check for OAuth error
            if (error != null) {
                logger.error("OAuth error for provider {}: {}", providerId, error)
                return HttpResponse.redirect<Any>(URI.create("/login?error=${java.net.URLEncoder.encode("OAuth authentication failed: $error", "UTF-8")}"))
            }

            // Validate required parameters
            if (code.isNullOrBlank() || state.isNullOrBlank()) {
                logger.error("Missing required OAuth parameters for provider: {}", providerId)
                return HttpResponse.redirect<Any>(URI.create("/login?error=${java.net.URLEncoder.encode("Invalid OAuth callback parameters", "UTF-8")}"))
            }

            // Process OAuth callback
            val result = oauthService.handleCallback(providerId, code, state)
            
            when (result) {
                is OAuthService.CallbackResult.Success -> {
                    logger.info("OAuth login successful for user: {}", result.user.username)
                    
                    // Create a successful login response page or redirect to frontend with token
                    // For now, redirect to frontend with success parameters
                    val encodedToken = java.net.URLEncoder.encode(result.token, "UTF-8")
                    val encodedUser = java.net.URLEncoder.encode(
                        """{"id":${result.user.id},"username":"${result.user.username}","email":"${result.user.email}","roles":${result.user.roles.joinToString(",", "[\"", "\"]") { it }}}""",
                        "UTF-8"
                    )
                    
                    HttpResponse.redirect<Any>(URI.create("/login/success?token=$encodedToken&user=$encodedUser"))
                }
                
                is OAuthService.CallbackResult.Error -> {
                    logger.error("OAuth callback error for provider {}: {}", providerId, result.message)
                    HttpResponse.redirect<Any>(URI.create("/login?error=${java.net.URLEncoder.encode(result.message, "UTF-8")}"))
                }
            }
        } catch (e: Exception) {
            logger.error("OAuth callback processing error for provider {}: {}", providerId, e.message, e)
            HttpResponse.redirect<Any>(URI.create("/login?error=${java.net.URLEncoder.encode("OAuth processing failed", "UTF-8")}"))
        }
    }

    /**
     * API endpoint for OAuth callback (for AJAX requests)
     */
    @Post("/callback/{providerId}")
    fun callbackApi(
        @PathVariable providerId: Long,
        @Body callbackRequest: CallbackRequest
    ): HttpResponse<*> {
        return try {
            val result = oauthService.handleCallback(providerId, callbackRequest.code, callbackRequest.state)
            
            when (result) {
                is OAuthService.CallbackResult.Success -> {
                    logger.info("OAuth API login successful for user: {}", result.user.username)
                    
                    val response = LoginResponse(
                        id = result.user.id,
                        username = result.user.username,
                        email = result.user.email,
                        roles = result.user.roles,
                        token = result.token
                    )
                    
                    HttpResponse.ok(response)
                }
                
                is OAuthService.CallbackResult.Error -> {
                    logger.error("OAuth API callback error for provider {}: {}", providerId, result.message)
                    HttpResponse.badRequest(ErrorResponse(result.message))
                }
            }
        } catch (e: Exception) {
            logger.error("OAuth API callback processing error for provider {}: {}", providerId, e.message, e)
            HttpResponse.serverError(ErrorResponse("OAuth processing failed: ${e.message}"))
        }
    }

    @Serdeable
    data class CallbackRequest(
        val code: String,
        val state: String
    )

    private fun getBaseUrl(request: HttpRequest<*>): String {
        val scheme = request.headers.get("X-Forwarded-Proto") ?: "http"
        val host = request.headers.get("X-Forwarded-Host") ?: request.headers.get("Host") ?: "localhost:8080"
        return "$scheme://$host"
    }
}