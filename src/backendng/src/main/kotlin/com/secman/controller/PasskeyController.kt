package com.secman.controller

import com.secman.repository.UserRepository
import com.secman.service.AuthCookieService
import com.secman.service.WebAuthnService
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.*
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.security.token.generator.TokenGenerator
import io.micronaut.serde.annotation.Serdeable
import jakarta.inject.Inject
import jakarta.validation.Valid
import org.slf4j.LoggerFactory

/**
 * Controller for Passkey/WebAuthn operations
 * Feature: Passkey MFA Support
 */
@Controller("/api/passkey")
open class PasskeyController(
    private val webAuthnService: WebAuthnService,
    private val userRepository: UserRepository,
    private val tokenGenerator: TokenGenerator,
    private val authCookieService: AuthCookieService
) {
    private val logger = LoggerFactory.getLogger(PasskeyController::class.java)

    @Serdeable
    data class RegisterOptionsRequest(
        val userId: Long? = null // Optional, uses authenticated user if not provided
    )

    @Serdeable
    data class RegisterCredentialRequest(
        val credentialName: String,
        val credential: WebAuthnService.RegistrationCredentialResponse
    )

    @Serdeable
    data class AuthenticationOptionsRequest(
        val username: String
    )

    @Serdeable
    data class AuthenticationRequest(
        val username: String,
        val credential: WebAuthnService.AuthenticationCredentialResponse
    )

    @Serdeable
    data class DeletePasskeyRequest(
        val credentialId: Long
    )

    /**
     * Generate registration options for creating a new passkey
     * GET /api/passkey/register-options
     */
    @Get("/register-options")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    fun getRegistrationOptions(authentication: Authentication): HttpResponse<*> {
        try {
            val username = authentication.name
            val user = userRepository.findByUsername(username).orElseThrow {
                IllegalArgumentException("User not found")
            }

            val options = webAuthnService.generateRegistrationOptions(user)
            return HttpResponse.ok(options)

        } catch (e: Exception) {
            logger.error("Failed to generate registration options", e)
            return HttpResponse.badRequest(mapOf("error" to "An internal error occurred"))
        }
    }

    /**
     * Register a new passkey credential
     * POST /api/passkey/register
     */
    @Post("/register")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    open fun registerCredential(
        @Valid @Body request: RegisterCredentialRequest,
        authentication: Authentication
    ): HttpResponse<*> {
        try {
            val username = authentication.name
            val user = userRepository.findByUsername(username).orElseThrow {
                IllegalArgumentException("User not found")
            }

            val credential = webAuthnService.registerCredential(
                user = user,
                credentialName = request.credentialName,
                registrationResponse = request.credential
            )

            return HttpResponse.ok(mapOf(
                "success" to true,
                "credentialId" to credential.id,
                "credentialName" to credential.credentialName,
                "message" to "Passkey registered successfully"
            ))

        } catch (e: Exception) {
            logger.error("Failed to register passkey", e)
            return HttpResponse.badRequest(mapOf("error" to "An internal error occurred"))
        }
    }

    /**
     * Generate authentication options for passkey login
     * POST /api/passkey/login-options
     */
    @Post("/login-options")
    @Secured(SecurityRule.IS_ANONYMOUS)
    open fun getAuthenticationOptions(@Valid @Body request: AuthenticationOptionsRequest): HttpResponse<*> {
        try {
            val options = webAuthnService.generateAuthenticationOptions(request.username)
            return HttpResponse.ok(options)

        } catch (e: Exception) {
            logger.error("Failed to generate authentication options", e)
            return HttpResponse.badRequest(mapOf("error" to "An internal error occurred"))
        }
    }

    /**
     * Authenticate with passkey
     * POST /api/passkey/authenticate
     */
    @Post("/authenticate")
    @Secured(SecurityRule.IS_ANONYMOUS)
    open fun authenticate(@Valid @Body request: AuthenticationRequest): HttpResponse<*> {
        try {
            val credential = webAuthnService.verifyAuthentication(
                username = request.username,
                authenticationResponse = request.credential
            )

            val user = credential.user

            // Generate JWT token using the same pattern as AuthController.login()
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
                logger.error("Failed to generate JWT token for passkey authentication")
                return HttpResponse.serverError<Any>().body(mapOf("error" to "Authentication failed"))
            }

            val token = tokenOptional.get()

            return HttpResponse.ok(mapOf(
                "success" to true,
                "userId" to user.id,
                "username" to user.username,
                "email" to user.email,
                "roles" to user.roles.map { it.name },
                "message" to "Authentication successful"
            )).cookie(authCookieService.createAuthCookie(token))

        } catch (e: Exception) {
            logger.error("Failed to authenticate with passkey", e)
            return HttpResponse.unauthorized<Any>().body(mapOf("error" to "An internal error occurred"))
        }
    }

    /**
     * List all passkeys for the authenticated user
     * GET /api/passkey/list
     */
    @Get("/list")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    fun listPasskeys(authentication: Authentication): HttpResponse<*> {
        try {
            val username = authentication.name
            val user = userRepository.findByUsername(username).orElseThrow {
                IllegalArgumentException("User not found")
            }

            val passkeys = webAuthnService.getUserPasskeys(user)
            return HttpResponse.ok(mapOf(
                "passkeys" to passkeys,
                "count" to passkeys.size
            ))

        } catch (e: Exception) {
            logger.error("Failed to list passkeys", e)
            return HttpResponse.badRequest(mapOf("error" to "An internal error occurred"))
        }
    }

    /**
     * Delete a passkey
     * DELETE /api/passkey/{id}
     */
    @Delete("/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    fun deletePasskey(@PathVariable id: Long, authentication: Authentication): HttpResponse<*> {
        try {
            val username = authentication.name
            val user = userRepository.findByUsername(username).orElseThrow {
                IllegalArgumentException("User not found")
            }

            val deleted = webAuthnService.deletePasskey(user, id)

            if (deleted) {
                return HttpResponse.ok(mapOf("success" to true, "message" to "Passkey deleted successfully"))
            } else {
                return HttpResponse.notFound<Any>().body(mapOf("error" to "Passkey not found"))
            }

        } catch (e: Exception) {
            logger.error("Failed to delete passkey", e)
            return HttpResponse.badRequest(mapOf("error" to "An internal error occurred"))
        }
    }
}
