package com.secman.service

import com.secman.domain.Asset
import com.secman.domain.Criticality
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
    private val assetRepository: AssetRepository,
    private val validationService: WorkgroupValidationService
) {

    /**
     * Create a new workgroup with validation
     * FR-001, FR-004, FR-006: Create with unique name (case-insensitive)
     * Feature 039: Accept criticality parameter (defaults to MEDIUM if not provided)
     *
     * @param name Workgroup name (1-100 chars, alphanumeric + spaces + hyphens)
     * @param description Optional description (max 512 chars)
     * @param criticality Criticality level (defaults to MEDIUM)
     * @return Created workgroup
     * @throws IllegalArgumentException if name already exists or validation fails
     */
    @Transactional
    open fun createWorkgroup(
        name: String,
        description: String? = null,
        criticality: Criticality = Criticality.MEDIUM
    ): Workgroup {
        // Validate name uniqueness (case-insensitive)
        if (workgroupRepository.existsByNameIgnoreCase(name)) {
            throw IllegalArgumentException("Workgroup name already exists (case-insensitive): $name")
        }

        // Name format validation handled by @Pattern annotation on entity
        // Length validation handled by @Size annotation on entity

        val workgroup = Workgroup(
            name = name,
            description = description,
            criticality = criticality
        )

        return workgroupRepository.save(workgroup)
    }

    /**
     * Update workgroup name and/or description and/or criticality
     * FR-002: Allow administrators to edit workgroups
     * Feature 039: Accept criticality parameter
     *
     * @param id Workgroup ID
     * @param name New name (optional, must be unique if provided)
     * @param description New description (optional)
     * @param criticality New criticality level (optional)
     * @return Updated workgroup
     * @throws IllegalArgumentException if workgroup not found or name already exists
     */
    @Transactional
    open fun updateWorkgroup(
        id: Long,
        name: String? = null,
        description: String? = null,
        criticality: Criticality? = null
    ): Workgroup {
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

        if (criticality != null) {
            workgroup.criticality = criticality
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
     * Feature 073: Uses findByIdWithWorkgroups() for LAZY loading support.
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
            // Feature 073: Use findByIdWithWorkgroups() to load workgroups with LAZY loading
            val user = userRepository.findByIdWithWorkgroups(userId).orElseThrow {
                IllegalArgumentException("User not found: $userId")
            }
            user.workgroups.add(workgroup)
            userRepository.update(user)
        }
    }

    /**
     * Remove user from workgroup
     * FR-008: Allow administrators to remove users from workgroups
     * Feature 073: Uses findByIdWithWorkgroups() for LAZY loading support.
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

        // Feature 073: Use findByIdWithWorkgroups() to load workgroups with LAZY loading
        val user = userRepository.findByIdWithWorkgroups(userId).orElseThrow {
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
     * Feature 073: Uses findByIdWithWorkgroups() for LAZY loading support.
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
            // Feature 073: Use findByIdWithWorkgroups() to load workgroups with LAZY loading
            val asset = assetRepository.findByIdWithWorkgroups(assetId).orElseThrow {
                IllegalArgumentException("Asset not found: $assetId")
            }
            asset.workgroups.add(workgroup)
            assetRepository.update(asset)
        }
    }

    /**
     * Remove asset from workgroup
     * FR-012: Allow administrators to remove assets from workgroups
     * Feature 073: Uses findByIdWithWorkgroups() for LAZY loading support.
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

        // Feature 073: Use findByIdWithWorkgroups() to load workgroups with LAZY loading
        val asset = assetRepository.findByIdWithWorkgroups(assetId).orElseThrow {
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

    // Feature 040: Nested Workgroups - Hierarchy Operations

    /**
     * Create a child workgroup under a parent
     * Feature 040: Nested Workgroups (User Story 1)
     *
     * @param parentId ID of parent workgroup
     * @param name Child workgroup name (must be unique among siblings)
     * @param description Optional description
     * @return Created child workgroup
     * @throws IllegalArgumentException if parent not found
     * @throws ValidationException if depth limit exceeded or name conflict
     */
    @Transactional
    open fun createChildWorkgroup(
        parentId: Long,
        name: String,
        description: String? = null
    ): Workgroup {
        val parent = workgroupRepository.findById(parentId).orElseThrow {
            IllegalArgumentException("Parent workgroup not found: $parentId")
        }

        // Validate depth limit
        validationService.validateDepthLimit(parent)

        // Validate sibling uniqueness
        validationService.validateSiblingUniqueness(name, parent)

        val child = Workgroup(
            name = name,
            description = description,
            parent = parent
        )

        return workgroupRepository.save(child)
    }

    /**
     * Move a workgroup to a new parent
     * Feature 040: Nested Workgroups (User Story 3)
     *
     * @param workgroupId ID of workgroup to move
     * @param newParentId ID of new parent (null to move to root level)
     * @return Updated workgroup
     * @throws IllegalArgumentException if workgroup or parent not found
     * @throws ValidationException if move would violate constraints
     */
    @Transactional
    open fun moveWorkgroup(workgroupId: Long, newParentId: Long?): Workgroup {
        val workgroup = workgroupRepository.findById(workgroupId).orElseThrow {
            IllegalArgumentException("Workgroup not found: $workgroupId")
        }

        val newParent = if (newParentId != null) {
            workgroupRepository.findById(newParentId).orElseThrow {
                IllegalArgumentException("New parent workgroup not found: $newParentId")
            }
        } else {
            null
        }

        // Validate move operation
        validationService.validateMove(workgroup, newParent)

        workgroup.parent = newParent
        return workgroupRepository.update(workgroup)
    }

    /**
     * Delete a workgroup and promote its children to grandparent
     * Feature 040: Nested Workgroups (User Story 4)
     *
     * @param workgroupId ID of workgroup to delete
     * @throws IllegalArgumentException if workgroup not found
     */
    @Transactional
    open fun deleteWorkgroupWithPromotion(workgroupId: Long) {
        val workgroup = workgroupRepository.findById(workgroupId).orElseThrow {
            IllegalArgumentException("Workgroup not found: $workgroupId")
        }

        val grandparent = workgroup.parent

        // Promote all children to grandparent level
        workgroup.children.forEach { child ->
            child.parent = grandparent
            workgroupRepository.update(child)
        }

        // Now delete the workgroup
        workgroupRepository.delete(workgroup)
    }

    /**
     * Get all direct children of a workgroup
     * Feature 040: Nested Workgroups (User Story 2)
     *
     * @param parentId ID of parent workgroup
     * @return List of direct children
     */
    open fun getChildren(parentId: Long): List<Workgroup> {
        val parent = workgroupRepository.findById(parentId).orElseThrow {
            IllegalArgumentException("Parent workgroup not found: $parentId")
        }
        return workgroupRepository.findByParent(parent)
    }

    /**
     * Get all root-level workgroups (no parent)
     * Feature 040: Nested Workgroups (User Story 2)
     *
     * @return List of root-level workgroups
     */
    open fun getRootWorkgroups(): List<Workgroup> {
        return workgroupRepository.findRootLevelWorkgroups()
    }

    /**
     * Get all ancestors from root to immediate parent
     * Feature 040: Nested Workgroups (User Story 5)
     *
     * @param workgroupId ID of workgroup
     * @return List of ancestors (root first, immediate parent last)
     */
    open fun getAncestors(workgroupId: Long): List<Workgroup> {
        val workgroup = workgroupRepository.findById(workgroupId).orElseThrow {
            IllegalArgumentException("Workgroup not found: $workgroupId")
        }
        return workgroup.getAncestors()
    }

    /**
     * Get all descendants (entire subtree)
     * Feature 040: Nested Workgroups (User Story 2)
     *
     * @param workgroupId ID of root workgroup
     * @return List of all descendants
     */
    open fun getDescendants(workgroupId: Long): List<Workgroup> {
        return workgroupRepository.findAllDescendants(workgroupId)
    }
}
