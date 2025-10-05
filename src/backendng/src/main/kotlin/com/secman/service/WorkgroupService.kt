package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import jakarta.inject.Singleton
import jakarta.transaction.Transactional

/**
 * Service for Workgroup business logic
 * Feature: 008-create-an-additional (Workgroup-Based Access Control)
 *
 * Handles workgroup CRUD operations, validation, and membership management
 */
@Singleton
open class WorkgroupService(
    private val workgroupRepository: WorkgroupRepository,
    private val userRepository: UserRepository,
    private val assetRepository: AssetRepository
) {

    /**
     * Create a new workgroup with validation
     * FR-001, FR-004, FR-006: Create with unique name (case-insensitive)
     *
     * @param name Workgroup name (1-100 chars, alphanumeric + spaces + hyphens)
     * @param description Optional description (max 512 chars)
     * @return Created workgroup
     * @throws IllegalArgumentException if name already exists or validation fails
     */
    @Transactional
    open fun createWorkgroup(name: String, description: String? = null): Workgroup {
        // Validate name uniqueness (case-insensitive)
        if (workgroupRepository.existsByNameIgnoreCase(name)) {
            throw IllegalArgumentException("Workgroup name already exists (case-insensitive): $name")
        }

        // Name format validation handled by @Pattern annotation on entity
        // Length validation handled by @Size annotation on entity

        val workgroup = Workgroup(
            name = name,
            description = description
        )

        return workgroupRepository.save(workgroup)
    }

    /**
     * Update workgroup name and/or description
     * FR-002: Allow administrators to edit workgroups
     *
     * @param id Workgroup ID
     * @param name New name (optional, must be unique if provided)
     * @param description New description (optional)
     * @return Updated workgroup
     * @throws IllegalArgumentException if workgroup not found or name already exists
     */
    @Transactional
    open fun updateWorkgroup(id: Long, name: String? = null, description: String? = null): Workgroup {
        val workgroup = workgroupRepository.findById(id).orElseThrow {
            IllegalArgumentException("Workgroup not found: $id")
        }

        // If name is being changed, validate uniqueness
        if (name != null && name != workgroup.name) {
            if (workgroupRepository.existsByNameIgnoreCase(name)) {
                throw IllegalArgumentException("Workgroup name already exists (case-insensitive): $name")
            }
            workgroup.name = name
        }

        if (description != null) {
            workgroup.description = description
        }

        return workgroupRepository.update(workgroup)
    }

    /**
     * Delete workgroup
     * FR-003, FR-026: Delete workgroup and clear all memberships (cascade handled by JPA)
     *
     * @param id Workgroup ID
     * @throws IllegalArgumentException if workgroup not found
     */
    @Transactional
    open fun deleteWorkgroup(id: Long) {
        val workgroup = workgroupRepository.findById(id).orElseThrow {
            IllegalArgumentException("Workgroup not found: $id")
        }

        // JPA cascade will automatically remove join table entries in user_workgroups and asset_workgroups
        workgroupRepository.delete(workgroup)
    }

    /**
     * Assign users to workgroup
     * FR-007: Allow administrators to assign users to workgroups
     *
     * @param workgroupId Workgroup ID
     * @param userIds List of user IDs to assign
     * @throws IllegalArgumentException if workgroup not found or any user not found
     */
    @Transactional
    open fun assignUsersToWorkgroup(workgroupId: Long, userIds: List<Long>) {
        val workgroup = workgroupRepository.findById(workgroupId).orElseThrow {
            IllegalArgumentException("Workgroup not found: $workgroupId")
        }

        userIds.forEach { userId ->
            val user = userRepository.findById(userId).orElseThrow {
                IllegalArgumentException("User not found: $userId")
            }
            user.workgroups.add(workgroup)
            userRepository.update(user)
        }
    }

    /**
     * Remove user from workgroup
     * FR-008: Allow administrators to remove users from workgroups
     *
     * @param workgroupId Workgroup ID
     * @param userId User ID
     * @throws IllegalArgumentException if workgroup or user not found, or user not in workgroup
     */
    @Transactional
    open fun removeUserFromWorkgroup(workgroupId: Long, userId: Long) {
        val workgroup = workgroupRepository.findById(workgroupId).orElseThrow {
            IllegalArgumentException("Workgroup not found: $workgroupId")
        }

        val user = userRepository.findById(userId).orElseThrow {
            IllegalArgumentException("User not found: $userId")
        }

        if (!user.workgroups.contains(workgroup)) {
            throw IllegalArgumentException("User $userId not found in workgroup $workgroupId")
        }

        user.workgroups.remove(workgroup)
        userRepository.update(user)
    }

    /**
     * Assign assets to workgroup
     * FR-011: Allow administrators to assign assets to workgroups
     *
     * @param workgroupId Workgroup ID
     * @param assetIds List of asset IDs to assign
     * @throws IllegalArgumentException if workgroup not found or any asset not found
     */
    @Transactional
    open fun assignAssetsToWorkgroup(workgroupId: Long, assetIds: List<Long>) {
        val workgroup = workgroupRepository.findById(workgroupId).orElseThrow {
            IllegalArgumentException("Workgroup not found: $workgroupId")
        }

        assetIds.forEach { assetId ->
            val asset = assetRepository.findById(assetId).orElseThrow {
                IllegalArgumentException("Asset not found: $assetId")
            }
            asset.workgroups.add(workgroup)
            assetRepository.update(asset)
        }
    }

    /**
     * Remove asset from workgroup
     * FR-012: Allow administrators to remove assets from workgroups
     *
     * @param workgroupId Workgroup ID
     * @param assetId Asset ID
     * @throws IllegalArgumentException if workgroup or asset not found, or asset not in workgroup
     */
    @Transactional
    open fun removeAssetFromWorkgroup(workgroupId: Long, assetId: Long) {
        val workgroup = workgroupRepository.findById(workgroupId).orElseThrow {
            IllegalArgumentException("Workgroup not found: $workgroupId")
        }

        val asset = assetRepository.findById(assetId).orElseThrow {
            IllegalArgumentException("Asset not found: $assetId")
        }

        if (!asset.workgroups.contains(workgroup)) {
            throw IllegalArgumentException("Asset $assetId not found in workgroup $workgroupId")
        }

        asset.workgroups.remove(workgroup)
        assetRepository.update(asset)
    }

    /**
     * Get all workgroups
     * FR-005: Provide list view of all workgroups
     *
     * @return List of all workgroups
     */
    open fun listAllWorkgroups(): List<Workgroup> {
        return workgroupRepository.findAll()
    }

    /**
     * Get workgroup by ID with details
     * FR-003: Get workgroup details including counts
     *
     * @param id Workgroup ID
     * @return Workgroup with loaded relationships
     * @throws IllegalArgumentException if workgroup not found
     */
    open fun getWorkgroupById(id: Long): Workgroup {
        return workgroupRepository.findById(id).orElseThrow {
            IllegalArgumentException("Workgroup not found: $id")
        }
    }
}
