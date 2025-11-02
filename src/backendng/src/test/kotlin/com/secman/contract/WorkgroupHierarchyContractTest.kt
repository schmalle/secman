package com.secman.contract

import com.secman.domain.User
import com.secman.domain.Workgroup
import com.secman.repository.UserRepository
import com.secman.repository.WorkgroupRepository
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.security.authentication.UsernamePasswordCredentials
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

/**
 * Contract tests for nested workgroups hierarchy endpoints
 * Feature 040: Nested Workgroups
 *
 * Tests endpoints per contract: contracts/api.yaml
 * - POST /api/workgroups/{id}/children - Create child workgroup
 * - GET /api/workgroups/{id}/children - Get direct children
 * - PUT /api/workgroups/{id}/parent - Move workgroup
 * - DELETE /api/workgroups/{id} - Delete with promotion
 * - GET /api/workgroups/{id}/ancestors - Get ancestor path
 * - GET /api/workgroups/{id}/descendants - Get subtree
 * - GET /api/workgroups/root - Get root-level workgroups
 */
@MicronautTest(transactional = false)
class WorkgroupHierarchyContractTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var workgroupRepository: WorkgroupRepository

    private val passwordEncoder = BCryptPasswordEncoder()
    private lateinit var adminToken: String
    private lateinit var userToken: String

    @BeforeEach
    fun setup() {
        // Clean up test data
        workgroupRepository.deleteAll()
        userRepository.deleteAll()

        // Create admin user for testing
        val adminUser = User(
            username = "admin_hierarchy_test",
            email = "admin_hierarchy@test.com",
            passwordHash = passwordEncoder.encode("admin123"),
            roles = mutableSetOf(User.Role.ADMIN)
        )
        userRepository.save(adminUser)

        // Create regular user for testing
        val regularUser = User(
            username = "user_hierarchy_test",
            email = "user_hierarchy@test.com",
            passwordHash = passwordEncoder.encode("user123"),
            roles = mutableSetOf(User.Role.USER)
        )
        userRepository.save(regularUser)

        // Get JWT tokens
        val adminCreds = UsernamePasswordCredentials("admin_hierarchy_test", "admin123")
        val adminLoginResponse = client.toBlocking().exchange(
            HttpRequest.POST("/api/auth/login", adminCreds),
            Map::class.java
        )
        adminToken = (adminLoginResponse.body() as Map<*, *>)["token"] as String

        val userCreds = UsernamePasswordCredentials("user_hierarchy_test", "user123")
        val userLoginResponse = client.toBlocking().exchange(
            HttpRequest.POST("/api/auth/login", userCreds),
            Map::class.java
        )
        userToken = (userLoginResponse.body() as Map<*, *>)["token"] as String
    }

    // ===== POST /api/workgroups/{id}/children Tests =====

    @Test
    fun `POST children - creates child workgroup successfully`() {
        // Given: A parent workgroup
        val parent = workgroupRepository.save(Workgroup(name = "IT Department"))

        // When: Creating a child workgroup
        val request = mapOf(
            "name" to "Engineering",
            "description" to "Engineering team"
        )
        val httpRequest = HttpRequest.POST("/api/workgroups/${parent.id}/children", request)
            .bearerAuth(adminToken)

        // Then: Expect 201 Created with child workgroup
        val response = client.toBlocking().exchange(httpRequest, Map::class.java)

        assertEquals(HttpStatus.CREATED, response.status)
        val body = response.body() as Map<*, *>
        assertNotNull(body["id"])
        assertEquals("Engineering", body["name"])
        assertEquals("Engineering team", body["description"])
        assertEquals(parent.id, (body["parentId"] as Number).toLong())
        assertEquals(2, body["depth"]) // Parent is depth 1, child is depth 2
        assertNotNull(body["createdAt"])
        assertNotNull(body["version"])
    }

    @Test
    fun `POST children - rejects blank name`() {
        // Given: A parent workgroup
        val parent = workgroupRepository.save(Workgroup(name = "IT Department"))

        // When: Creating child with blank name
        val request = mapOf("name" to "   ")
        val httpRequest = HttpRequest.POST("/api/workgroups/${parent.id}/children", request)
            .bearerAuth(adminToken)

        // Then: Expect 400 Bad Request
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, Map::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    fun `POST children - rejects when parent at maximum depth`() {
        // Given: A hierarchy at maximum depth (5 levels)
        val level1 = workgroupRepository.save(Workgroup(name = "Level 1"))
        val level2 = workgroupRepository.save(Workgroup(name = "Level 2", parent = level1))
        val level3 = workgroupRepository.save(Workgroup(name = "Level 3", parent = level2))
        val level4 = workgroupRepository.save(Workgroup(name = "Level 4", parent = level3))
        val level5 = workgroupRepository.save(Workgroup(name = "Level 5", parent = level4))

        // When: Attempting to create child under level 5
        val request = mapOf("name" to "Level 6 - Should Fail")
        val httpRequest = HttpRequest.POST("/api/workgroups/${level5.id}/children", request)
            .bearerAuth(adminToken)

        // Then: Expect 400 Bad Request with depth limit error
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, Map::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        val errorBody = exception.response.getBody(Map::class.java).get()
        assertTrue((errorBody["error"] as String).contains("maximum depth", ignoreCase = true))
    }

    @Test
    fun `POST children - rejects duplicate sibling name`() {
        // Given: A parent with existing child "Engineering"
        val parent = workgroupRepository.save(Workgroup(name = "IT Department"))
        workgroupRepository.save(Workgroup(name = "Engineering", parent = parent))

        // When: Attempting to create another child named "Engineering"
        val request = mapOf("name" to "Engineering")
        val httpRequest = HttpRequest.POST("/api/workgroups/${parent.id}/children", request)
            .bearerAuth(adminToken)

        // Then: Expect 400 Bad Request with sibling conflict error
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, Map::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        val errorBody = exception.response.getBody(Map::class.java).get()
        assertTrue((errorBody["error"] as String).contains("already exists", ignoreCase = true))
    }

    @Test
    fun `POST children - requires ADMIN role`() {
        // Given: A parent workgroup
        val parent = workgroupRepository.save(Workgroup(name = "IT Department"))

        // When: Regular user attempts to create child
        val request = mapOf("name" to "Engineering")
        val httpRequest = HttpRequest.POST("/api/workgroups/${parent.id}/children", request)
            .bearerAuth(userToken)

        // Then: Expect 403 Forbidden
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, Map::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    @Test
    fun `POST children - requires authentication`() {
        // Given: A parent workgroup
        val parent = workgroupRepository.save(Workgroup(name = "IT Department"))

        // When: Creating child without authentication
        val request = mapOf("name" to "Engineering")
        val httpRequest = HttpRequest.POST("/api/workgroups/${parent.id}/children", request)

        // Then: Expect 401 Unauthorized
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, Map::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    fun `POST children - returns 404 when parent not found`() {
        // When: Creating child for non-existent parent
        val request = mapOf("name" to "Engineering")
        val httpRequest = HttpRequest.POST("/api/workgroups/99999/children", request)
            .bearerAuth(adminToken)

        // Then: Expect 404 Not Found
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, Map::class.java)
        }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    // ===== GET /api/workgroups/{id}/children Tests =====

    @Test
    fun `GET children - returns direct children only`() {
        // Given: A parent with 2 children and 1 grandchild
        val parent = workgroupRepository.save(Workgroup(name = "IT Department"))
        val child1 = workgroupRepository.save(Workgroup(name = "Engineering", parent = parent))
        val child2 = workgroupRepository.save(Workgroup(name = "Support", parent = parent))
        workgroupRepository.save(Workgroup(name = "Backend Team", parent = child1)) // grandchild

        // When: Getting children of parent
        val httpRequest = HttpRequest.GET<Any>("/api/workgroups/${parent.id}/children")
            .bearerAuth(adminToken)

        // Then: Expect 200 OK with only direct children (not grandchild)
        val response = client.toBlocking().exchange(httpRequest, List::class.java)

        assertEquals(HttpStatus.OK, response.status)
        val children = response.body() as List<Map<*, *>>
        assertEquals(2, children.size)

        val names = children.map { it["name"] as String }.toSet()
        assertTrue(names.contains("Engineering"))
        assertTrue(names.contains("Support"))
        assertFalse(names.contains("Backend Team")) // grandchild not included
    }

    @Test
    fun `GET children - returns empty list when no children`() {
        // Given: A workgroup with no children
        val parent = workgroupRepository.save(Workgroup(name = "IT Department"))

        // When: Getting children
        val httpRequest = HttpRequest.GET<Any>("/api/workgroups/${parent.id}/children")
            .bearerAuth(adminToken)

        // Then: Expect 200 OK with empty list
        val response = client.toBlocking().exchange(httpRequest, List::class.java)

        assertEquals(HttpStatus.OK, response.status)
        val children = response.body() as List<*>
        assertEquals(0, children.size)
    }

    @Test
    fun `GET children - requires authentication`() {
        // Given: A parent workgroup
        val parent = workgroupRepository.save(Workgroup(name = "IT Department"))

        // When: Getting children without authentication
        val httpRequest = HttpRequest.GET<Any>("/api/workgroups/${parent.id}/children")

        // Then: Expect 401 Unauthorized
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, List::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    @Test
    fun `GET children - returns 404 when parent not found`() {
        // When: Getting children of non-existent workgroup
        val httpRequest = HttpRequest.GET<Any>("/api/workgroups/99999/children")
            .bearerAuth(adminToken)

        // Then: Expect 404 Not Found
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, List::class.java)
        }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }
}
