package com.secman.service

import com.secman.domain.Workgroup
import com.secman.repository.WorkgroupRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for WorkgroupValidationService
 * Feature 040: Nested Workgroups
 *
 * Tests validation logic in isolation using mocks:
 * - Depth limit enforcement (5 levels max)
 * - Circular reference prevention
 * - Sibling name uniqueness
 * - Move operation validation
 */
class WorkgroupValidationServiceTest {

    private lateinit var workgroupRepository: WorkgroupRepository
    private lateinit var validationService: WorkgroupValidationService

    @BeforeEach
    fun setup() {
        workgroupRepository = mockk()
        validationService = WorkgroupValidationService(workgroupRepository)
    }

    // ===== validateDepthLimit Tests =====

    @Test
    @DisplayName("validateDepthLimit - allows child creation at depth 1")
    fun testValidateDepthLimit_allowsChildAtDepth1() {
        // Given: A root-level parent (depth 1)
        val parent = Workgroup(id = 1L, name = "Level 1", parent = null)

        // When/Then: No exception thrown
        assertDoesNotThrow {
            validationService.validateDepthLimit(parent)
        }
    }

    @Test
    @DisplayName("validateDepthLimit - allows child creation up to depth 4")
    fun testValidateDepthLimit_allowsChildAtDepth4() {
        // Given: A hierarchy at depth 4
        val level1 = Workgroup(id = 1L, name = "Level 1", parent = null)
        val level2 = Workgroup(id = 2L, name = "Level 2", parent = level1)
        val level3 = Workgroup(id = 3L, name = "Level 3", parent = level2)
        val level4 = Workgroup(id = 4L, name = "Level 4", parent = level3)

        // When/Then: No exception thrown (child would be at depth 5)
        assertDoesNotThrow {
            validationService.validateDepthLimit(level4)
        }
    }

    @Test
    @DisplayName("validateDepthLimit - rejects child creation at depth 5")
    fun testValidateDepthLimit_rejectsChildAtDepth5() {
        // Given: A hierarchy at depth 5 (maximum)
        val level1 = Workgroup(id = 1L, name = "Level 1", parent = null)
        val level2 = Workgroup(id = 2L, name = "Level 2", parent = level1)
        val level3 = Workgroup(id = 3L, name = "Level 3", parent = level2)
        val level4 = Workgroup(id = 4L, name = "Level 4", parent = level3)
        val level5 = Workgroup(id = 5L, name = "Level 5", parent = level4)

        // When/Then: Validation exception thrown
        val exception = assertThrows(ValidationException::class.java) {
            validationService.validateDepthLimit(level5)
        }

        assertTrue(exception.message?.contains("maximum depth") == true)
        assertTrue(exception.message?.contains("5") == true)
    }

    @Test
    @DisplayName("validateDepthLimit - allows null parent (root level)")
    fun testValidateDepthLimit_allowsNullParent() {
        // When/Then: No exception thrown for null parent
        assertDoesNotThrow {
            validationService.validateDepthLimit(null)
        }
    }

    // ===== validateNoCircularReference Tests =====

    @Test
    @DisplayName("validateNoCircularReference - rejects self as parent")
    fun testValidateNoCircularReference_rejectsSelfAsParent() {
        // Given: A workgroup
        val workgroup = Workgroup(id = 1L, name = "IT Department", parent = null)

        // When/Then: Validation exception thrown
        val exception = assertThrows(ValidationException::class.java) {
            validationService.validateNoCircularReference(workgroup, workgroup)
        }

        assertTrue(exception.message?.contains("own parent") == true)
    }

    @Test
    @DisplayName("validateNoCircularReference - rejects descendant as parent")
    fun testValidateNoCircularReference_rejectsDescendantAsParent() {
        // Given: A hierarchy where grandchild tries to become parent of grandparent
        val grandparent = Workgroup(id = 1L, name = "Grandparent", parent = null)
        val parent = Workgroup(id = 2L, name = "Parent", parent = grandparent)
        val child = Workgroup(id = 3L, name = "Child", parent = parent)

        // When/Then: Validation exception thrown
        val exception = assertThrows(ValidationException::class.java) {
            validationService.validateNoCircularReference(grandparent, child)
        }

        assertTrue(exception.message?.contains("circular reference") == true)
    }

    @Test
    @DisplayName("validateNoCircularReference - allows non-descendant as parent")
    fun testValidateNoCircularReference_allowsNonDescendantAsParent() {
        // Given: Two separate workgroups
        val workgroup1 = Workgroup(id = 1L, name = "IT", parent = null)
        val workgroup2 = Workgroup(id = 2L, name = "HR", parent = null)

        // When/Then: No exception thrown
        assertDoesNotThrow {
            validationService.validateNoCircularReference(workgroup1, workgroup2)
        }
    }

    // ===== validateSiblingUniqueness Tests =====

    @Test
    @DisplayName("validateSiblingUniqueness - allows unique name among siblings")
    fun testValidateSiblingUniqueness_allowsUniqueName() {
        // Given: A parent with one child "Engineering"
        val parent = Workgroup(id = 1L, name = "IT", parent = null)
        val existingChild = Workgroup(id = 2L, name = "Engineering", parent = parent)

        every { workgroupRepository.findByParent(parent) } returns listOf(existingChild)

        // When/Then: No exception for new name "Support"
        assertDoesNotThrow {
            validationService.validateSiblingUniqueness("Support", parent)
        }
    }

    @Test
    @DisplayName("validateSiblingUniqueness - rejects duplicate name among siblings")
    fun testValidateSiblingUniqueness_rejectsDuplicateName() {
        // Given: A parent with one child "Engineering"
        val parent = Workgroup(id = 1L, name = "IT", parent = null)
        val existingChild = Workgroup(id = 2L, name = "Engineering", parent = parent)

        every { workgroupRepository.findByParent(parent) } returns listOf(existingChild)

        // When/Then: Validation exception for duplicate name "Engineering"
        val exception = assertThrows(ValidationException::class.java) {
            validationService.validateSiblingUniqueness("Engineering", parent)
        }

        assertTrue(exception.message?.contains("already exists") == true)
        assertTrue(exception.message?.contains("Engineering") == true)
    }

    @Test
    @DisplayName("validateSiblingUniqueness - is case-insensitive")
    fun testValidateSiblingUniqueness_caseInsensitive() {
        // Given: A parent with one child "Engineering"
        val parent = Workgroup(id = 1L, name = "IT", parent = null)
        val existingChild = Workgroup(id = 2L, name = "Engineering", parent = parent)

        every { workgroupRepository.findByParent(parent) } returns listOf(existingChild)

        // When/Then: Validation exception for "engineering" (different case)
        val exception = assertThrows(ValidationException::class.java) {
            validationService.validateSiblingUniqueness("engineering", parent)
        }

        assertTrue(exception.message?.contains("already exists") == true)
    }

    @Test
    @DisplayName("validateSiblingUniqueness - excludes self when updating")
    fun testValidateSiblingUniqueness_excludesSelf() {
        // Given: A parent with two children
        val parent = Workgroup(id = 1L, name = "IT", parent = null)
        val child1 = Workgroup(id = 2L, name = "Engineering", parent = parent)
        val child2 = Workgroup(id = 3L, name = "Support", parent = parent)

        every { workgroupRepository.findByParent(parent) } returns listOf(child1, child2)

        // When/Then: No exception when validating "Engineering" for child1 (excludeId = 2)
        assertDoesNotThrow {
            validationService.validateSiblingUniqueness("Engineering", parent, excludeId = 2L)
        }
    }

    @Test
    @DisplayName("validateSiblingUniqueness - validates root-level uniqueness")
    fun testValidateSiblingUniqueness_rootLevel() {
        // Given: Root-level workgroups
        val root1 = Workgroup(id = 1L, name = "IT", parent = null)
        val root2 = Workgroup(id = 2L, name = "HR", parent = null)

        every { workgroupRepository.findRootLevelWorkgroups() } returns listOf(root1, root2)

        // When/Then: Validation exception for duplicate "IT"
        val exception = assertThrows(ValidationException::class.java) {
            validationService.validateSiblingUniqueness("IT", null)
        }

        assertTrue(exception.message?.contains("already exists") == true)
        assertTrue(exception.message?.contains("root level") == true)
    }

    // ===== validateMove Tests =====

    @Test
    @DisplayName("validateMove - allows valid move")
    fun testValidateMove_allowsValidMove() {
        // Given: A workgroup and a new parent at depth 2
        val newParent = Workgroup(id = 1L, name = "IT", parent = null)
        val workgroup = Workgroup(id = 2L, name = "Engineering", parent = null)

        every { workgroupRepository.findByParent(newParent) } returns emptyList()

        // When/Then: No exception thrown
        assertDoesNotThrow {
            validationService.validateMove(workgroup, newParent)
        }
    }

    @Test
    @DisplayName("validateMove - rejects move causing circular reference")
    fun testValidateMove_rejectsCircularReference() {
        // Given: Parent trying to move under its own child
        val parent = Workgroup(id = 1L, name = "Parent", parent = null)
        val child = Workgroup(id = 2L, name = "Child", parent = parent)

        // When/Then: Validation exception thrown
        val exception = assertThrows(ValidationException::class.java) {
            validationService.validateMove(parent, child)
        }

        assertTrue(exception.message?.contains("circular reference") == true)
    }

    @Test
    @DisplayName("validateMove - rejects move exceeding depth limit")
    fun testValidateMove_rejectsDepthExceeded() {
        // Given: Moving a subtree of depth 3 under a parent at depth 3 (would result in depth 6)
        val level1 = Workgroup(id = 1L, name = "L1", parent = null)
        val level2 = Workgroup(id = 2L, name = "L2", parent = level1)
        val newParent = Workgroup(id = 3L, name = "L3", parent = level2)

        val moveRoot = Workgroup(id = 4L, name = "MoveRoot", parent = null)
        val moveChild1 = Workgroup(id = 5L, name = "MoveChild1", parent = moveRoot)
        val moveChild2 = Workgroup(id = 6L, name = "MoveChild2", parent = moveChild1)

        // Add children to moveRoot
        moveRoot.children.add(moveChild1)
        moveChild1.children.add(moveChild2)

        every { workgroupRepository.findByParent(newParent) } returns emptyList()

        // When/Then: Validation exception thrown
        val exception = assertThrows(ValidationException::class.java) {
            validationService.validateMove(moveRoot, newParent)
        }

        assertTrue(exception.message?.contains("resulting depth") == true)
        assertTrue(exception.message?.contains("exceeds maximum") == true)
    }

    @Test
    @DisplayName("validateMove - rejects move with sibling name conflict")
    fun testValidateMove_rejectsSiblingConflict() {
        // Given: New parent already has child with same name
        val newParent = Workgroup(id = 1L, name = "IT", parent = null)
        val existingChild = Workgroup(id = 2L, name = "Engineering", parent = newParent)
        val workgroupToMove = Workgroup(id = 3L, name = "Engineering", parent = null)

        every { workgroupRepository.findByParent(newParent) } returns listOf(existingChild)

        // When/Then: Validation exception thrown
        val exception = assertThrows(ValidationException::class.java) {
            validationService.validateMove(workgroupToMove, newParent)
        }

        assertTrue(exception.message?.contains("already exists") == true)
    }
}
