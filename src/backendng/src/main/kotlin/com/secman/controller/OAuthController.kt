package com.secman.controller

import com.secman.config.AppConfig
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
    private val appConfig: AppConfig
) {
    
    private val logger = LoggerFactory.getLogger(OAuthController::class.java)
    private val backendBaseUrl: String = appConfig.backend.baseUrl
    private val frontendBaseUrl: String = appConfig.frontend.baseUrl

    init {
        logger.info("OAuthController initialized with backendBaseUrl: {}, frontendBaseUrl: {}", backendBaseUrl, frontendBaseUrl)
        // Validate that the URLs are properly formatted
        if (!frontendBaseUrl.startsWith("http://") && !frontendBaseUrl.startsWith("https://")) {
            logger.error("Invalid frontendBaseUrl configuration: {}. Expected format: http://localhost:4321", frontendBaseUrl)
        }
        if (!backendBaseUrl.startsWith("http://") && !backendBaseUrl.startsWith("https://")) {
            logger.error("Invalid backendBaseUrl configuration: {}. Expected format: http://localhost:8080", backendBaseUrl)
        }
    }

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
                logger.error("Invalid OAuth state: state token not found in database (first 10 chars: {})", state.take(10) + "...")
                logger.error("Possible causes: transaction not committed, state expired, or state already used")
                return HttpResponse.redirect<Any>(URI.create("$frontendBaseUrl/login?error=${java.net.URLEncoder.encode("Invalid or expired state parameter", "UTF-8")}"))
            }

            val providerId = stateOpt.get().providerId

            // Process OAuth callback
            val result = oauthService.handleCallback(providerId, code, state)
            
            when (result) {
                is OAuthService.CallbackResult.Success -> {
                    logger.info("OAuth login successful for user: {}", result.user.username)
                    
                    // Create user info JSON
                    val userInfoJson = """{"id":${result.user.id},"username":"${result.user.username}","email":"${result.user.email}","roles":[${result.user.roles.joinToString(",") { "\"$it\"" }}]}"""
                    
                    // Pass token and user data as URL parameters as expected by the frontend
                    val encodedUser = java.net.URLEncoder.encode(userInfoJson, "UTF-8")
                    val redirectUrl = "$frontendBaseUrl/login/success?token=${result.token}&user=$encodedUser"
                    
                    logger.debug("Redirecting to: {}", redirectUrl)
                    HttpResponse.redirect<Any>(URI.create(redirectUrl))
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
                logger.error("Invalid OAuth state in API callback: state token not found (first 10 chars: {})",
                    callbackRequest.state.take(10) + "...")
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
        // Check X-Forwarded headers first (for reverse proxy/load balancer scenarios)
        val forwardedHost = request.headers.get("X-Forwarded-Host")
        if (forwardedHost != null) {
            val scheme = request.headers.get("X-Forwarded-Proto") ?: "http"
			logger.info("getBaseUrl: Found forwarded-host header returning URL from ForwardHost field $scheme://$forwardedHost")

			return "$scheme://$forwardedHost"
        }

        // Fall back to Host header
        val hostHeader = request.headers.get("Host")
        if (hostHeader != null) {
            val scheme = request.headers.get("X-Forwarded-Proto") ?: "http"
			logger.info("getBaseUrl: Found host header returning URL from hostheader field $scheme://$hostHeader")

			return "$scheme://$hostHeader"
        }

        // Finally, use configured backend base URL
        logger.info("getBaseUrl: Found not matching http headers returning configured backend base URL $backendBaseUrl")
		return backendBaseUrl
    }
}