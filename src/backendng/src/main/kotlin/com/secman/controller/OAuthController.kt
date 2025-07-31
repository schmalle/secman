package com.secman.controller

import com.secman.service.OAuthService
import io.micronaut.context.annotation.Value
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.scheduling.TaskExecutors
import io.micronaut.scheduling.annotation.ExecuteOn
import io.micronaut.security.annotation.Secured
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.LocalDateTime

@Controller("/oauth")
@Secured(SecurityRule.IS_ANONYMOUS)
class OAuthController(
    private val oauthService: OAuthService,
    @Value("\${app.frontend.base-url}") private val frontendBaseUrl: String
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
    @ExecuteOn(TaskExecutors.BLOCKING)
    fun callbackWithoutProvider(
        @QueryValue code: String?,
        @QueryValue state: String?,
        @QueryValue error: String?
    ): HttpResponse<*> {
        return try {
            // Check for OAuth error
            if (error != null) {
                logger.error("OAuth error: {}", error)
                val errorMessage = when (error) {
                    "access_denied" -> "Access was denied. Please try again if you wish to log in."
                    "unauthorized_client" -> "OAuth application is not properly configured. Please contact support."
                    "invalid_request" -> "Invalid OAuth request. Please try logging in again."
                    "unsupported_response_type" -> "OAuth configuration error. Please contact support."
                    "invalid_scope" -> "Requested permissions are not available. Please contact support."
                    "server_error" -> "OAuth provider encountered an error. Please try again later."
                    "temporarily_unavailable" -> "OAuth service is temporarily unavailable. Please try again later."
                    else -> "OAuth authentication failed. Please try again."
                }
                return HttpResponse.redirect<Any>(URI.create("$frontendBaseUrl/login?error=${java.net.URLEncoder.encode(errorMessage, "UTF-8")}"))
            }

            // Validate required parameters
            if (code.isNullOrBlank() || state.isNullOrBlank()) {
                logger.error("Missing required OAuth parameters")
                return HttpResponse.redirect<Any>(URI.create("$frontendBaseUrl/login?error=${java.net.URLEncoder.encode("Invalid OAuth callback parameters", "UTF-8")}"))
            }

            // Find provider ID from state
            val stateOpt = oauthService.findStateByValue(state)
            if (!stateOpt.isPresent) {
                logger.error("Invalid OAuth state")
                return HttpResponse.redirect<Any>(URI.create("$frontendBaseUrl/login?error=${java.net.URLEncoder.encode("Invalid or expired state parameter", "UTF-8")}"))
            }

            val providerId = stateOpt.get().providerId

            // Process OAuth callback
            val result = oauthService.handleCallback(providerId, code, state)
            
            when (result) {
                is OAuthService.CallbackResult.Success -> {
                    logger.info("OAuth login successful for user: {}", result.user.username)
                    
                    // Use secure session/cookie storage instead of URL parameters
                    val userInfoJson = """{"id":${result.user.id},"username":"${result.user.username}","email":"${result.user.email}","roles":${result.user.roles.joinToString(",", "[\"", "\"]") { it }}}"""
                    
                    HttpResponse.redirect<Any>(URI.create("$frontendBaseUrl/login/success"))
                        .header("Set-Cookie", "auth_token=${result.token}; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=3600")
                        .header("Set-Cookie", "user_info=${java.net.URLEncoder.encode(userInfoJson, "UTF-8")}; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=3600")
                }
                
                is OAuthService.CallbackResult.Error -> {
                    logger.error("OAuth callback error: {}", result.message)
                    HttpResponse.redirect<Any>(URI.create("$frontendBaseUrl/login?error=${java.net.URLEncoder.encode(result.message, "UTF-8")}"))
                }
            }
        } catch (e: Exception) {
            logger.error("OAuth callback processing error: {}", e.message, e)
            HttpResponse.redirect<Any>(URI.create("$frontendBaseUrl/login?error=${java.net.URLEncoder.encode("OAuth processing failed", "UTF-8")}"))
        }
    }


    /**
     * API endpoint for OAuth callback (for AJAX requests)
     */
    @Post("/callback")
    @ExecuteOn(TaskExecutors.BLOCKING)
    fun callbackApi(
        @Body callbackRequest: CallbackRequest
    ): HttpResponse<*> {
        return try {
            // Find provider ID from state
            val stateOpt = oauthService.findStateByValue(callbackRequest.state)
            if (!stateOpt.isPresent) {
                logger.error("Invalid OAuth state")
                return HttpResponse.badRequest(ErrorResponse("Invalid or expired state parameter"))
            }

            val providerId = stateOpt.get().providerId
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
                        .header("Set-Cookie", "auth_token=${result.token}; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=3600")
                }
                
                is OAuthService.CallbackResult.Error -> {
                    logger.error("OAuth API callback error: {}", result.message)
                    HttpResponse.badRequest(ErrorResponse(result.message))
                }
            }
        } catch (e: Exception) {
            logger.error("OAuth API callback processing error: {}", e.message, e)
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