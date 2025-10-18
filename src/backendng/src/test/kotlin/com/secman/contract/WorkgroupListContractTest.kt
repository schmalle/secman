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
 * Contract test for GET /api/workgroups endpoint
 * Feature: 008-create-an-additional (Workgroup-Based Access Control)
 *
 * Tests the workgroup list endpoint per contract: workgroup-crud.yaml
 * Expected to FAIL until WorkgroupController is implemented
 */
@MicronautTest
class WorkgroupListContractTest {

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
            username = "admin_list_test",
            email = "admin_list@test.com",
            passwordHash = passwordEncoder.encode("admin123"),
            roles = mutableSetOf(User.Role.ADMIN)
        )
        userRepository.save(adminUser)

        val credentials = UsernamePasswordCredentials("admin_list_test", "admin123")
        val loginResponse = client.toBlocking().exchange(
            HttpRequest.POST("/api/auth/login", credentials),
            Map::class.java
        )
        adminToken = (loginResponse.body() as Map<*, *>)["token"] as String
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `test returns list of all workgroups`() {
        // Given: Multiple workgroups exist
        val workgroup1 = mapOf("name" to "Engineering", "description" to "Engineering team")
        val workgroup2 = mapOf("name" to "DevOps", "description" to "DevOps team")

        client.toBlocking().exchange(
            HttpRequest.POST("/api/workgroups", workgroup1).bearerAuth(adminToken),
            Map::class.java
        )
        client.toBlocking().exchange(
            HttpRequest.POST("/api/workgroups", workgroup2).bearerAuth(adminToken),
            Map::class.java
        )

        // When: GET /api/workgroups
        val httpRequest = HttpRequest.GET<Any>("/api/workgroups")
            .bearerAuth(adminToken)

        // Then: Expect 200 OK with array of workgroups
        // EXPECTED TO FAIL: WorkgroupController not implemented yet
        val response = client.toBlocking().exchange(httpRequest, List::class.java)

        assertEquals(HttpStatus.OK, response.status)
        val workgroups = response.body() as List<Map<*, *>>
        assertTrue(workgroups.size >= 2)
        assertTrue(workgroups.any { it["name"] == "Engineering" })
        assertTrue(workgroups.any { it["name"] == "DevOps" })
    }

    @Test
    fun `test requires ADMIN role`() {
        // Given: A regular user
        val regularUser = User(
            username = "regular_list_user",
            email = "user_list@test.com",
            passwordHash = passwordEncoder.encode("user123"),
            roles = mutableSetOf(User.Role.USER)
        )
        userRepository.save(regularUser)

        val credentials = UsernamePasswordCredentials("regular_list_user", "user123")
        val loginResponse = client.toBlocking().exchange(
            HttpRequest.POST("/api/auth/login", credentials),
            Map::class.java
        )
        val userToken = (loginResponse.body() as Map<*, *>)["token"] as String

        // When: Regular user attempts to list workgroups
        val httpRequest = HttpRequest.GET<Any>("/api/workgroups")
            .bearerAuth(userToken)

        // Then: Expect 403 Forbidden
        // EXPECTED TO FAIL: @Secured("ADMIN") not implemented yet
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, List::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    @Test
    fun `test returns 401 for unauthenticated requests`() {
        // Given: No authentication token
        val httpRequest = HttpRequest.GET<Any>("/api/workgroups")

        // When: GET /api/workgroups without token
        // Then: Expect 401 Unauthorized
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(httpRequest, List::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }
}
