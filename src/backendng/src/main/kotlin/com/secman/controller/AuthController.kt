package com.secman.controller

import com.secman.domain.User
import com.secman.repository.UserRepository
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.*

@Controller("/api/auth")
class AuthController(
    private val userRepository: UserRepository,
    private val tokenGenerator: TokenGenerator
) {
    
    private val passwordEncoder = BCryptPasswordEncoder()

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
    fun login(@Body loginRequest: LoginRequest): HttpResponse<*> {
        if (loginRequest.username.isBlank() || loginRequest.password.isBlank()) {
            return HttpResponse.badRequest(mapOf("error" to "Username and password are required"))
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

        val response = LoginResponse(
            id = user.id!!,
            username = user.username,
            email = user.email,
            roles = user.roles.map { it.name },
            token = tokenOptional.get()
        )

        return HttpResponse.ok(response)
    }

    @Post("/logout")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    fun logout(): HttpResponse<*> {
        return HttpResponse.ok(mapOf("message" to "Logged out successfully"))
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
}