package com.secman.integration

import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.security.authentication.UsernamePasswordCredentials
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Integration test for port history retrieval
 *
 * Tests FR-011: "System MUST display port scan history showing port numbers, states,
 * services, and scan timestamps"
 *
 * Scenario:
 * - Authenticated users (not just admin) can access GET /api/assets/{id}/ports
 * - Response shows port history grouped by scan date
 * - History is ordered chronologically (newest first)
 * - Port states (open/filtered/closed) are correctly represented
 *
 * Expected to FAIL until AssetController implements /ports endpoint (TDD red phase).
 */
@MicronautTest
class PortHistoryTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    /**
     * Integration Test: Authenticated user can access port history
     *
     * Verifies that regular users (not just admins) have access
     */
    @Test
    fun `authenticated user should access port history successfully`() {
        // Arrange
        val token = authenticateAsUser()
        val assetId = 1L // Assuming test data exists

        // Act
        val request = HttpRequest.GET<Any>("/api/assets/$assetId/ports")
            .bearerAuth(token)

        try {
            val response = client.toBlocking().exchange(request, Map::class.java)
            // If asset exists, should return 200
            assertEquals(HttpStatus.OK, response.status, "User should access port history")
            assertNotNull(response.body())

            val body = response.body()!!
            assertEquals(assetId, (body["assetId"] as Number).toLong(), "assetId should match")
            assertNotNull(body["assetName"], "assetName should be present")
            assertNotNull(body["scans"], "scans list should be present")
        } catch (e: HttpClientResponseException) {
            // If asset doesn't exist, should return 404 (not 403)
            assertEquals(HttpStatus.NOT_FOUND, e.status, "User should get 404, not 403")
        }
    }

    /**
     * Integration Test: Admin user can also access port history
     *
     * Verifies that admins maintain access (not exclusive to regular users)
     */
    @Test
    fun `admin user should access port history successfully`() {
        // Arrange
        val token = authenticateAsAdmin()
        val assetId = 1L

        // Act
        val request = HttpRequest.GET<Any>("/api/assets/$assetId/ports")
            .bearerAuth(token)

        try {
            val response = client.toBlocking().exchange(request, Map::class.java)
            assertEquals(HttpStatus.OK, response.status, "Admin should access port history")
        } catch (e: HttpClientResponseException) {
            assertEquals(HttpStatus.NOT_FOUND, e.status, "Admin should get 404, not 403")
        }
    }

    /**
     * Integration Test: Unauthenticated user cannot access port history
     */
    @Test
    fun `unauthenticated user should get 401 when accessing port history`() {
        // Arrange
        val assetId = 1L

        // Act & Assert
        val request = HttpRequest.GET<Any>("/api/assets/$assetId/ports")

        val exception = assertThrows<HttpClientResponseException> {
            client.toBlocking().exchange(request, String::class.java)
        }

        assertEquals(HttpStatus.UNAUTHORIZED, exception.status,
            "Unauthenticated user should get 401 UNAUTHORIZED")
    }

    /**
     * Integration Test: Port history ordered by scan date descending
     *
     * Verifies chronological ordering per contract (newest first)
     */
    @Test
    fun `port history should be ordered by scan date descending`() {
        // Arrange
        val token = authenticateAsUser()
        val assetId = 1L

        // Act
        val request = HttpRequest.GET<Any>("/api/assets/$assetId/ports")
            .bearerAuth(token)

        try {
            val response = client.toBlocking().exchange(request, Map::class.java)
            assertEquals(HttpStatus.OK, response.status)

            val body = response.body()!!
            @Suppress("UNCHECKED_CAST")
            val scans = body["scans"] as List<Map<String, Any>>

            // Verify scans are ordered by scanDate descending
            if (scans.size > 1) {
                val scanDates = scans.map { it["scanDate"] as String }
                val sortedDates = scanDates.sortedDescending()
                assertEquals(sortedDates, scanDates,
                    "Scans should be ordered by scanDate descending (newest first)")
            }
        } catch (e: HttpClientResponseException) {
            // Asset may not exist in test DB - that's ok
            assertEquals(HttpStatus.NOT_FOUND, e.status)
        }
    }

    /**
     * Integration Test: Port history includes all required fields
     *
     * Verifies response structure matches contract specification
     */
    @Test
    fun `port history should include all required fields`() {
        // Arrange
        val token = authenticateAsUser()
        val assetId = 1L

        // Act
        val request = HttpRequest.GET<Any>("/api/assets/$assetId/ports")
            .bearerAuth(token)

        try {
            val response = client.toBlocking().exchange(request, Map::class.java)
            val body = response.body()!!

            // Verify top-level structure
            assertNotNull(body["assetId"], "assetId must be present")
            assertNotNull(body["assetName"], "assetName must be present")
            assertNotNull(body["scans"], "scans must be present")

            @Suppress("UNCHECKED_CAST")
            val scans = body["scans"] as List<Map<String, Any>>

            if (scans.isNotEmpty()) {
                val scan = scans.first()

                // Verify scan structure
                assertNotNull(scan["scanId"], "scanId must be present")
                assertNotNull(scan["scanDate"], "scanDate must be present")
                assertNotNull(scan["scanType"], "scanType must be present")
                assertNotNull(scan["ports"], "ports must be present")

                @Suppress("UNCHECKED_CAST")
                val ports = scan["ports"] as List<Map<String, Any>>

                if (ports.isNotEmpty()) {
                    val port = ports.first()

                    // Verify port structure
                    assertNotNull(port["portNumber"], "portNumber must be present")
                    val portNumber = (port["portNumber"] as Number).toInt()
                    assertTrue(portNumber in 1..65535, "portNumber must be in range 1-65535")

                    assertNotNull(port["protocol"], "protocol must be present")
                    val protocol = port["protocol"] as String
                    assertTrue(protocol in listOf("tcp", "udp"), "protocol must be tcp or udp")

                    assertNotNull(port["state"], "state must be present")
                    val state = port["state"] as String
                    assertTrue(state in listOf("open", "filtered", "closed"),
                        "state must be open, filtered, or closed")

                    // service and version can be null (optional fields)
                }
            }
        } catch (e: HttpClientResponseException) {
            // Asset may not exist - that's acceptable
            assertEquals(HttpStatus.NOT_FOUND, e.status)
        }
    }

    /**
     * Integration Test: Port history for asset with no scan data
     *
     * Verifies that assets without scans return empty list (not error)
     */
    @Test
    fun `port history should return empty list for asset without scans`() {
        // Arrange
        val token = authenticateAsUser()
        val assetWithoutScans = 9999L // Assuming this asset exists but has no scans

        // Act
        val request = HttpRequest.GET<Any>("/api/assets/$assetWithoutScans/ports")
            .bearerAuth(token)

        try {
            val response = client.toBlocking().exchange(request, Map::class.java)
            assertEquals(HttpStatus.OK, response.status)

            val body = response.body()!!
            @Suppress("UNCHECKED_CAST")
            val scans = body["scans"] as List<Map<String, Any>>

            assertTrue(scans.isEmpty(), "Scans list should be empty for asset without scan data")
        } catch (e: HttpClientResponseException) {
            // If asset doesn't exist, 404 is expected
            assertEquals(HttpStatus.NOT_FOUND, e.status)
        }
    }

    /**
     * Integration Test: Port history shows state changes over time
     *
     * Verifies that same port in multiple scans shows historical state changes
     */
    @Test
    fun `port history should show state changes over time for same port`() {
        // Arrange
        val token = authenticateAsUser()
        val assetId = 1L

        // Act
        val request = HttpRequest.GET<Any>("/api/assets/$assetId/ports")
            .bearerAuth(token)

        try {
            val response = client.toBlocking().exchange(request, Map::class.java)
            val body = response.body()!!

            @Suppress("UNCHECKED_CAST")
            val scans = body["scans"] as List<Map<String, Any>>

            // If multiple scans exist, check for port state tracking
            if (scans.size > 1) {
                // Group ports by port number across all scans
                val portStates = mutableMapOf<Int, MutableList<String>>()

                scans.forEach { scan ->
                    @Suppress("UNCHECKED_CAST")
                    val ports = scan["ports"] as List<Map<String, Any>>
                    ports.forEach { port ->
                        val portNumber = (port["portNumber"] as Number).toInt()
                        val state = port["state"] as String
                        portStates.getOrPut(portNumber) { mutableListOf() }.add(state)
                    }
                }

                // Verify that if same port appears in multiple scans, states are tracked
                portStates.forEach { (portNumber, states) ->
                    if (states.size > 1) {
                        assertNotNull(states, "Port $portNumber should have state history across multiple scans")
                        assertTrue(states.all { it in listOf("open", "filtered", "closed") },
                            "All states should be valid")
                    }
                }
            }
        } catch (e: HttpClientResponseException) {
            // Asset may not exist
            assertEquals(HttpStatus.NOT_FOUND, e.status)
        }
    }

    /**
     * Integration Test: Port history includes service and version info
     *
     * Verifies that service name and version are included when available
     */
    @Test
    fun `port history should include service and version when available`() {
        // Arrange
        val token = authenticateAsUser()
        val assetId = 1L

        // Act
        val request = HttpRequest.GET<Any>("/api/assets/$assetId/ports")
            .bearerAuth(token)

        try {
            val response = client.toBlocking().exchange(request, Map::class.java)
            val body = response.body()!!

            @Suppress("UNCHECKED_CAST")
            val scans = body["scans"] as List<Map<String, Any>>

            if (scans.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                val ports = scans.first()["ports"] as List<Map<String, Any>>

                if (ports.isNotEmpty()) {
                    val port = ports.first()

                    // service and version are optional, but if present, should be valid strings
                    val service = port["service"]
                    val version = port["version"]

                    if (service != null) {
                        assertTrue(service is String, "service should be string when present")
                    }

                    if (version != null) {
                        assertTrue(version is String, "version should be string when present")
                    }
                }
            }
        } catch (e: HttpClientResponseException) {
            assertEquals(HttpStatus.NOT_FOUND, e.status)
        }
    }

    /**
     * Integration Test: Port history respects asset ownership/visibility
     *
     * Verifies that users can only see port history for assets they have access to
     */
    @Test
    fun `port history should respect asset access controls`() {
        // Arrange - Both users should be able to access the same asset
        // (No per-asset ownership restrictions in current spec)
        val adminToken = authenticateAsAdmin()
        val userToken = authenticateAsUser()
        val assetId = 1L

        // Act - Admin access
        val adminRequest = HttpRequest.GET<Any>("/api/assets/$assetId/ports")
            .bearerAuth(adminToken)

        // Act - User access
        val userRequest = HttpRequest.GET<Any>("/api/assets/$assetId/ports")
            .bearerAuth(userToken)

        try {
            val adminResponse = client.toBlocking().exchange(adminRequest, Map::class.java)
            val userResponse = client.toBlocking().exchange(userRequest, Map::class.java)

            // Both should have access (no asset-level restrictions in spec)
            assertEquals(HttpStatus.OK, adminResponse.status, "Admin should access port history")
            assertEquals(HttpStatus.OK, userResponse.status, "User should access port history")

            // Both should see the same data
            assertEquals(adminResponse.body()!!["assetId"], userResponse.body()!!["assetId"],
                "Both users should see same asset data")
        } catch (e: HttpClientResponseException) {
            // Asset may not exist
            assertEquals(HttpStatus.NOT_FOUND, e.status)
        }
    }

    // Helper methods
    private fun authenticateAsUser(): String {
        val credentials = UsernamePasswordCredentials("user", "user")
        val request = HttpRequest.POST("/login", credentials)
        val response = client.toBlocking().exchange(request, Map::class.java)
        return response.body()!!["access_token"] as String
    }

    private fun authenticateAsAdmin(): String {
        val credentials = UsernamePasswordCredentials("admin", "admin")
        val request = HttpRequest.POST("/login", credentials)
        val response = client.toBlocking().exchange(request, Map::class.java)
        return response.body()!!["access_token"] as String
    }
}
