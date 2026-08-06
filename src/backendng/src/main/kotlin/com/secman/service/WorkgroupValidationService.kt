package com.secman.service

import com.secman.domain.Workgroup
import com.secman.repository.WorkgroupRepository
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

/**
 * Validation service for workgroup hierarchy operations
 * Feature 040: Nested Workgroups
 *
 * Centralizes validation logic for:
 * - Depth limit enforcement (5 levels max)
 * - Circular reference prevention
 * - Sibling name uniqueness
 * - Move operation validation
 */
@Singleton
class WorkgroupValidationService(
    private val workgroupRepository: WorkgroupRepository
) {
    private val logger = LoggerFactory.getLogger(WorkgroupValidationService::class.java)
    private val maxDepth = 5

    /**
     * Validate that creating a child under this parent won't exceed depth limit.
     * @throws ValidationException if depth would exceed limit
     */
    fun validateDepthLimit(parent: Workgroup?) {
        if (parent == null) return  // Root level always valid

        val parentDepth = parent.calculateDepth()
        if (parentDepth >= maxDepth) {
            throw ValidationException(
                "Cannot create child: parent is at maximum depth ($maxDepth)"
            )
        }
    }

    /**
     * Validate that setting this parent won't create a circular reference.
     * @throws ValidationException if circular reference detected
     */
    fun validateNoCircularReference(workgroup: Workgroup, newParent: Workgroup) {
        // Can't be own parent
        if (workgroup.id == newParent.id) {
            throw ValidationException("Workgroup cannot be its own parent")
        }

        // New parent can't be a descendant
        if (newParent.isDescendantOf(workgroup)) {
            throw ValidationException(
                "Cannot set parent: would create circular reference"
            )
        }
    }

    /**
     * Validate that the name is unique among siblings.
     * @throws ValidationException if sibling with same name exists
     */
    fun validateSiblingUniqueness(name: String, parent: Workgroup?, excludeId: Long? = null) {
        val siblings = if (parent != null) {
            workgroupRepository.findByParent(parent)
        } else {
            workgroupRepository.findRootLevelWorkgroups()
        }

        val duplicate = siblings.firstOrNull {
            it.name.equals(name, ignoreCase = true) && it.id != excludeId
        }

        if (duplicate != null) {
            val parentName = parent?.name ?: "root level"
            throw ValidationException(
                "A workgroup named '$name' already exists under $parentName"
            )
        }
    }

    /**
     * Validate that a workgroup can be moved to a new parent.
     * Checks depth, circular references, and name uniqueness.
     */
    fun validateMove(workgroup: Workgroup, newParent: Workgroup?) {
        // Check circular references
        if (newParent != null) {
            validateNoCircularReference(workgroup, newParent)
        }

        // Check depth limit (workgroup + all descendants must fit)
        val workgroupSubtreeDepth = calculateSubtreeDepth(workgroup)
        val newParentDepth = newParent?.calculateDepth() ?: 0
        val resultingDepth = newParentDepth + workgroupSubtreeDepth

        if (resultingDepth > maxDepth) {
            throw ValidationException(
                "Cannot move: resulting depth ($resultingDepth) exceeds maximum ($maxDepth)"
            )
        }

        // Check name uniqueness in new parent's children
        validateSiblingUniqueness(workgroup.name, newParent, excludeId = workgroup.id)
    }

    /**
     * Calculate the maximum depth of the subtree rooted at this workgroup.
     */
    private fun calculateSubtreeDepth(root: Workgroup): Int {
        fun maxDepth(node: Workgroup, currentDepth: Int): Int {
            if (node.children.isEmpty()) return currentDepth
            return node.children.maxOf { maxDepth(it, currentDepth + 1) }
        }
        return maxDepth(root, 1)
    }
}

/**
 * Exception thrown when workgroup hierarchy validation fails
 */
class ValidationException(message: String) : RuntimeException(message)
