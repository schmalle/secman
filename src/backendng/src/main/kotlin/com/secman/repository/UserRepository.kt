package com.secman.repository

import com.secman.domain.User
import io.micronaut.data.annotation.Query
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.*

@Repository
interface UserRepository : JpaRepository<User, Long> {

    /**
     * Identity fields checked on every authenticated request (CurrentIdentityRule), one row per
     * role. A JPQL projection in a single indexed round trip, instead of hydrating the entity and
     * then lazily selecting its role collection.
     */
    @Query("SELECT u.enabled, u.username, u.email, r FROM User u LEFT JOIN u.roles r WHERE u.id = :id")
    fun findIdentityRows(id: Long): List<Array<Any?>>

    /**
     * Everything `GET /api/auth/status` reports besides roles, in one round trip. That endpoint
     * runs on every page navigation; it used to issue five separate count/lookup queries.
     * Columns: id, username, email, direct AWS accounts, received shares, enabled direct
     * workgroups, domains, profile-picture timestamp (null when there is no picture).
     */
    @Query("""
        SELECT u.id, u.username, u.email,
            (SELECT COUNT(DISTINCT m.awsAccountId) FROM UserMapping m WHERE m.email = u.email AND m.awsAccountId IS NOT NULL),
            (SELECT COUNT(s) FROM AwsAccountSharing s WHERE s.targetUser.id = u.id),
            (SELECT COUNT(DISTINCT w.id) FROM Workgroup w JOIN w.users wu WHERE wu.email = u.email AND w.enabled = true),
            (SELECT COUNT(DISTINCT m2.domain) FROM UserMapping m2 WHERE m2.email = u.email AND m2.domain IS NOT NULL),
            (SELECT p.updatedAt FROM UserProfilePicture p WHERE p.userId = u.id)
        FROM User u WHERE u.username = :username
    """)
    fun findAuthStatusRows(username: String): List<Array<Any?>>

    /** Username/email pairs for pickers, without hydrating users or their role collections. */
    @Query("SELECT u.username, u.email FROM User u ORDER BY LOWER(u.username)")
    fun findUsernameEmailPairs(): List<Array<Any?>>

    // Memory Optimization - Feature 073

    /**
     * Find user by ID with workgroups eagerly loaded
     * Used for authentication/authorization operations when LAZY loading is enabled
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
     * @return List of all users with workgroups loaded
     */
    @Query("""
        SELECT DISTINCT u FROM User u
        LEFT JOIN FETCH u.workgroups
        ORDER BY u.username ASC
    """)
    fun findAllWithWorkgroups(): List<User>

    @Query("SELECT u FROM User u WHERE u.enabled = true AND (LOWER(u.username) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(u.email) LIKE LOWER(CONCAT('%', :query, '%'))) ORDER BY u.username")
    fun searchAssessmentParticipants(query: String, pageable: io.micronaut.data.model.Pageable): List<User>

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

    fun countByLastLoginGreaterThanEqual(since: Instant): Long

    fun countByLastLoginIsNull(): Long
}
