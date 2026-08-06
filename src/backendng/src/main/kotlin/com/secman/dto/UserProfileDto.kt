package com.secman.dto

import com.secman.domain.User
import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

/**
 * Data Transfer Object for user profile API responses
 * Feature 028: User Profile Page
 * Feature 051: User Password Change (added canChangePassword)
 * Feature: Profile Picture Management (added hasProfilePicture, profilePictureUpdatedAt)
 *
 * Exposes only safe, user-visible fields:
 * - username: User's display name
 * - email: User's email address
 * - roles: User's assigned roles
 * - canChangePassword: Whether user can change their password (LOCAL/HYBRID users only)
 * - hasProfilePicture: Whether the user has an avatar (gates the <img> so the UI never
 *   requests a picture that does not exist)
 * - profilePictureUpdatedAt: Last-modified stamp, used as a cache-busting query parameter
 *
 * Security: Excludes passwordHash, id, timestamps, and workgroups
 */
@Serdeable
data class UserProfileDto(
    val username: String,
    val email: String,
    val roles: Set<String>,
    val canChangePassword: Boolean = true,
    val hasProfilePicture: Boolean = false,
    val profilePictureUpdatedAt: Instant? = null
) {
    companion object {
        /**
         * Factory method to create UserProfileDto from User entity
         * Implements defensive programming with null-safe email handling
         *
         * @param user The User entity to convert
         * @param profilePictureUpdatedAt Last-modified stamp of the user's avatar, null if none
         * @return UserProfileDto with user data
         */
        fun fromUser(user: User, profilePictureUpdatedAt: Instant? = null): UserProfileDto {
            return UserProfileDto(
                username = user.username,
                email = user.email,
                roles = user.roles.map { it.name }.toSet(),
                canChangePassword = user.authSource != User.AuthSource.OAUTH,
                hasProfilePicture = profilePictureUpdatedAt != null,
                profilePictureUpdatedAt = profilePictureUpdatedAt
            )
        }
    }
}
