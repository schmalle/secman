package com.secman.controller

import com.secman.domain.Release
import com.secman.domain.User
import com.secman.repository.ReleaseRepository
import com.secman.repository.UserRepository
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.security.authentication.UsernamePasswordCredentials
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Contract Tests for Release Management API
 * Feature: 011-i-want-to (Release-Based Requirement Version Management)
 *
 * These tests validate the OpenAPI contract defined in:
 * specs/011-i-want-to/contracts/release-api.yaml
 *
 * ⚠️ TDD: These tests are written BEFORE implementation and MUST FAIL initially
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ReleaseControllerTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var releaseRepository: ReleaseRepository

    private lateinit var adminToken: String
    private lateinit var releaseManagerToken: String
    private lateinit var userToken: String

    @BeforeAll
    fun setup() {
        // Create test users with different roles
        val adminUser = User(
            username = "test-admin",
            email = "admin@test.com",
            passwordHash = "\$2a\$10\$dummyhash",
            roles = mutableSetOf(User.Role.ADMIN)
        )

        val releaseManagerUser = User(
            username = "test-release-manager",
            email = "releasemanager@test.com",
            passwordHash = "\$2a\$10\$dummyhash",
            roles = mutableSetOf(User.Role.RELEASE_MANAGER)
        )

        val normalUser = User(
            username = "test-user",
            email = "user@test.com",
            passwordHash = "\$2a\$10\$dummyhash",
            roles = mutableSetOf(User.Role.USER)
        )

        userRepository.save(adminUser)
        userRepository.save(releaseManagerUser)
        userRepository.save(normalUser)

        // Obtain JWT tokens for authentication
        // Note: This assumes /api/auth/login endpoint exists
        // If not, these tests will fail during setup - expected for TDD
        try {
            adminToken = login("test-admin", "password")
            releaseManagerToken = login("test-release-manager", "password")
            userToken = login("test-user", "password")
        } catch (e: Exception) {
            println("⚠️ Auth setup failed - expected if auth not implemented yet: ${e.message}")
        }
    }

    @AfterEach
    fun cleanup() {
        // Clean up releases after each test
        releaseRepository.deleteAll()
    }

    private fun login(username: String, password: String): String {
        val credentials = UsernamePasswordCredentials(username, password)
        val request = HttpRequest.POST("/api/auth/login", credentials)
        val response = client.toBlocking().exchange(request, Map::class.java)
        return response.body()?.get("access_token") as? String
            ?: throw IllegalStateException("No token received")
    }

    // ========== T004: POST /api/releases (Create Release) ==========

    @Test
    @Order(1)
    fun `T004-1 POST releases - Success 201 with valid data as ADMIN`() {
        val createRequest = mapOf(
            "version" to "1.0.0",
            "name" to "Q4 2024 Compliance Review",
            "description" to "Annual compliance review for regulatory submission"
        )

        val request = HttpRequest.POST("/api/releases", createRequest)
            .bearerAuth(adminToken)

        val response = client.toBlocking().exchange(request, Map::class.java)

        assertEquals(HttpStatus.CREATED, response.status)
        val body = response.body()!!
        assertTrue(body.containsKey("id") && body["id"] != null)
        assertEquals("1.0.0", body["version"])
        assertEquals("Q4 2024 Compliance Review", body["name"])
        assertEquals("DRAFT", body["status"])
        assertTrue(body.containsKey("createdAt") && body["createdAt"] != null)
    }

    @Test
    @Order(2)
    fun `T004-2 POST releases - Success 201 with valid data as RELEASE_MANAGER`() {
        val createRequest = mapOf(
            "version" to "1.1.0",
            "name" to "Q1 2025 Update"
        )

        val request = HttpRequest.POST("/api/releases", createRequest)
            .bearerAuth(releaseManagerToken)

        val response = client.toBlocking().exchange(request, Map::class.java)

        assertEquals(HttpStatus.CREATED, response.status)
        assertEquals("1.1.0", response.body()!!["version"])
    }

    @Test
    @Order(3)
    fun `T004-3 POST releases - Error 400 duplicate version`() {
        // Create first release
        val release = Release(version = "1.0.0", name = "Existing Release")
        releaseRepository.save(release)

        // Attempt to create duplicate
        val createRequest = mapOf(
            "version" to "1.0.0",
            "name" to "Duplicate Release"
        )

        val request = HttpRequest.POST("/api/releases", createRequest)
            .bearerAuth(adminToken)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, Map::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        assertTrue(exception.message!!.contains("version") || exception.message!!.contains("duplicate"))
    }

    @Test
    @Order(4)
    fun `T004-4 POST releases - Error 400 invalid version format v1_0`() {
        val createRequest = mapOf(
            "version" to "v1.0",
            "name" to "Invalid Version"
        )

        val request = HttpRequest.POST("/api/releases", createRequest)
            .bearerAuth(adminToken)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, Map::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
        assertTrue(exception.message!!.contains("semantic versioning") || exception.message!!.contains("format"))
    }

    @Test
    @Order(5)
    fun `T004-5 POST releases - Error 400 invalid version format Q4-2024`() {
        val createRequest = mapOf(
            "version" to "Q4-2024",
            "name" to "Invalid CalVer"
        )

        val request = HttpRequest.POST("/api/releases", createRequest)
            .bearerAuth(adminToken)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, Map::class.java)
        }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    @Order(6)
    fun `T004-6 POST releases - Error 403 USER role forbidden`() {
        val createRequest = mapOf(
            "version" to "1.0.0",
            "name" to "Unauthorized Release"
        )

        val request = HttpRequest.POST("/api/releases", createRequest)
            .bearerAuth(userToken)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, Map::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    @Test
    @Order(7)
    fun `T004-7 POST releases - Error 401 unauthenticated`() {
        val createRequest = mapOf(
            "version" to "1.0.0",
            "name" to "Unauthenticated Release"
        )

        val request = HttpRequest.POST("/api/releases", createRequest)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, Map::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    // ========== T005: GET /api/releases (List Releases) ==========

    @Test
    @Order(10)
    fun `T005-1 GET releases - Success 200 returns array`() {
        // Create test releases
        releaseRepository.save(Release(version = "1.0.0", name = "Release 1", status = Release.ReleaseStatus.PUBLISHED))
        releaseRepository.save(Release(version = "1.1.0", name = "Release 2", status = Release.ReleaseStatus.DRAFT))

        val request = HttpRequest.GET<Any>("/api/releases")
            .bearerAuth(adminToken)

        val response = client.toBlocking().exchange(request, List::class.java)

        assertEquals(HttpStatus.OK, response.status)
        val releases = response.body() as List<*>
        assertTrue(releases.size >= 2)
    }

    @Test
    @Order(11)
    fun `T005-2 GET releases - Filter by status PUBLISHED`() {
        releaseRepository.save(Release(version = "1.0.0", name = "Published", status = Release.ReleaseStatus.PUBLISHED))
        releaseRepository.save(Release(version = "2.0.0", name = "Draft", status = Release.ReleaseStatus.DRAFT))

        val request = HttpRequest.GET<Any>("/api/releases?status=PUBLISHED")
            .bearerAuth(adminToken)

        val response = client.toBlocking().exchange(request, List::class.java)

        assertEquals(HttpStatus.OK, response.status)
        val releases = response.body() as List<*>
        assertTrue(releases.isNotEmpty())
        // Note: Full validation would check each release has status=PUBLISHED
    }

    @Test
    @Order(12)
    fun `T005-3 GET releases - Error 401 unauthenticated`() {
        val request = HttpRequest.GET<Any>("/api/releases")

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, List::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    // ========== T006: GET /api/releases/{id} (Get Release) ==========

    @Test
    @Order(20)
    fun `T006-1 GET releases by ID - Success 200 returns ReleaseResponse`() {
        val release = releaseRepository.save(Release(version = "1.0.0", name = "Test Release"))

        val request = HttpRequest.GET<Any>("/api/releases/${release.id}")
            .bearerAuth(adminToken)

        val response = client.toBlocking().exchange(request, Map::class.java)

        assertEquals(HttpStatus.OK, response.status)
        val body = response.body()!!
        assertEquals(release.id, (body["id"] as Number).toLong())
        assertEquals("1.0.0", body["version"])
        assertEquals("Test Release", body["name"])
    }

    @Test
    @Order(21)
    fun `T006-2 GET releases by ID - Error 404 not found`() {
        val request = HttpRequest.GET<Any>("/api/releases/99999")
            .bearerAuth(adminToken)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, Map::class.java)
        }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    @Test
    @Order(22)
    fun `T006-3 GET releases by ID - Error 401 unauthenticated`() {
        val request = HttpRequest.GET<Any>("/api/releases/1")

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, Map::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    // ========== T007: DELETE /api/releases/{id} (Delete Release) ==========

    @Test
    @Order(30)
    fun `T007-1 DELETE releases - Success 204 as ADMIN`() {
        val release = releaseRepository.save(Release(version = "1.0.0", name = "To Delete"))

        val request = HttpRequest.DELETE<Any>("/api/releases/${release.id}")
            .bearerAuth(adminToken)

        val response = client.toBlocking().exchange(request, Void::class.java)

        assertEquals(HttpStatus.NO_CONTENT, response.status)
        assertFalse(releaseRepository.existsById(release.id!!))
    }

    @Test
    @Order(31)
    fun `T007-2 DELETE releases - Success 204 as RELEASE_MANAGER`() {
        val release = releaseRepository.save(Release(version = "2.0.0", name = "To Delete"))

        val request = HttpRequest.DELETE<Any>("/api/releases/${release.id}")
            .bearerAuth(releaseManagerToken)

        val response = client.toBlocking().exchange(request, Void::class.java)

        assertEquals(HttpStatus.NO_CONTENT, response.status)
    }

    @Test
    @Order(32)
    fun `T007-3 DELETE releases - Error 403 USER role forbidden`() {
        val release = releaseRepository.save(Release(version = "3.0.0", name = "Protected"))

        val request = HttpRequest.DELETE<Any>("/api/releases/${release.id}")
            .bearerAuth(userToken)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, Void::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
        assertTrue(releaseRepository.existsById(release.id!!))
    }

    @Test
    @Order(33)
    fun `T007-4 DELETE releases - Error 404 not found`() {
        val request = HttpRequest.DELETE<Any>("/api/releases/99999")
            .bearerAuth(adminToken)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, Void::class.java)
        }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    @Test
    @Order(34)
    fun `T007-5 DELETE releases - Error 401 unauthenticated`() {
        val request = HttpRequest.DELETE<Any>("/api/releases/1")

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, Void::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status)
    }

    // ========== T008: GET /api/releases/{id}/requirements (List Snapshots) ==========

    @Test
    @Order(40)
    fun `T008-1 GET release requirements - Success 200 returns array`() {
        val release = releaseRepository.save(Release(version = "1.0.0", name = "Test"))

        val request = HttpRequest.GET<Any>("/api/releases/${release.id}/requirements")
            .bearerAuth(adminToken)

        val response = client.toBlocking().exchange(request, List::class.java)

        assertEquals(HttpStatus.OK, response.status)
        assertTrue(response.body() != null)
        // Note: Will be empty array initially as no snapshots created yet
    }

    @Test
    @Order(41)
    fun `T008-2 GET release requirements - Error 404 release not found`() {
        val request = HttpRequest.GET<Any>("/api/releases/99999/requirements")
            .bearerAuth(adminToken)

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, List::class.java)
        }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }
}
