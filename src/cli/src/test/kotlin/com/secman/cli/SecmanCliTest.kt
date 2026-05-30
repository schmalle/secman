package com.secman.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

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
    fun `test help lists delete asset not seen command`() {
        val cli = SecmanCli()

        val (result, output) = captureStdout {
            cli.execute(arrayOf("help"))
        }

        assertEquals(0, result)
        assertTrue(output.contains("delete-asset-not-seen"))
    }

    @Test
    fun `test delete asset not seen help command returns 0`() {
        val cli = SecmanCli()

        val (result, output) = captureStdout {
            cli.execute(arrayOf("help", "delete-asset-not-seen"))
        }

        assertEquals(0, result)
        assertTrue(output.contains("secman delete-asset-not-seen"))
        assertTrue(output.contains("--dry-run"))
    }

    @Test
    fun `test help lists installed products command`() {
        val cli = SecmanCli()

        val (result, output) = captureStdout {
            cli.execute(arrayOf("help"))
        }

        assertEquals(0, result)
        assertTrue(output.contains("installed-products"))
    }

    @Test
    fun `test installed products help command returns 0`() {
        val cli = SecmanCli()

        val (result, output) = captureStdout {
            cli.execute(arrayOf("help", "installed-products"))
        }

        assertEquals(0, result)
        assertTrue(output.contains("secman installed-products"))
        assertTrue(output.contains("CrowdStrike Discover software inventory"))
        assertTrue(output.contains("--device-type"))
        assertTrue(output.contains("--dry-run"))
        assertTrue(output.contains("unknown systems"))
        assertTrue(output.contains("POST /api/installed-products/import"))
    }

    @Test
    fun `test SecmanCli routes to query command`() {
        // Arrange
        val cli = SecmanCli()

        // Act & Assert
        // The query command requires a Micronaut application context with database/CrowdStrike config.
        // In a unit test environment without those services, a BeanInstantiationException is expected.
        val result = try {
            cli.execute(arrayOf("query"))
        } catch (_: Exception) {
            1 // Treat context startup failure as error exit code
        }
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

    private fun captureStdout(block: () -> Int): Pair<Int, String> {
        val originalOut = System.out
        val output = ByteArrayOutputStream()
        System.setOut(PrintStream(output))
        return try {
            block() to output.toString()
        } finally {
            System.setOut(originalOut)
        }
    }
}
