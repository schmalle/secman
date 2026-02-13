package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.secman.config.OAuthConfig
import com.secman.domain.IdentityProvider
import com.secman.domain.OAuthState
import com.secman.domain.User
import com.secman.event.UserCreatedEvent
import com.secman.repository.IdentityProviderRepository
import com.secman.repository.OAuthStateRepository
import com.secman.repository.UserRepository
import com.secman.util.MicrosoftErrorMapper
import io.micronaut.context.event.ApplicationEventPublisher
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.exceptions.ReadTimeoutException
import io.micronaut.security.token.generator.TokenGenerator
import io.micronaut.scheduling.annotation.Async
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.transaction.Transactional
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.util.*

/**
 * OAuth/OIDC Authentication Service
 *
 * Handles complete OAuth 2.0 and OpenID Connect (OIDC) authentication flows for external identity providers.
 * This is the core authentication service for all single sign-on (SSO) integrations in Secman.
 *
 * ## Supported Identity Providers
 * - **Microsoft Azure AD / Entra ID**: Full OIDC support with tenant validation, JWKS signature verification
 * - **GitHub**: OAuth 2.0 with user info endpoint for profile data
 * - **Generic OIDC**: Any standards-compliant OIDC provider via dynamic configuration
 *
 * ## OAuth Flow Architecture
 *
 * The service implements a standard OAuth 2.0 Authorization Code flow with OIDC extensions:
 *
 * ```
 * ┌─────────┐     ┌─────────────┐     ┌────────────────┐     ┌─────────────┐
 * │ Browser │────▶│ OAuthCtrl   │────▶│ OAuthService   │────▶│ IdP (Azure/ │
 * │         │◀────│ /authorize  │◀────│ buildAuthUrl() │◀────│ GitHub/etc) │
 * └─────────┘     └─────────────┘     └────────────────┘     └─────────────┘
 *      │                                      │
 *      │ 1. User clicks "Login with..."       │ 2. Generate state, save to DB
 *      │                                      │
 *      ▼                                      ▼
 * ┌─────────┐     ┌─────────────┐     ┌────────────────┐     ┌─────────────┐
 * │ Browser │────▶│ OAuthCtrl   │────▶│ OAuthService   │────▶│ IdP Token   │
 * │         │◀────│ /callback   │◀────│ handleCallback │◀────│ Endpoint    │
 * └─────────┘     └─────────────┘     └────────────────┘     └─────────────┘
 *                                             │
 *      3. IdP redirects with code ───────────▶│ 4. Exchange code for token
 *                                             │ 5. Validate ID token (OIDC)
 *      6. Return JWT for frontend ◀───────────│ 7. Find or create user
 * ```
 *
 * ## Race Condition Handling (Microsoft Azure SSO)
 *
 * **Problem**: Microsoft Azure callbacks can arrive within 100-500ms when users have cached SSO sessions.
 * This creates a race condition where the OAuth callback arrives before the state-save transaction
 * is fully committed and visible to other database connections.
 *
 * **Solution**: This service implements two complementary strategies:
 *
 * 1. **Immediate State Commit**: [saveOAuthStateImmediately] uses `REQUIRES_NEW` transaction
 *    with explicit `entityManager.flush()` to ensure the state is committed before redirect.
 *
 * 2. **Retry with Exponential Backoff**: [findStateByValueWithRetry] retries state lookups
 *    with configurable backoff (default: 100ms → 150ms → 225ms → 337ms → 500ms).
 *
 * Configuration via environment variables or `application.yml`:
 * ```yaml
 * secman:
 *   oauth:
 *     state-retry:
 *       max-attempts: 5           # OAUTH_STATE_RETRY_MAX_ATTEMPTS
 *       initial-delay-ms: 100     # OAUTH_STATE_RETRY_INITIAL_DELAY
 *       max-delay-ms: 500         # OAUTH_STATE_RETRY_MAX_DELAY
 *       backoff-multiplier: 1.5   # OAUTH_STATE_RETRY_BACKOFF_MULTIPLIER
 *     token-exchange:
 *       max-retries: 2            # OAUTH_TOKEN_EXCHANGE_MAX_RETRIES
 *       retry-delay-ms: 500       # OAUTH_TOKEN_EXCHANGE_RETRY_DELAY
 * ```
 *
 * ## Security Features
 *
 * - **State Parameter**: CSRF protection via cryptographically random state tokens
 * - **JWKS Validation**: ID token signatures verified against provider's JWKS endpoint
 * - **Tenant Validation**: Microsoft Azure tenant ID verified against configured tenant
 * - **Token Expiry**: OAuth states auto-expire (default 10 minutes) with scheduled cleanup
 * - **Audit Logging**: Security events logged to `security.audit` logger for compliance
 *
 * ## User Provisioning
 *
 * When a user authenticates via OAuth for the first time:
 * 1. User is auto-created if `autoProvision` is enabled on the identity provider
 * 2. Default roles (USER, VULN) are assigned per Feature 046
 * 3. [UserCreatedEvent] is published for user mapping application (Feature 042)
 * 4. Admin notifications sent asynchronously (Feature 027)
 * 5. Existing users preserve their roles on subsequent logins (FR-006)
 *
 * ## Error Handling
 *
 * User-friendly error messages are provided via [OAuthErrorCode] enum:
 * - State validation errors (not found, expired, mismatch)
 * - Token exchange failures (timeout, server error)
 * - User provisioning failures
 *
 * Microsoft-specific errors are mapped via [MicrosoftErrorMapper] for clearer diagnostics.
 *
 * ## Related Components
 *
 * - [OAuthController]: REST endpoints for OAuth flow (`/oauth/authorize`, `/oauth/callback`)
 * - [OAuthConfig]: Retry and timeout configuration
 * - [JwksValidationService]: JWT signature validation using JWKS
 * - [IdentityProvider]: Database entity for provider configuration
 * - [OAuthState]: Database entity for state parameter storage
 * - [MicrosoftErrorMapper]: Azure AD error code translation
 *
 * ## Logging
 *
 * - Standard logs: `com.secman.service.OAuthService`
 * - Security audit: `security.audit` (for compliance/SIEM integration)
 * - Correlation IDs: Each OAuth flow gets a unique correlation ID for tracing
 *
 * @see OAuthController
 * @see OAuthConfig
 * @see JwksValidationService
 * @see IdentityProvider
 * @see MicrosoftErrorMapper
 * @since 1.0
 * @author Secman Development Team
 */
@Singleton
open class OAuthService(
    private val identityProviderRepository: IdentityProviderRepository,
    private val oauthStateRepository: OAuthStateRepository,
    private val userRepository: UserRepository,
    private val tokenGenerator: TokenGenerator,
    private val objectMapper: ObjectMapper,
    private val microsoftErrorMapper: MicrosoftErrorMapper,
    private val adminNotificationService: AdminNotificationService,
    private val eventPublisher: ApplicationEventPublisher<UserCreatedEvent>,
    private val entityManager: EntityManager,
    private val emailSender: EmailSender,
    private val oauthConfig: OAuthConfig,
    private val jwksValidationService: JwksValidationService,
    @Client("\${oauth.http-client.url:https://api.github.com}") private val githubApiClient: HttpClient,
    @Client private val genericHttpClient: HttpClient
) {

    private val logger = LoggerFactory.getLogger(OAuthService::class.java)
    private val securityLog = LoggerFactory.getLogger("security.audit")
    private val passwordEncoder = BCryptPasswordEncoder()

    /**
     * OAuth error codes with user-friendly messages
     */
    enum class OAuthErrorCode(val userMessage: String) {
        STATE_NOT_FOUND("Your login session was not found. Please try again."),
        STATE_EXPIRED("Your login session expired. Please try again."),
        STATE_MISMATCH("Invalid login state. Please start over."),
        TOKEN_EXCHANGE_FAILED("Could not complete authentication. Please try again."),
        TOKEN_EXCHANGE_TIMEOUT("Authentication timed out. Please try again."),
        USER_INFO_FAILED("Could not retrieve your account information. Please try again."),
        USER_CREATION_FAILED("Could not create your account. Please contact support."),
        EMAIL_REQUIRED("Your account does not have an email address configured."),
        TENANT_MISMATCH("Your account is not from the expected organization."),
        PROVIDER_NOT_FOUND("Identity provider not found. Please contact support."),
        UNEXPECTED_ERROR("An unexpected error occurred during login. Please try again.")
    }

    /**
     * Find OAuth state by state value with retry logic for race conditions.
     *
     * Microsoft Azure OAuth callbacks can arrive within 100-500ms when users
     * have cached SSO sessions. This retry mechanism handles the rare case
     * where the state lookup occurs before the save transaction commits.
     */
    fun findStateByValue(stateToken: String): Optional<OAuthState> {
        return oauthStateRepository.findByStateToken(stateToken)
    }

    /**
     * Find OAuth state with retry mechanism to handle race conditions.
     *
     * When Microsoft Azure users have cached SSO sessions, callbacks can arrive
     * in 100-500ms. This retry logic handles cases where the callback arrives
     * before the state-save transaction is fully visible.
     *
     * Uses exponential backoff: 100ms -> 150ms -> 225ms -> 337ms -> 500ms (default config)
     * Total max wait: ~1.3 seconds
     *
     * @param stateToken The OAuth state token to find
     * @return Optional containing the state if found, empty otherwise
     */
    fun findStateByValueWithRetry(stateToken: String): Optional<OAuthState> {
        val config = oauthConfig.stateRetry
        var attempt = 0
        var currentDelayMs = config.initialDelayMs
        var totalDelayMs = 0L

        while (attempt <= config.maxAttempts) {
            val result = oauthStateRepository.findByStateToken(stateToken)
            if (result.isPresent) {
                if (attempt > 0) {
                    logger.info("OAuth state found after {} retry attempts ({}ms total delay)",
                        attempt, totalDelayMs)
                }
                return result
            }

            if (attempt < config.maxAttempts) {
                logger.debug("OAuth state not found, retry {}/{} after {}ms delay (exponential backoff)",
                    attempt + 1, config.maxAttempts, currentDelayMs)
                try {
                    Thread.sleep(currentDelayMs)
                    totalDelayMs += currentDelayMs
                    // Apply exponential backoff with max cap
                    currentDelayMs = minOf(
                        (currentDelayMs * config.backoffMultiplier).toLong(),
                        config.maxDelayMs
                    )
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            attempt++
        }

        logger.warn("OAuth state not found after {} retries ({}ms total delay with exponential backoff)",
            config.maxAttempts, totalDelayMs)
        return Optional.empty()
    }

    /**
     * Save OAuth state in a NEW transaction to ensure immediate commit.
     *
     * CRITICAL: This method uses REQUIRES_NEW to create an independent transaction
     * that commits immediately upon return, BEFORE the redirect to the OAuth provider.
     * This prevents race conditions where fast OAuth callbacks (Microsoft Azure with
     * cached SSO can return in 100-500ms) arrive before the state is visible in the
     * database.
     *
     * @param oauthState The OAuth state to persist
     * @return The saved OAuth state with generated ID
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    open fun saveOAuthStateImmediately(oauthState: OAuthState): OAuthState {
        val saved = oauthStateRepository.save(oauthState)
        entityManager.flush()
        logger.debug("OAuth state saved and committed immediately: {} (first 10 chars)",
            saved.stateToken.take(10) + "...")
        return saved
    }

    /**
     * Build authorization URL for OAuth provider
     *
     * CRITICAL: This method must NOT be @Transactional to prevent race conditions.
     * The OAuth state MUST be committed to the database BEFORE redirecting the user
     * to the OAuth provider. With @Transactional, the transaction commits AFTER the
     * method returns, causing the redirect to happen before the state is visible in
     * the database. Fast OAuth callbacks (especially with cached provider sessions)
     * then fail with "Invalid or expired state parameter" because the state lookup
     * occurs before the transaction commits. We use entityManager.flush() instead
     * to force immediate persistence within the existing transaction context.
     */
    open fun buildAuthorizationUrl(providerId: Long, baseUrl: String): String? {

		logger.info("OAuthService.buildAuthorizationUrl: Starting for providerId={}, baseUrl={}", providerId, baseUrl)

		val providerOpt = identityProviderRepository.findById(providerId)
        if (!providerOpt.isPresent) {
            //logger.error("Identity provider not found: {}", providerId)
            return null
        }

        val provider = providerOpt.get()
        if (!provider.enabled) {
            //logger.error("Identity provider is disabled: {}", provider.name)
            return null
        }

        // Clean up expired states from all providers (states auto-expire after 10 min)
        oauthStateRepository.deleteExpiredStates()

        // Log active state count for observability (do NOT delete other users' states)
        val activeStateCount = oauthStateRepository.countByProviderId(providerId)
        if (activeStateCount > 0) {
            logger.debug("{} active OAuth states exist for provider {} (concurrent logins in progress)",
                activeStateCount, providerId)
        }

        // Generate state parameter
        val state = generateState()

        // Use custom callback URL if configured, otherwise fall back to baseUrl
        val redirectUri = provider.callbackUrl?.takeIf {
			it.isNotBlank()
		} ?: "$baseUrl/oauth/callback"

		logger.info("OAuthService.buildAuthorizationUrl: Provider callback URL: {}", provider.callbackUrl)
		logger.info("OAuthService.buildAuthorizationUrl: Constructed RedirectUri: {}", redirectUri)

        // Save state in a SEPARATE transaction that commits immediately
        // CRITICAL: This prevents race conditions with fast OAuth callbacks (Microsoft Azure SSO)
        val oauthState = OAuthState(
            stateToken = state,
            providerId = providerId,
            redirectUri = redirectUri
        )

        // Use REQUIRES_NEW transaction to ensure state is COMMITTED before redirect
        val savedState = saveOAuthStateImmediately(oauthState)
        logger.info("OAuth state saved and COMMITTED with token {} (first 10 chars) for provider {}, expires at {}",
            state.take(10) + "...", providerId, savedState.expiresAt)

        // Verify state is visible in a NEW connection (simulates callback scenario)
        // This verification runs OUTSIDE the save transaction since saveOAuthStateImmediately already committed
        val verificationState = oauthStateRepository.findByStateToken(state)
        if (!verificationState.isPresent) {
            logger.error("CRITICAL: OAuth state not visible after commit! This indicates a database replication lag or configuration issue.")
            return null
        }
        logger.info("Verified OAuth state is visible to other transactions - ready for redirect")

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

        val authorizationUrl = "$authUrl?client_id=$clientId&redirect_uri=$encodedRedirectUri&scope=$scopes&state=$encodedState&response_type=code"

        // For Microsoft providers, force account picker to prevent silent SSO.
        // Without this, Microsoft redirects back in 100-500ms with cached sessions,
        // giving users no chance to pick an account and maximizing timing issues.
        if (!tenantId.isNullOrBlank()) {
            return "$authorizationUrl&prompt=select_account"
        }

        return authorizationUrl
    }

    /**
     * Handle OAuth callback and exchange code for token
     *
     * Includes correlation ID logging for tracking OAuth flow through logs.
     */
    /**
     * Handle OAuth callback and exchange code for token.
     *
     * Accepts the already-validated OAuthState directly from the controller,
     * avoiding a redundant state lookup that could fail if the state was
     * consumed or expired between the controller's retry-based lookup and this call.
     *
     * @param oauthState The validated OAuth state (already found via findStateByValueWithRetry)
     * @param code The authorization code from the OAuth provider
     */
    @Transactional
    open fun handleCallback(oauthState: OAuthState, code: String): CallbackResult {
        val providerId = oauthState.providerId
        val state = oauthState.stateToken

        // Generate correlation ID for tracking this OAuth flow through logs
        val correlationId = UUID.randomUUID().toString().take(8)
        MDC.put("oauth_correlation_id", correlationId)

        try {
            val callbackStartTime = LocalDateTime.now()
            logger.info("[{}] OAuth callback started for provider {}, state={}",
                correlationId, providerId, state.take(10) + "...")

        val stateAge = java.time.Duration.between(oauthState.createdAt, callbackStartTime).toMillis()
        logger.info("OAuth state found: created at {}, callback at {}, age={}ms",
            oauthState.createdAt, callbackStartTime, stateAge)

        if (oauthState.expiresAt.isBefore(LocalDateTime.now())) {
            logger.error("Expired OAuth state: created at {}, expired at {}, current time {}",
                oauthState.createdAt, oauthState.expiresAt, LocalDateTime.now())
            oauthStateRepository.deleteByStateToken(state)
            return CallbackResult.Error(OAuthErrorCode.STATE_EXPIRED.userMessage)
        }

        val providerOpt = identityProviderRepository.findById(providerId)
        if (!providerOpt.isPresent) {
            logger.error("Identity provider not found: {}", providerId)
            return CallbackResult.Error(OAuthErrorCode.PROVIDER_NOT_FOUND.userMessage)
        }

        val provider = providerOpt.get()
        
        logger.debug("Processing OAuth callback for provider: {}", provider.name)

        try {
            // Exchange code for access token (with retry for transient failures)
            val tokenResponse = exchangeCodeForTokenWithRetry(provider, code, oauthState.redirectUri!!)
            if (tokenResponse == null) {
                oauthStateRepository.deleteByStateToken(state)
                return CallbackResult.Error(OAuthErrorCode.TOKEN_EXCHANGE_FAILED.userMessage)
            }

            // For OIDC providers (Microsoft), validate ID token signature and parse claims
            var emailFromIdToken: String? = null
            if (tokenResponse.idToken != null) {
                // Validate JWT signature using JWKS and parse claims
                val idTokenClaims = jwksValidationService.validateAndParseJwt(
                    tokenResponse.idToken,
                    provider.jwksUri,
                    provider.issuer
                )
                if (idTokenClaims == null) {
                    oauthStateRepository.deleteByStateToken(state)
                    return CallbackResult.Error(OAuthErrorCode.TOKEN_EXCHANGE_FAILED.userMessage)
                }

                // Log all claims for debugging
                logger.info("=== ID Token Claims Debug ===")
                logger.info("Provider: {}", provider.name)
                logger.info("All claims in ID token:")
                idTokenClaims.forEach { (key, value) ->
                    logger.info("  {}: {}", key, value)
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
                logger.info("Extracted email from ID token: {}", emailFromIdToken ?: "NULL")

                // For Microsoft providers, email is required
                if (provider.name.contains("Microsoft", ignoreCase = true) && emailFromIdToken.isNullOrBlank()) {
                    logger.error("Email extraction failed - checking available claims for email-like values...")
                    idTokenClaims.forEach { (key, value) ->
                        if (key.contains("mail", ignoreCase = true) ||
                            key.contains("email", ignoreCase = true) ||
                            key.contains("upn", ignoreCase = true) ||
                            key.contains("unique_name", ignoreCase = true)) {
                            logger.error("  Found potential email claim: {} = {}", key, value)
                        }
                    }
                    oauthStateRepository.deleteByStateToken(state)
                    return CallbackResult.Error(OAuthErrorCode.EMAIL_REQUIRED.userMessage)
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
                    return CallbackResult.Error(OAuthErrorCode.USER_INFO_FAILED.userMessage)
                }
                info
            }

            // Find or create user
            val userResult = findOrCreateUser(provider, userInfo)
            if (userResult == null) {
                oauthStateRepository.deleteByStateToken(state)
                return CallbackResult.Error(OAuthErrorCode.USER_CREATION_FAILED.userMessage)
            }
            val user = userResult.user

            // Send notification if user was newly created via OAuth (Feature 027)
            if (userResult.isNewUser) {
                try {
                    adminNotificationService.sendNewUserNotificationForOAuth(user, provider.name)
                } catch (e: Exception) {
                    // Log but don't fail OAuth flow if notification fails
                    logger.error("Failed to send OAuth user notification: ${e.message}", e)
                }
            } else {
                // Feature 046: Log existing user login (roles preserved per FR-006)
                logger.info("Existing OIDC user logged in: ${userInfo.email}, roles preserved")
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

            // Update last login timestamp
            user.lastLogin = Instant.now()
            userRepository.update(user)

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
            logger.error("[{}] OAuth callback error: {}", correlationId, e.message, e)
            // Clean up state on error to prevent accumulation
            oauthStateRepository.deleteByStateToken(state)
            return CallbackResult.Error(OAuthErrorCode.UNEXPECTED_ERROR.userMessage)
        }
        } finally {
            // Clean up MDC correlation ID
            MDC.remove("oauth_correlation_id")
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

            logger.info("=== Token Exchange Debug ===")
            logger.info("Provider: {}", provider.name)
            logger.info("Token URL: {}", tokenUrl)
            logger.info("Redirect URI being sent: {}", redirectUri)
            logger.info("Client ID: {}", provider.clientId)
            logger.info("Code (first 20 chars): {}", code.take(20) + "...")

            // Build OAuth 2.0 token exchange request (required by Microsoft, GitHub, etc.)
            val formData = "grant_type=authorization_code" +
                    "&client_id=${URLEncoder.encode(provider.clientId, StandardCharsets.UTF_8)}" +
                    "&client_secret=${URLEncoder.encode(provider.clientSecret ?: "", StandardCharsets.UTF_8)}" +
                    "&code=${URLEncoder.encode(code, StandardCharsets.UTF_8)}" +
                    "&redirect_uri=${URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)}"

            logger.info("Sending token exchange request...")
            val response = genericHttpClient.toBlocking().retrieve(
                HttpRequest.POST(tokenUrl, formData)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded"),
                String::class.java
            )
            logger.info("Received token response")
            
            
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
            logger.error("=== Token Exchange Failed ===")
            logger.error("Provider: {}", provider.name)
            logger.error("Error type: {}", e.javaClass.simpleName)
            logger.error("Error message: {}", e.message)

            // If it's an HTTP error, try to get the response body
            if (e is io.micronaut.http.client.exceptions.HttpClientResponseException) {
                val errorBody = e.response.getBody(String::class.java).orElse("No error body")
                logger.error("HTTP Status: {}", e.status)
                logger.error("Error response body: {}", errorBody)

                // Try to parse error for better diagnostics
                try {
                    val errorData = parseJsonResponse(errorBody)
                    logger.error("Parsed error: {}", errorData)
                } catch (parseEx: Exception) {
                    logger.error("Could not parse error response as JSON")
                }
            }

            logger.error("Full stack trace:", e)
            null
        }
    }

    /**
     * Exchange authorization code for token with retry logic for transient failures.
     *
     * Retries on 5xx server errors and timeouts. Does NOT retry on 4xx errors
     * as those indicate permanent failures (invalid code, bad credentials, etc.).
     *
     * @param provider The identity provider configuration
     * @param code The authorization code from OAuth callback
     * @param redirectUri The redirect URI used in the authorization request
     * @return TokenResponse if successful, null if all retries fail
     */
    private fun exchangeCodeForTokenWithRetry(
        provider: IdentityProvider,
        code: String,
        redirectUri: String
    ): TokenResponse? {
        val config = oauthConfig.tokenExchange
        var lastException: Exception? = null

        repeat(config.maxRetries + 1) { attempt ->
            try {
                val result = exchangeCodeForToken(provider, code, redirectUri)
                if (result != null) {
                    if (attempt > 0) {
                        logger.info("Token exchange succeeded on attempt {} after previous failures", attempt + 1)
                    }
                    return result
                }
                // exchangeCodeForToken returned null (4xx error or parse issue) - don't retry
                return null
            } catch (e: HttpClientResponseException) {
                if (e.status.code >= 500) {
                    logger.warn("Token exchange attempt {} failed with server error {}, retrying...",
                        attempt + 1, e.status)
                    lastException = e
                    if (attempt < config.maxRetries) {
                        Thread.sleep(config.retryDelayMs)
                    }
                } else {
                    // 4xx errors are not retryable
                    logger.error("Token exchange failed with client error {}, not retrying", e.status)
                    throw e
                }
            } catch (e: ReadTimeoutException) {
                logger.warn("Token exchange attempt {} timed out, retrying...", attempt + 1)
                lastException = e
                if (attempt < config.maxRetries) {
                    Thread.sleep(config.retryDelayMs)
                }
            }
        }

        logger.error("Token exchange failed after {} attempts", config.maxRetries + 1, lastException)
        return null
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

    // NOTE: parseIdToken() method was removed in security fix.
    // JWT signature validation is now handled by JwksValidationService.validateAndParseJwt()
    // which properly validates signatures using JWKS before parsing claims.

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
        // Try 'email' claim first (standard OIDC claim)
        val email = idTokenClaims["email"] as? String
        if (!email.isNullOrBlank()) {
            logger.debug("Found email in 'email' claim: {}", email)
            return email
        }

        // Try 'preferred_username' (Microsoft often uses this for email)
        val preferredUsername = idTokenClaims["preferred_username"] as? String
        if (!preferredUsername.isNullOrBlank() && preferredUsername.contains("@")) {
            logger.debug("Found email in 'preferred_username' claim: {}", preferredUsername)
            return preferredUsername
        }

        // Try 'upn' (User Principal Name - Microsoft specific)
        val upn = idTokenClaims["upn"] as? String
        if (!upn.isNullOrBlank() && upn.contains("@")) {
            logger.debug("Found email in 'upn' claim: {}", upn)
            return upn
        }

        // Try 'unique_name' (older Microsoft format)
        val uniqueName = idTokenClaims["unique_name"] as? String
        if (!uniqueName.isNullOrBlank() && uniqueName.contains("@")) {
            logger.debug("Found email in 'unique_name' claim: {}", uniqueName)
            return uniqueName
        }

        logger.warn("Could not find email in any known claim (email, preferred_username, upn, unique_name)")
        return null
    }

    private fun findOrCreateUser(provider: IdentityProvider, userInfo: UserInfoResponse): UserCreationResult? {
        val email = userInfo.email ?: return null

        // Try to find existing user by email
        val existingUserOpt = userRepository.findByEmail(email)
        if (existingUserOpt.isPresent) {
            // FR-006: Existing users preserve their roles; no modification on re-authentication
            return UserCreationResult(user = existingUserOpt.get(), isNewUser = false)
        }

        // Auto-provision user if enabled
        if (!provider.autoProvision) {
            logger.warn("User not found and auto-provisioning is disabled for provider: {}", provider.name)
            return null
        }

        // Create new user with default roles (Feature 046: OIDC Default Roles)
        val username = userInfo.login ?: userInfo.email.substringBefore("@")

        return try {
            // FR-001, FR-002: Create user with USER and VULN roles via transactional method
            val savedUser = createNewOidcUser(email, username, provider.name)
            logger.info("Created new user via OAuth: username={}, email={}, provider={}",
                savedUser.username, savedUser.email, provider.name)

            // Feature 042: Publish event to trigger automatic application of future user mappings
            eventPublisher.publishEvent(UserCreatedEvent(user = savedUser, source = "OAUTH"))

            UserCreationResult(user = savedUser, isNewUser = true)
        } catch (e: Exception) {
            logger.error("Failed to create user: {}", e.message, e)
            null
        }
    }

    /**
     * Audit log role assignment for new OIDC users
     * Feature: 046-oidc-default-roles (FR-010, NFR-001)
     *
     * Logs role assignment events to security.audit logger with structured JSON format.
     * Uses MDC (Mapped Diagnostic Context) for contextual information.
     *
     * @param user The newly created user
     * @param roles Comma-separated list of assigned roles
     * @param idpName Identity provider name
     */
    private fun auditRoleAssignment(user: User, roles: String, idpName: String) {
        try {
            MDC.put("event", "role_assignment")
            MDC.put("user_id", user.id.toString())
            MDC.put("username", user.username)
            MDC.put("email", user.email)
            MDC.put("roles", roles)
            MDC.put("identity_provider", idpName)

            securityLog.info("OIDC user created with default roles")

            MDC.clear()
        } catch (e: Exception) {
            // Log errors but don't propagate (audit logging is best-effort)
            logger.error("Failed to write audit log for user ${user.username}", e)
        }
    }

    /**
     * Send email notification to all administrators about new OIDC user creation
     * Feature: 046-oidc-default-roles (FR-011, FR-012, NFR-003, NFR-004)
     *
     * Executes asynchronously to avoid blocking user creation transaction.
     * Email delivery is best-effort - failures are logged but do not prevent user creation.
     *
     * @param user The newly created user
     * @param idpName Identity provider name
     */
    @Async
    open fun notifyAdminsNewUser(user: User, idpName: String) {
        try {
            logger.info("Sending admin notifications for new user: ${user.username}")

            val admins = userRepository.findByRolesContaining(User.Role.ADMIN)
            if (admins.isEmpty()) {
                logger.warn("No administrators found to notify about new user: ${user.username}")
                return
            }

            admins.forEach { admin ->
                try {
                    // SECURITY: Escape all user-controlled data to prevent XSS in HTML emails
                    val htmlBody = """
                        <!DOCTYPE html>
                        <html>
                        <head><meta charset="UTF-8"></head>
                        <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                            <h2 style="color: #0066cc;">New OIDC User Created</h2>
                            <p>A new user has been automatically created via OIDC authentication:</p>
                            <table style="border-collapse: collapse; width: 100%; margin: 20px 0;">
                                <tr><td style="padding: 8px; border: 1px solid #ddd; font-weight: bold;">Username:</td><td style="padding: 8px; border: 1px solid #ddd;">${escapeHtml(user.username)}</td></tr>
                                <tr><td style="padding: 8px; border: 1px solid #ddd; font-weight: bold;">Email:</td><td style="padding: 8px; border: 1px solid #ddd;">${escapeHtml(user.email)}</td></tr>
                                <tr><td style="padding: 8px; border: 1px solid #ddd; font-weight: bold;">Roles:</td><td style="padding: 8px; border: 1px solid #ddd;">${escapeHtml(user.roles.joinToString(", "))}</td></tr>
                                <tr><td style="padding: 8px; border: 1px solid #ddd; font-weight: bold;">Provider:</td><td style="padding: 8px; border: 1px solid #ddd;">${escapeHtml(idpName)}</td></tr>
                            </table>
                            <p style="color: #666; font-size: 12px;">This is an automated notification from Secman.</p>
                        </body>
                        </html>
                    """.trimIndent()

                    // Subject and plain text don't need HTML escaping, but we use the raw values
                    emailSender.sendEmail(EmailSender.EmailMessage(
                        to = admin.email,
                        subject = "New OIDC User Created - ${user.username}",
                        htmlBody = htmlBody,
                        plainTextBody = "New OIDC user created: ${user.username} (${user.email}) with roles: ${user.roles.joinToString(", ")} via provider: $idpName"
                    ))
                    logger.debug("Admin notification sent to: ${admin.email}")
                } catch (e: Exception) {
                    // FR-012: Log but don't propagate (best-effort delivery)
                    logger.error("Failed to send notification to admin ${admin.email}", e)
                }
            }
        } catch (e: Exception) {
            // NFR-004: Log failures for troubleshooting
            logger.error("Failed to send admin notifications for user ${user.username}", e)
            // Don't rethrow - email is best-effort
        }
    }

    /**
     * Create new OIDC user with default roles (USER, VULN)
     * Feature: 046-oidc-default-roles (FR-001, FR-002, FR-009)
     *
     * Transaction-wrapped method ensures atomicity: user creation + role assignment succeed together or rollback entirely.
     * Includes audit logging and async admin notifications.
     *
     * @param email User email from OIDC claims
     * @param username Derived from email or OIDC login claim
     * @param idpName Identity provider name for audit trail
     * @return Saved User entity with default roles
     */
    @Transactional
    open fun createNewOidcUser(email: String, username: String, idpName: String): User {
        logger.info("Creating new OIDC user: $email from provider: $idpName")

        // FR-004, FR-005: Default roles applied before identity provider role mappings; consistent across all providers
        val newUser = User(
            username = username,
            email = email,
            passwordHash = passwordEncoder.encode(UUID.randomUUID().toString())!!, // Random password for OIDC users
            roles = mutableSetOf(User.Role.USER, User.Role.VULN, User.Role.REQ), // Default roles: USER, VULN, REQ
            authSource = User.AuthSource.OAUTH // Feature 051: Mark as OAuth user (cannot change password)
        )

        val savedUser = userRepository.save(newUser)
        logger.info("User created successfully: ${savedUser.id}, username: ${savedUser.username}")

        // FR-010: Audit logging
        auditRoleAssignment(savedUser, "USER,VULN,REQ", idpName)

        // FR-011: Notify admins (async, best-effort per FR-012)
        notifyAdminsNewUser(savedUser, idpName)

        return savedUser
    }

    /**
     * Scheduled job to clean up expired OAuth states
     * Runs every 5 minutes to prevent database bloat from orphaned states
     */
    @Scheduled(fixedDelay = "5m")
    open fun cleanupExpiredOAuthStates() {
        try {
            logger.debug("Running scheduled cleanup of expired OAuth states")
            oauthStateRepository.deleteExpiredStates()
            logger.debug("Completed scheduled cleanup of expired OAuth states")
        } catch (e: Exception) {
            logger.error("Error during scheduled OAuth state cleanup", e)
        }
    }

    /**
     * Result of user lookup/creation during OAuth flow
     * Feature: 027-admin-user-notifications
     */
    private data class UserCreationResult(
        val user: User,
        val isNewUser: Boolean
    )

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

    /**
     * SECURITY: Escape HTML special characters to prevent XSS in email templates.
     * Always use this function when including user-controlled data in HTML emails.
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}