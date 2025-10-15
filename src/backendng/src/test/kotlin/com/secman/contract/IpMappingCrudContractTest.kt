package com.secman.contract

import com.secman.fixtures.IpMappingTestFixtures
import com.secman.repository.UserMappingRepository
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Contract tests for IP Mapping CRUD operations
 *
 * Tests API contract compliance for Feature 020 (IP Address Mapping):
 * - POST /api/user-mappings (create with IP address)
 * - GET /api/user-mappings (list including IP mappings)
 * - GET /api/user-mappings/{id} (get single mapping)
 * - PUT /api/user-mappings/{id} (update IP mapping)
 * - DELETE /api/user-mappings/{id} (delete mapping)
 *
 * TDD PHASE: RED - These tests MUST FAIL before implementation exists.
 *
 * Feature: User Story 1 (P1) - Map Single IP Address to User
 */
@MicronautTest
@Transactional
class IpMappingCrudContractTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userMappingRepository: UserMappingRepository

    private val adminToken = "mock-admin-jwt-token"

    @BeforeEach
    fun setup() {
        userMappingRepository.deleteAll()
    }

    // ========== POST /api/user-mappings (Create) ==========

    @Test
    @DisplayName("POST /api/user-mappings with single IP returns 201 Created")
    fun testCreateIpMappingSingleIp() {
        // Arrange
        val requestBody = mapOf(
            "email" to "user@example.com",
            "ipAddress" to "192.168.1.100",
            "domain" to "example.com"
        )

        val request = HttpRequest.POST("/api/user-mappings", requestBody)
            .header("Authorization", "Bearer $adminToken")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert
        assertEquals(HttpStatus.CREATED, response.status, "Expected 201 Created")

        val body = response.body() as Map<*, *>
        assertTrue(body.containsKey("id"), "Response must contain 'id'")
        assertEquals("user@example.com", body["email"])
        assertEquals("192.168.1.100", body["ipAddress"])
        assertEquals("SINGLE", body["ipRangeType"])
        assertEquals(1, (body["ipCount"] as Number).toInt())
        assertEquals("example.com", body["domain"])
        assertTrue(body.containsKey("createdAt"))
        assertTrue(body.containsKey("updatedAt"))
    }

    @Test
    @DisplayName("POST /api/user-mappings with CIDR range returns 201 Created")
    fun testCreateIpMappingCidr() {
        // Arrange
        val requestBody = mapOf(
            "email" to "user@example.com",
            "ipAddress" to "10.0.0.0/24",
            "domain" to "example.com"
        )

        val request = HttpRequest.POST("/api/user-mappings", requestBody)
            .header("Authorization", "Bearer $adminToken")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert
        assertEquals(HttpStatus.CREATED, response.status)

        val body = response.body() as Map<*, *>
        assertEquals("10.0.0.0/24", body["ipAddress"])
        assertEquals("CIDR", body["ipRangeType"])
        assertEquals(256, (body["ipCount"] as Number).toInt(), "CIDR /24 should have 256 IPs")
    }

    @Test
    @DisplayName("POST /api/user-mappings with dash range returns 201 Created")
    fun testCreateIpMappingDashRange() {
        // Arrange
        val requestBody = mapOf(
            "email" to "user@example.com",
            "ipAddress" to "172.16.0.1-172.16.0.100",
            "domain" to "example.com"
        )

        val request = HttpRequest.POST("/api/user-mappings", requestBody)
            .header("Authorization", "Bearer $adminToken")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert
        assertEquals(HttpStatus.CREATED, response.status)

        val body = response.body() as Map<*, *>
        assertEquals("172.16.0.1-172.16.0.100", body["ipAddress"])
        assertEquals("DASH_RANGE", body["ipRangeType"])
        assertEquals(100, (body["ipCount"] as Number).toInt())
    }

    @Test
    @DisplayName("POST /api/user-mappings with both AWS account and IP returns 201 Created")
    fun testCreateCombinedMapping() {
        // Arrange
        val requestBody = mapOf(
            "email" to "user@example.com",
            "awsAccountId" to "123456789012",
            "ipAddress" to "192.168.1.100",
            "domain" to "example.com"
        )

        val request = HttpRequest.POST("/api/user-mappings", requestBody)
            .header("Authorization", "Bearer $adminToken")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert
        assertEquals(HttpStatus.CREATED, response.status)

        val body = response.body() as Map<*, *>
        assertEquals("123456789012", body["awsAccountId"])
        assertEquals("192.168.1.100", body["ipAddress"])
    }

    @Test
    @DisplayName("POST /api/user-mappings with invalid IP format returns 400 Bad Request")
    fun testCreateIpMappingInvalidFormat() {
        // Arrange
        val requestBody = mapOf(
            "email" to "user@example.com",
            "ipAddress" to "999.999.999.999",
            "domain" to "example.com"
        )

        val request = HttpRequest.POST("/api/user-mappings", requestBody)
            .header("Authorization", "Bearer $adminToken")

        // Act & Assert
        try {
            client.toBlocking().exchange(request, Map::class.java)
            fail("Expected 400 Bad Request for invalid IP format")
        } catch (e: HttpClientResponseException) {
            assertEquals(HttpStatus.BAD_REQUEST, e.status)
            assertTrue(e.message!!.contains("Invalid IP") || e.message!!.contains("Validation"))
        }
    }

    @Test
    @DisplayName("POST /api/user-mappings with duplicate IP mapping returns 400 Bad Request")
    fun testCreateDuplicateIpMapping() {
        // Arrange: Create first mapping
        userMappingRepository.save(
            IpMappingTestFixtures.createSingleIpMapping(
                email = "user@example.com",
                ipAddress = "192.168.1.100",
                domain = "example.com"
            )
        )

        // Try to create duplicate
        val requestBody = mapOf(
            "email" to "user@example.com",
            "ipAddress" to "192.168.1.100",
            "domain" to "example.com"
        )

        val request = HttpRequest.POST("/api/user-mappings", requestBody)
            .header("Authorization", "Bearer $adminToken")

        // Act & Assert
        try {
            client.toBlocking().exchange(request, Map::class.java)
            fail("Expected 400 Bad Request for duplicate mapping")
        } catch (e: HttpClientResponseException) {
            assertEquals(HttpStatus.BAD_REQUEST, e.status)
            assertTrue(e.message!!.contains("already exists") || e.message!!.contains("duplicate"))
        }
    }

    @Test
    @DisplayName("POST /api/user-mappings without authentication returns 401 Unauthorized")
    fun testCreateIpMappingUnauthorized() {
        // Arrange
        val requestBody = mapOf(
            "email" to "user@example.com",
            "ipAddress" to "192.168.1.100"
        )

        val request = HttpRequest.POST("/api/user-mappings", requestBody)
        // No Authorization header

        // Act & Assert
        try {
            client.toBlocking().exchange(request, Map::class.java)
            fail("Expected 401 Unauthorized")
        } catch (e: HttpClientResponseException) {
            assertEquals(HttpStatus.UNAUTHORIZED, e.status)
        }
    }

    // ========== GET /api/user-mappings (List) ==========

    @Test
    @DisplayName("GET /api/user-mappings returns IP mappings in list")
    fun testListIpMappings() {
        // Arrange: Create test mappings
        userMappingRepository.save(
            IpMappingTestFixtures.createSingleIpMapping(
                email = "user1@example.com",
                ipAddress = "192.168.1.100"
            )
        )
        userMappingRepository.save(
            IpMappingTestFixtures.createCidrMapping(
                email = "user2@example.com",
                cidr = "10.0.0.0/24"
            )
        )

        val request = HttpRequest.GET<Any>("/api/user-mappings")
            .header("Authorization", "Bearer $adminToken")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body() as Map<*, *>
        assertTrue(body.containsKey("content"), "Response must contain 'content' array")

        val content = body["content"] as List<*>
        assertEquals(2, content.size, "Should have 2 mappings")

        // Verify first mapping has IP fields
        val firstMapping = content[0] as Map<*, *>
        assertTrue(firstMapping.containsKey("ipAddress"))
        assertTrue(firstMapping.containsKey("ipRangeType"))
        assertTrue(firstMapping.containsKey("ipCount"))
    }

    @Test
    @DisplayName("GET /api/user-mappings with pagination returns correct page")
    fun testListIpMappingsPagination() {
        // Arrange: Create 5 mappings
        repeat(5) { i ->
            userMappingRepository.save(
                IpMappingTestFixtures.createSingleIpMapping(
                    email = "user$i@example.com",
                    ipAddress = "192.168.1.$i"
                )
            )
        }

        val request = HttpRequest.GET<Any>("/api/user-mappings?page=0&size=2")
            .header("Authorization", "Bearer $adminToken")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body() as Map<*, *>
        val content = body["content"] as List<*>
        assertEquals(2, content.size, "Page size should be 2")
        assertEquals(5, (body["totalElements"] as Number).toInt(), "Total elements should be 5")
    }

    // ========== GET /api/user-mappings/{id} (Get Single) ==========

    @Test
    @DisplayName("GET /api/user-mappings/{id} returns single IP mapping")
    fun testGetIpMappingById() {
        // Arrange
        val saved = userMappingRepository.save(
            IpMappingTestFixtures.createSingleIpMapping(
                email = "user@example.com",
                ipAddress = "192.168.1.100"
            )
        )

        val request = HttpRequest.GET<Any>("/api/user-mappings/${saved.id}")
            .header("Authorization", "Bearer $adminToken")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body() as Map<*, *>
        assertEquals(saved.id, (body["id"] as Number).toLong())
        assertEquals("user@example.com", body["email"])
        assertEquals("192.168.1.100", body["ipAddress"])
        assertEquals("SINGLE", body["ipRangeType"])
    }

    @Test
    @DisplayName("GET /api/user-mappings/{id} with non-existent id returns 404 Not Found")
    fun testGetIpMappingByIdNotFound() {
        // Arrange
        val request = HttpRequest.GET<Any>("/api/user-mappings/99999")
            .header("Authorization", "Bearer $adminToken")

        // Act & Assert
        try {
            client.toBlocking().exchange(request, Map::class.java)
            fail("Expected 404 Not Found")
        } catch (e: HttpClientResponseException) {
            assertEquals(HttpStatus.NOT_FOUND, e.status)
        }
    }

    // ========== PUT /api/user-mappings/{id} (Update) ==========

    @Test
    @DisplayName("PUT /api/user-mappings/{id} updates IP address")
    fun testUpdateIpMapping() {
        // Arrange: Create initial mapping
        val saved = userMappingRepository.save(
            IpMappingTestFixtures.createSingleIpMapping(
                email = "user@example.com",
                ipAddress = "192.168.1.100"
            )
        )

        // Update to different IP
        val updateBody = mapOf(
            "email" to "user@example.com",
            "ipAddress" to "10.0.0.50",
            "domain" to "example.com"
        )

        val request = HttpRequest.PUT("/api/user-mappings/${saved.id}", updateBody)
            .header("Authorization", "Bearer $adminToken")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body() as Map<*, *>
        assertEquals("10.0.0.50", body["ipAddress"], "IP should be updated")
        assertEquals("SINGLE", body["ipRangeType"])
    }

    @Test
    @DisplayName("PUT /api/user-mappings/{id} updates from single IP to CIDR range")
    fun testUpdateIpMappingToRange() {
        // Arrange
        val saved = userMappingRepository.save(
            IpMappingTestFixtures.createSingleIpMapping(
                email = "user@example.com",
                ipAddress = "192.168.1.100"
            )
        )

        // Update to CIDR range
        val updateBody = mapOf(
            "email" to "user@example.com",
            "ipAddress" to "192.168.1.0/24",
            "domain" to "example.com"
        )

        val request = HttpRequest.PUT("/api/user-mappings/${saved.id}", updateBody)
            .header("Authorization", "Bearer $adminToken")

        // Act
        val response = client.toBlocking().exchange(request, Map::class.java)

        // Assert
        assertEquals(HttpStatus.OK, response.status)

        val body = response.body() as Map<*, *>
        assertEquals("192.168.1.0/24", body["ipAddress"])
        assertEquals("CIDR", body["ipRangeType"])
        assertEquals(256, (body["ipCount"] as Number).toInt())
    }

    // ========== DELETE /api/user-mappings/{id} (Delete) ==========

    @Test
    @DisplayName("DELETE /api/user-mappings/{id} removes IP mapping")
    fun testDeleteIpMapping() {
        // Arrange
        val saved = userMappingRepository.save(
            IpMappingTestFixtures.createSingleIpMapping(
                email = "user@example.com",
                ipAddress = "192.168.1.100"
            )
        )

        val request = HttpRequest.DELETE<Any>("/api/user-mappings/${saved.id}")
            .header("Authorization", "Bearer $adminToken")

        // Act
        val response = client.toBlocking().exchange(request, Void::class.java)

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.status)

        // Verify deletion
        assertFalse(userMappingRepository.findById(saved.id!!).isPresent, "Mapping should be deleted")
    }

    @Test
    @DisplayName("DELETE /api/user-mappings/{id} with non-existent id returns 404 Not Found")
    fun testDeleteIpMappingNotFound() {
        // Arrange
        val request = HttpRequest.DELETE<Any>("/api/user-mappings/99999")
            .header("Authorization", "Bearer $adminToken")

        // Act & Assert
        try {
            client.toBlocking().exchange(request, Void::class.java)
            fail("Expected 404 Not Found")
        } catch (e: HttpClientResponseException) {
            assertEquals(HttpStatus.NOT_FOUND, e.status)
        }
    }
}
