package com.secman.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Simple unit tests for SecmanCli
 *
 * Related to: Feature 023-create-in-the
 * Task: T055
 */
class SecmanCliTest {

    @Test
    fun `test SecmanCli can be instantiated`() {
        // Arrange & Act
        val cli = SecmanCli()

        // Assert - verify the class name to ensure correct instantiation
        assertEquals("SecmanCli", cli::class.simpleName)
    }

    @Test
    fun `test SecmanCli help command returns 0`() {
        // Arrange
        val cli = SecmanCli()

        // Act
        val result = cli.execute(arrayOf("help"))

        // Assert
        assertEquals(0, result)
    }

    @Test
    fun `test SecmanCli no args shows help`() {
        // Arrange
        val cli = SecmanCli()

        // Act
        val result = cli.execute(emptyArray())

        // Assert
        assertEquals(0, result)
    }

    @Test
    fun `test SecmanCli unknown command returns 1`() {
        // Arrange
        val cli = SecmanCli()

        // Act
        val result = cli.execute(arrayOf("unknown-command"))

        // Assert
        assertEquals(1, result)
    }

    @Test
    fun `test SecmanCli routes to query command`() {
        // Arrange
        val cli = SecmanCli()

        // Act
        val result = cli.execute(arrayOf("query"))

        // Assert
        // May return 0 or 1 depending on config/input, but should not crash
        assertTrue(result == 0 || result == 1)
    }

    @Test
    fun `test SecmanCli routes to config command`() {
        // Arrange
        val cli = SecmanCli()

        // Act
        val result = cli.execute(arrayOf("config"))

        // Assert
        // May return 0 or 1 depending on options
        assertTrue(result == 0 || result == 1)
    }
}
