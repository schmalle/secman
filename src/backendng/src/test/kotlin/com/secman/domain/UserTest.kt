package com.secman.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for User entity
 * Feature: 025-role-based-access-control
 *
 * Tests role enum values, helper methods, and role-based behavior
 * TDD approach: Tests written BEFORE implementation (Phase 2 Foundation)
 */
class UserTest {

    private fun createTestUser(
        username: String = "testuser",
        email: String = "test@example.com",
        roles: MutableSet<User.Role> = mutableSetOf(User.Role.USER)
    ): User {
        return User(
            username = username,
            email = email,
            passwordHash = "hashedPassword123",
            roles = roles
        )
    }

    // T005: Test for RISK role in enum
    @Test
    fun `RISK role should exist in User Role enum`() {
        // This test will FAIL initially (Red phase of TDD)
        // RISK role doesn't exist yet - will be added in T014
        val riskRole = User.Role.valueOf("RISK")
        assertNotNull(riskRole)
        assertEquals("RISK", riskRole.name)
    }

    // T006: Test for SECCHAMPION role in enum
    @Test
    fun `SECCHAMPION role should exist in User Role enum`() {
        // This test will FAIL initially (Red phase of TDD)
        // SECCHAMPION role doesn't exist yet - CHAMPION will be renamed in T014
        val secChampionRole = User.Role.valueOf("SECCHAMPION")
        assertNotNull(secChampionRole)
        assertEquals("SECCHAMPION", secChampionRole.name)
    }

    // T007: Test for hasRole() method
    @Test
    fun `hasRole should return true when user has the specified role`() {
        val user = createTestUser(roles = mutableSetOf(User.Role.USER, User.Role.ADMIN))

        assertTrue(user.hasRole(User.Role.USER))
        assertTrue(user.hasRole(User.Role.ADMIN))
        assertFalse(user.hasRole(User.Role.VULN))
    }

    @Test
    fun `hasRole should return false when user does not have the specified role`() {
        val user = createTestUser(roles = mutableSetOf(User.Role.USER))

        assertFalse(user.hasRole(User.Role.ADMIN))
        assertFalse(user.hasRole(User.Role.VULN))
        assertFalse(user.hasRole(User.Role.RELEASE_MANAGER))
    }

    // T008: Test for isRisk() helper method
    @Test
    fun `isRisk should return true when user has RISK role`() {
        // This test will FAIL initially - isRisk() method doesn't exist yet
        val user = createTestUser(roles = mutableSetOf(User.Role.RISK))
        assertTrue(user.isRisk())
    }

    @Test
    fun `isRisk should return false when user does not have RISK role`() {
        // This test will FAIL initially - isRisk() method doesn't exist yet
        val user = createTestUser(roles = mutableSetOf(User.Role.USER))
        assertFalse(user.isRisk())
    }

    @Test
    fun `isRisk should return true when user has both RISK and other roles`() {
        // This test will FAIL initially - isRisk() method doesn't exist yet
        val user = createTestUser(roles = mutableSetOf(User.Role.USER, User.Role.RISK, User.Role.REQ))
        assertTrue(user.isRisk())
    }

    // T009: Test for isReq() helper method
    @Test
    fun `isReq should return true when user has REQ role`() {
        // This test will FAIL initially - isReq() method doesn't exist yet
        val user = createTestUser(roles = mutableSetOf(User.Role.REQ))
        assertTrue(user.isReq())
    }

    @Test
    fun `isReq should return false when user does not have REQ role`() {
        // This test will FAIL initially - isReq() method doesn't exist yet
        val user = createTestUser(roles = mutableSetOf(User.Role.USER))
        assertFalse(user.isReq())
    }

    @Test
    fun `isReq should return true when user has both REQ and other roles`() {
        // This test will FAIL initially - isReq() method doesn't exist yet
        val user = createTestUser(roles = mutableSetOf(User.Role.USER, User.Role.REQ, User.Role.RISK))
        assertTrue(user.isReq())
    }

    // T010: Test for isSecChampion() helper method
    @Test
    fun `isSecChampion should return true when user has SECCHAMPION role`() {
        // This test will FAIL initially - isSecChampion() method doesn't exist yet
        val user = createTestUser(roles = mutableSetOf(User.Role.SECCHAMPION))
        assertTrue(user.isSecChampion())
    }

    @Test
    fun `isSecChampion should return false when user does not have SECCHAMPION role`() {
        // This test will FAIL initially - isSecChampion() method doesn't exist yet
        val user = createTestUser(roles = mutableSetOf(User.Role.USER))
        assertFalse(user.isSecChampion())
    }

    @Test
    fun `isSecChampion should return true when user has both SECCHAMPION and other roles`() {
        // This test will FAIL initially - isSecChampion() method doesn't exist yet
        val user = createTestUser(roles = mutableSetOf(User.Role.USER, User.Role.SECCHAMPION, User.Role.VULN))
        assertTrue(user.isSecChampion())
    }

    // T013: Test for User.Role enum completeness
    @Test
    fun `User Role enum should contain all expected roles`() {
        // After implementation, enum should have exactly these roles:
        // USER, ADMIN, VULN, RELEASE_MANAGER, REQ, RISK, SECCHAMPION
        val expectedRoles = setOf(
            "USER",
            "ADMIN",
            "VULN",
            "RELEASE_MANAGER",
            "REQ",
            "RISK",
            "SECCHAMPION"
        )

        val actualRoles = User.Role.values().map { it.name }.toSet()

        // This will FAIL initially - RISK and SECCHAMPION don't exist yet
        // CHAMPION will be renamed to SECCHAMPION in T014
        assertEquals(expectedRoles, actualRoles,
            "Role enum should contain exactly: ${expectedRoles.sorted()}, but found: ${actualRoles.sorted()}")
    }

    @Test
    fun `User Role enum should NOT contain deprecated CHAMPION role`() {
        // After migration, CHAMPION should not exist (renamed to SECCHAMPION)
        // This test will FAIL initially - CHAMPION still exists
        val roleNames = User.Role.values().map { it.name }
        assertFalse(roleNames.contains("CHAMPION"),
            "CHAMPION role should be renamed to SECCHAMPION")
    }

    // Additional tests for existing functionality
    @Test
    fun `isAdmin should return true when user has ADMIN role`() {
        val user = createTestUser(roles = mutableSetOf(User.Role.ADMIN))
        assertTrue(user.isAdmin())
    }

    @Test
    fun `isAdmin should return false when user does not have ADMIN role`() {
        val user = createTestUser(roles = mutableSetOf(User.Role.USER))
        assertFalse(user.isAdmin())
    }

    @Test
    fun `user should have default USER role when created without explicit roles`() {
        val user = User(
            username = "newuser",
            email = "newuser@example.com",
            passwordHash = "hash123"
        )

        assertEquals(1, user.roles.size)
        assertTrue(user.hasRole(User.Role.USER))
    }

    @Test
    fun `user can have multiple roles simultaneously`() {
        val user = createTestUser(
            roles = mutableSetOf(
                User.Role.USER,
                User.Role.ADMIN,
                User.Role.VULN,
                User.Role.RELEASE_MANAGER,
                User.Role.REQ
            )
        )

        assertEquals(5, user.roles.size)
        assertTrue(user.hasRole(User.Role.USER))
        assertTrue(user.hasRole(User.Role.ADMIN))
        assertTrue(user.hasRole(User.Role.VULN))
        assertTrue(user.hasRole(User.Role.RELEASE_MANAGER))
        assertTrue(user.hasRole(User.Role.REQ))
    }

    @Test
    fun `roles can be added and removed dynamically`() {
        val user = createTestUser(roles = mutableSetOf(User.Role.USER))

        // Add role
        user.roles.add(User.Role.ADMIN)
        assertTrue(user.hasRole(User.Role.ADMIN))
        assertEquals(2, user.roles.size)

        // Remove role
        user.roles.remove(User.Role.USER)
        assertFalse(user.hasRole(User.Role.USER))
        assertEquals(1, user.roles.size)
    }

    @Test
    fun `user equality should be based on id`() {
        val user1 = createTestUser(username = "user1").apply { id = 1L }
        val user2 = createTestUser(username = "user2").apply { id = 1L }
        val user3 = createTestUser(username = "user1").apply { id = 2L }

        assertEquals(user1, user2, "Users with same id should be equal")
        assertNotEquals(user1, user3, "Users with different ids should not be equal")
    }

    @Test
    fun `user toString should include id, username, email, and roles`() {
        val user = createTestUser(
            username = "testuser",
            roles = mutableSetOf(User.Role.USER, User.Role.ADMIN)
        ).apply { id = 42L }

        val toString = user.toString()
        assertTrue(toString.contains("id=42"))
        assertTrue(toString.contains("username='testuser'"))
        assertTrue(toString.contains("email='test@example.com'"))
        assertTrue(toString.contains("roles="))
    }
}
