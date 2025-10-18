package com.secman.cli.commands

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Simple unit tests for ConfigCommand
 *
 * Related to: Feature 023-create-in-the
 * Task: T055
 */
class ConfigCommandTest {

    @Test
    fun `test ConfigCommand can be instantiated`() {
        // Arrange & Act
        val command = ConfigCommand()

        // Assert
        assertEquals("yaml", command.format)
        assertEquals("https://api.crowdstrike.com", command.baseUrl)
    }

    @Test
    fun `test ConfigCommand properties can be set`() {
        // Arrange
        val command = ConfigCommand()

        // Act
        command.clientId = "test-id"
        command.clientSecret = "test-secret"
        command.show = true
        command.format = "conf"

        // Assert
        assertEquals("test-id", command.clientId)
        assertEquals("test-secret", command.clientSecret)
        assertEquals(true, command.show)
        assertEquals("conf", command.format)
    }

    @Test
    fun `test maskSecret masks long secrets`() {
        // Arrange
        val command = ConfigCommand()
        val secret = "my-super-secret-key-12345"

        // Act
        val masked = command.maskSecret(secret)

        // Assert
        assertTrue(masked.contains("*"))
        assertTrue(masked.startsWith("my"))
        // Last 2 chars of "my-super-secret-key-12345" should be "45"
        assertTrue(masked.endsWith("45"))
    }

    @Test
    fun `test maskSecret masks short secrets`() {
        // Arrange
        val command = ConfigCommand()

        // Act
        val masked = command.maskSecret("abc")

        // Assert
        assertEquals("***", masked)
    }
}
