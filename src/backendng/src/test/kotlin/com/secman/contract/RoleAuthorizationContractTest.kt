package com.secman.contract

import com.secman.domain.Asset
import com.secman.domain.Demand
import com.secman.domain.DemandType
import com.secman.domain.User
import com.secman.repository.AssetRepository
import com.secman.repository.DemandRepository
import com.secman.repository.RiskAssessmentRepository
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
import java.time.LocalDate

/**
 * Role Authorization Contract Tests
 * Feature: 025-role-based-access-control
 *
 * Tests role-based access control for RISK, REQ, and SECCHAMPION roles
 * TDD Approach: Tests written FIRST, expected to FAIL until @Secured annotations are updated
 *
 * Constitutional Compliance:
 * - Principle V (RBAC): Tests role enforcement at API layer
 * - Principle I (Security-First): Tests access denial with generic messages
 */
@MicronautTest
class RoleAuthorizationContractTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var demandRepository: DemandRepository

    @Inject
    lateinit var assetRepository: AssetRepository

    @Inject
    lateinit var riskAssessmentRepository: RiskAssessmentRepository

    private val passwordEncoder = BCryptPasswordEncoder()
    private lateinit var riskUserToken: String
    private lateinit var reqUserToken: String
    private lateinit var secChampionUserToken: String
    private lateinit var regularUserToken: String
    private var testDemandId: Long = 0
    private var testAssetId: Long = 0

    @BeforeEach
    fun setup() {
        // Create test users with different roles
        val riskUser = User(
            username = "risk_manager",
            email = "risk@test.com",
            passwordHash = passwordEncoder.encode("risk123"),
            roles = mutableSetOf(User.Role.USER, User.Role.RISK)
        )
        userRepository.save(riskUser)

        val reqUser = User(
            username = "req_manager",
            email = "req@test.com",
            passwordHash = passwordEncoder.encode("req123"),
            roles = mutableSetOf(User.Role.USER, User.Role.REQ)
        )
        userRepository.save(reqUser)

        val secChampionUser = User(
            username = "sec_champion",
            email = "secchampion@test.com",
            passwordHash = passwordEncoder.encode("secchampion123"),
            roles = mutableSetOf(User.Role.USER, User.Role.SECCHAMPION)
        )
        userRepository.save(secChampionUser)

        val regularUser = User(
            username = "regular_user",
            email = "user@test.com",
            passwordHash = passwordEncoder.encode("user123"),
            roles = mutableSetOf(User.Role.USER)
        )
        userRepository.save(regularUser)

        // Create test data
        val testDemand = Demand(
            title = "Test Demand for Risk Assessment",
            demandType = DemandType.CREATE_NEW,
            requestor = regularUser,
            newAssetName = "Test Asset",
            newAssetType = "Server",
            newAssetOwner = "Test Owner"
        )
        val savedDemand = demandRepository.save(testDemand)
        testDemandId = savedDemand.id!!

        val testAsset = Asset(
            name = "Test Risk Server",
            type = "Server",
            owner = "Test Owner"
        )
        val savedAsset = assetRepository.save(testAsset)
        testAssetId = savedAsset.id!!

        // Get JWT tokens for each user
        riskUserToken = getAuthToken("risk_manager", "risk123")
        reqUserToken = getAuthToken("req_manager", "req123")
        secChampionUserToken = getAuthToken("sec_champion", "secchampion123")
        regularUserToken = getAuthToken("regular_user", "user123")
    }

    private fun getAuthToken(username: String, password: String): String {
        val credentials = UsernamePasswordCredentials(username, password)
        val loginRequest = HttpRequest.POST("/api/auth/login", credentials)
        val loginResponse = client.toBlocking().exchange(loginRequest, Map::class.java)
        return (loginResponse.body() as Map<*, *>)["token"] as String
    }

    // ========== T021-T024: RISK role CAN access Risk Management endpoints ==========

    @Test
    fun `T021 - RISK role can GET risk-assessments list`() {
        // Given: User with RISK role
        // When: GET /api/risk-assessments
        val request = HttpRequest.GET<Any>("/api/risk-assessments")
            .bearerAuth(riskUserToken)

        // Then: Expect 200 OK (WILL FAIL initially - @Secured needs update)
        val response = client.toBlocking().exchange(request, List::class.java)

        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
    }

    @Test
    fun `T022 - RISK role can POST new risk-assessment`() {
        // Given: User with RISK role and valid request
        val createRequest = mapOf(
            "demandId" to testDemandId,
            "assessorId" to 1L,  // Will be the risk user's ID
            "endDate" to LocalDate.now().plusDays(30).toString()
        )

        // When: POST /api/risk-assessments
        val request = HttpRequest.POST("/api/risk-assessments", createRequest)
            .bearerAuth(riskUserToken)

        // Then: Expect 201 Created (WILL FAIL initially - @Secured needs update)
        val response = client.toBlocking().exchange(request, Map::class.java)

        assertEquals(HttpStatus.CREATED, response.status)
        val body = response.body() as Map<*, *>
        assertNotNull(body["id"])
    }

    @Test
    fun `T023 - RISK role can PUT update risk-assessment`() {
        // Given: User with RISK role and existing risk assessment
        // First create one
        val createRequest = mapOf(
            "demandId" to testDemandId,
            "assessorId" to 1L,
            "endDate" to LocalDate.now().plusDays(30).toString()
        )
        val createHttpRequest = HttpRequest.POST("/api/risk-assessments", createRequest)
            .bearerAuth(riskUserToken)
        val createResponse = client.toBlocking().exchange(createHttpRequest, Map::class.java)
        val riskAssessmentId = (createResponse.body() as Map<*, *>)["id"] as Int

        // When: PUT /api/risk-assessments/{id}
        val updateRequest = mapOf(
            "endDate" to LocalDate.now().plusDays(60).toString()
        )
        val updateHttpRequest = HttpRequest.PUT("/api/risk-assessments/$riskAssessmentId", updateRequest)
            .bearerAuth(riskUserToken)

        // Then: Expect 200 OK (WILL FAIL initially - @Secured needs update)
        val response = client.toBlocking().exchange(updateHttpRequest, Map::class.java)

        assertEquals(HttpStatus.OK, response.status)
    }

    @Test
    fun `T024 - RISK role can DELETE risk-assessment`() {
        // Given: User with RISK role and existing risk assessment
        val createRequest = mapOf(
            "demandId" to testDemandId,
            "assessorId" to 1L,
            "endDate" to LocalDate.now().plusDays(30).toString()
        )
        val createHttpRequest = HttpRequest.POST("/api/risk-assessments", createRequest)
            .bearerAuth(riskUserToken)
        val createResponse = client.toBlocking().exchange(createHttpRequest, Map::class.java)
        val riskAssessmentId = (createResponse.body() as Map<*, *>)["id"] as Int

        // When: DELETE /api/risk-assessments/{id}
        val deleteRequest = HttpRequest.DELETE<Any>("/api/risk-assessments/$riskAssessmentId")
            .bearerAuth(riskUserToken)

        // Then: Expect 204 No Content (WILL FAIL initially - @Secured needs update)
        val response = client.toBlocking().exchange(deleteRequest, Any::class.java)

        assertEquals(HttpStatus.NO_CONTENT, response.status)
    }

    // ========== T025-T026: RISK role CANNOT access other endpoints ==========

    @Test
    fun `T025 - RISK role CANNOT GET requirements - expects 403 Forbidden`() {
        // Given: User with RISK role (but NOT REQ or ADMIN)
        // When: GET /api/requirements
        val request = HttpRequest.GET<Any>("/api/requirements")
            .bearerAuth(riskUserToken)

        // Then: Expect 403 Forbidden with generic error message
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, List::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)

        // Verify generic error message (no role disclosure per spec)
        val errorMessage = exception.response.getBody(String::class.java).orElse("")
        assertFalse(errorMessage.contains("ADMIN", ignoreCase = true),
            "Error message should not disclose required ADMIN role")
        assertFalse(errorMessage.contains("REQ", ignoreCase = true),
            "Error message should not disclose required REQ role")
    }

    @Test
    fun `T026 - RISK role CANNOT GET admin endpoints - expects 403 Forbidden`() {
        // Given: User with RISK role (but NOT ADMIN)
        // When: GET /api/admin/* (e.g., admin users endpoint)
        val request = HttpRequest.GET<Any>("/api/admin/users")
            .bearerAuth(riskUserToken)

        // Then: Expect 403 Forbidden with generic error message
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, List::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)

        // Verify generic error message (no role disclosure per spec)
        val errorMessage = exception.response.getBody(String::class.java).orElse("")
        assertFalse(errorMessage.contains("ADMIN", ignoreCase = true),
            "Error message should not disclose required ADMIN role")
    }

    // ========== T039-T044: REQ role tests (Phase 4 - User Story 2) ==========

    @Test
    fun `T039 - REQ role can GET requirements`() {
        // Given: User with REQ role
        // When: GET /api/requirements
        val request = HttpRequest.GET<Any>("/api/requirements")
            .bearerAuth(reqUserToken)

        // Then: Expect 200 OK (WILL FAIL initially - @Secured needs update)
        val response = client.toBlocking().exchange(request, List::class.java)

        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
    }

    @Test
    fun `T040 - REQ role can POST new requirement`() {
        // Given: User with REQ role
        // When: POST /api/requirements (minimal valid payload)
        val createRequest = mapOf(
            "shortreq" to "Test Requirement",
            "chapter" to "1.1",
            "details" to "Test requirement details"
        )
        val request = HttpRequest.POST("/api/requirements", createRequest)
            .bearerAuth(reqUserToken)

        // Then: Expect 201 Created (WILL FAIL initially - @Secured needs update)
        val response = client.toBlocking().exchange(request, Map::class.java)

        assertEquals(HttpStatus.CREATED, response.status)
        val body = response.body() as Map<*, *>
        assertNotNull(body["id"])
    }

    @Test
    fun `T041 - REQ role can GET norms`() {
        // Given: User with REQ role
        // When: GET /api/norms
        val request = HttpRequest.GET<Any>("/api/norms")
            .bearerAuth(reqUserToken)

        // Then: Expect 200 OK (WILL FAIL initially - @Secured needs update)
        val response = client.toBlocking().exchange(request, List::class.java)

        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
    }

    @Test
    fun `T042 - REQ role can GET usecases`() {
        // Given: User with REQ role
        // When: GET /api/usecases
        val request = HttpRequest.GET<Any>("/api/usecases")
            .bearerAuth(reqUserToken)

        // Then: Expect 200 OK (WILL FAIL initially - @Secured needs update)
        val response = client.toBlocking().exchange(request, List::class.java)

        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
    }

    @Test
    fun `T043 - REQ role CANNOT GET risk-assessments - expects 403 Forbidden`() {
        // Given: User with REQ role (but NOT RISK or ADMIN)
        // When: GET /api/risk-assessments
        val request = HttpRequest.GET<Any>("/api/risk-assessments")
            .bearerAuth(reqUserToken)

        // Then: Expect 403 Forbidden with generic error message
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, List::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)

        // Verify generic error message (no role disclosure)
        val errorMessage = exception.response.getBody(String::class.java).orElse("")
        assertFalse(errorMessage.contains("ADMIN", ignoreCase = true),
            "Error message should not disclose required roles")
        assertFalse(errorMessage.contains("RISK", ignoreCase = true),
            "Error message should not disclose required roles")
    }

    @Test
    fun `T044 - REQ role CANNOT GET admin endpoints - expects 403 Forbidden`() {
        // Given: User with REQ role (but NOT ADMIN)
        // When: GET /api/admin/users
        val request = HttpRequest.GET<Any>("/api/admin/users")
            .bearerAuth(reqUserToken)

        // Then: Expect 403 Forbidden with generic error message
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, List::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)

        // Verify generic error message (no role disclosure)
        val errorMessage = exception.response.getBody(String::class.java).orElse("")
        assertFalse(errorMessage.contains("ADMIN", ignoreCase = true),
            "Error message should not disclose required ADMIN role")
    }

    // ========== Additional test: Regular USER role (no special permissions) ==========

    @Test
    fun `Regular USER role CANNOT access risk-assessments`() {
        // Given: User with ONLY USER role (no RISK/REQ/ADMIN)
        // When: GET /api/risk-assessments
        val request = HttpRequest.GET<Any>("/api/risk-assessments")
            .bearerAuth(regularUserToken)

        // Then: Expect 403 Forbidden
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, List::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    @Test
    fun `Regular USER role CANNOT access requirements`() {
        // Given: User with ONLY USER role (no RISK/REQ/ADMIN)
        // When: GET /api/requirements
        val request = HttpRequest.GET<Any>("/api/requirements")
            .bearerAuth(regularUserToken)

        // Then: Expect 403 Forbidden
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, List::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)
    }

    // ========== T058-T064: SECCHAMPION role tests (Phase 5 - User Story 3) ==========

    @Test
    fun `T058 - SECCHAMPION role can GET risk-assessments`() {
        // Given: User with SECCHAMPION role
        // When: GET /api/risk-assessments
        val request = HttpRequest.GET<Any>("/api/risk-assessments")
            .bearerAuth(secChampionUserToken)

        // Then: Expect 200 OK (SECCHAMPION has broad access)
        val response = client.toBlocking().exchange(request, List::class.java)

        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
    }

    @Test
    fun `T059 - SECCHAMPION role can GET requirements`() {
        // Given: User with SECCHAMPION role
        // When: GET /api/requirements
        val request = HttpRequest.GET<Any>("/api/requirements")
            .bearerAuth(secChampionUserToken)

        // Then: Expect 200 OK
        val response = client.toBlocking().exchange(request, List::class.java)

        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
    }

    @Test
    fun `T060 - SECCHAMPION role can GET current vulnerabilities`() {
        // Given: User with SECCHAMPION role
        // When: GET /api/vulnerabilities/current
        val request = HttpRequest.GET<Any>("/api/vulnerabilities/current")
            .bearerAuth(secChampionUserToken)

        // Then: Expect 200 OK
        val response = client.toBlocking().exchange(request, List::class.java)

        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
    }

    @Test
    fun `T061 - SECCHAMPION role can GET vulnerability-exceptions`() {
        // Given: User with SECCHAMPION role
        // When: GET /api/vulnerability-exceptions
        val request = HttpRequest.GET<Any>("/api/vulnerability-exceptions")
            .bearerAuth(secChampionUserToken)

        // Then: Expect 200 OK
        val response = client.toBlocking().exchange(request, List::class.java)

        assertEquals(HttpStatus.OK, response.status)
        assertNotNull(response.body())
    }

    @Test
    fun `T062 - SECCHAMPION role CANNOT GET admin endpoints - expects 403 Forbidden`() {
        // Given: User with SECCHAMPION role (but NOT ADMIN)
        // When: GET /api/admin/users
        val request = HttpRequest.GET<Any>("/api/admin/users")
            .bearerAuth(secChampionUserToken)

        // Then: Expect 403 Forbidden with generic error message
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, List::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)

        // Verify generic error message (no role disclosure per spec)
        val errorMessage = exception.response.getBody(String::class.java).orElse("")
        assertFalse(errorMessage.contains("ADMIN", ignoreCase = true),
            "Error message should not disclose required ADMIN role")
    }

    @Test
    fun `T063 - SECCHAMPION role CANNOT GET users endpoint - expects 403 Forbidden`() {
        // Given: User with SECCHAMPION role (but NOT ADMIN)
        // When: GET /api/users
        val request = HttpRequest.GET<Any>("/api/users")
            .bearerAuth(secChampionUserToken)

        // Then: Expect 403 Forbidden with generic error message
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, List::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)

        // Verify generic error message
        val errorMessage = exception.response.getBody(String::class.java).orElse("")
        assertFalse(errorMessage.contains("ADMIN", ignoreCase = true),
            "Error message should not disclose required ADMIN role")
    }

    @Test
    fun `T064 - SECCHAMPION role CANNOT GET workgroups - expects 403 Forbidden`() {
        // Given: User with SECCHAMPION role (but NOT ADMIN)
        // When: GET /api/workgroups
        val request = HttpRequest.GET<Any>("/api/workgroups")
            .bearerAuth(secChampionUserToken)

        // Then: Expect 403 Forbidden with generic error message
        val exception = assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, List::class.java)
        }

        assertEquals(HttpStatus.FORBIDDEN, exception.status)

        // Verify generic error message
        val errorMessage = exception.response.getBody(String::class.java).orElse("")
        assertFalse(errorMessage.contains("ADMIN", ignoreCase = true),
            "Error message should not disclose required ADMIN role")
    }
}
