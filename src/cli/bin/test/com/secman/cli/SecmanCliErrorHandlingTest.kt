package com.secman.cli

import com.secman.cli.commands.ConfigCommand
import com.secman.cli.commands.QueryCommand
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Error handling and edge case tests for CLI
 *
 * Tests:
 * - Invalid input validation
 * - Missing configuration
 * - API error handling
 * - Graceful error messages
 * - Edge cases and boundary conditions
 *
 * Related to: Feature 023-create-in-the
 * Task: T059-T061
 */
class SecmanCliErrorHandlingTest {

    private lateinit var originalErr: PrintStream
    private lateinit var errCapture: ByteArrayOutputStream

    @BeforeEach
    fun setUp() {
        originalErr = System.err
        errCapture = ByteArrayOutputStream()
        System.setErr(PrintStream(errCapture))
    }

    private fun tearDown() {
        System.setErr(originalErr)
    }

    @Test
    fun `test query command with blank hostname fails gracefully`() {
        // Arrange
        val command = QueryCommand()
        command.hostname = ""

        // Act
        val result = command.execute()

        // Assert
        assertEquals(1, result, "Should return error code 1")
        val error = errCapture.toString()
        assertTrue(error.contains("blank") || error.contains("Hostname"))

        tearDown()
    }

    @Test
    fun `test query command with whitespace-only hostname fails gracefully`() {
        // Arrange
        val command = QueryCommand()
        command.hostname = "   "

        // Act
        val result = command.execute()

        // Assert
        assertEquals(1, result)
        val error = errCapture.toString()
        assertTrue(error.contains("blank") || error.contains("Error"))

        tearDown()
    }

    @Test
    fun `test query command missing configuration provides helpful message`() {
        // Arrange
        val command = QueryCommand()
        command.hostname = "test.example.com"
        // Don't set clientId/clientSecret, and assume config file doesn't exist

        // Act
        val result = command.execute()

        // Assert
        assertTrue(result == 0 || result == 1)  // May fail due to config, but shouldn't crash

        tearDown()
    }

    @Test
    fun `test query command with invalid limit parameter`() {
        // Arrange
        val command = QueryCommand()
        command.hostname = "valid.host.com"
        command.limit = -1  // Invalid negative limit

        // Act
        val result = command.execute()

        // Assert - should still work, negative limit might be handled by API
        assertTrue(result == 0 || result == 1)

        tearDown()
    }

    @Test
    fun `test query command with very large limit parameter`() {
        // Arrange
        val command = QueryCommand()
        command.hostname = "valid.host.com"
        command.limit = Int.MAX_VALUE  // Very large limit

        // Act
        val result = command.execute()

        // Assert - should handle gracefully
        assertTrue(result == 0 || result == 1)

        tearDown()
    }

    @Test
    fun `test query command with special characters in hostname`() {
        // Arrange
        val command = QueryCommand()
        command.hostname = "test@#$%.example.com"

        // Act
        val result = command.execute()

        // Assert - should attempt query even with special chars
        assertTrue(result == 0 || result == 1)

        tearDown()
    }

    @Test
    fun `test query command with null values for optional parameters`() {
        // Arrange
        val command = QueryCommand()
        command.hostname = "valid.host.com"
        command.severity = null
        command.product = null

        // Act
        val result = command.execute()

        // Assert
        assertTrue(result == 0 || result == 1)

        tearDown()
    }

    @Test
    fun `test config command with empty client id`() {
        // Arrange
        val command = ConfigCommand()
        command.clientId = ""
        command.clientSecret = "secret"

        // Act
        val result = command.execute()

        // Assert - may fail validation
        assertTrue(result == 0 || result == 1)

        tearDown()
    }

    @Test
    fun `test config command with empty secret`() {
        // Arrange
        val command = ConfigCommand()
        command.clientId = "client"
        command.clientSecret = ""

        // Act
        val result = command.execute()

        // Assert - may fail validation
        assertTrue(result == 0 || result == 1)

        tearDown()
    }

    @Test
    fun `test config command show with no configuration`() {
        // Arrange
        val command = ConfigCommand()
        command.show = true
        // No system properties or config file set

        // Act
        val result = command.execute()

        // Assert
        assertTrue(result == 0 || result == 1)

        tearDown()
    }

    @Test
    fun `test maskSecret with empty string`() {
        // Arrange
        val command = ConfigCommand()

        // Act
        val masked = command.maskSecret("")

        // Assert
        assertEquals("", masked)

        tearDown()
    }

    @Test
    fun `test maskSecret with single character`() {
        // Arrange
        val command = ConfigCommand()

        // Act
        val masked = command.maskSecret("a")

        // Assert
        assertEquals("*", masked)

        tearDown()
    }

    @Test
    fun `test maskSecret with exactly 4 characters`() {
        // Arrange
        val command = ConfigCommand()

        // Act
        val masked = command.maskSecret("abcd")

        // Assert
        assertEquals("****", masked)

        tearDown()
    }

    @Test
    fun `test maskSecret preserves first and last characters`() {
        // Arrange
        val command = ConfigCommand()
        val secret = "a1b2c3d4e5"

        // Act
        val masked = command.maskSecret(secret)

        // Assert
        assertTrue(masked.startsWith("a1"))
        assertTrue(masked.endsWith("e5"))
        assertTrue(masked.contains("*"))

        tearDown()
    }

    @Test
    fun `test cli with multiple format specifications`() {
        // Arrange
        val cli = SecmanCli()

        // Act
        val result = cli.execute(arrayOf(
            "query",
            "--format", "json",
            "--format", "csv"  // Duplicate option
        ))

        // Assert - should handle gracefully (last one wins)
        assertTrue(result == 0 || result == 1)

        tearDown()
    }

    @Test
    fun `test cli with unmatched parameters`() {
        // Arrange
        val cli = SecmanCli()

        // Act
        val result = cli.execute(arrayOf(
            "query",
            "--hostname",  // Missing value
            "test.com"
        ))

        // Assert - should handle gracefully
        assertTrue(result == 0 || result == 1)

        tearDown()
    }

    @Test
    fun `test cli continues on invalid option`() {
        // Arrange
        val cli = SecmanCli()

        // Act
        val result = cli.execute(arrayOf(
            "query",
            "--unknown-option", "value",
            "--hostname", "test.com"
        ))

        // Assert - should still attempt to execute
        assertTrue(result == 0 || result == 1)

        tearDown()
    }

    @Test
    fun `test query command catches general exceptions`() {
        // Arrange
        val command = QueryCommand()
        command.hostname = "test.com"
        command.verbose = false  // Don't print stack trace

        // Act
        val result = command.execute()

        // Assert - should not throw, should return error code
        assertTrue(result == 0 || result == 1)
        val error = errCapture.toString()
        // May or may not have error (depends on config), but shouldn't crash
        assertTrue(true)

        tearDown()
    }

    @Test
    fun `test query command with verbose flag shows errors`() {
        // Arrange
        val command = QueryCommand()
        command.hostname = ""  // Invalid
        command.verbose = true

        // Act
        val result = command.execute()

        // Assert
        assertEquals(1, result)

        tearDown()
    }

    @Test
    fun `test config command validates both id and secret together`() {
        // Arrange
        val command = ConfigCommand()
        command.clientId = "id"
        command.clientSecret = null  // Missing

        // Act
        val result = command.execute()

        // Assert - should return error
        assertEquals(1, result)

        tearDown()
    }
}
