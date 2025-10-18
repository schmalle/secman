package com.secman.crowdstrike.unit

import com.secman.crowdstrike.model.Host
import com.secman.crowdstrike.model.Severity
import com.secman.crowdstrike.model.Vulnerability
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for Host domain model
 *
 * Tests hostname validation and severity analysis
 * Related to: Feature 023-create-in-the
 * Task: T043
 */
class HostTest {

    /**
     * Test: Valid hostname creation
     */
    @Test
    fun `Host should accept valid hostname`() {
        // Arrange & Act
        val host = Host(
            hostname = "web-server-01.example.com",
            hostId = "aid-123",
            operatingSystem = "Windows Server 2019"
        )

        // Assert
        assertEquals("web-server-01.example.com", host.hostname)
        assertEquals("aid-123", host.hostId)
    }

    /**
     * Test: Blank hostname rejected
     */
    @Test
    fun `Host should reject blank hostname`() {
        // Act & Assert
        assertThrows<IllegalArgumentException> {
            Host(hostname = "")
        }
    }

    /**
     * Test: Hostname with invalid characters rejected
     */
    @Test
    fun `Host should reject hostname with invalid characters`() {
        // Act & Assert
        assertThrows<IllegalArgumentException> {
            Host(hostname = "server@#$%")
        }
    }

    /**
     * Test: Valid hostname characters
     */
    @Test
    fun `Host should accept hostnames with letters numbers hyphens and periods`() {
        // Arrange & Act
        val host = Host(hostname = "srv-db1.test.example.com")

        // Assert
        assertEquals("srv-db1.test.example.com", host.hostname)
    }

    /**
     * Test: Single character hostname
     */
    @Test
    fun `Host should accept single character hostname`() {
        // Act
        val host = Host(hostname = "a")

        // Assert
        assertEquals("a", host.hostname)
    }

    /**
     * Test: vulnerabilitiesBySeverity() empty
     */
    @Test
    fun `vulnerabilitiesBySeverity should return empty map when no vulnerabilities`() {
        // Arrange
        val host = Host(hostname = "test-host")

        // Act
        val bySeverity = host.vulnerabilitiesBySeverity()

        // Assert
        assertEquals(0, bySeverity.size)
    }

    /**
     * Test: vulnerabilitiesBySeverity() counts correctly
     */
    @Test
    fun `vulnerabilitiesBySeverity should count vulnerabilities by severity`() {
        // Arrange
        val vulns = listOf(
            Vulnerability(
                cveId = "CVE-2023-0001",
                severity = Severity.CRITICAL,
                affectedSoftware = "OpenSSL",
                description = "Critical bug",
                cvssScore = 9.5
            ),
            Vulnerability(
                cveId = "CVE-2023-0002",
                severity = Severity.CRITICAL,
                affectedSoftware = "nginx",
                description = "Critical config",
                cvssScore = 9.0
            ),
            Vulnerability(
                cveId = "CVE-2023-0003",
                severity = Severity.HIGH,
                affectedSoftware = "curl",
                description = "High severity",
                cvssScore = 7.5
            ),
            Vulnerability(
                cveId = "CVE-2023-0004",
                severity = Severity.MEDIUM,
                affectedSoftware = "git",
                description = "Medium issue",
                cvssScore = 5.0
            )
        )

        val host = Host(hostname = "test-host", vulnerabilities = vulns)

        // Act
        val bySeverity = host.vulnerabilitiesBySeverity()

        // Assert
        assertEquals(3, bySeverity.size)
        assertEquals(2, bySeverity[Severity.CRITICAL])
        assertEquals(1, bySeverity[Severity.HIGH])
        assertEquals(1, bySeverity[Severity.MEDIUM])
    }

    /**
     * Test: vulnerabilitiesBySeverity() handles all severity levels
     */
    @Test
    fun `vulnerabilitiesBySeverity should include all severity levels present`() {
        // Arrange
        val vulns = listOf(
            Vulnerability(
                cveId = "CVE-2023-0001",
                severity = Severity.LOW,
                affectedSoftware = "lib",
                description = "Low severity",
                cvssScore = 2.0
            )
        )

        val host = Host(hostname = "test-host", vulnerabilities = vulns)

        // Act
        val bySeverity = host.vulnerabilitiesBySeverity()

        // Assert
        assertEquals(1, bySeverity.size)
        assertEquals(1, bySeverity[Severity.LOW])
    }

    /**
     * Test: Optional fields can be null
     */
    @Test
    fun `Host should allow optional fields to be null`() {
        // Arrange & Act
        val host = Host(
            hostname = "test-host",
            hostId = null,
            operatingSystem = null,
            ipAddress = null,
            lastSeen = null,
            vulnerabilities = emptyList()
        )

        // Assert
        assertEquals("test-host", host.hostname)
        assertEquals(null, host.hostId)
        assertEquals(null, host.operatingSystem)
    }

    /**
     * Test: Data class with all fields
     */
    @Test
    fun `Host should store all provided fields`() {
        // Arrange
        val lastSeen = Instant.now()

        // Act
        val host = Host(
            hostname = "prod-server",
            hostId = "aid-456",
            operatingSystem = "Linux",
            ipAddress = "192.168.1.100",
            lastSeen = lastSeen,
            vulnerabilities = emptyList()
        )

        // Assert
        assertEquals("prod-server", host.hostname)
        assertEquals("aid-456", host.hostId)
        assertEquals("Linux", host.operatingSystem)
        assertEquals("192.168.1.100", host.ipAddress)
        assertEquals(lastSeen, host.lastSeen)
    }

    /**
     * Test: Data class equality
     */
    @Test
    fun `Host with same data should be equal`() {
        // Arrange
        val host1 = Host(hostname = "server-01", hostId = "id-123")
        val host2 = Host(hostname = "server-01", hostId = "id-123")

        // Assert
        assertEquals(host1, host2)
    }
}
