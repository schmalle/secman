package com.secman.dto

import io.micronaut.serde.annotation.Serdeable
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * DTOs for Workgroup Hierarchy Operations
 * Feature 040: Nested Workgroups
 *
 * Related Requirements:
 * - FR-014: System MUST support up to 5 levels of nesting depth
 * - FR-019: System MUST use optimistic locking to detect concurrent modifications
 * - FR-020: System MUST enforce sibling uniqueness
 */

/**
 * Request DTO for creating a child workgroup
 * Feature 040: Nested Workgroups (User Story 1)
 *
 * @param name Name of the child workgroup (must be unique among siblings)
 * @param description Optional description
 */
@Serdeable
data class CreateChildWorkgroupRequest(
    @field:NotBlank(message = "Name is required")
    @field:Size(min = 1, max = 255, message = "Name must be 1-255 characters")
    val name: String,

    @field:Size(max = 1000, message = "Description must not exceed 1000 characters")
    val description: String? = null
)

/**
 * Request DTO for moving a workgroup to a different parent
 * Feature 040: Nested Workgroups (User Story 3)
 *
 * @param newParentId ID of the new parent workgroup (null to move to root level)
 */
@Serdeable
data class MoveWorkgroupRequest(
    @field:NotNull(message = "New parent ID is required")
    val newParentId: Long?  // Nullable to allow moving to root level
)

/**
 * Response DTO for workgroup with hierarchy information
 * Feature 040: Nested Workgroups
 *
 * Includes hierarchy-specific fields:
 * - parentId: ID of parent workgroup (null if root-level)
 * - depth: Depth in hierarchy (1 = root level)
 * - childCount: Number of direct children
 * - hasChildren: Whether this workgroup has any children (for lazy loading)
 * - ancestors: Full path from root to immediate parent
 * - version: Optimistic locking version
 */
@Serdeable
data class WorkgroupResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val parentId: Long?,  // Feature 040: Hierarchy support
    val depth: Int,  // Feature 040: Calculated depth
    val childCount: Int,  // Feature 040: Number of direct children
    val hasChildren: Boolean,  // Feature 040: For lazy-loading trees
    val ancestors: List<BreadcrumbItem>,  // Feature 040: Breadcrumb navigation
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long  // Feature 040: Optimistic locking
)

/**
 * Breadcrumb item for ancestor path navigation
 * Feature 040: Nested Workgroups
 */
@Serdeable
data class BreadcrumbItem(
    val id: Long,
    val name: String
)
