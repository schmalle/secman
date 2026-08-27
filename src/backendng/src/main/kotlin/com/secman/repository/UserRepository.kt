package com.secman.repository

import com.secman.domain.User
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.*

@Repository
interface UserRepository : JpaRepository<User, Long> {

    // Memory Optimization - Feature 073

    /**
     * Find user by ID with workgroups eagerly loaded
     * Used for authentication/authorization operations when LAZY loading is enabled
     *
     * Feature: 073-memory-optimization
     * Task: T007
     *
     * @param id The user ID
     * @return Optional containing the user with workgroups loaded
     */
    @Query("""
        SELECT u FROM User u
        LEFT JOIN FETCH u.workgroups
        WHERE u.id = :id
    """)
    fun findByIdWithWorkgroups(id: Long): Optional<User>

    /**
     * Find all users with workgroups eagerly loaded
     * Used for export operations when LAZY loading is enabled
     *
     * Feature: 073-memory-optimization
     *
     * @return List of all users with workgroups loaded
     */
    @Query("""
        SELECT DISTINCT u FROM User u
        LEFT JOIN FETCH u.workgroups
        ORDER BY u.username ASC
    """)
    fun findAllWithWorkgroups(): List<User>

    fun findByUsername(username: String): Optional<User>

    fun findByEmail(email: String): Optional<User>

    fun findByEmailIgnoreCase(email: String): Optional<User>

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

    // Note: User deletion validation requires service-level logic
    // since Asset has manualCreator/scanUploader FKs but User doesn't have reverse relationships.
    // Validation moved to UserDeletionValidator service.

    // Role-Based Queries - Feature 046

    /**
     * Find users with a specific role
     * Used for admin notifications when new OIDC users are created
     *
     * Related to: Feature 046 (OIDC Default Roles) - FR-011
     *
     * @param role The role to search for (e.g., User.Role.ADMIN)
     * @return List of users with the specified role
     *
     * Note: Queries ElementCollection; may not be as efficient as direct column query
     * For better performance with large user bases, consider denormalizing roles to User table
     */
    @Query("""
        SELECT DISTINCT u FROM User u
        JOIN u.roles r
        WHERE r = :role
    """)
    fun findByRolesContaining(role: User.Role): List<User>

    /**
     * Find users holding any of the given roles (e.g. ADMIN or SECCHAMPION recipients).
     */
    @Query("""
        SELECT DISTINCT u FROM User u
        JOIN u.roles r
        WHERE r IN (:roles)
    """)
    fun findByRolesContainingAny(roles: Collection<User.Role>): List<User>

    /**
     * Count users with a specific role without materializing them
     * (last-admin protection, config-bundle validation).
     */
    @Query("""
        SELECT COUNT(DISTINCT u) FROM User u
        JOIN u.roles r
        WHERE r = :role
    """)
    fun countByRolesContaining(role: User.Role): Long

    /**
     * Users belonging to any of the given workgroups, in one query.
     * Batch variant of [findByWorkgroupsIdOrderByUsernameAsc]; a user in several
     * of the workgroups appears once per membership — deduplicate by id.
     */
    fun findByWorkgroupsIdInOrderByUsernameAsc(workgroupIds: Collection<Long>): List<User>

    /**
     * Find all users that have logged in at least once.
     * Used by the admin broadcast feature to skip pending (never-activated) accounts.
     */
    fun findByLastLoginIsNotNull(): List<User>
}