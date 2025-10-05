package com.secman.contract

import com.secman.domain.User
import com.secman.repository.UserRepository
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
 * Contract test for GET /api/workgroups/{id} endpoint
 * Feature: 008-create-an-additional
 * Expected to FAIL until WorkgroupController is implemented
 */
@MicronautTest
class WorkgroupGetContractTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    private val passwordEncoder = BCryptPasswordEncoder()
    private lateinit var adminToken: String

    @BeforeEach
    fun setup() {
        val adminUser = User(
            username = "admin_get_test",
            email = "admin_get@test.com",
            passwordHash = passwordEncoder.encode("admin123"),
            roles = mutableSetOf(User.Role.ADMIN)
        )
        userRepository.save(adminUser)

        val credentials = UsernamePasswordCredentials("admin_get_test", "admin123")
        val loginResponse = client.toBlocking().exchange(
            HttpRequest.POST("/api/auth/login", credentials),
            Map::class.java
        )
        adminToken = (loginResponse.body() as Map<*, *>)["token"] as String
    }

    @Test
    fun `test returns workgroup details by ID`() {
        // Given: A workgroup exists
        val createRequest = mapOf("name" to "Security Team", "description" to "Security professionals")
        val createResponse = client.toBlocking().exchange(
            HttpRequest.POST("/api/workgroups", createRequest).bearerAuth(adminToken),
            Map::class.java
        )
        val workgroupId = (createResponse.body() as Map<*, *>)["id"]

        // When: GET /api/workgroups/{id}
        val httpRequest = HttpRequest.GET<Any>("/api/workgroups/$workgroupId")
            .bearerAuth(adminToken)

        // Then: Expect 200 OK with workgroup details including counts
        // EXPECTED TO FAIL: WorkgroupController not implemented
        val response = client.toBlocking().exchange(httpRequest, Map::class.java)

        assertEquals(HttpStatus.OK, response.status)
        val body = response.body() as Map<*, *>
        assertEquals(workgroupId, body["id"])
        assertEquals("Security Team", body["name"])
        assertEquals("Security professionals", body["description"])
        assertNotNull(body["userCount"])
        assertNotNull(body["assetCount"])
    }

    @Test
    fun `test returns 404 for non-existent workgroup`() {
        // Given: A non-existent workgroup ID
        val nonExistentId = 99999L

        // When: GET /api/workgroups/{id}
        val httpRequest = HttpRequest.GET<Any>("/api/workgroups/$nonExistentId")
            .bearerAuth(adminToken)

        // Then: Expect 404 Not Found
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, Map::class.java)
        }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    @Test
    fun `test requires ADMIN role`() {
        // Given: A workgroup and a regular user
        val createRequest = mapOf("name" to "Test Workgroup")
        val createResponse = client.toBlocking().exchange(
            HttpRequest.POST("/api/workgroups", createRequest).bearerAuth(adminToken),
            Map::class.java
        )
        val workgroupId = (createResponse.body() as Map<*, *>)["id"]

        val regularUser = User(
            username = "regular_get_user",
            email = "user_get@test.com",
            passwordHash = passwordEncoder.encode("user123"),
            roles = mutableSetOf(User.Role.USER)
        )
        userRepository.save(regularUser)

        val credentials = UsernamePasswordCredentials("regular_get_user", "user123")
        val loginResponse = client.toBlocking().exchange(
            HttpRequest.POST("/api/auth/login", credentials),
            Map::class.java
        )
        val userToken = (loginResponse.body() as Map<*, *>)["token"] as String

        // When: Regular user attempts to get workgroup
        val httpRequest = HttpRequest.GET<Any>("/api/workgroups/$workgroupId")
            .bearerAuth(userToken)

        // Then: Expect 403 Forbidden
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, Map::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }
}
