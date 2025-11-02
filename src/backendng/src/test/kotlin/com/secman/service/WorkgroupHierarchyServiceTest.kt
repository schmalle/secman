package com.secman.service

import com.secman.domain.Workgroup
import com.secman.repository.AssetRepository
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.*

/**
 * Unit tests for WorkgroupService hierarchy operations
 * Feature 040: Nested Workgroups
 *
 * Tests hierarchy operations in isolation using mocks:
 * - Create child workgroup
 * - Move workgroup
 * - Delete with child promotion
 * - Get children/ancestors/descendants
 */
class WorkgroupHierarchyServiceTest {

    private lateinit var workgroupRepository: WorkgroupRepository
    private lateinit var userRepository: UserRepository
    private lateinit var assetRepository: AssetRepository
    private lateinit var validationService: WorkgroupValidationService
    private lateinit var workgroupService: WorkgroupService

    @BeforeEach
    fun setup() {
        workgroupRepository = mockk()
        userRepository = mockk()
        assetRepository = mockk()
        validationService = mockk()
        workgroupService = WorkgroupService(
            workgroupRepository,
            userRepository,
            assetRepository,
            validationService
        )
    }

    // ===== createChildWorkgroup Tests =====

    @Test
    @DisplayName("createChildWorkgroup - successfully creates child")
    fun testCreateChildWorkgroup_success() {
        // Given: A parent workgroup
        val parent = Workgroup(id = 1L, name = "IT Department", parent = null)
        every { workgroupRepository.findById(1L) } returns Optional.of(parent)

        // Mock validation passes
        every { validationService.validateDepthLimit(parent) } returns Unit
        every { validationService.validateSiblingUniqueness("Engineering", parent) } returns Unit

        // Mock save returns child with ID
        val savedChild = slot<Workgroup>()
        every { workgroupRepository.save(capture(savedChild)) } answers {
            savedChild.captured.copy(id = 2L)
        }

        // When: Creating child workgroup
        val result = workgroupService.createChildWorkgroup(
            parentId = 1L,
            name = "Engineering",
            description = "Engineering team"
        )

        // Then: Child is created with correct properties
        assertEquals(2L, result.id)
        assertEquals("Engineering", result.name)
        assertEquals("Engineering team", result.description)
        assertEquals(parent, result.parent)

        // Verify validations were called
        verify { validationService.validateDepthLimit(parent) }
        verify { validationService.validateSiblingUniqueness("Engineering", parent) }
        verify { workgroupRepository.save(any()) }
    }

    @Test
    @DisplayName("createChildWorkgroup - throws exception when parent not found")
    fun testCreateChildWorkgroup_parentNotFound() {
        // Given: Non-existent parent
        every { workgroupRepository.findById(999L) } returns Optional.empty()

        // When/Then: IllegalArgumentException thrown
        val exception = assertThrows(IllegalArgumentException::class.java) {
            workgroupService.createChildWorkgroup(
                parentId = 999L,
                name = "Engineering"
            )
        }

        assertTrue(exception.message?.contains("not found") == true)
    }

    @Test
    @DisplayName("createChildWorkgroup - throws exception when depth limit exceeded")
    fun testCreateChildWorkgroup_depthLimitExceeded() {
        // Given: A parent at maximum depth
        val level1 = Workgroup(id = 1L, name = "L1", parent = null)
        val level2 = Workgroup(id = 2L, name = "L2", parent = level1)
        val level3 = Workgroup(id = 3L, name = "L3", parent = level2)
        val level4 = Workgroup(id = 4L, name = "L4", parent = level3)
        val level5 = Workgroup(id = 5L, name = "L5", parent = level4)

        every { workgroupRepository.findById(5L) } returns Optional.of(level5)

        // Mock validation throws exception
        every { validationService.validateDepthLimit(level5) } throws ValidationException(
            "Cannot create child: parent is at maximum depth (5)"
        )

        // When/Then: ValidationException thrown
        val exception = assertThrows(ValidationException::class.java) {
            workgroupService.createChildWorkgroup(
                parentId = 5L,
                name = "L6 - Should Fail"
            )
        }

        assertTrue(exception.message?.contains("maximum depth") == true)
    }

    @Test
    @DisplayName("createChildWorkgroup - throws exception for duplicate sibling name")
    fun testCreateChildWorkgroup_duplicateSiblingName() {
        // Given: A parent with existing child
        val parent = Workgroup(id = 1L, name = "IT", parent = null)
        every { workgroupRepository.findById(1L) } returns Optional.of(parent)

        every { validationService.validateDepthLimit(parent) } returns Unit

        // Mock sibling uniqueness validation throws exception
        every { validationService.validateSiblingUniqueness("Engineering", parent) } throws ValidationException(
            "A workgroup named 'Engineering' already exists under IT"
        )

        // When/Then: ValidationException thrown
        val exception = assertThrows(ValidationException::class.java) {
            workgroupService.createChildWorkgroup(
                parentId = 1L,
                name = "Engineering"
            )
        }

        assertTrue(exception.message?.contains("already exists") == true)
    }

    // ===== moveWorkgroup Tests =====

    @Test
    @DisplayName("moveWorkgroup - successfully moves to new parent")
    fun testMoveWorkgroup_success() {
        // Given: A workgroup and a new parent
        val oldParent = Workgroup(id = 1L, name = "Old Parent", parent = null)
        val workgroup = Workgroup(id = 2L, name = "Engineering", parent = oldParent)
        val newParent = Workgroup(id = 3L, name = "New Parent", parent = null)

        every { workgroupRepository.findById(2L) } returns Optional.of(workgroup)
        every { workgroupRepository.findById(3L) } returns Optional.of(newParent)

        // Mock validation passes
        every { validationService.validateMove(workgroup, newParent) } returns Unit

        // Mock update
        every { workgroupRepository.update(any()) } answers { firstArg() }

        // When: Moving workgroup
        val result = workgroupService.moveWorkgroup(workgroupId = 2L, newParentId = 3L)

        // Then: Workgroup parent is updated
        assertEquals(newParent, result.parent)
        verify { validationService.validateMove(workgroup, newParent) }
        verify { workgroupRepository.update(workgroup) }
    }

    @Test
    @DisplayName("moveWorkgroup - successfully moves to root level")
    fun testMoveWorkgroup_toRootLevel() {
        // Given: A child workgroup
        val parent = Workgroup(id = 1L, name = "Parent", parent = null)
        val workgroup = Workgroup(id = 2L, name = "Engineering", parent = parent)

        every { workgroupRepository.findById(2L) } returns Optional.of(workgroup)

        // Mock validation passes
        every { validationService.validateMove(workgroup, null) } returns Unit

        // Mock update
        every { workgroupRepository.update(any()) } answers { firstArg() }

        // When: Moving to root level (newParentId = null)
        val result = workgroupService.moveWorkgroup(workgroupId = 2L, newParentId = null)

        // Then: Workgroup parent is null (root level)
        assertNull(result.parent)
        verify { validationService.validateMove(workgroup, null) }
    }

    @Test
    @DisplayName("moveWorkgroup - throws exception when workgroup not found")
    fun testMoveWorkgroup_workgroupNotFound() {
        // Given: Non-existent workgroup
        every { workgroupRepository.findById(999L) } returns Optional.empty()

        // When/Then: IllegalArgumentException thrown
        val exception = assertThrows(IllegalArgumentException::class.java) {
            workgroupService.moveWorkgroup(workgroupId = 999L, newParentId = 1L)
        }

        assertTrue(exception.message?.contains("not found") == true)
    }

    // ===== deleteWorkgroupWithPromotion Tests =====

    @Test
    @DisplayName("deleteWorkgroupWithPromotion - promotes children to grandparent")
    fun testDeleteWorkgroupWithPromotion_promotesChildren() {
        // Given: A workgroup with children
        val grandparent = Workgroup(id = 1L, name = "Grandparent", parent = null)
        val parent = Workgroup(id = 2L, name = "Parent", parent = grandparent)
        val child1 = Workgroup(id = 3L, name = "Child 1", parent = parent)
        val child2 = Workgroup(id = 4L, name = "Child 2", parent = parent)

        parent.children.add(child1)
        parent.children.add(child2)

        every { workgroupRepository.findById(2L) } returns Optional.of(parent)
        every { workgroupRepository.update(any()) } answers { firstArg() }
        every { workgroupRepository.delete(parent) } returns Unit

        // When: Deleting parent
        workgroupService.deleteWorkgroupWithPromotion(2L)

        // Then: Children are promoted to grandparent
        assertEquals(grandparent, child1.parent)
        assertEquals(grandparent, child2.parent)
        verify(exactly = 2) { workgroupRepository.update(any()) }
        verify { workgroupRepository.delete(parent) }
    }

    @Test
    @DisplayName("deleteWorkgroupWithPromotion - promotes children to root when no grandparent")
    fun testDeleteWorkgroupWithPromotion_promotesToRoot() {
        // Given: A root-level workgroup with children
        val parent = Workgroup(id = 1L, name = "Parent", parent = null)
        val child1 = Workgroup(id = 2L, name = "Child 1", parent = parent)
        val child2 = Workgroup(id = 3L, name = "Child 2", parent = parent)

        parent.children.add(child1)
        parent.children.add(child2)

        every { workgroupRepository.findById(1L) } returns Optional.of(parent)
        every { workgroupRepository.update(any()) } answers { firstArg() }
        every { workgroupRepository.delete(parent) } returns Unit

        // When: Deleting parent
        workgroupService.deleteWorkgroupWithPromotion(1L)

        // Then: Children are promoted to root (parent = null)
        assertNull(child1.parent)
        assertNull(child2.parent)
        verify(exactly = 2) { workgroupRepository.update(any()) }
        verify { workgroupRepository.delete(parent) }
    }

    // ===== getChildren Tests =====

    @Test
    @DisplayName("getChildren - returns direct children")
    fun testGetChildren_success() {
        // Given: A parent with children
        val parent = Workgroup(id = 1L, name = "Parent", parent = null)
        val child1 = Workgroup(id = 2L, name = "Child 1", parent = parent)
        val child2 = Workgroup(id = 3L, name = "Child 2", parent = parent)

        every { workgroupRepository.findById(1L) } returns Optional.of(parent)
        every { workgroupRepository.findByParent(parent) } returns listOf(child1, child2)

        // When: Getting children
        val result = workgroupService.getChildren(1L)

        // Then: Direct children returned
        assertEquals(2, result.size)
        assertTrue(result.contains(child1))
        assertTrue(result.contains(child2))
    }

    // ===== getRootWorkgroups Tests =====

    @Test
    @DisplayName("getRootWorkgroups - returns all root-level workgroups")
    fun testGetRootWorkgroups_success() {
        // Given: Multiple root-level workgroups
        val root1 = Workgroup(id = 1L, name = "IT", parent = null)
        val root2 = Workgroup(id = 2L, name = "HR", parent = null)

        every { workgroupRepository.findRootLevelWorkgroups() } returns listOf(root1, root2)

        // When: Getting root workgroups
        val result = workgroupService.getRootWorkgroups()

        // Then: All root workgroups returned
        assertEquals(2, result.size)
        assertTrue(result.contains(root1))
        assertTrue(result.contains(root2))
    }

    // ===== getAncestors Tests =====

    @Test
    @DisplayName("getAncestors - returns ancestor path")
    fun testGetAncestors_success() {
        // Given: A hierarchy
        val level1 = Workgroup(id = 1L, name = "L1", parent = null)
        val level2 = Workgroup(id = 2L, name = "L2", parent = level1)
        val level3 = Workgroup(id = 3L, name = "L3", parent = level2)

        every { workgroupRepository.findById(3L) } returns Optional.of(level3)

        // When: Getting ancestors of level3
        val result = workgroupService.getAncestors(3L)

        // Then: Ancestors returned in root-to-parent order
        assertEquals(2, result.size)
        assertEquals(level1, result[0])
        assertEquals(level2, result[1])
    }

    // ===== getDescendants Tests =====

    @Test
    @DisplayName("getDescendants - returns entire subtree")
    fun testGetDescendants_success() {
        // Given: A hierarchy
        val root = Workgroup(id = 1L, name = "Root", parent = null)
        val child = Workgroup(id = 2L, name = "Child", parent = root)
        val grandchild = Workgroup(id = 3L, name = "Grandchild", parent = child)

        every { workgroupRepository.findAllDescendants(1L) } returns listOf(root, child, grandchild)

        // When: Getting descendants
        val result = workgroupService.getDescendants(1L)

        // Then: All descendants returned
        assertEquals(3, result.size)
        assertTrue(result.contains(root))
        assertTrue(result.contains(child))
        assertTrue(result.contains(grandchild))
    }
}
