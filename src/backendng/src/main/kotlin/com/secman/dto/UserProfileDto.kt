package com.secman.dto

import com.secman.domain.User
import io.micronaut.serde.annotation.Serdeable

/**
 * Data Transfer Object for user profile API responses
 * Feature 028: User Profile Page
 *
 * Exposes only safe, user-visible fields:
 * - username: User's display name
 * - email: User's email address
 * - roles: User's assigned roles
 *
 * Security: Excludes passwordHash, id, timestamps, and workgroups
 */
@Serdeable
data class UserProfileDto(
    val username: String,
    val email: String,
    val roles: Set<String>
) {
    companion object {
        /**
         * Factory method to create UserProfileDto from User entity
         * Implements defensive programming with null-safe email handling
         *
         * @param user The User entity to convert
         * @return UserProfileDto with user data
         */
        fun fromUser(user: User): UserProfileDto {
            return UserProfileDto(
                username = user.username,
                email = user.email ?: "Not set",
                roles = user.roles.map { it.name }.toSet()
            )
        }
    }
}
