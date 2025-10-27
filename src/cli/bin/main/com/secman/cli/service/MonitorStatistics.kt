package com.secman.cli.service

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Statistics tracker for monitoring operations
 *
 * Tracks:
 * - Total polls executed
 * - Total vulnerabilities found
 * - Total errors encountered
 * - Poll durations
 * - Runtime
 *
 * Related to: Feature 026-crowdstrike-polling-monitor
 * Task: T18-T20
 */
class MonitorStatistics {
    private val startTime = System.currentTimeMillis()
    private val _totalPolls = AtomicInteger(0)
    private val _totalVulnerabilitiesFound = AtomicLong(0)
    private val _totalErrors = AtomicInteger(0)
    private val _totalPollDuration = AtomicLong(0)
    
    val totalPolls: Int
        get() = _totalPolls.get()
    
    val totalVulnerabilitiesFound: Long
        get() = _totalVulnerabilitiesFound.get()
    
    val totalErrors: Int
        get() = _totalErrors.get()
    
    /**
     * Record a successful poll
     */
    fun recordPoll(vulnerabilitiesFound: Int, durationMs: Long) {
        _totalPolls.incrementAndGet()
        _totalVulnerabilitiesFound.addAndGet(vulnerabilitiesFound.toLong())
        _totalPollDuration.addAndGet(durationMs)
    }
    
    /**
     * Record an error
     */
    fun recordError() {
        _totalErrors.incrementAndGet()
    }
    
    /**
     * Calculate average vulnerabilities per poll
     */
    fun averageVulnerabilitiesPerPoll(): Double {
        return if (totalPolls > 0) {
            totalVulnerabilitiesFound.toDouble() / totalPolls
        } else {
            0.0
        }
    }
    
    /**
     * Calculate average poll duration in milliseconds
     */
    fun averagePollDuration(): Long {
        return if (totalPolls > 0) {
            _totalPollDuration.get() / totalPolls
        } else {
            0L
        }
    }
    
    /**
     * Get total runtime as formatted string
     */
    fun totalRuntime(): String {
        val runtimeMs = System.currentTimeMillis() - startTime
        val seconds = (runtimeMs / 1000) % 60
        val minutes = (runtimeMs / (1000 * 60)) % 60
        val hours = (runtimeMs / (1000 * 60 * 60))
        
        return when {
            hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes, seconds)
            minutes > 0 -> String.format("%02d:%02d", minutes, seconds)
            else -> "${seconds}s"
        }
    }
}
