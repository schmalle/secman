package com.secman.controller

import com.secman.domain.User
import com.secman.repository.UserRepository
import com.secman.service.InputValidationService
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.http.cookie.Cookie
import io.micronaut.http.cookie.SameSite
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.authentication.UsernamePasswordCredentials
import io.micronaut.security.rules.SecurityRule
import io.micronaut.security.token.generator.TokenGenerator
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Duration
import java.time.Instant
import java.util.*

@Controller("/api/auth")
open class AuthController(
    private val userRepository: UserRepository,
    private val tokenGenerator: TokenGenerator,
    private val inputValidationService: InputValidationService
) {

    private val passwordEncoder = BCryptPasswordEncoder()

    companion object {
        const val AUTH_COOKIE_NAME = "secman_auth"
        private val AUTH_COOKIE_MAX_AGE = Duration.ofHours(8)
    }

    /**
     * Create HttpOnly secure cookie for JWT token.
     * Security properties:
     * - HttpOnly: Prevents JavaScript access (XSS protection)
     * - Secure: Only sent over HTTPS
     * - SameSite=Lax: CSRF protection while allowing navigation
     * - Path=/: Available for all API endpoints
     */
    private fun createAuthCookie(token: String): Cookie {
        return Cookie.of(AUTH_COOKIE_NAME, token)
            .httpOnly(true)
            .secure(true)  // Only sent over HTTPS
            .sameSite(SameSite.Lax)  // CSRF protection
            .maxAge(AUTH_COOKIE_MAX_AGE)
            .path("/")
    }

    /**
     * Create expired cookie to clear authentication.
     */
    private fun createLogoutCookie(): Cookie {
        return Cookie.of(AUTH_COOKIE_NAME, "")
            .httpOnly(true)
            .secure(true)
            .sameSite(SameSite.Lax)
            .maxAge(Duration.ZERO)  // Expire immediately
            .path("/")
    }

    @Serdeable
    data class LoginRequest(
        val username: String,
        val password: String
    )

    @Serdeable
    data class LoginResponse(
        val id: Long,
        val username: String,
        val email: String,
        val roles: List<String>,
        val token: String
    )

    @Serdeable
    data class StatusResponse(
        val id: Long,
        val username: String,
        val email: String,
        val roles: List<String>
    )

    @Post("/login")
    @Secured(SecurityRule.IS_ANONYMOUS)
    @Transactional
    open fun login(@Body loginRequest: LoginRequest): HttpResponse<*> {
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

        val userOptional = userRepository.findByUsername(loginRequest.username)
        
        if (userOptional.isEmpty) {
            return HttpResponse.unauthorized<Any>().body(mapOf("error" to "Invalid credentials"))
        }

        val user = userOptional.get()
        
        if (!passwordEncoder.matches(loginRequest.password, user.passwordHash)) {
            return HttpResponse.unauthorized<Any>().body(mapOf("error" to "Invalid credentials"))
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

        val response = LoginResponse(
            id = user.id!!,
            username = user.username,
            email = user.email,
            roles = user.roles.map { it.name },
            token = token  // Still included for backward compatibility during transition
        )

        // Set JWT in HttpOnly secure cookie (primary auth mechanism)
        return HttpResponse.ok(response).cookie(createAuthCookie(token))
    }

    @Post("/logout")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    fun logout(): HttpResponse<*> {
        // Clear the auth cookie by setting an expired cookie
        return HttpResponse.ok(mapOf("message" to "Logged out successfully"))
            .cookie(createLogoutCookie())
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
        val response = StatusResponse(
            id = user.id!!,
            username = user.username,
            email = user.email,
            roles = user.roles.map { it.name }
        )

        return HttpResponse.ok(response)
    }

    @Serdeable
    data class RefreshResponse(
        val token: String,
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
        // Update both the cookie and return the token in response
        return HttpResponse.ok(RefreshResponse(token = token))
            .cookie(createAuthCookie(token))
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