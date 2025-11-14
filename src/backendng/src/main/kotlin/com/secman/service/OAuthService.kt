package com.secman.service

import com.fasterxml.jackson.databind.ObjectMapper
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
import io.micronaut.security.token.generator.TokenGenerator
import io.micronaut.scheduling.annotation.Async
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.slf4j.MDC
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
    private val adminNotificationService: AdminNotificationService,
    private val eventPublisher: ApplicationEventPublisher<UserCreatedEvent>,
    private val entityManager: EntityManager,
    private val emailSender: EmailSender,
    @Client("\${oauth.http-client.url:https://api.github.com}") private val githubApiClient: HttpClient,
    @Client private val genericHttpClient: HttpClient
) {

    private val logger = LoggerFactory.getLogger(OAuthService::class.java)
    private val securityLog = LoggerFactory.getLogger("security.audit")
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
     * Note: This method MUST be @Transactional to ensure the OAuth state is committed
     * to the database before redirecting to the OAuth provider. Without a transaction,
     * the save() call may not commit, causing "state not found" errors on callback.
     */
    @Transactional
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

        // Clean up expired states
        oauthStateRepository.deleteExpiredStates()

        // Generate state parameter
        val state = generateState()

        // Use custom callback URL if configured, otherwise fall back to baseUrl
        val redirectUri = provider.callbackUrl?.takeIf {
			it.isNotBlank()
		} ?: "$baseUrl/oauth/callback"

		logger.info("OAuthService.buildAuthorizationUrl: Provider callback URL: {}", provider.callbackUrl)
		logger.info("OAuthService.buildAuthorizationUrl: Constructed RedirectUri: {}", redirectUri)

        // Save state and flush immediately to ensure it's persisted before redirect
        val oauthState = OAuthState(
            stateToken = state,
            providerId = providerId,
            redirectUri = redirectUri
        )
        val savedState = oauthStateRepository.save(oauthState)

        // Force immediate flush to database - critical for OAuth flow!
        entityManager.flush()
        logger.info("Saved and flushed OAuth state with token {} (first 10 chars) for provider {}, expires at {}",
            state.take(10) + "...", providerId, savedState.expiresAt)

        // Verify state was actually saved
        val verificationState = oauthStateRepository.findByStateToken(state)
        if (!verificationState.isPresent) {
            logger.error("Failed to verify saved OAuth state - state not found after save and flush!")
            return null
        }
        logger.info("Verified OAuth state exists in database after flush - ready for redirect")

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
            val userResult = findOrCreateUser(provider, userInfo)
            if (userResult == null) {
                oauthStateRepository.deleteByStateToken(state)
                return CallbackResult.Error("Unable to create user account. Please contact support if this persists.")
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
                    val htmlBody = """
                        <!DOCTYPE html>
                        <html>
                        <head><meta charset="UTF-8"></head>
                        <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                            <h2 style="color: #0066cc;">New OIDC User Created</h2>
                            <p>A new user has been automatically created via OIDC authentication:</p>
                            <table style="border-collapse: collapse; width: 100%; margin: 20px 0;">
                                <tr><td style="padding: 8px; border: 1px solid #ddd; font-weight: bold;">Username:</td><td style="padding: 8px; border: 1px solid #ddd;">${user.username}</td></tr>
                                <tr><td style="padding: 8px; border: 1px solid #ddd; font-weight: bold;">Email:</td><td style="padding: 8px; border: 1px solid #ddd;">${user.email}</td></tr>
                                <tr><td style="padding: 8px; border: 1px solid #ddd; font-weight: bold;">Roles:</td><td style="padding: 8px; border: 1px solid #ddd;">${user.roles.joinToString(", ")}</td></tr>
                                <tr><td style="padding: 8px; border: 1px solid #ddd; font-weight: bold;">Provider:</td><td style="padding: 8px; border: 1px solid #ddd;">$idpName</td></tr>
                            </table>
                            <p style="color: #666; font-size: 12px;">This is an automated notification from Secman.</p>
                        </body>
                        </html>
                    """.trimIndent()

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
            passwordHash = passwordEncoder.encode(UUID.randomUUID().toString()), // Random password for OIDC users
            roles = mutableSetOf(User.Role.USER, User.Role.VULN) // FR-001, FR-002: Default roles
        )

        val savedUser = userRepository.save(newUser)
        logger.info("User created successfully: ${savedUser.id}, username: ${savedUser.username}")

        // FR-010: Audit logging
        auditRoleAssignment(savedUser, "USER,VULN", idpName)

        // FR-011: Notify admins (async, best-effort per FR-012)
        notifyAdminsNewUser(savedUser, idpName)

        return savedUser
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
}