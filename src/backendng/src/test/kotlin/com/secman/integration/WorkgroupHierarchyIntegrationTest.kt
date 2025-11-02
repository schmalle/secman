package com.secman.integration

import com.secman.domain.Workgroup
import com.secman.repository.WorkgroupRepository
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import javax.sql.DataSource

/**
 * Integration tests for workgroup hierarchy operations
 * Feature 040: Nested Workgroups
 *
 * Tests database-level operations:
 * - Recursive CTE queries (ancestors, descendants)
 * - Database constraints (unique constraint on parent_id + name)
 * - Foreign key relationships
 * - Optimistic locking
 */
@MicronautTest(transactional = false)
class WorkgroupHierarchyIntegrationTest {

    @Inject
    lateinit var workgroupRepository: WorkgroupRepository

    @Inject
    lateinit var dataSource: DataSource

    @BeforeEach
    fun cleanup() {
        // Clean up workgroups before each test
        workgroupRepository.deleteAll()
    }

    // ===== Database Schema Tests =====

    @Test
    @DisplayName("Database schema - parent_id foreign key exists")
    fun testDatabaseSchema_parentIdForeignKey() {
        // Given: A parent workgroup
        val parent = workgroupRepository.save(Workgroup(name = "Parent"))

        // When: Creating a child with valid parent
        val child = workgroupRepository.save(Workgroup(name = "Child", parent = parent))

        // Then: Child is saved with parent reference
        assertNotNull(child.id)
        assertEquals(parent.id, child.parent?.id)
    }

    @Test
    @DisplayName("Database schema - version column for optimistic locking")
    fun testDatabaseSchema_versionColumn() {
        // Given: A new workgroup
        val workgroup = workgroupRepository.save(Workgroup(name = "Test"))

        // Then: Version is initialized to 0
        assertEquals(0L, workgroup.version)

        // When: Updating workgroup
        workgroup.name = "Test Updated"
        val updated = workgroupRepository.update(workgroup)

        // Then: Version is incremented
        assertEquals(1L, updated.version)
    }

    @Test
    @DisplayName("Database schema - unique constraint on (parent_id, name)")
    fun testDatabaseSchema_siblingUniqueConstraint() {
        // Given: A parent with one child
        val parent = workgroupRepository.save(Workgroup(name = "Parent"))
        workgroupRepository.save(Workgroup(name = "Engineering", parent = parent))

        // When/Then: Attempting to create duplicate sibling name throws exception
        assertThrows(Exception::class.java) {
            workgroupRepository.save(Workgroup(name = "Engineering", parent = parent))
        }
    }

    @Test
    @DisplayName("Database schema - allows duplicate names under different parents")
    fun testDatabaseSchema_allowsDuplicateNamesUnderDifferentParents() {
        // Given: Two different parents
        val parent1 = workgroupRepository.save(Workgroup(name = "IT"))
        val parent2 = workgroupRepository.save(Workgroup(name = "HR"))

        // When: Creating children with same name under different parents
        val child1 = workgroupRepository.save(Workgroup(name = "Support", parent = parent1))
        val child2 = workgroupRepository.save(Workgroup(name = "Support", parent = parent2))

        // Then: Both children are saved successfully
        assertNotNull(child1.id)
        assertNotNull(child2.id)
        assertNotEquals(child1.id, child2.id)
    }

    @Test
    @DisplayName("Database schema - allows duplicate names at root level")
    fun testDatabaseSchema_allowsDuplicateNamesAtRootLevel() {
        // Note: MariaDB treats NULL parent_id values as distinct in unique constraints
        // This is the expected behavior - application-level validation handles root uniqueness

        // When: Creating two root workgroups with same name
        val root1 = workgroupRepository.save(Workgroup(name = "IT", parent = null))
        val root2 = workgroupRepository.save(Workgroup(name = "IT", parent = null))

        // Then: Both are saved (database allows it, application validation prevents it)
        assertNotNull(root1.id)
        assertNotNull(root2.id)
    }

    // ===== Hierarchy Query Tests (Recursive CTEs) =====

    @Test
    @DisplayName("Recursive CTE - findByParent returns direct children only")
    fun testRecursiveCTE_findByParent() {
        // Given: A hierarchy with 3 levels
        val level1 = workgroupRepository.save(Workgroup(name = "Level 1"))
        val level2a = workgroupRepository.save(Workgroup(name = "Level 2a", parent = level1))
        val level2b = workgroupRepository.save(Workgroup(name = "Level 2b", parent = level1))
        val level3 = workgroupRepository.save(Workgroup(name = "Level 3", parent = level2a))

        // When: Getting direct children of level1
        val children = workgroupRepository.findByParent(level1)

        // Then: Only direct children returned (not grandchildren)
        assertEquals(2, children.size)
        assertTrue(children.any { it.name == "Level 2a" })
        assertTrue(children.any { it.name == "Level 2b" })
        assertFalse(children.any { it.name == "Level 3" })
    }

    @Test
    @DisplayName("Recursive CTE - findRootLevelWorkgroups returns only roots")
    fun testRecursiveCTE_findRootLevelWorkgroups() {
        // Given: A hierarchy with roots and children
        val root1 = workgroupRepository.save(Workgroup(name = "Root 1"))
        val root2 = workgroupRepository.save(Workgroup(name = "Root 2"))
        workgroupRepository.save(Workgroup(name = "Child of Root 1", parent = root1))
        workgroupRepository.save(Workgroup(name = "Child of Root 2", parent = root2))

        // When: Getting root-level workgroups
        val roots = workgroupRepository.findRootLevelWorkgroups()

        // Then: Only root workgroups returned
        assertEquals(2, roots.size)
        assertTrue(roots.any { it.name == "Root 1" })
        assertTrue(roots.any { it.name == "Root 2" })
        assertFalse(roots.any { it.name?.contains("Child") == true })
    }

    @Test
    @DisplayName("Recursive CTE - findAllDescendants returns entire subtree")
    fun testRecursiveCTE_findAllDescendants() {
        // Given: A hierarchy with 4 levels
        val level1 = workgroupRepository.save(Workgroup(name = "Level 1"))
        val level2 = workgroupRepository.save(Workgroup(name = "Level 2", parent = level1))
        val level3a = workgroupRepository.save(Workgroup(name = "Level 3a", parent = level2))
        val level3b = workgroupRepository.save(Workgroup(name = "Level 3b", parent = level2))
        val level4 = workgroupRepository.save(Workgroup(name = "Level 4", parent = level3a))

        // Create separate tree to ensure we don't get unrelated workgroups
        val otherRoot = workgroupRepository.save(Workgroup(name = "Other Root"))
        workgroupRepository.save(Workgroup(name = "Other Child", parent = otherRoot))

        // When: Getting all descendants of level1
        val descendants = workgroupRepository.findAllDescendants(level1.id!!)

        // Then: All descendants in subtree returned (including root)
        assertEquals(5, descendants.size) // level1 + level2 + level3a + level3b + level4
        assertTrue(descendants.any { it.name == "Level 1" })
        assertTrue(descendants.any { it.name == "Level 2" })
        assertTrue(descendants.any { it.name == "Level 3a" })
        assertTrue(descendants.any { it.name == "Level 3b" })
        assertTrue(descendants.any { it.name == "Level 4" })
        assertFalse(descendants.any { it.name == "Other Root" })
        assertFalse(descendants.any { it.name == "Other Child" })
    }

    @Test
    @DisplayName("Recursive CTE - findAllAncestors returns path to root")
    fun testRecursiveCTE_findAllAncestors() {
        // Given: A hierarchy with 4 levels
        val level1 = workgroupRepository.save(Workgroup(name = "Level 1"))
        val level2 = workgroupRepository.save(Workgroup(name = "Level 2", parent = level1))
        val level3 = workgroupRepository.save(Workgroup(name = "Level 3", parent = level2))
        val level4 = workgroupRepository.save(Workgroup(name = "Level 4", parent = level3))

        // When: Getting ancestors of level4
        val ancestors = workgroupRepository.findAllAncestors(level4.id!!)

        // Then: All ancestors returned (excluding self)
        assertEquals(3, ancestors.size) // level1, level2, level3
        assertTrue(ancestors.any { it.name == "Level 1" })
        assertTrue(ancestors.any { it.name == "Level 2" })
        assertTrue(ancestors.any { it.name == "Level 3" })
        assertFalse(ancestors.any { it.name == "Level 4" }) // Self not included
    }

    @Test
    @DisplayName("Recursive CTE - countDescendants returns correct count")
    fun testRecursiveCTE_countDescendants() {
        // Given: A hierarchy with branching
        val root = workgroupRepository.save(Workgroup(name = "Root"))
        val child1 = workgroupRepository.save(Workgroup(name = "Child 1", parent = root))
        val child2 = workgroupRepository.save(Workgroup(name = "Child 2", parent = root))
        workgroupRepository.save(Workgroup(name = "Grandchild 1a", parent = child1))
        workgroupRepository.save(Workgroup(name = "Grandchild 1b", parent = child1))
        workgroupRepository.save(Workgroup(name = "Grandchild 2a", parent = child2))

        // When: Counting descendants of root
        val count = workgroupRepository.countDescendants(root.id!!)

        // Then: Count excludes root itself
        assertEquals(5L, count) // 2 children + 3 grandchildren
    }

    // ===== Entity Helper Method Tests =====

    @Test
    @DisplayName("Entity helper - calculateDepth works correctly")
    fun testEntityHelper_calculateDepth() {
        // Given: A hierarchy with 5 levels
        val level1 = workgroupRepository.save(Workgroup(name = "L1"))
        val level2 = workgroupRepository.save(Workgroup(name = "L2", parent = level1))
        val level3 = workgroupRepository.save(Workgroup(name = "L3", parent = level2))
        val level4 = workgroupRepository.save(Workgroup(name = "L4", parent = level3))
        val level5 = workgroupRepository.save(Workgroup(name = "L5", parent = level4))

        // When: Calculating depth for each level
        val depth1 = level1.calculateDepth()
        val depth2 = level2.calculateDepth()
        val depth3 = level3.calculateDepth()
        val depth4 = level4.calculateDepth()
        val depth5 = level5.calculateDepth()

        // Then: Depths are correct (root = 1)
        assertEquals(1, depth1)
        assertEquals(2, depth2)
        assertEquals(3, depth3)
        assertEquals(4, depth4)
        assertEquals(5, depth5)
    }

    @Test
    @DisplayName("Entity helper - getAncestors returns correct path")
    fun testEntityHelper_getAncestors() {
        // Given: A hierarchy
        val level1 = workgroupRepository.save(Workgroup(name = "L1"))
        val level2 = workgroupRepository.save(Workgroup(name = "L2", parent = level1))
        val level3 = workgroupRepository.save(Workgroup(name = "L3", parent = level2))

        // When: Getting ancestors of level3
        val ancestors = level3.getAncestors()

        // Then: Ancestors in root-to-parent order
        assertEquals(2, ancestors.size)
        assertEquals("L1", ancestors[0].name)
        assertEquals("L2", ancestors[1].name)
    }

    @Test
    @DisplayName("Entity helper - isDescendantOf detects descendant relationship")
    fun testEntityHelper_isDescendantOf() {
        // Given: A hierarchy
        val grandparent = workgroupRepository.save(Workgroup(name = "Grandparent"))
        val parent = workgroupRepository.save(Workgroup(name = "Parent", parent = grandparent))
        val child = workgroupRepository.save(Workgroup(name = "Child", parent = parent))
        val unrelated = workgroupRepository.save(Workgroup(name = "Unrelated"))

        // When/Then: Descendant relationships detected
        assertTrue(child.isDescendantOf(parent))
        assertTrue(child.isDescendantOf(grandparent))
        assertFalse(child.isDescendantOf(unrelated))
        assertFalse(parent.isDescendantOf(child))
        assertFalse(grandparent.isDescendantOf(parent))
    }

    // ===== Optimistic Locking Tests =====

    @Test
    @DisplayName("Optimistic locking - version increments on update")
    fun testOptimisticLocking_versionIncrements() {
        // Given: A saved workgroup
        val workgroup = workgroupRepository.save(Workgroup(name = "Test"))
        assertEquals(0L, workgroup.version)

        // When: Updating multiple times
        workgroup.description = "Update 1"
        val updated1 = workgroupRepository.update(workgroup)
        assertEquals(1L, updated1.version)

        updated1.description = "Update 2"
        val updated2 = workgroupRepository.update(updated1)
        assertEquals(2L, updated2.version)
    }
}
