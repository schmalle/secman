package com.secman.crowdstrike.unit

import com.secman.crowdstrike.model.Severity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Unit tests for Severity enum
 *
 * Tests enum values and parsing functionality
 * Related to: Feature 023-create-in-the
 * Task: T044
 */
class SeverityTest {

    /**
     * Test: All severity levels exist
     */
    @Test
    fun `Severity should have all required levels`() {
        // Assert
        val severities = Severity.values()
        assertEquals(4, severities.size)
        assertEquals(setOf(Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM, Severity.LOW), severities.toSet())
    }

    /**
     * Test: fromString() with uppercase
     */
    @Test
    fun `fromString should parse uppercase severity`() {
        // Assert
        assertEquals(Severity.CRITICAL, Severity.fromString("CRITICAL"))
        assertEquals(Severity.HIGH, Severity.fromString("HIGH"))
        assertEquals(Severity.MEDIUM, Severity.fromString("MEDIUM"))
        assertEquals(Severity.LOW, Severity.fromString("LOW"))
    }

    /**
     * Test: fromString() with lowercase
     */
    @Test
    fun `fromString should parse lowercase severity`() {
        // Assert
        assertEquals(Severity.CRITICAL, Severity.fromString("critical"))
        assertEquals(Severity.HIGH, Severity.fromString("high"))
        assertEquals(Severity.MEDIUM, Severity.fromString("medium"))
        assertEquals(Severity.LOW, Severity.fromString("low"))
    }

    /**
     * Test: fromString() with mixed case
     */
    @Test
    fun `fromString should parse mixed case severity`() {
        // Assert
        assertEquals(Severity.CRITICAL, Severity.fromString("Critical"))
        assertEquals(Severity.HIGH, Severity.fromString("High"))
        assertEquals(Severity.MEDIUM, Severity.fromString("Medium"))
        assertEquals(Severity.LOW, Severity.fromString("Low"))
    }

    /**
     * Test: fromString() with invalid value defaults to MEDIUM
     */
    @Test
    fun `fromString should default to MEDIUM for invalid values`() {
        // Assert
        assertEquals(Severity.MEDIUM, Severity.fromString("INVALID"))
        assertEquals(Severity.MEDIUM, Severity.fromString("UNKNOWN"))
        assertEquals(Severity.MEDIUM, Severity.fromString(""))
        assertEquals(Severity.MEDIUM, Severity.fromString("XYZ"))
    }

    /**
     * Test: Enum name()
     */
    @Test
    fun `Severity enum name should match constant`() {
        // Assert
        assertEquals("CRITICAL", Severity.CRITICAL.name)
        assertEquals("HIGH", Severity.HIGH.name)
        assertEquals("MEDIUM", Severity.MEDIUM.name)
        assertEquals("LOW", Severity.LOW.name)
    }
}
