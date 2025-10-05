package com.secman.repository

import com.secman.domain.User
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface UserRepository : JpaRepository<User, Long> {

    fun findByUsername(username: String): Optional<User>

    fun findByEmail(email: String): Optional<User>

    fun existsByUsername(username: String): Boolean

    fun existsByEmail(email: String): Boolean

    fun findByUsernameOrEmail(username: String, email: String): Optional<User>

    // Workgroup-Based Access Control - Feature 008

    /**
     * Find users in a specific workgroup
     * Used for admin workgroup management views
     *
     * Related to: Feature 008 (Workgroup-Based Access Control) - FR-010
     *
     * @param workgroupId The workgroup ID to filter by
     * @return List of users in the specified workgroup
     */
    fun findByWorkgroupsIdOrderByUsernameAsc(workgroupId: Long): List<User>

    /**
     * Count users assigned to a workgroup
     * Used for workgroup detail views and validation
     *
     * Related to: Feature 008 (Workgroup-Based Access Control) - FR-010
     *
     * @param workgroupId The workgroup ID
     * @return Number of users in the workgroup
     */
    fun countByWorkgroupsId(workgroupId: Long): Long

    // Note: User deletion validation requires service-level logic
    // since Asset has manualCreator/scanUploader FKs but User doesn't have reverse relationships.
    // Validation moved to UserDeletionValidator service.
}