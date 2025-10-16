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
}
