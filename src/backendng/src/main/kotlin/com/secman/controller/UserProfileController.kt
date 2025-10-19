package com.secman.controller

import com.secman.dto.UserProfileDto
import com.secman.repository.UserRepository
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.micronaut.serde.annotation.Serdeable
import org.slf4j.LoggerFactory

/**
 * Controller for user profile operations
 * Feature 028: User Profile Page
 *
 * Endpoints:
 * - GET /api/users/profile: Returns current user's profile data
 *
 * Security: All endpoints require authentication
 */
@Controller("/api/users")
@Secured(SecurityRule.IS_AUTHENTICATED)
class UserProfileController(
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(UserProfileController::class.java)

    @Serdeable
    data class ErrorResponse(
        val message: String
    )

    /**
     * Get current user's profile
     *
     * Retrieves profile information (username, email, roles) for the authenticated user.
     * User is identified from the JWT token in the Authorization header.
     *
     * @param authentication Micronaut Security authentication object
     * @return HttpResponse with UserProfileDto or 404 error
     */
    @Get("/profile")
    fun getCurrentUserProfile(authentication: Authentication): HttpResponse<*> {
        val username = authentication.name
        logger.debug("Fetching profile for user: {}", username)

        val userOptional = userRepository.findByUsername(username)
        if (userOptional.isEmpty) {
            logger.warn("User not found: {}", username)
            return HttpResponse.notFound(ErrorResponse("User not found"))
        }

        val user = userOptional.get()
        logger.debug("Profile retrieved for user: {}", username)
        return HttpResponse.ok(UserProfileDto.fromUser(user))
    }
}
