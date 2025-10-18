package com.secman.cli.commands

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for MonitorCommand
 *
 * Related to: Feature 026-crowdstrike-polling-monitor
 * Task: T24
 */
class MonitorCommandTest {

    @Test
    fun `test monitor command initialization`() {
        val command = MonitorCommand()
        
        // Verify default values
        assertEquals(5, command.intervalMinutes)
        assertEquals(emptyList<String>(), command.hostnames)
        assertNull(command.backendUrl)
        assertFalse(command.dryRun)
        assertFalse(command.verbose)
        assertFalse(command.noStorage)
    }
    
    @Test
    fun `test monitor command with custom interval`() {
        val command = MonitorCommand()
        command.intervalMinutes = 10
        
        assertEquals(10, command.intervalMinutes)
    }
    
    @Test
    fun `test monitor command with hostnames`() {
        val command = MonitorCommand()
        command.hostnames = listOf("server01", "server02", "server03")
        
        assertEquals(3, command.hostnames.size)
        assertTrue(command.hostnames.contains("server01"))
    }
    
    @Test
    fun `test monitor command with dry run`() {
        val command = MonitorCommand()
        command.dryRun = true
        
        assertTrue(command.dryRun)
    }
}
