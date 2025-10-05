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
}
