package com.secman.service

import io.micronaut.security.authentication.Authentication
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender

/**
 * Integration tests for AccessDenialLogger
 * Feature: 025-role-based-access-control
 *
 * Tests access denial logging with MDC context
 * TDD approach: Tests written BEFORE usage in controllers (Phase 2 Foundation)
 */
@MicronautTest
class AccessDenialLoggerTest {

    @Inject
    private lateinit var accessDenialLogger: AccessDenialLogger

    private lateinit var logAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun setup() {
        // Setup log capture
        logger = LoggerFactory.getLogger("ACCESS_DENIAL_AUDIT") as Logger
        logAppender = ListAppender<ILoggingEvent>()
        logAppender.start()
        logger.addAppender(logAppender)

        // Clear MDC before each test
        MDC.clear()
    }

    @AfterEach
    fun cleanup() {
        // Remove appender and clear MDC
        logger.detachAppender(logAppender)
        logAppender.stop()
        MDC.clear()
    }

    private fun createMockAuthentication(
        username: String,
        roles: List<String>
    ): Authentication {
        return Authentication.build(username, roles)
    }

    // T011: Integration test for logAccessDenial()
    @Test
    fun `logAccessDenial should log with correct message format`() {
        // Arrange
        val auth = createMockAuthentication("john.doe", listOf("USER"))
        val resource = "/api/risk-assessments"
        val requiredRoles = listOf("ADMIN", "RISK")
        val ipAddress = "192.168.1.100"

        // Act
        accessDenialLogger.logAccessDenial(auth, resource, requiredRoles, ipAddress)

        // Assert
        assertEquals(1, logAppender.list.size, "Should log exactly one event")

        val logEvent = logAppender.list[0]
        assertEquals(ch.qos.logback.classic.Level.WARN, logEvent.level, "Should log at WARN level")

        val message = logEvent.formattedMessage
        assertTrue(message.contains("Access denied"), "Message should contain 'Access denied'")
        assertTrue(message.contains("user='john.doe'"), "Message should contain username")
        assertTrue(message.contains("roles=[USER]"), "Message should contain user roles")
        assertTrue(message.contains("resource='/api/risk-assessments'"), "Message should contain resource")
        assertTrue(message.contains("required=[ADMIN,RISK]"), "Message should contain required roles")
        assertTrue(message.contains("ip='192.168.1.100'"), "Message should contain IP address")
    }

    @Test
    fun `logAccessDenial should handle missing IP address gracefully`() {
        // Arrange
        val auth = createMockAuthentication("jane.smith", listOf("USER", "REQ"))
        val resource = "/api/risk-assessments/123"
        val requiredRoles = listOf("RISK")

        // Act - no IP address provided
        accessDenialLogger.logAccessDenial(auth, resource, requiredRoles)

        // Assert
        assertEquals(1, logAppender.list.size)

        val message = logAppender.list[0].formattedMessage
        assertTrue(message.contains("ip='unknown'"), "Should use 'unknown' when IP is null")
    }

    @Test
    fun `logAccessDenial should handle user with multiple roles`() {
        // Arrange
        val auth = createMockAuthentication("admin.user", listOf("USER", "ADMIN", "VULN", "REQ"))
        val resource = "/api/admin/users"
        val requiredRoles = listOf("ADMIN")

        // Act
        accessDenialLogger.logAccessDenial(auth, resource, requiredRoles, "10.0.0.1")

        // Assert
        val message = logAppender.list[0].formattedMessage
        assertTrue(message.contains("roles=[USER,ADMIN,VULN,REQ]"),
            "Should log all user roles comma-separated")
    }

    @Test
    fun `logAccessDenial should handle multiple required roles`() {
        // Arrange
        val auth = createMockAuthentication("user1", listOf("USER"))
        val resource = "/api/requirements"
        val requiredRoles = listOf("ADMIN", "REQ", "SECCHAMPION")

        // Act
        accessDenialLogger.logAccessDenial(auth, resource, requiredRoles)

        // Assert
        val message = logAppender.list[0].formattedMessage
        assertTrue(message.contains("required=[ADMIN,REQ,SECCHAMPION]"),
            "Should log all required roles comma-separated")
    }

    @Test
    fun `logAccessDenial should clear MDC after logging to prevent context leakage`() {
        // Arrange
        val auth = createMockAuthentication("testuser", listOf("USER"))
        val resource = "/api/test"
        val requiredRoles = listOf("ADMIN")

        // Act
        accessDenialLogger.logAccessDenial(auth, resource, requiredRoles, "1.2.3.4")

        // Assert - MDC should be empty after method completes
        assertNull(MDC.get("event_type"), "MDC should be cleared after logging")
        assertNull(MDC.get("user_id"), "MDC should be cleared after logging")
        assertNull(MDC.get("user_roles"), "MDC should be cleared after logging")
        assertNull(MDC.get("resource"), "MDC should be cleared after logging")
        assertNull(MDC.get("required_roles"), "MDC should be cleared after logging")
        assertNull(MDC.get("timestamp"), "MDC should be cleared after logging")
        assertNull(MDC.get("ip_address"), "MDC should be cleared after logging")
    }

    // T012: Integration test for logAccessDenialWithMethod()
    @Test
    fun `logAccessDenialWithMethod should log with HTTP method included`() {
        // Arrange
        val auth = createMockAuthentication("bob.jones", listOf("USER", "VULN"))
        val httpMethod = "POST"
        val resource = "/api/risk-assessments"
        val requiredRoles = listOf("RISK")
        val ipAddress = "172.16.0.50"

        // Act
        accessDenialLogger.logAccessDenialWithMethod(auth, httpMethod, resource, requiredRoles, ipAddress)

        // Assert
        assertEquals(1, logAppender.list.size)

        val logEvent = logAppender.list[0]
        assertEquals(ch.qos.logback.classic.Level.WARN, logEvent.level)

        val message = logEvent.formattedMessage
        assertTrue(message.contains("Access denied"), "Message should contain 'Access denied'")
        assertTrue(message.contains("user='bob.jones'"), "Message should contain username")
        assertTrue(message.contains("method=POST"), "Message should contain HTTP method")
        assertTrue(message.contains("resource='/api/risk-assessments'"), "Message should contain resource")
        assertTrue(message.contains("roles=[USER,VULN]"), "Message should contain user roles")
        assertTrue(message.contains("required=[RISK]"), "Message should contain required roles")
    }

    @Test
    fun `logAccessDenialWithMethod should handle all HTTP methods`() {
        val auth = createMockAuthentication("testuser", listOf("USER"))
        val resource = "/api/test"
        val requiredRoles = listOf("ADMIN")

        // Test common HTTP methods
        val methods = listOf("GET", "POST", "PUT", "DELETE", "PATCH")

        methods.forEach { method ->
            // Clear previous logs
            logAppender.list.clear()

            // Act
            accessDenialLogger.logAccessDenialWithMethod(auth, method, resource, requiredRoles)

            // Assert
            val message = logAppender.list[0].formattedMessage
            assertTrue(message.contains("method=$method"),
                "Should log HTTP method $method correctly")
        }
    }

    @Test
    fun `logAccessDenialWithMethod should handle missing IP address`() {
        // Arrange
        val auth = createMockAuthentication("alice", listOf("REQ"))
        val httpMethod = "GET"
        val resource = "/api/risk-assessments/search"
        val requiredRoles = listOf("ADMIN", "RISK")

        // Act - no IP address provided
        accessDenialLogger.logAccessDenialWithMethod(auth, httpMethod, resource, requiredRoles)

        // Assert
        assertEquals(1, logAppender.list.size)
        val message = logAppender.list[0].formattedMessage
        // Note: logAccessDenialWithMethod doesn't include IP in the message format
        // It only logs: user, method, resource, roles, required
        assertFalse(message.contains("ip="), "IP should not be in this message format")
    }

    @Test
    fun `logAccessDenialWithMethod should clear MDC after logging`() {
        // Arrange
        val auth = createMockAuthentication("testuser", listOf("USER"))
        val resource = "/api/test"
        val requiredRoles = listOf("ADMIN")

        // Act
        accessDenialLogger.logAccessDenialWithMethod(auth, "DELETE", resource, requiredRoles, "5.6.7.8")

        // Assert - MDC should be empty after method completes
        assertNull(MDC.get("event_type"), "MDC should be cleared")
        assertNull(MDC.get("user_id"), "MDC should be cleared")
        assertNull(MDC.get("user_roles"), "MDC should be cleared")
        assertNull(MDC.get("http_method"), "MDC should be cleared")
        assertNull(MDC.get("resource"), "MDC should be cleared")
        assertNull(MDC.get("required_roles"), "MDC should be cleared")
        assertNull(MDC.get("timestamp"), "MDC should be cleared")
        assertNull(MDC.get("ip_address"), "MDC should be cleared")
    }

    @Test
    fun `logAccessDenialWithMethod should use dedicated ACCESS_DENIAL_AUDIT logger`() {
        // Arrange
        val auth = createMockAuthentication("sectest", listOf("SECCHAMPION"))
        val resource = "/api/admin/settings"
        val requiredRoles = listOf("ADMIN")

        // Act
        accessDenialLogger.logAccessDenialWithMethod(auth, "PUT", resource, requiredRoles)

        // Assert
        assertEquals(1, logAppender.list.size)
        assertEquals("ACCESS_DENIAL_AUDIT", logAppender.list[0].loggerName,
            "Should use dedicated ACCESS_DENIAL_AUDIT logger")
    }

    @Test
    fun `multiple consecutive access denials should not leak MDC context between calls`() {
        // Arrange
        val auth1 = createMockAuthentication("user1", listOf("USER"))
        val auth2 = createMockAuthentication("user2", listOf("REQ"))

        // Act - log two denials in sequence
        accessDenialLogger.logAccessDenial(auth1, "/api/risk1", listOf("RISK"), "1.1.1.1")

        // Check MDC is clear before second call
        assertNull(MDC.get("user_id"), "MDC should be cleared between calls")

        accessDenialLogger.logAccessDenial(auth2, "/api/risk2", listOf("ADMIN"), "2.2.2.2")

        // Assert - both should be logged correctly without interference
        assertEquals(2, logAppender.list.size)
        assertTrue(logAppender.list[0].formattedMessage.contains("user1"))
        assertTrue(logAppender.list[1].formattedMessage.contains("user2"))

        // MDC should still be clear
        assertNull(MDC.get("user_id"), "MDC should be cleared after all calls")
    }

    @Test
    fun `logAccessDenial should handle empty roles gracefully`() {
        // Arrange - user with no roles (edge case)
        val auth = createMockAuthentication("noroles", emptyList())
        val resource = "/api/test"
        val requiredRoles = listOf("ADMIN")

        // Act
        accessDenialLogger.logAccessDenial(auth, resource, requiredRoles)

        // Assert
        assertEquals(1, logAppender.list.size)
        val message = logAppender.list[0].formattedMessage
        assertTrue(message.contains("roles=[]"), "Should handle empty roles list")
    }

    @Test
    fun `logAccessDenial should handle empty required roles`() {
        // Arrange
        val auth = createMockAuthentication("testuser", listOf("USER"))
        val resource = "/api/test"
        val requiredRoles = emptyList<String>()

        // Act
        accessDenialLogger.logAccessDenial(auth, resource, requiredRoles)

        // Assert
        assertEquals(1, logAppender.list.size)
        val message = logAppender.list[0].formattedMessage
        assertTrue(message.contains("required=[]"), "Should handle empty required roles list")
    }
}
