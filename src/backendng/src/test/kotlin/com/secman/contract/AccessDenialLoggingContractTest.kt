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
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Access Denial Logging Contract Tests
 * Feature: 025-role-based-access-control
 *
 * Tests that access denials are properly logged to ACCESS_DENIAL_AUDIT logger
 * with full context (user, roles, resource, timestamp, IP)
 *
 * TDD Approach: Tests written FIRST, expected to FAIL until logging is added to controllers
 *
 * Constitutional Compliance:
 * - Principle I (Security-First): FR-014 - Log all access denials
 * - Per clarification: Log denials only (not grants) with full context
 */
@MicronautTest
class AccessDenialLoggingContractTest {

    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject
    lateinit var userRepository: UserRepository

    private val passwordEncoder = BCryptPasswordEncoder()
    private lateinit var riskUserToken: String
    private lateinit var reqUserToken: String
    private lateinit var secChampionUserToken: String
    private lateinit var logAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun setup() {
        // Setup log capture for ACCESS_DENIAL_AUDIT logger
        logger = LoggerFactory.getLogger("ACCESS_DENIAL_AUDIT") as Logger
        logAppender = ListAppender<ILoggingEvent>()
        logAppender.start()
        logger.addAppender(logAppender)

        // Create test users
        val riskUser = User(
            username = "risk_logger_test",
            email = "risklog@test.com",
            passwordHash = passwordEncoder.encode("risk123"),
            roles = mutableSetOf(User.Role.USER, User.Role.RISK)
        )
        userRepository.save(riskUser)

        val reqUser = User(
            username = "req_logger_test",
            email = "reqlog@test.com",
            passwordHash = passwordEncoder.encode("req123"),
            roles = mutableSetOf(User.Role.USER, User.Role.REQ)
        )
        userRepository.save(reqUser)

        val secChampionUser = User(
            username = "secchampion_logger_test",
            email = "secchampionlog@test.com",
            passwordHash = passwordEncoder.encode("secchampion123"),
            roles = mutableSetOf(User.Role.USER, User.Role.SECCHAMPION)
        )
        userRepository.save(secChampionUser)

        // Get tokens
        riskUserToken = getAuthToken("risk_logger_test", "risk123")
        reqUserToken = getAuthToken("req_logger_test", "req123")
        secChampionUserToken = getAuthToken("secchampion_logger_test", "secchampion123")

        // Clear any logs from setup
        logAppender.list.clear()
    }

    private fun getAuthToken(username: String, password: String): String {
        val credentials = UsernamePasswordCredentials(username, password)
        val loginRequest = HttpRequest.POST("/api/auth/login", credentials)
        val loginResponse = client.toBlocking().exchange(loginRequest, Map::class.java)
        return (loginResponse.body() as Map<*, *>)["token"] as String
    }

    // ========== T027: RISK role access denial logging ==========

    @Test
    fun `T027 - Access denial is logged when RISK user accesses Requirements endpoint`() {
        // Given: User with RISK role (but NOT REQ or ADMIN)
        // When: Attempt to access /api/requirements (should be denied)
        val request = HttpRequest.GET<Any>("/api/requirements")
            .bearerAuth(riskUserToken)

        // Execute request (expect 403)
        assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, List::class.java)
        }

        // Then: Verify access denial was logged to ACCESS_DENIAL_AUDIT
        // WILL FAIL initially - logging not implemented in RequirementController yet
        assertTrue(logAppender.list.size > 0,
            "Expected at least one log entry in ACCESS_DENIAL_AUDIT logger")

        val logEvent = logAppender.list.firstOrNull {
            it.formattedMessage.contains("Access denied")
        }
        assertNotNull(logEvent, "Expected 'Access denied' log entry")

        // Verify log contains required context
        val message = logEvent!!.formattedMessage
        assertTrue(message.contains("risk_logger_test"),
            "Log should contain username")
        assertTrue(message.contains("RISK") || message.contains("USER"),
            "Log should contain user roles")
        assertTrue(message.contains("/api/requirements"),
            "Log should contain resource path")

        // Verify MDC context was populated (visible in structured logs)
        val mdcProperties = logEvent.mdcPropertyMap
        assertNotNull(mdcProperties, "MDC context should be present")

        // Note: MDC is cleared after logging, so we can't check individual keys here
        // But we can verify the log level and logger name
        assertEquals("ACCESS_DENIAL_AUDIT", logEvent.loggerName,
            "Should use dedicated ACCESS_DENIAL_AUDIT logger")
        assertEquals(ch.qos.logback.classic.Level.WARN, logEvent.level,
            "Access denial should be logged at WARN level (not ERROR - expected behavior)")
    }

    @Test
    fun `T027 - Multiple access denials are logged separately`() {
        // Given: User with RISK role
        // When: Multiple denied access attempts
        val request1 = HttpRequest.GET<Any>("/api/requirements")
            .bearerAuth(riskUserToken)
        val request2 = HttpRequest.GET<Any>("/api/admin/users")
            .bearerAuth(riskUserToken)

        // Execute both requests (both should fail with 403)
        assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request1, List::class.java)
        }
        assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request2, List::class.java)
        }

        // Then: Verify both denials were logged
        val accessDeniedLogs = logAppender.list.filter {
            it.formattedMessage.contains("Access denied")
        }
        assertTrue(accessDeniedLogs.size >= 2,
            "Expected at least 2 access denial log entries, got ${accessDeniedLogs.size}")
    }

    // ========== T045: REQ role access denial logging (Phase 4) ==========

    @Test
    fun `T045 - Access denial is logged when REQ user accesses Risk Assessment endpoint`() {
        // Given: User with REQ role (but NOT RISK or ADMIN)
        // When: Attempt to access /api/risk-assessments (should be denied)
        val request = HttpRequest.GET<Any>("/api/risk-assessments")
            .bearerAuth(reqUserToken)

        // Execute request (expect 403)
        assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, List::class.java)
        }

        // Then: Verify access denial was logged
        // WILL FAIL initially - logging not implemented in RiskAssessmentController yet
        val logEvent = logAppender.list.firstOrNull {
            it.formattedMessage.contains("Access denied")
        }
        assertNotNull(logEvent, "Expected 'Access denied' log entry")

        val message = logEvent!!.formattedMessage
        assertTrue(message.contains("req_logger_test"),
            "Log should contain username")
        assertTrue(message.contains("REQ") || message.contains("USER"),
            "Log should contain user roles")
        assertTrue(message.contains("/api/risk-assessments"),
            "Log should contain resource path")

        assertEquals("ACCESS_DENIAL_AUDIT", logEvent.loggerName)
        assertEquals(ch.qos.logback.classic.Level.WARN, logEvent.level)
    }

    // ========== T065: SECCHAMPION role access denial logging (Phase 5) ==========

    @Test
    fun `T065 - Access denial is logged when SECCHAMPION user accesses Admin endpoint`() {
        // Given: User with SECCHAMPION role (but NOT ADMIN)
        // When: Attempt to access /api/admin/users (should be denied)
        val request = HttpRequest.GET<Any>("/api/admin/users")
            .bearerAuth(secChampionUserToken)

        // Execute request (expect 403)
        assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, List::class.java)
        }

        // Then: Verify access denial was logged
        val logEvent = logAppender.list.firstOrNull {
            it.formattedMessage.contains("Access denied")
        }
        assertNotNull(logEvent, "Expected 'Access denied' log entry")

        val message = logEvent!!.formattedMessage
        assertTrue(message.contains("secchampion_logger_test"),
            "Log should contain username")
        assertTrue(message.contains("SECCHAMPION") || message.contains("USER"),
            "Log should contain user roles")
        assertTrue(message.contains("/api/admin/users"),
            "Log should contain resource path")

        assertEquals("ACCESS_DENIAL_AUDIT", logEvent.loggerName)
        assertEquals(ch.qos.logback.classic.Level.WARN, logEvent.level)
    }

    @Test
    fun `Access denial logs should NOT contain sensitive information`() {
        // Given: User with RISK role
        // When: Access denied to requirements
        val request = HttpRequest.GET<Any>("/api/requirements")
            .bearerAuth(riskUserToken)

        assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, List::class.java)
        }

        // Then: Verify log does NOT contain sensitive info like passwords
        val logEvent = logAppender.list.firstOrNull {
            it.formattedMessage.contains("Access denied")
        }

        if (logEvent != null) {
            val message = logEvent.formattedMessage
            assertFalse(message.contains("password", ignoreCase = true),
                "Log should not contain password information")
            assertFalse(message.contains("token", ignoreCase = true),
                "Log should not contain token information")
        }
    }

    @Test
    fun `Access denial log includes HTTP method when available`() {
        // Given: User with RISK role
        // When: POST request denied (not just GET)
        val request = HttpRequest.POST("/api/requirements", mapOf("test" to "data"))
            .bearerAuth(riskUserToken)

        assertThrows(HttpClientResponseException::class.java) {
            client.toBlocking().exchange(request, Map::class.java)
        }

        // Then: Verify HTTP method is logged if using logAccessDenialWithMethod
        // (This is optional based on controller implementation)
        val logEvent = logAppender.list.firstOrNull {
            it.formattedMessage.contains("Access denied")
        }

        assertNotNull(logEvent, "Expected access denial log entry")
        // HTTP method logging is implementation-specific, so we just verify the log exists
    }
}
