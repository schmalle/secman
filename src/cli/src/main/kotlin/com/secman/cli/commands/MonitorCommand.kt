package com.secman.cli.commands

import com.secman.cli.config.ConfigLoader
import com.secman.cli.service.CrowdStrikePollerService
import com.secman.cli.service.MonitorStatistics
import com.secman.crowdstrike.dto.FalconConfigDto
import io.micronaut.context.ApplicationContext
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Monitor command for continuous CrowdStrike vulnerability polling
 *
 * Functionality:
 * - Poll CrowdStrike API at regular intervals
 * - Filter for HIGH and CRITICAL severity vulnerabilities
 * - Store results in secman database
 * - Graceful shutdown handling
 *
 * Usage:
 *   secman monitor [options]
 *   secman monitor --interval 10
 *   secman monitor --hostnames server01,server02
 *   secman monitor --dry-run
 *
 * Related to: Feature 026-crowdstrike-polling-monitor
 * Tasks: T1-T3
 */
class MonitorCommand {
    private val log = LoggerFactory.getLogger(MonitorCommand::class.java)
    private val configLoader = ConfigLoader()
    private val appContext = ApplicationContext.run()
    private val pollerService: CrowdStrikePollerService = appContext.getBean(CrowdStrikePollerService::class.java)
    private val statistics = MonitorStatistics()
    
    private var scheduler: ScheduledExecutorService? = null
    private val running = AtomicBoolean(false)
    
    // Command line options
    var intervalMinutes: Int = 5
    var hostnames: List<String> = emptyList()
    var backendUrl: String? = null
    var dryRun: Boolean = false
    var verbose: Boolean = false
    var noStorage: Boolean = false
    var configPath: String? = null
    
    fun execute(): Int {
        return try {
            log.info("Starting CrowdStrike vulnerability monitor")
            System.out.println("=== CrowdStrike Vulnerability Monitor ===")
            System.out.println("Polling interval: $intervalMinutes minutes")
            System.out.println("Severity filter: HIGH, CRITICAL")
            System.out.println("Dry run: ${if (dryRun) "YES (no storage)" else "NO"}")
            if (hostnames.isNotEmpty()) {
                System.out.println("Monitoring hostnames: ${hostnames.joinToString(", ")}")
            } else {
                System.out.println("Monitoring: ALL devices")
            }
            System.out.println("Storage: ${if (noStorage || dryRun) "DISABLED" else "ENABLED"}")
            System.out.println()
            
            // Load CrowdStrike configuration
            val config = configLoader.loadConfig()
            log.info("CrowdStrike configuration loaded successfully")
            
            // Setup shutdown hook for graceful termination
            setupShutdownHook()
            
            // Start monitoring
            startMonitoring(config)
            
            // Wait for shutdown signal
            while (running.get()) {
                Thread.sleep(1000)
            }
            
            log.info("Monitor stopped")
            0
        } catch (e: IllegalStateException) {
            System.err.println("Configuration error: ${e.message}")
            log.error("Configuration error", e)
            1
        } catch (e: Exception) {
            System.err.println("Monitor error: ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            log.error("Monitor error", e)
            1
        } finally {
            cleanup()
        }
    }
    
    /**
     * Start the monitoring scheduler
     */
    private fun startMonitoring(config: FalconConfigDto) {
        running.set(true)
        scheduler = Executors.newScheduledThreadPool(1)
        
        // Configure poller service
        pollerService.backendUrl = backendUrl
        pollerService.dryRun = dryRun || noStorage
        pollerService.verbose = verbose
        
        System.out.println("Starting monitor... Press Ctrl+C to stop")
        System.out.println()
        
        // Run first poll immediately
        executePoll(config)
        
        // Schedule periodic polling
        scheduler!!.scheduleAtFixedRate(
            {
                try {
                    executePoll(config)
                } catch (e: Exception) {
                    log.error("Poll execution failed", e)
                    statistics.recordError()
                }
            },
            intervalMinutes.toLong(),
            intervalMinutes.toLong(),
            TimeUnit.MINUTES
        )
        
        log.info("Monitor started with {}-minute interval", intervalMinutes)
    }
    
    /**
     * Execute a single poll cycle
     */
    private fun executePoll(config: FalconConfigDto) {
        val startTime = System.currentTimeMillis()
        
        try {
            log.info("=== Starting poll cycle #{} ===", statistics.totalPolls + 1)
            System.out.println("[${getCurrentTimestamp()}] Polling CrowdStrike API...")
            
            val result = if (hostnames.isNotEmpty()) {
                pollerService.pollHostnames(hostnames, config)
            } else {
                pollerService.pollAllDevices(config)
            }
            
            val duration = System.currentTimeMillis() - startTime
            statistics.recordPoll(result.totalVulnerabilities, duration)
            
            System.out.println("[${getCurrentTimestamp()}] Poll complete:")
            System.out.println("  - Devices queried: ${result.devicesQueried}")
            System.out.println("  - HIGH/CRITICAL vulnerabilities: ${result.totalVulnerabilities}")
            System.out.println("  - Stored: ${result.stored}")
            System.out.println("  - Skipped (duplicates): ${result.skipped}")
            System.out.println("  - Duration: ${duration}ms")
            System.out.println()
            
            log.info("Poll cycle completed: devices={}, vulnerabilities={}, stored={}, duration={}ms",
                result.devicesQueried, result.totalVulnerabilities, result.stored, duration)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            statistics.recordError()
            
            System.err.println("[${getCurrentTimestamp()}] Poll failed: ${e.message}")
            log.error("Poll cycle failed after {}ms", duration, e)
        }
    }
    
    /**
     * Setup shutdown hook for graceful termination
     */
    private fun setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread {
            if (running.get()) {
                System.out.println()
                System.out.println("Shutting down monitor...")
                log.info("Shutdown signal received")
                
                running.set(false)
                cleanup()
                
                // Display final statistics
                displayStatistics()
            }
        })
    }
    
    /**
     * Cleanup resources
     */
    private fun cleanup() {
        scheduler?.let {
            log.info("Shutting down scheduler")
            it.shutdown()
            try {
                if (!it.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("Scheduler did not terminate gracefully, forcing shutdown")
                    it.shutdownNow()
                }
            } catch (e: InterruptedException) {
                log.error("Interrupted while waiting for scheduler shutdown", e)
                it.shutdownNow()
            }
        }
        
        try {
            appContext.close()
        } catch (e: Exception) {
            log.error("Error closing application context", e)
        }
    }
    
    /**
     * Display monitoring statistics
     */
    private fun displayStatistics() {
        System.out.println()
        System.out.println("=== Monitor Statistics ===")
        System.out.println("Total polls: ${statistics.totalPolls}")
        System.out.println("Total vulnerabilities found: ${statistics.totalVulnerabilitiesFound}")
        System.out.println("Average vulnerabilities per poll: ${statistics.averageVulnerabilitiesPerPoll()}")
        System.out.println("Total errors: ${statistics.totalErrors}")
        System.out.println("Average poll duration: ${statistics.averagePollDuration()}ms")
        System.out.println("Total runtime: ${statistics.totalRuntime()}")
        System.out.println()
    }
    
    /**
     * Get current timestamp as formatted string
     */
    private fun getCurrentTimestamp(): String {
        return java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }
}
