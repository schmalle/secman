package com.secman.controller

import com.secman.domain.User
import com.secman.repository.AwsAccountSharingRepository
import com.secman.repository.UserMappingRepository
import com.secman.repository.UserRepository
import com.secman.service.AuthCookieService
import com.secman.service.InputValidationService
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.authentication.UsernamePasswordCredentials
import io.micronaut.security.rules.SecurityRule
import io.micronaut.security.token.generator.TokenGenerator
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

@Controller("/api/auth")
open class AuthController(
    private val userRepository: UserRepository,
    private val userMappingRepository: UserMappingRepository,
    private val awsAccountSharingRepository: AwsAccountSharingRepository,
    private val tokenGenerator: TokenGenerator,
    private val inputValidationService: InputValidationService,
    private val authCookieService: AuthCookieService
) {

    private val passwordEncoder = BCryptPasswordEncoder()
    private val logger = LoggerFactory.getLogger(AuthController::class.java)

    // Login rate limiting: track failed attempts per username with automatic TTL eviction.
    // Uses Caffeine cache to prevent unbounded memory growth and ensure entries expire
    // after the lockout window, even under sustained enumeration attacks.
    private data class LoginAttemptRecord(val count: Int, val firstAttemptAt: Instant)
    private val loginAttempts = Caffeine.newBuilder()
        .expireAfterWrite(15, TimeUnit.MINUTES)
        .maximumSize(10_000)
        .build<String, LoginAttemptRecord>()
    private val maxLoginAttempts = 5
    private val lockoutDurationSeconds = 900L // 15 minutes

    private fun isLoginLocked(key: String): Boolean {
        val record = loginAttempts.getIfPresent(key) ?: return false
        if (record.count >= maxLoginAttempts) {
            val elapsed = java.time.Duration.between(record.firstAttemptAt, Instant.now()).seconds
            if (elapsed < lockoutDurationSeconds) {
                return true
            }
            // Lockout expired, reset
            loginAttempts.invalidate(key)
        }
        return false
    }

    private fun recordFailedLogin(key: String) {
        val existing = loginAttempts.getIfPresent(key)
        if (existing == null) {
            loginAttempts.put(key, LoginAttemptRecord(1, Instant.now()))
        } else {
            val elapsed = java.time.Duration.between(existing.firstAttemptAt, Instant.now()).seconds
            if (elapsed >= lockoutDurationSeconds) {
                loginAttempts.put(key, LoginAttemptRecord(1, Instant.now()))
            } else {
                loginAttempts.put(key, LoginAttemptRecord(existing.count + 1, existing.firstAttemptAt))
            }
        }
    }

    private fun clearFailedLogins(key: String) {
        loginAttempts.invalidate(key)
    }

    @Serdeable
    data class LoginRequest(
        val username: String,
        val password: String
    )

    @Serdeable
    data class MfaRequiredResponse(
        val mfaRequired: Boolean = true,
        val username: String
    )

    @Serdeable
    data class LoginResponse(
        val id: Long,
        val username: String,
        val email: String,
        val roles: List<String>,
        val workgroupCount: Long = 0,
        val awsAccountCount: Long = 0,
        val domainCount: Long = 0
    )

    @Serdeable
    data class StatusResponse(
        val id: Long,
        val username: String,
        val email: String,
        val roles: List<String>,
        val workgroupCount: Long = 0,
        val awsAccountCount: Long = 0,
        val domainCount: Long = 0
    )

    @Post("/login")
    @Secured(SecurityRule.IS_ANONYMOUS)
    @Transactional
    open fun login(@Valid @Body loginRequest: LoginRequest): HttpResponse<*> {
        if (loginRequest.username.isBlank() || loginRequest.password.isBlank()) {
            return HttpResponse.badRequest(mapOf("error" to "Username and password are required"))
        }
        
        // Validate username format
        val usernameValidation = inputValidationService.validateName(loginRequest.username, "Username")
        if (!usernameValidation.isValid) {
            return HttpResponse.badRequest(mapOf("error" to usernameValidation.errorMessage))
        }
        
        // Validate password length (basic check, don't reveal too much)
        if (loginRequest.password.length > 200) {
            return HttpResponse.badRequest(mapOf("error" to "Invalid credentials"))
        }
        
        // Check for potential injection attempts
        if (inputValidationService.containsCommandInjection(loginRequest.username) ||
            inputValidationService.containsPathTraversal(loginRequest.username)) {
            // Log the attempt but return generic error
            return HttpResponse.unauthorized<Any>().body(mapOf("error" to "Invalid credentials"))
        }

        // Rate limiting: check if login is locked for this username
        val rateLimitKey = loginRequest.username.lowercase()
        if (isLoginLocked(rateLimitKey)) {
            logger.warn("Login rate limit exceeded for user: {}", loginRequest.username)
            return HttpResponse.status<Any>(io.micronaut.http.HttpStatus.TOO_MANY_REQUESTS)
                .body(mapOf("error" to "Too many failed login attempts. Please try again later."))
        }

        val userOptional = userRepository.findByUsername(loginRequest.username)
        
        if (userOptional.isEmpty) {
            recordFailedLogin(rateLimitKey)
            return HttpResponse.unauthorized<Any>().body(mapOf("error" to "Invalid credentials"))
        }

        val user = userOptional.get()
        
        if (!passwordEncoder.matches(loginRequest.password, user.passwordHash)) {
            recordFailedLogin(rateLimitKey)
            return HttpResponse.unauthorized<Any>().body(mapOf("error" to "Invalid credentials"))
        }

        // Successful login - clear failed attempts
        clearFailedLogins(rateLimitKey)

        // Check if MFA is required - do NOT issue JWT until MFA is completed
        if (user.mfaEnabled) {
            logger.info("MFA required for user: {}", user.username)
            return HttpResponse.ok(MfaRequiredResponse(username = user.username))
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
            return HttpResponse.serverError<Any>().body(mapOf("error" to "Failed to generate token"))
        }

        // Update last login timestamp
        user.lastLogin = Instant.now()
        userRepository.update(user)

        val token = tokenOptional.get()

        val directAwsCount = userMappingRepository.countDistinctAwsAccountsByEmail(user.email)
        val sharedAwsCount = awsAccountSharingRepository.countByTargetUserId(user.id!!)

        val response = LoginResponse(
            id = user.id!!,
            username = user.username,
            email = user.email,
            roles = user.roles.map { it.name },
            workgroupCount = userRepository.countWorkgroupsByUsername(user.username),
            awsAccountCount = directAwsCount + sharedAwsCount,
            domainCount = userMappingRepository.countDistinctDomainsByEmail(user.email)
        )

        // Set JWT in HttpOnly secure cookie (primary auth mechanism)
        return HttpResponse.ok(response).cookie(authCookieService.createAuthCookie(token))
    }

    @Post("/logout")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    fun logout(): HttpResponse<*> {
        // Clear the auth cookie by setting an expired cookie
        return HttpResponse.ok(mapOf("message" to "Logged out successfully"))
            .cookie(authCookieService.createLogoutCookie())
    }

    /**
     * Clear the auth session cookie without requiring authentication.
     * Used by the frontend before initiating OAuth flows to prevent stale-cookie login loops.
     */
    @Post("/clear-session")
    @Secured(SecurityRule.IS_ANONYMOUS)
    fun clearSession(): HttpResponse<*> {
        return HttpResponse.ok(mapOf("message" to "Session cleared"))
            .cookie(authCookieService.createLogoutCookie())
    }

    @Get("/status")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    fun status(authentication: Authentication): HttpResponse<*> {
        val username = authentication.name
        val userOptional = userRepository.findByUsername(username)

        if (userOptional.isEmpty) {
            return HttpResponse.unauthorized<Any>().body(mapOf("error" to "User not found"))
        }

        val user = userOptional.get()
        val directAwsCount = userMappingRepository.countDistinctAwsAccountsByEmail(user.email)
        val sharedAwsCount = awsAccountSharingRepository.countByTargetUserId(user.id!!)

        val response = StatusResponse(
            id = user.id!!,
            username = user.username,
            email = user.email,
            roles = user.roles.map { it.name },
            workgroupCount = userRepository.countWorkgroupsByUsername(user.username),
            awsAccountCount = directAwsCount + sharedAwsCount,
            domainCount = userMappingRepository.countDistinctDomainsByEmail(user.email)
        )

        return HttpResponse.ok(response)
    }

    @Serdeable
    data class RefreshResponse(
        val success: Boolean = true,
        val expiresIn: Int = 28800  // 8 hours in seconds
    )

    @Serdeable
    data class HeartbeatResponse(
        val status: String = "ok",
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Refresh the current JWT token.
     * Issues a new token with extended expiration if the current token is still valid.
     */
    @Post("/refresh")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    fun refresh(authentication: Authentication): HttpResponse<*> {
        val username = authentication.name
        val userOptional = userRepository.findByUsername(username)

        if (userOptional.isEmpty) {
            return HttpResponse.unauthorized<Any>().body(mapOf("error" to "User not found"))
        }

        val user = userOptional.get()

        // Generate a fresh JWT token
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
            return HttpResponse.serverError<Any>().body(mapOf("error" to "Failed to generate token"))
        }

        val token = tokenOptional.get()
        // SECURITY: Only set the token in the HttpOnly cookie, never expose in response body
        return HttpResponse.ok(RefreshResponse())
            .cookie(authCookieService.createAuthCookie(token))
    }

    /**
     * Lightweight heartbeat endpoint for session keep-alive.
     * Validates the token is still valid without generating a new one.
     */
    @Get("/heartbeat")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    fun heartbeat(authentication: Authentication): HttpResponse<HeartbeatResponse> {
        // If we reach here, the token is valid
        return HttpResponse.ok(HeartbeatResponse())
    }
}