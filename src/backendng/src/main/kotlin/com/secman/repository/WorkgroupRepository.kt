package com.secman.repository

import com.secman.domain.Workgroup
import io.micronaut.data.annotation.Repository
import io.micronaut.data.jpa.repository.JpaRepository
import java.util.Optional

/**
 * Repository for Workgroup entity operations
 * Feature: 008-create-an-additional (Workgroup-Based Access Control)
 *
 * Provides CRUD operations and case-insensitive name queries for workgroups
 */
@Repository
interface WorkgroupRepository : JpaRepository<Workgroup, Long> {

    /**
     * Check if a workgroup with the given name exists (case-insensitive)
     * Used for duplicate detection per FR-004, FR-006
     *
     * @param name The workgroup name to check
     * @return true if a workgroup with this name exists (ignoring case)
     */
    fun existsByNameIgnoreCase(name: String): Boolean

    /**
     * Find workgroup by name (case-insensitive)
     * Used for validation and lookup operations
     *
     * @param name The workgroup name to find
     * @return Optional containing the workgroup if found
     */
    fun findByNameIgnoreCase(name: String): Optional<Workgroup>

    /**
     * Find workgroup by ID
     * Inherited from JpaRepository but explicitly documented
     *
     * @param id The workgroup ID
     * @return Optional containing the workgroup if found
     */
    override fun findById(id: Long): Optional<Workgroup>

    /**
     * Find all workgroups that a user is a member of by user email
     * Used for WG Vulns feature (022-wg-vulns-handling)
     *
     * This query joins the workgroup table with the user_workgroups join table
     * and filters by the user's email address.
     *
     * @param email User email address
     * @return List of workgroups the user is a member of (empty list if none)
     */
    @io.micronaut.data.annotation.Query("""
        SELECT DISTINCT w FROM Workgroup w
        JOIN w.users u
        WHERE u.email = :email
        ORDER BY w.name ASC
    """)
    fun findWorkgroupsByUserEmail(email: String): List<Workgroup>

    /**
     * Find the user's *effective* workgroup memberships — direct memberships
     * UNION all descendants reachable through the parent→child hierarchy
     * (Feature 040). Membership cascades downward: a user assigned to an L2
     * workgroup is treated as a member of every L3/L4/... beneath it for the
     * purposes of listing workgroups and viewing their content.
     *
     * Implemented as a recursive CTE seeded from direct memberships and walking
     * children via `parent_id`. Depth-capped at 10 (matches the safety limit in
     * `Workgroup.calculateDepth` and `findAllDescendants`).
     *
     * @param email User email address
     * @return Distinct list of workgroups the user has effective access to,
     *         ordered by name.
     */
    @io.micronaut.data.annotation.Query(value = """
        WITH RECURSIVE effective AS (
            SELECT w.id, w.parent_id, 1 AS depth
            FROM workgroup w
            INNER JOIN user_workgroups uw ON uw.workgroup_id = w.id
            INNER JOIN users u ON u.id = uw.user_id
            WHERE u.email = :email

            UNION ALL

            SELECT w.id, w.parent_id, e.depth + 1
            FROM workgroup w
            INNER JOIN effective e ON w.parent_id = e.id
            WHERE e.depth < 10
        )
        SELECT w.* FROM workgroup w
        WHERE w.id IN (SELECT DISTINCT id FROM effective)
        ORDER BY w.name ASC
    """, nativeQuery = true)
    fun findEffectiveWorkgroupsByUserEmail(email: String): List<Workgroup>

    @io.micronaut.data.annotation.Query(value = """
        WITH RECURSIVE effective AS (
            SELECT w.id, w.parent_id, 1 AS depth
            FROM workgroup w
            INNER JOIN user_workgroups uw ON uw.workgroup_id = w.id
            INNER JOIN users u ON u.id = uw.user_id
            WHERE u.email = :email

            UNION ALL

            SELECT w.id, w.parent_id, e.depth + 1
            FROM workgroup w
            INNER JOIN effective e ON w.parent_id = e.id
            WHERE e.depth < 10
        )
        SELECT COUNT(DISTINCT id) FROM effective
    """, nativeQuery = true)
    fun countEffectiveWorkgroupsByUserEmail(email: String): Long

    // Feature 040: Nested Workgroups - Hierarchy Query Methods

    /**
     * Find all children of a parent workgroup.
     * Feature 040: Nested Workgroups
     */
    fun findByParent(parent: Workgroup): List<Workgroup>

    /**
     * Find all root-level workgroups (no parent).
     * Feature 040: Nested Workgroups
     */
    @io.micronaut.data.annotation.Query("SELECT w FROM Workgroup w WHERE w.parent IS NULL")
    fun findRootLevelWorkgroups(): List<Workgroup>

    /**
     * Find all descendants of a workgroup using recursive CTE.
     * Returns all workgroups in the subtree, including the root.
     * Feature 040: Nested Workgroups
     */
    @io.micronaut.data.annotation.Query(value = """
        WITH RECURSIVE descendants AS (
            SELECT id, parent_id, name, 1 AS depth
            FROM workgroup
            WHERE id = :workgroupId

            UNION ALL

            SELECT w.id, w.parent_id, w.name, d.depth + 1
            FROM workgroup w
            INNER JOIN descendants d ON w.parent_id = d.id
            WHERE d.depth < 10
        )
        SELECT w.* FROM workgroup w
        WHERE w.id IN (SELECT id FROM descendants)
    """, nativeQuery = true)
    fun findAllDescendants(workgroupId: Long): List<Workgroup>

    /**
     * Find all ancestors of a workgroup using recursive CTE.
     * Returns all workgroups from root to immediate parent, ordered from root.
     * Feature 040: Nested Workgroups
     */
    @io.micronaut.data.annotation.Query(value = """
        WITH RECURSIVE ancestors AS (
            SELECT id, parent_id, name, 1 AS depth
            FROM workgroup
            WHERE id = :workgroupId

            UNION ALL

            SELECT w.id, w.parent_id, w.name, a.depth + 1
            FROM workgroup w
            INNER JOIN ancestors a ON a.parent_id = w.id
            WHERE a.depth < 10
        )
        SELECT w.* FROM workgroup w
        WHERE w.id IN (SELECT id FROM ancestors) AND w.id != :workgroupId
        ORDER BY w.id
    """, nativeQuery = true)
    fun findAllAncestors(workgroupId: Long): List<Workgroup>

    /**
     * Count total number of descendants (for admin dashboards).
     * Feature 040: Nested Workgroups
     */
    @io.micronaut.data.annotation.Query(value = """
        WITH RECURSIVE descendants AS (
            SELECT id FROM workgroup WHERE id = :workgroupId
            UNION ALL
            SELECT w.id FROM workgroup w
            INNER JOIN descendants d ON w.parent_id = d.id
        )
        SELECT COUNT(*) - 1 FROM descendants
    """, nativeQuery = true)
    fun countDescendants(workgroupId: Long): Long
}
