package com.secman.controller

import com.secman.config.AppConfig
import com.secman.service.AuthCookieService
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
    private val appConfig: AppConfig,
    private val authCookieService: AuthCookieService
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
     *
     * IMPORTANT: Cache-control headers are added by SecurityHeadersFilter for all OAuth endpoints
     * to prevent browser caching of OAuth redirects, which can cause "state" errors in
     * corporate AAD environments where cached responses contain stale state tokens.
     */
    @Get("/authorize/{providerId}")
    fun authorize(@PathVariable providerId: Long, request: HttpRequest<*>): HttpResponse<*> {
        return try {
            logger.info("=== OAuth Authorization Request START ===")
            logger.info("Provider ID: {}", providerId)
            logger.info("Backend Base URL (from config): {}", backendBaseUrl)
            logger.info("Request URI: {}", request.uri)
            logger.info("Request remote address: {}", request.remoteAddress.toString())

            val authUrl = oauthService.buildAuthorizationUrl(providerId, backendBaseUrl)

            if (authUrl != null) {
                logger.info("Successfully built authorization URL")
                logger.info("Full authorization URL: {}", authUrl)
                logger.info("Redirecting to OAuth provider {} with URL: {}", providerId, authUrl)
                logger.info("=== OAuth Authorization Request END ===")
                HttpResponse.redirect<Any>(URI.create(authUrl))
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
     *
     * Cache-control headers are added by SecurityHeadersFilter for all OAuth endpoints.
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

            // Find provider ID from state with retry mechanism
            // Microsoft Azure with cached SSO can return in 100-500ms, potentially before
            // the state-save transaction is fully visible. Retry handles this race condition.
            val stateOpt = oauthService.findStateByValueWithRetry(state)
            if (!stateOpt.isPresent) {
                logger.error("Invalid OAuth state: state token not found in database after retries (first 10 chars: {})", state.take(10) + "...")
                logger.error("Possible causes: state never saved, state expired (>10min), or state already consumed by another callback")
                return HttpResponse.redirect<Any>(URI.create("$frontendBaseUrl/login?error=${java.net.URLEncoder.encode("Invalid or expired state parameter. Please try logging in again.", "UTF-8")}"))
            }

            val oauthState = stateOpt.get()
            logger.debug("OAuth state validated successfully for provider {}", oauthState.providerId)

            // Process OAuth callback with the already-validated state
            val result = oauthService.handleCallback(oauthState, code)

            when (result) {
                is OAuthService.CallbackResult.Success -> {
                    logger.info("OAuth login successful for user: {}", result.user.username)

                    // Create user info JSON (non-sensitive metadata only, token is in HttpOnly cookie)
                    val userInfoJson = """{"id":${result.user.id},"username":"${result.user.username}","email":"${result.user.email}","roles":[${result.user.roles.joinToString(",") { "\"$it\"" }}]}"""

                    // Only pass user metadata in URL - JWT is delivered solely via HttpOnly cookie
                    // This prevents token exposure in browser history, proxy logs, and referrer headers
                    val encodedUser = java.net.URLEncoder.encode(userInfoJson, "UTF-8")
                    val redirectUrl = "$frontendBaseUrl/login/success?user=$encodedUser"

                    logger.debug("Redirecting to: {}", redirectUrl)
                    // Set HttpOnly cookie for authentication (same as local login)
                    HttpResponse.redirect<Any>(URI.create(redirectUrl))
                        .cookie(authCookieService.createAuthCookie(result.token))
                }

                is OAuthService.CallbackResult.Error -> {
                    logger.error("OAuth callback error: {}", result.message)
                    HttpResponse.redirect<Any>(URI.create("$frontendBaseUrl/login?error=${java.net.URLEncoder.encode(result.message, "UTF-8")}"))
                }
            }
        } catch (e: Exception) {
            logger.error("OAuth callback processing error: {}", e.message, e)
            val errorMsg = "An unexpected error occurred during login. Please try again."
            HttpResponse.redirect<Any>(URI.create("$frontendBaseUrl/login?error=${java.net.URLEncoder.encode(errorMsg, "UTF-8")}"))
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
            // Find provider ID from state with retry mechanism
            val stateOpt = oauthService.findStateByValueWithRetry(callbackRequest.state)
            if (!stateOpt.isPresent) {
                logger.error("Invalid OAuth state in API callback: state token not found after retries (first 10 chars: {})",
                    callbackRequest.state.take(10) + "...")
                return HttpResponse.badRequest(ErrorResponse("Invalid or expired state parameter. Please try again."))
            }

            val oauthState = stateOpt.get()
            val result = oauthService.handleCallback(oauthState, callbackRequest.code)
            
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
                        .cookie(authCookieService.createAuthCookie(result.token))
                }
                
                is OAuthService.CallbackResult.Error -> {
                    logger.error("OAuth API callback error: {}", result.message)
                    HttpResponse.badRequest(ErrorResponse(result.message))
                }
            }
        } catch (e: Exception) {
            logger.error("OAuth API callback processing error: {}", e.message, e)
            HttpResponse.serverError(ErrorResponse("An unexpected error occurred during login. Please try again."))
        }
    }

    @Serdeable
    data class CallbackRequest(
        val code: String,
        val state: String
    )

}