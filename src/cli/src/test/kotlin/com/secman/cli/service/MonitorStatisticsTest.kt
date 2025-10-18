package com.secman.cli.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for MonitorStatistics
 *
 * Related to: Feature 026-crowdstrike-polling-monitor
 * Task: T24
 */
class MonitorStatisticsTest {

    @Test
    fun `test initial statistics are zero`() {
        val stats = MonitorStatistics()
        
        assertEquals(0, stats.totalPolls)
        assertEquals(0L, stats.totalVulnerabilitiesFound)
        assertEquals(0, stats.totalErrors)
        assertEquals(0.0, stats.averageVulnerabilitiesPerPoll())
        assertEquals(0L, stats.averagePollDuration())
    }
    
    @Test
    fun `test record poll updates statistics`() {
        val stats = MonitorStatistics()
        
        stats.recordPoll(10, 1000)
        
        assertEquals(1, stats.totalPolls)
        assertEquals(10L, stats.totalVulnerabilitiesFound)
        assertEquals(10.0, stats.averageVulnerabilitiesPerPoll())
        assertEquals(1000L, stats.averagePollDuration())
    }
    
    @Test
    fun `test multiple polls calculate correct averages`() {
        val stats = MonitorStatistics()
        
        stats.recordPoll(10, 1000)
        stats.recordPoll(20, 2000)
        stats.recordPoll(30, 3000)
        
        assertEquals(3, stats.totalPolls)
        assertEquals(60L, stats.totalVulnerabilitiesFound)
        assertEquals(20.0, stats.averageVulnerabilitiesPerPoll())
        assertEquals(2000L, stats.averagePollDuration())
    }
    
    @Test
    fun `test record error increments error count`() {
        val stats = MonitorStatistics()
        
        stats.recordError()
        stats.recordError()
        
        assertEquals(2, stats.totalErrors)
    }
    
    @Test
    fun `test total runtime is formatted correctly`() {
        val stats = MonitorStatistics()
        Thread.sleep(100) // Wait a bit
        
        val runtime = stats.totalRuntime()
        assertNotNull(runtime)
        assertTrue(runtime.isNotEmpty())
    }
}
