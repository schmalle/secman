package com.secman.repository

import com.secman.domain.UserProfilePicture
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.Optional

/**
 * Repository for user profile pictures.
 *
 * Feature: Profile Picture Management
 *
 * IMPORTANT: [findByUserId] loads the LONGBLOB. `@Basic(fetch = LAZY)` on a `@Lob` is inert
 * without Hibernate bytecode enhancement, which this build does not enable, so there is no
 * lazy-loading safety net here. Metadata-only callers - in particular `GET /api/auth/status`,
 * which runs on every page load - must use [existsByUserId] or [findUpdatedAtByUserId].
 */
@Repository
interface UserProfilePictureRepository : JpaRepository<UserProfilePicture, Long> {

    /** Loads the full row including the image bytes. Use only when the bytes are actually needed. */
    fun findByUserId(userId: Long): Optional<UserProfilePicture>

    fun existsByUserId(userId: Long): Boolean

    fun deleteByUserId(userId: Long): Long

    /**
     * Blob-free projection of the last-modified timestamp, used for cache busting and for the
     * `hasProfilePicture` / `profilePictureUpdatedAt` fields on the profile and auth-status DTOs.
     */
    @Query("SELECT p.updatedAt FROM UserProfilePicture p WHERE p.userId = :userId")
    fun findUpdatedAtByUserId(userId: Long): Optional<Instant>
}
